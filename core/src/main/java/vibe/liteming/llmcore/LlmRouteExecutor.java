// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns one logical request's stages, retries, winner and cancellation. Never mutates messages. */
final class LlmRouteExecutor {
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "llm-core-routing");
        thread.setDaemon(true);
        return thread;
    });

    record Result(LlmResponse response, HostedWebSearchAdapters.Evidence evidence, boolean missingSearch) { }
    private record CandidateResult(LlmResponse response, HostedWebSearchAdapters.Evidence evidence, boolean terminal) { }

    private final LlmOrchestrator orchestrator;
    private final LlmRequest request;
    private final LlmRoute route;
    private final Consumer<String> onDelta;
    private final Map<String, LlmOrchestrator.ProviderRuntime> providers;
    private final Map<String, HostedWebSearchAdapters.HostedWebSearchAdapter> searchAdapters;
    private final boolean requireSearch;
    private final CompletableFuture<Result> result = new CompletableFuture<>();
    private final List<LlmResponse.Attempt> attempts = new ArrayList<>();
    private final Set<String> visitedProviders = new HashSet<>();
    private final long startedAt = System.nanoTime();
    private final long deadline;
    private List<Candidate> running = List.of();
    private ScheduledFuture<?> timeout;
    private int calls;
    private boolean searching;
    private boolean finishing;
    private boolean missingSearch;
    private LlmResponse lastFailure = LlmResponse.failure("No routed provider is available", List.of());

    LlmRouteExecutor(LlmOrchestrator orchestrator, LlmRequest request, LlmRoute route,
            Consumer<String> onDelta, Map<String, HostedWebSearchAdapters.HostedWebSearchAdapter> searchAdapters,
            boolean requireSearch) {
        this.orchestrator = orchestrator;
        this.request = request;
        this.route = route;
        this.onDelta = onDelta;
        this.providers = orchestrator.providerSnapshot();
        this.searchAdapters = searchAdapters;
        this.requireSearch = requireSearch;
        this.searching = searchAdapters != null;
        this.deadline = startedAt + TimeUnit.SECONDS.toNanos(route.deadlineSeconds());
    }

    synchronized CompletableFuture<Result> start() {
        timeout = TIMER.schedule(() -> {
            synchronized (this) {
                finish(LlmResponse.failure("LLM route deadline exceeded (" + route.deadlineSeconds() + "s)", List.of()),
                        HostedWebSearchAdapters.Evidence.none());
            }
        }, route.deadlineSeconds(), TimeUnit.SECONDS);
        result.whenComplete((value, failure) -> {
            synchronized (this) {
                timeout.cancel(false);
                running.forEach(candidate -> candidate.cancel("Request cancelled"));
            }
        });
        stage(0);
        return result;
    }

    private void stage(int index) {
        if (expired()) return;
        if (index >= route.stages().size()) {
            if (searching && !requireSearch) {
                searching = false;
                stage(0);
            } else finish(lastFailure, HostedWebSearchAdapters.Evidence.none());
            return;
        }
        LlmRoute.Stage stage = route.stages().get(index);
        List<Candidate> candidates = new ArrayList<>();
        for (LlmRoute.Target target : stage.candidates()) {
            if (searching && !searchAdapters.containsKey(target.provider())) continue;
            candidates.add(new Candidate(target, stage.racing(), index));
        }
        running = List.copyOf(candidates);
        if (candidates.isEmpty()) { stage(index + 1); return; }
        // Start candidates together; a synchronous terminal result prevents any further sends.
        for (Candidate candidate : candidates) {
            candidate.start();
            CandidateResult immediate = candidate.completion.isCompletedExceptionally() ? null : candidate.completion.getNow(null);
            if (immediate != null && immediate.terminal()) {
                finish(immediate.response(), immediate.evidence());
                return;
            }
        }
        int[] remaining = {candidates.size()};
        for (Candidate candidate : candidates) {
            candidate.completion.whenComplete((value, failure) -> {
                synchronized (this) {
                    if (expired()) return;
                    if (value != null && (value.response().success() || value.terminal())) {
                        for (Candidate other : candidates) {
                            if (other != candidate) other.cancel(value.response().success() ? "Race loser cancelled" : "Request denied");
                        }
                        finish(value.response(), value.evidence());
                        return;
                    }
                    if (value != null) lastFailure = value.response();
                    if (--remaining[0] == 0) stage(index + 1);
                }
            });
        }
    }

    private void finish(LlmResponse response, HostedWebSearchAdapters.Evidence evidence) {
        if (result.isDone()) return;
        finishing = true;
        running.forEach(candidate -> candidate.cancel(response.success() ? "Race loser cancelled" : response.error()));
        result.complete(new Result(withAttempts(response, List.copyOf(attempts), elapsedMs()), evidence, missingSearch));
    }

    private long elapsedMs() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt); }

    private boolean expired() {
        if (result.isDone() || finishing) return true;
        if (System.nanoTime() < deadline) return false;
        finish(LlmResponse.failure("LLM route deadline exceeded (" + route.deadlineSeconds() + "s)", List.of()),
                HostedWebSearchAdapters.Evidence.none());
        return true;
    }

    static LlmResponse withAttempts(LlmResponse response, List<LlmResponse.Attempt> attempts, long elapsedMs) {
        return new LlmResponse(response.success(), response.content(), response.error(), response.provider(),
                response.model(), response.credentialId(), response.promptTokens(), response.completionTokens(),
                elapsedMs, attempts, response.requestBody(), response.responseBody(), response.finishReason(),
                response.denyCode(), response.cacheUsage());
    }

    private final class Candidate {
        final LlmRoute.Target target;
        final boolean racing;
        final int stageIndex;
        final LlmOrchestrator.ProviderRuntime provider;
        final Set<LlmOrchestrator.CredentialRuntime> tried = new HashSet<>();
        final CompletableFuture<CandidateResult> completion = new CompletableFuture<>();
        final boolean mayWait;
        CompletableFuture<LlmOrchestrator.AttemptResult> transport;
        ScheduledFuture<?> retry;
        LlmOrchestrator.CredentialRuntime credential;
        long attemptStart;
        int sent;
        boolean emitted;
        boolean attemptRecorded;

        Candidate(LlmRoute.Target target, boolean racing, int stageIndex) {
            this.target = target;
            this.racing = racing;
            this.stageIndex = stageIndex;
            this.provider = providers.get(target.provider());
            this.mayWait = target.retries() != null || visitedProviders.contains(target.provider());
        }

        void start() {
            if (completion.isDone() || expired()) return;
            if (provider == null) { unavailable("Provider not found"); return; }
            if (sent >= target.maxAttempts(provider.credentials.size())) { unavailable("No healthy credential"); return; }
            long now = System.currentTimeMillis();
            credential = provider.selectAvailable(now, tried);
            if (credential == null && target.retries() != null) credential = provider.selectAvailable(now, Set.of());
            if (credential == null) {
                long readyAt = provider.credentials.stream()
                        .filter(key -> !key.disabled && (target.retries() != null || !tried.contains(key)))
                        .mapToLong(key -> key.cooldownUntil).min().orElse(Long.MAX_VALUE);
                if (mayWait && readyAt != Long.MAX_VALUE && schedule(Math.max(1, readyAt - now))) return;
                unavailable("No healthy credential before route deadline");
                return;
            }
            if (calls >= LlmRoute.MAX_ATTEMPTS) {
                completion.complete(new CandidateResult(LlmResponse.failure("Route attempt limit reached", List.of(),
                        LlmRequestAccounting.DenyCode.CHAIN_CALLS_EXHAUSTED), HostedWebSearchAdapters.Evidence.none(), true));
                return;
            }
            calls++;
            sent++;
            tried.add(credential);
            visitedProviders.add(target.provider());
            attemptStart = System.nanoTime();
            attemptRecorded = false;
            credential.inflight.incrementAndGet();
            LlmOrchestrator.CredentialRuntime usedCredential = credential;
            try {
                Consumer<String> delta = !racing && !searching && onDelta != null ? text -> {
                    synchronized (LlmRouteExecutor.this) {
                        if (result.isDone() || completion.isDone()) return;
                        if (text != null && !text.isEmpty()) emitted = true;
                        onDelta.accept(text);
                    }
                } : null;
                transport = orchestrator.sendRouteAttempt(request, provider.spec, credential.spec, delta,
                        searching ? searchAdapters.get(target.provider()) : null, racing);
            } catch (RuntimeException failure) {
                transport = CompletableFuture.failedFuture(failure);
            }
            transport.whenComplete((value, failure) -> {
                usedCredential.inflight.decrementAndGet();
                synchronized (LlmRouteExecutor.this) {
                    if (completion.isDone() || expired()) return;
                    if (failure != null) {
                        value = LlmOrchestrator.AttemptResult.failure(failure.getMessage());
                    }
                    completedAttempt(value, usedCredential);
                }
            });
        }

        void completedAttempt(LlmOrchestrator.AttemptResult value, LlmOrchestrator.CredentialRuntime key) {
            LlmOrchestrator.SingleResult base = value.result();
            if (base.policyRejected()) {
                LlmResponse denied = new LlmResponse(false, "", base.error(), provider.spec.name(),
                        provider.spec.model(), "", 0, 0, base.latencyMs(), List.of(), base.requestBody(),
                        base.responseBody(), base.finishReason(), base.denyCode(), base.cacheUsage());
                completion.complete(new CandidateResult(denied, value.evidence(), true));
                return;
            }
            boolean usable = base.success() && !base.content().isBlank();
            String error = base.error();
            if (!base.success() && base.httpStatus() > 0) error = "HTTP " + base.httpStatus() + ": " + error;
            if (base.success() && !usable) error = "Provider returned no usable text";
            if (usable && racing && !"stop".equals(base.finishReason())) {
                usable = false;
                error = "Race candidate did not finish normally: " + base.finishReason();
            }
            if (usable && searching && requireSearch && !value.evidence().used()) {
                usable = false;
                missingSearch = true;
                error = "Hosted Web Search was requested but no invocation evidence was returned";
            }
            record(key.spec.id(), usable, error, base.finishReason(), base.cacheUsage());
            LlmRequestLogger.publishAttempt(new LlmRequestLogger.AttemptEvent(
                    request.context() == null ? "" : request.context().purpose(),
                    request.context() == null ? "" : request.context().requestId(),
                    provider.spec.name(), provider.spec.model(), key.spec.id(), usable,
                    base.latencyMs(), error, base.finishReason(), route.expression(),
                    provider.spec.name() + "/" + provider.spec.model(),
                    LlmOrchestrator.cacheDomainIdentity(provider.spec.name(), provider.spec.model(), key.spec.id()),
                    base.cacheUsage()));
            LlmResponse response = new LlmResponse(usable, usable ? base.content() : "", error,
                    provider.spec.name(), provider.spec.model(), key.spec.id(), base.promptTokens(), base.completionTokens(),
                    base.latencyMs(), List.of(), base.requestBody(), base.responseBody(), base.finishReason(),
                    base.denyCode(), base.cacheUsage());
            if (usable || base.policyRejected() || emitted || value.emittedContent()) {
                if (usable) key.consecutiveFailures.set(0);
                completion.complete(new CandidateResult(response, value.evidence(), true));
                return;
            }
            if (base.success()) {
                // A complete but unusable reply has already been billed. Identical retries do not repair its contract.
                completion.complete(new CandidateResult(response, value.evidence(), false));
                return;
            }
            LlmOrchestrator.applyFailure(key, base.httpStatus(), value.retryAfterMs());
            if (!LlmOrchestrator.isCredentialRetryable(base.httpStatus()) || sent >= target.maxAttempts(provider.credentials.size())) {
                completion.complete(new CandidateResult(response, value.evidence(), false));
                return;
            }
            lastFailure = response;
            // Healthy alternate keys may be used immediately; reusing a key honors its cooldown / Retry-After.
            if (provider.selectAvailable(System.currentTimeMillis(), tried) != null) start();
            else if (target.retries() == null) completion.complete(new CandidateResult(response, value.evidence(), false));
            else if (!schedule(Math.min(5000L, 500L << Math.min(sent - 1, 4)) + ThreadLocalRandom.current().nextLong(100))) {
                completion.complete(new CandidateResult(response, value.evidence(), false));
            }
        }

        boolean schedule(long delayMs) {
            if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs) >= deadline) return false;
            retry = TIMER.schedule(() -> { synchronized (LlmRouteExecutor.this) { start(); } }, delayMs, TimeUnit.MILLISECONDS);
            return true;
        }

        void unavailable(String reason) {
            completion.complete(new CandidateResult(LlmResponse.failure(target.provider() + ": " + reason, List.of()),
                    HostedWebSearchAdapters.Evidence.none(), false));
        }

        void record(String keyId, boolean success, String error, String finishReason, LlmCacheUsage cacheUsage) {
            if (attemptRecorded) return;
            attemptRecorded = true;
            long latency = attemptStart == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attemptStart);
            attempts.add(new LlmResponse.Attempt(target.provider(), keyId, success, error, latency, finishReason,
                    provider == null ? "" : provider.spec.model(),
                    cacheUsage));
        }

        void cancel(String reason) {
            if (completion.isDone()) return;
            if (retry != null) retry.cancel(false);
            if (transport != null && !transport.isDone()) {
                LlmCacheUsage cancelledUsage = LlmCacheUsage.unknown(
                        "attempt was cancelled before usage was available");
                record(credential == null ? "" : credential.spec.id(), false, reason, "cancelled",
                        cancelledUsage);
                if (credential != null && provider != null) {
                    LlmRequestLogger.publishAttempt(new LlmRequestLogger.AttemptEvent(
                            request.context() == null ? "" : request.context().purpose(),
                            request.context() == null ? "" : request.context().requestId(),
                            provider.spec.name(), provider.spec.model(), credential.spec.id(), false,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attemptStart), reason, "cancelled",
                            route.expression(), provider.spec.name() + "/" + provider.spec.model(),
                            LlmOrchestrator.cacheDomainIdentity(
                                    provider.spec.name(), provider.spec.model(), credential.spec.id()),
                            cancelledUsage));
                }
                completion.cancel(false);
                transport.cancel(true);
            } else completion.cancel(false);
        }
    }
}
