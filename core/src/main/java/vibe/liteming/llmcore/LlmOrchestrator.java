package vibe.liteming.llmcore;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class LlmOrchestrator {
    private static final long RATE_LIMIT_COOLDOWN_MS = 60_000L;
    private static final long TRANSIENT_FAILURE_COOLDOWN_MS = 10_000L;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_000;
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final Map<String, ProviderRuntime> providers = new ConcurrentHashMap<>();
    private volatile PriorityRoutingConfig routingConfig = PriorityRoutingConfig.empty();
    private volatile LlmRouteOptions globalDefaults = new LlmRouteOptions(null, null, 30, null, null);

    public LlmOrchestrator(Map<String, ProviderSpec> providerSpecs) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        replaceProviders(providerSpecs);
    }

    public void replaceProviders(Map<String, ProviderSpec> providerSpecs) {
        Map<String, ProviderRuntime> replacement = new LinkedHashMap<>();
        if (providerSpecs != null) {
            providerSpecs.forEach((name, spec) -> replacement.put(name, new ProviderRuntime(spec)));
        }
        providers.clear();
        providers.putAll(replacement);
    }

    /**
     * Install the global priority-routing table. May be {@code null} to clear.
     * Callers (typically the /llm console persistence layer) update this at runtime;
     * {@link #send(LlmRequest)} and {@link #sendStreaming(LlmRequest, Consumer)}
     * consult it when the request carries no explicit providerChain.
     */
    public void setRoutingConfig(PriorityRoutingConfig config) {
        this.routingConfig = config == null ? PriorityRoutingConfig.empty() : config;
    }

    public PriorityRoutingConfig getRoutingConfig() {
        return routingConfig;
    }

    /** Generic host defaults; provider values still win for temperature/max output. */
    public void setGlobalDefaults(LlmRouteOptions defaults) {
        this.globalDefaults = defaults == null ? LlmRouteOptions.empty() : defaults;
    }

    public LlmRouteOptions getGlobalDefaults() {
        return globalDefaults;
    }

    /**
     * Resolve the effective provider chain for a request: explicit caller-supplied
     * chain wins; otherwise the global {@link PriorityRoutingConfig} for the request's
     * purpose (with fallback to its default chain); otherwise every known provider.
     */
    public List<String> resolveChain(LlmRequest request) {
        List<String> requested = request.providerChain();
        if (requested != null && !requested.isEmpty()) return new ArrayList<>(requested);
        List<String> all = new ArrayList<>(providers.keySet());
        return new ArrayList<>(routingConfig.resolveChain(
                request.context() == null ? null : request.context().purpose(), all));
    }

    public Set<String> getProviderNames() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    public ProviderSpec getProviderSpec(String name) {
        ProviderRuntime runtime = providers.get(name);
        return runtime == null ? null : runtime.spec;
    }

    /** Resolve one provider attempt using test > purpose > provider/global precedence. */
    public LlmResolvedParameters resolveParameters(LlmRequest request, String providerName) {
        LlmRequest safeRequest = request == null ? LlmRequest.routed(List.of(), LlmRequestContext.chat()) : request;
        ProviderSpec provider = getProviderSpec(providerName);
        LlmRouteOptions providerDefaults = provider == null
                ? LlmRouteOptions.empty()
                : new LlmRouteOptions(provider.temperature(), provider.maxTokens(), null, null, null);
        String purpose = safeRequest.context() == null ? null : safeRequest.context().purpose();
        LlmRouteOptions purposeOverrides = routingConfig.resolveOptions(purpose);
        LlmRouteOptions effective = globalDefaults.overlay(providerDefaults)
                .overlay(purposeOverrides)
                .overlay(safeRequest.requestOverrides());

        int timeout = effective.timeoutSeconds() == null ? 30 : effective.timeoutSeconds();
        int maxOutput = effective.maxOutputTokens() == null
                ? DEFAULT_MAX_OUTPUT_TOKENS : effective.maxOutputTokens();
        int reserve = effective.outputReserveTokens() == null
                ? maxOutput : effective.outputReserveTokens();
        int inputBudget = effective.inputBudgetTokens() == null
                ? LlmResolvedParameters.UNBOUNDED_INPUT : effective.inputBudgetTokens();
        Integer contextWindow = provider == null ? null : provider.contextWindowTokens();
        if (contextWindow != null) {
            inputBudget = Math.min(inputBudget, Math.max(0, contextWindow - reserve));
        }
        return new LlmResolvedParameters(providerName, effective.temperature(), maxOutput,
                timeout, inputBudget, reserve, contextWindow);
    }

    public LlmResolvedParameters resolveParameters(String purpose, String providerName) {
        LlmRequestContext context = new LlmRequestContext("", purpose, "", "", "", "", "", false);
        return resolveParameters(LlmRequest.routed(List.of(), context), providerName);
    }

    public LlmMessageFinalization finalizeDraft(LlmMessageDraft draft, LlmRequest request, String providerName,
            LlmMessageFinalizer.TokenEstimator estimator) {
        return LlmMessageFinalizer.finalize(draft,
                resolveParameters(request, providerName).inputBudgetTokens(), estimator);
    }

    public CompletableFuture<LlmResponse> send(LlmRequest request) {
        List<String> chain = resolveChain(request);
        return attemptProvider(request, chain, 0, new ArrayList<>())
                .thenApply(response -> {
                    LlmRequestLogger.publish("llm-core", request, response);
                    return response;
                });
    }

    public CompletableFuture<LlmResponse> sendStreaming(LlmRequest request, Consumer<String> onDelta) {
        List<String> chain = resolveChain(request);
        return attemptStreaming(request, chain, 0, new ArrayList<>(), onDelta == null ? delta -> { } : onDelta)
                .thenApply(response -> {
                    LlmRequestLogger.publish("llm-core", request, response);
                    return response;
                });
    }

    /** Worst-case provider attempts and reservations for one logical request. */
    public LlmCallBudget estimateWorstCaseBudget(LlmRequest request) {
        int calls = 0;
        long tokens = 0L;
        for (String providerName : resolveChain(request)) {
            ProviderRuntime provider = providers.get(providerName);
            if (provider == null) continue;
            int attempts = provider.credentials.size();
            if (attempts <= 0) continue;
            LlmRequestAccounting.AttemptEstimate estimate = attemptEstimate(
                    request, provider.spec, resolveParameters(request, providerName));
            calls = saturatedAdd(calls, attempts);
            tokens = saturatedAdd(tokens, saturatedMultiply(estimate.totalTokens(), attempts));
        }
        return new LlmCallBudget(calls, tokens);
    }

    private CompletableFuture<LlmResponse> attemptStreaming(LlmRequest request, List<String> chain, int index,
            List<LlmResponse.Attempt> attempts, Consumer<String> onDelta) {
        if (index >= chain.size()) return CompletableFuture.completedFuture(LlmResponse.failure("All providers failed", attempts));
        String providerName = chain.get(index);
        ProviderRuntime provider = providers.get(providerName);
        if (provider == null) {
            attempts.add(new LlmResponse.Attempt(providerName, "", false, "Provider not found", 0L));
            return attemptStreaming(request, chain, index + 1, attempts, onDelta);
        }
        if (!"openai".equals(provider.spec.format())) {
            LlmRequest singleProvider = new LlmRequest(request.messages(), List.of(providerName), request.temperature(),
                    request.maxTokens(), request.timeoutSeconds(), request.context(), request.overrides(),
                    request.billingContext());
            return attemptProvider(singleProvider, List.of(providerName), 0, new ArrayList<>()).thenCompose(response -> {
                attempts.addAll(response.attempts());
                if (response.success()) {
                    onDelta.accept(response.content());
                    return CompletableFuture.completedFuture(new LlmResponse(true, response.content(), "",
                            response.provider(), response.model(), response.credentialId(), response.promptTokens(),
                            response.completionTokens(), response.latencyMs(), attempts, "", "",
                            response.finishReason()));
                }
                if (response.denyCode() != LlmRequestAccounting.DenyCode.NONE) {
                    return CompletableFuture.completedFuture(
                            LlmResponse.failure(response.error(), attempts, response.denyCode()));
                }
                return attemptStreaming(request, chain, index + 1, attempts, onDelta);
            });
        }
        CredentialRuntime credential = provider.selectCredential(System.currentTimeMillis(), null);
        if (credential == null) {
            attempts.add(new LlmResponse.Attempt(providerName, "", false, "No healthy credential", 0L));
            return attemptStreaming(request, chain, index + 1, attempts, onDelta);
        }
        credential.inflight.incrementAndGet();
        long startedAt = System.currentTimeMillis();
        return sendSingleStreaming(request, provider.spec, credential.spec, onDelta).handle((result, throwable) -> {
            credential.inflight.decrementAndGet();
                    return throwable == null ? result : StreamResult.failure(rootMessage(throwable), 0,
                    System.currentTimeMillis() - startedAt, false);
        }).thenCompose(result -> {
            attempts.add(new LlmResponse.Attempt(providerName, credential.spec.id(), result.success, result.error,
                    result.latencyMs, result.finishReason));
            if (result.policyRejected) {
                return CompletableFuture.completedFuture(
                        LlmResponse.failure(result.error, attempts, result.denyCode));
            }
            if (result.success) {
                credential.consecutiveFailures.set(0);
                return CompletableFuture.completedFuture(new LlmResponse(true, result.content, "", providerName,
                        provider.spec.model(), credential.spec.id(), result.promptTokens,
                        result.completionTokens, result.latencyMs, attempts,
                        result.requestBody, result.responseBody, result.finishReason));
            }
            applyFailure(credential, result.httpStatus);
            if (result.emittedContent) {
                return CompletableFuture.completedFuture(new LlmResponse(false, "",
                        "Streaming provider failed after emitting content: " + result.error, providerName,
                        provider.spec.model(), credential.spec.id(), 0, 0, result.latencyMs, attempts,
                        result.requestBody, result.responseBody, result.finishReason));
            }
            if (isCredentialRetryable(result.httpStatus)
                    && provider.selectCredential(System.currentTimeMillis(), credential) != null) {
                return attemptStreaming(request, chain, index, attempts, onDelta);
            }
            return attemptStreaming(request, chain, index + 1, attempts, onDelta);
        });
    }

    public CompletableFuture<LlmResponse> testProvider(String providerName, int timeoutSeconds) {
        LlmRequest request = providerTestRequest(providerName, timeoutSeconds, LlmBillingContext.unspecified());
        return send(request);
    }

    public CompletableFuture<LlmResponse> testProvider(String providerName, int timeoutSeconds,
            LlmBillingContext.PrincipalKind principalKind, String principalId, String causalRootRequestId) {
        LlmRequest template = providerTestRequest(providerName, timeoutSeconds, LlmBillingContext.unspecified());
        LlmCallBudget budget = estimateWorstCaseBudget(template);
        int maxCalls = Math.max(1, budget.maxCalls());
        long maxTokens = Math.max(1L, budget.maxTokens());
        LlmBillingContext billing = principalKind == LlmBillingContext.PrincipalKind.PLAYER
                ? LlmBillingContext.player(principalId, causalRootRequestId, maxCalls, maxTokens)
                : LlmBillingContext.system(principalKind, causalRootRequestId, maxCalls, maxTokens);
        return send(template.withBillingContext(billing));
    }

    private static LlmRequest providerTestRequest(String providerName, int timeoutSeconds,
            LlmBillingContext billing) {
        return new LlmRequest(
                List.of(new LlmMessage("user", "Reply with OK.")),
                List.of(providerName),
                0.0,
                8,
                timeoutSeconds,
                new LlmRequestContext("", "DEBUG_TEST", "", "", "", "", providerName, false,
                        "", "", "console-provider-test", "", "test"),
                LlmRouteOptions.empty(), billing);
    }

    private CompletableFuture<LlmResponse> attemptProvider(LlmRequest request, List<String> chain, int index,
            List<LlmResponse.Attempt> attempts) {
        if (index >= chain.size()) {
            return CompletableFuture.completedFuture(LlmResponse.failure("All providers failed", attempts));
        }
        String providerName = chain.get(index);
        ProviderRuntime provider = providers.get(providerName);
        if (provider == null) {
            attempts.add(new LlmResponse.Attempt(providerName, "", false, "Provider not found", 0L));
            return attemptProvider(request, chain, index + 1, attempts);
        }
        CredentialRuntime credential = provider.selectCredential(System.currentTimeMillis(), null);
        if (credential == null) {
            attempts.add(new LlmResponse.Attempt(providerName, "", false, "No healthy credential", 0L));
            return attemptProvider(request, chain, index + 1, attempts);
        }

        long startedAt = System.currentTimeMillis();
        credential.inflight.incrementAndGet();
        return sendSingle(request, provider.spec, credential.spec)
                .handle((result, throwable) -> {
                    credential.inflight.decrementAndGet();
                    long latency = System.currentTimeMillis() - startedAt;
                    if (throwable != null) {
                        credential.cooldownUntil = System.currentTimeMillis() + TRANSIENT_FAILURE_COOLDOWN_MS;
                        return SingleResult.failure("Request failed: " + rootMessage(throwable), 0, latency);
                    }
                    return result;
                })
                .thenCompose(result -> {
                    attempts.add(new LlmResponse.Attempt(providerName, credential.spec.id(), result.success,
                            result.error, result.latencyMs, result.finishReason));
                    if (result.policyRejected) {
                        return CompletableFuture.completedFuture(
                                LlmResponse.failure(result.error, attempts, result.denyCode));
                    }
                    if (result.success) {
                        credential.consecutiveFailures.set(0);
                        return CompletableFuture.completedFuture(new LlmResponse(
                                true,
                                result.content,
                                "",
                                providerName,
                                provider.spec.model(),
                                credential.spec.id(),
                                result.promptTokens,
                                result.completionTokens,
                                result.latencyMs,
                                attempts,
                                result.requestBody,
                                result.responseBody,
                                result.finishReason));
                    }
                    applyFailure(credential, result.httpStatus);
                    if (!isCredentialRetryable(result.httpStatus)) {
                        return attemptProvider(request, chain, index + 1, attempts);
                    }
                    CredentialRuntime nextCredential = provider.selectCredential(System.currentTimeMillis(), credential);
                    if (nextCredential != null) {
                        return attemptProviderWithCredential(request, chain, index, attempts, provider, nextCredential);
                    }
                    return attemptProvider(request, chain, index + 1, attempts);
                });
    }

    private CompletableFuture<LlmResponse> attemptProviderWithCredential(LlmRequest request, List<String> chain,
            int index, List<LlmResponse.Attempt> attempts, ProviderRuntime provider, CredentialRuntime credential) {
        long startedAt = System.currentTimeMillis();
        credential.inflight.incrementAndGet();
        return sendSingle(request, provider.spec, credential.spec)
                .handle((result, throwable) -> {
                    credential.inflight.decrementAndGet();
                    long latency = System.currentTimeMillis() - startedAt;
                    return throwable == null ? result
                            : SingleResult.failure("Request failed: " + rootMessage(throwable), 0, latency);
                })
                .thenCompose(result -> {
                    attempts.add(new LlmResponse.Attempt(provider.spec.name(), credential.spec.id(), result.success,
                            result.error, result.latencyMs, result.finishReason));
                    if (result.policyRejected) {
                        return CompletableFuture.completedFuture(
                                LlmResponse.failure(result.error, attempts, result.denyCode));
                    }
                    if (result.success) {
                        credential.consecutiveFailures.set(0);
                        return CompletableFuture.completedFuture(new LlmResponse(true, result.content, "",
                                provider.spec.name(), provider.spec.model(), credential.spec.id(), result.promptTokens,
                                result.completionTokens, result.latencyMs, attempts, result.requestBody,
                                result.responseBody, result.finishReason));
                    }
                    applyFailure(credential, result.httpStatus);
                    if (!isCredentialRetryable(result.httpStatus)) {
                        return attemptProvider(request, chain, index + 1, attempts);
                    }
                    CredentialRuntime next = provider.selectCredential(System.currentTimeMillis(), credential);
                    return next != null
                            ? attemptProviderWithCredential(request, chain, index, attempts, provider, next)
                            : attemptProvider(request, chain, index + 1, attempts);
                });
    }

    private static LlmRequestAccounting.AttemptEstimate attemptEstimate(LlmRequest request,
            ProviderSpec provider, LlmResolvedParameters parameters) {
        long inputTokens = 0L;
        for (LlmMessage message : request.messages()) {
            inputTokens = saturatedAdd(inputTokens,
                    Math.max(0, LlmMessageFinalizer.CONSERVATIVE_ESTIMATOR.estimate(message)));
        }
        return new LlmRequestAccounting.AttemptEstimate(provider.name(), inputTokens,
                Math.max(0, parameters.outputReserveTokens()));
    }

    private static LlmRequestAccounting.AttemptUsage attemptUsage(
            LlmRequestAccounting.Reservation reservation, int promptTokens, int completionTokens) {
        if (promptTokens > 0 || completionTokens > 0) {
            return new LlmRequestAccounting.AttemptUsage(promptTokens, completionTokens, 0L);
        }
        return new LlmRequestAccounting.AttemptUsage(0L, 0L, reservation.reservedTokens());
    }

    private static void finishAccounting(LlmRequest request,
            LlmRequestAccounting.Reservation reservation, boolean success,
            int promptTokens, int completionTokens) {
        if (!success) {
            LlmRequestAccounting.release(request, reservation);
            return;
        }
        LlmRequestAccounting.settle(request, reservation,
                attemptUsage(reservation, promptTokens, completionTokens));
    }

    private CompletableFuture<SingleResult> sendSingle(LlmRequest request, ProviderSpec provider,
            ProviderSpec.Credential credential) {
        String format = provider.format();
        String url = resolveUrl(provider, credential.key());
        LlmResolvedParameters parameters = resolveParameters(request, provider.name());
        JsonObject body = buildBody(format, provider, request, parameters);
        String requestBody = GSON.toJson(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(parameters.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        applyHeaders(builder, format, credential.key());
        LlmRequestAccounting.Reservation reservation = LlmRequestAccounting.reserve(request,
                attemptEstimate(request, provider, parameters));
        if (!reservation.allowed()) {
            return CompletableFuture.completedFuture(
                    SingleResult.policyFailure(reservation.denyCode(), reservation.reason()));
        }
        long startedAt = System.currentTimeMillis();
        CompletableFuture<SingleResult> execution;
        try {
            execution = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> parseResponse(format, response.statusCode(), response.body(),
                            System.currentTimeMillis() - startedAt, requestBody));
        } catch (RuntimeException failure) {
            execution = CompletableFuture.completedFuture(SingleResult.failure(
                    rootMessage(failure), 0, System.currentTimeMillis() - startedAt, requestBody, ""));
        }
        execution.whenComplete((result, throwable) -> finishAccounting(request, reservation,
                throwable == null && result != null && result.success,
                result == null ? 0 : result.promptTokens,
                result == null ? 0 : result.completionTokens));
        return execution.handle((result, throwable) -> throwable == null ? result
                : SingleResult.failure(rootMessage(throwable), 0,
                        System.currentTimeMillis() - startedAt, requestBody, ""));
    }

    private CompletableFuture<StreamResult> sendSingleStreaming(LlmRequest request, ProviderSpec provider,
            ProviderSpec.Credential credential, Consumer<String> onDelta) {
        LlmResolvedParameters parameters = resolveParameters(request, provider.name());
        JsonObject body = buildOpenAiBody(provider.model(), request.messages(),
                parameters.temperature(), parameters.maxOutputTokens(), false);
        body.addProperty("stream", true);
        String requestBody = GSON.toJson(body);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(provider.url()))
                .timeout(Duration.ofSeconds(parameters.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + credential.key())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        LlmRequestAccounting.Reservation reservation = LlmRequestAccounting.reserve(request,
                attemptEstimate(request, provider, parameters));
        if (!reservation.allowed()) {
            return CompletableFuture.completedFuture(
                    StreamResult.policyFailure(reservation.denyCode(), reservation.reason()));
        }
        long startedAt = System.currentTimeMillis();
        CompletableFuture<StreamResult> execution;
        try {
            execution = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream()).thenApplyAsync(response -> {
            long latency;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try {
                    String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    latency = System.currentTimeMillis() - startedAt;
                    return StreamResult.failure(extractError(error), response.statusCode(), latency, false,
                            requestBody, error);
                } catch (Exception e) {
                    return StreamResult.failure(rootMessage(e), response.statusCode(),
                            System.currentTimeMillis() - startedAt, false, requestBody, "");
                }
            }
            StringBuilder accumulated = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            String finishReason = "";
            boolean streamDone = false;
            int chunkCount = 0;
            int promptTokens = 0;
            int completionTokens = 0;
            boolean emitted = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) {
                        streamDone = true;
                        continue;
                    }
                    chunkCount++;
                    JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                    if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
                        JsonObject usage = chunk.getAsJsonObject("usage");
                        if (usage.has("prompt_tokens") && !usage.get("prompt_tokens").isJsonNull()) {
                            promptTokens = Math.max(promptTokens, usage.get("prompt_tokens").getAsInt());
                        }
                        if (usage.has("completion_tokens") && !usage.get("completion_tokens").isJsonNull()) {
                            completionTokens = Math.max(completionTokens, usage.get("completion_tokens").getAsInt());
                        }
                    }
                    JsonArray choices = chunk.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                        finishReason = normalizeFinishReason(choice.get("finish_reason").getAsString());
                    }
                    JsonObject delta = choice.getAsJsonObject("delta");
                    if (delta == null) continue;
                    // Some providers (e.g. LongCat) stream chain-of-thought separately.
                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                        String thought = delta.get("reasoning_content").getAsString();
                        if (!thought.isEmpty()) reasoning.append(thought);
                    }
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String text = delta.get("content").getAsString();
                        if (!text.isEmpty()) {
                            accumulated.append(text);
                            emitted = true;
                            onDelta.accept(text);
                        }
                    }
                }
                latency = System.currentTimeMillis() - startedAt;
                // Log a reconstructed chat-completion style body, not raw SSE chunks.
                if (finishReason.isBlank()) finishReason = streamDone ? "stop" : "unknown";
                String responseBody = buildStreamLogBody(provider.model(), accumulated.toString(),
                        reasoning.toString(), chunkCount, finishReason);
                return accumulated.isEmpty()
                        ? StreamResult.failure("LLM stream produced no content", 0, latency, emitted, requestBody,
                                responseBody, finishReason)
                        : StreamResult.success(accumulated.toString(), promptTokens, completionTokens,
                                latency, requestBody, responseBody, finishReason);
            } catch (Exception e) {
                String responseBody = buildStreamLogBody(provider.model(), accumulated.toString(),
                        reasoning.toString(), chunkCount, finishReason.isBlank() ? "error" : finishReason);
                return StreamResult.failure(rootMessage(e), 0, System.currentTimeMillis() - startedAt, emitted,
                        requestBody, responseBody, "error");
            }
            });
        } catch (RuntimeException failure) {
            execution = CompletableFuture.completedFuture(StreamResult.failure(
                    rootMessage(failure), 0, System.currentTimeMillis() - startedAt, false, requestBody, ""));
        }
        execution.whenComplete((result, throwable) -> finishAccounting(request, reservation,
                throwable == null && result != null && result.success,
                result == null ? 0 : result.promptTokens,
                result == null ? 0 : result.completionTokens));
        return execution.handle((result, throwable) -> throwable == null ? result
                : StreamResult.failure(rootMessage(throwable), 0,
                        System.currentTimeMillis() - startedAt, false, requestBody, ""));
    }

    /** Human-readable body for console logs (avoids dumping every SSE line). */
    private static String buildStreamLogBody(String model, String content, String reasoning, int chunkCount,
            String finishReason) {
        JsonObject body = new JsonObject();
        body.addProperty("object", "chat.completion");
        body.addProperty("stream", true);
        body.addProperty("model", model == null ? "" : model);
        body.addProperty("chunk_count", chunkCount);
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content == null ? "" : content);
        if (reasoning != null && !reasoning.isBlank()) {
            message.addProperty("reasoning_content", reasoning);
        }
        choice.add("message", message);
        choice.addProperty("finish_reason", finishReason == null || finishReason.isBlank() ? "unknown" : finishReason);
        choices.add(choice);
        body.add("choices", choices);
        return GSON.toJson(body);
    }

    private static JsonObject buildBody(String format, ProviderSpec provider, LlmRequest request,
            LlmResolvedParameters parameters) {
        Double temperature = parameters.temperature();
        Integer maxTokens = parameters.maxOutputTokens();
        return switch (format) {
            case "claude", "anthropic" -> buildClaudeBody(provider.model(), request.messages(), temperature, maxTokens);
            case "gemini" -> buildGeminiBody(request.messages(), temperature, maxTokens);
            default -> buildOpenAiBody(provider.model(), request.messages(), temperature, maxTokens,
                    request.context().structured());
        };
    }

    private static JsonObject buildOpenAiBody(String model, List<LlmMessage> messages, Double temperature,
            Integer maxTokens, boolean structured) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray array = new JsonArray();
        for (LlmMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            if (message.hasImage()) {
                JsonArray parts = new JsonArray();
                for (LlmMessage.Part part : message.parts()) {
                    JsonObject contentPart = new JsonObject();
                    if (part instanceof LlmMessage.ImagePart image) {
                        contentPart.addProperty("type", "image_url");
                        JsonObject imageUrl = new JsonObject();
                        imageUrl.addProperty("url", image.dataUrl());
                        imageUrl.addProperty("detail", image.detail());
                        contentPart.add("image_url", imageUrl);
                    } else {
                        contentPart.addProperty("type", "text");
                        contentPart.addProperty("text", part.asText());
                    }
                    parts.add(contentPart);
                }
                item.add("content", parts);
            } else {
                item.addProperty("content", message.content());
            }
            array.add(item);
        }
        body.add("messages", array);
        if (temperature != null) body.addProperty("temperature", temperature);
        if (maxTokens != null) body.addProperty("max_tokens", maxTokens);
        if (structured) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        }
        return body;
    }

    private static JsonObject buildClaudeBody(String model, List<LlmMessage> messages, Double temperature,
            Integer maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens == null ? 512 : maxTokens);
        if (temperature != null) body.addProperty("temperature", temperature);
        StringBuilder system = new StringBuilder();
        JsonArray array = new JsonArray();
        for (LlmMessage message : messages) {
            if ("system".equals(message.role())) {
                if (!system.isEmpty()) system.append('\n');
                system.append(message.content());
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("role", "assistant".equals(message.role()) ? "assistant" : "user");
            JsonArray content = new JsonArray();
            for (LlmMessage.Part part : message.parts()) {
                JsonObject contentPart = new JsonObject();
                if (part instanceof LlmMessage.ImagePart image) {
                    contentPart.addProperty("type", "image");
                    JsonObject source = new JsonObject();
                    source.addProperty("type", "base64");
                    source.addProperty("media_type", image.mimeType());
                    source.addProperty("data", image.base64Data());
                    contentPart.add("source", source);
                } else {
                    contentPart.addProperty("type", "text");
                    contentPart.addProperty("text", part.asText());
                }
                content.add(contentPart);
            }
            item.add("content", content);
            array.add(item);
        }
        if (!system.isEmpty()) body.addProperty("system", system.toString());
        body.add("messages", array);
        return body;
    }

    private static JsonObject buildGeminiBody(List<LlmMessage> messages, Double temperature, Integer maxTokens) {
        JsonObject body = new JsonObject();
        JsonArray contents = new JsonArray();
        for (LlmMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", "assistant".equals(message.role()) ? "model" : "user");
            JsonArray parts = new JsonArray();
            for (LlmMessage.Part messagePart : message.parts()) {
                JsonObject part = new JsonObject();
                if (messagePart instanceof LlmMessage.ImagePart image) {
                    JsonObject inlineData = new JsonObject();
                    inlineData.addProperty("mime_type", image.mimeType());
                    inlineData.addProperty("data", image.base64Data());
                    part.add("inline_data", inlineData);
                } else {
                    part.addProperty("text", ("system".equals(message.role()) ? "[System]\n" : "")
                            + messagePart.asText());
                }
                parts.add(part);
            }
            item.add("parts", parts);
            contents.add(item);
        }
        body.add("contents", contents);
        JsonObject generation = new JsonObject();
        if (temperature != null) generation.addProperty("temperature", temperature);
        if (maxTokens != null) generation.addProperty("maxOutputTokens", maxTokens);
        body.add("generationConfig", generation);
        return body;
    }

    private static SingleResult parseResponse(String format, int status, String body, long latencyMs,
            String requestBody) {
        if (status < 200 || status >= 300) {
            return SingleResult.failure(extractError(body), status, latencyMs, requestBody, body);
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String content;
            String finishReason;
            int promptTokens = 0;
            int completionTokens = 0;
            if ("gemini".equals(format)) {
                JsonObject candidate = root.getAsJsonArray("candidates").get(0).getAsJsonObject();
                content = candidate.getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject()
                        .get("text").getAsString();
                finishReason = candidate.has("finishReason")
                        ? normalizeFinishReason(candidate.get("finishReason").getAsString()) : "";
                if (root.has("usageMetadata")) {
                    JsonObject usage = root.getAsJsonObject("usageMetadata");
                    promptTokens = getInt(usage, "promptTokenCount");
                    completionTokens = getInt(usage, "candidatesTokenCount");
                }
            } else if ("claude".equals(format) || "anthropic".equals(format)) {
                content = root.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                finishReason = root.has("stop_reason")
                        ? normalizeFinishReason(root.get("stop_reason").getAsString()) : "";
                if (root.has("usage")) {
                    JsonObject usage = root.getAsJsonObject("usage");
                    promptTokens = getInt(usage, "input_tokens");
                    completionTokens = getInt(usage, "output_tokens");
                }
            } else {
                JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
                content = choice.getAsJsonObject("message").get("content").getAsString();
                finishReason = choice.has("finish_reason")
                        ? normalizeFinishReason(choice.get("finish_reason").getAsString()) : "";
                if (root.has("usage")) {
                    JsonObject usage = root.getAsJsonObject("usage");
                    promptTokens = getInt(usage, "prompt_tokens");
                    completionTokens = getInt(usage, "completion_tokens");
                }
            }
            return SingleResult.success(content, promptTokens, completionTokens, latencyMs, requestBody, body,
                    finishReason);
        } catch (Exception e) {
            return SingleResult.failure("Invalid provider response: " + rootMessage(e), -1, latencyMs, requestBody, body);
        }
    }

    private static String normalizeFinishReason(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "stop", "end_turn", "stop_sequence", "done" -> "stop";
            case "length", "max_tokens", "max_output_tokens", "max_output_token" -> "length";
            case "content_filter", "safety", "blocked" -> "content_filter";
            case "tool_calls", "function_call" -> "tool_calls";
            default -> value;
        };
    }

    private static void applyHeaders(HttpRequest.Builder builder, String format, String key) {
        if ("claude".equals(format) || "anthropic".equals(format)) {
            builder.header("x-api-key", key).header("anthropic-version", "2023-06-01");
        } else if (!"gemini".equals(format)) {
            builder.header("Authorization", "Bearer " + key);
        }
    }

    private static String resolveUrl(ProviderSpec provider, String key) {
        if (!"gemini".equals(provider.format())) {
            return provider.url();
        }
        String base = provider.url().replace("{model}", provider.model());
        if (base.contains("{key}")) return base.replace("{key}", key);
        return base + (base.contains("?") ? "&" : "?") + "key=" + key;
    }

    private static void applyFailure(CredentialRuntime credential, int status) {
        int failures = credential.consecutiveFailures.incrementAndGet();
        long now = System.currentTimeMillis();
        if (status == 401 || status == 403) {
            credential.disabled = true;
        } else if (status == 429) {
            credential.cooldownUntil = now + RATE_LIMIT_COOLDOWN_MS;
        } else if (status >= 500 || status == 0 || failures >= 3) {
            credential.cooldownUntil = now + TRANSIENT_FAILURE_COOLDOWN_MS;
        } else {
            credential.cooldownUntil = now + 1_000L;
        }
    }

    private static boolean isCredentialRetryable(int status) {
        return status == 0 || status == 401 || status == 403 || status == 429 || status >= 500;
    }

    private static String extractError(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                if (object.has("error")) {
                    JsonElement error = object.get("error");
                    if (error.isJsonPrimitive()) return error.getAsString();
                    if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                        return error.getAsJsonObject().get("message").getAsString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return body == null || body.isBlank() ? "Provider request failed" : body.substring(0, Math.min(300, body.length()));
    }

    private static int getInt(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : 0;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class ProviderRuntime {
        private final ProviderSpec spec;
        private final List<CredentialRuntime> credentials;
        private final AtomicInteger cursor = new AtomicInteger();

        private ProviderRuntime(ProviderSpec spec) {
            this.spec = spec;
            this.credentials = spec.credentials().stream()
                    .filter(ProviderSpec.Credential::isConfigured)
                    .map(CredentialRuntime::new)
                    .toList();
        }

        private CredentialRuntime selectCredential(long nowMs, CredentialRuntime excluded) {
            if (credentials.isEmpty()) return null;
            int start = Math.floorMod(cursor.getAndIncrement(), credentials.size());
            CredentialRuntime best = null;
            for (int offset = 0; offset < credentials.size(); offset++) {
                CredentialRuntime candidate = credentials.get((start + offset) % credentials.size());
                if (candidate == excluded || candidate.disabled || candidate.cooldownUntil > nowMs) continue;
                if (best == null || candidate.inflight.get() < best.inflight.get()) best = candidate;
            }
            return best;
        }
    }

    private static final class CredentialRuntime {
        private final ProviderSpec.Credential spec;
        private final AtomicInteger inflight = new AtomicInteger();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile long cooldownUntil;
        private volatile boolean disabled;

        private CredentialRuntime(ProviderSpec.Credential spec) {
            this.spec = spec;
        }
    }

    private record SingleResult(boolean success, String content, String error, int httpStatus,
            int promptTokens, int completionTokens, long latencyMs, String requestBody, String responseBody,
            String finishReason, boolean policyRejected, LlmRequestAccounting.DenyCode denyCode) {
        private static SingleResult success(String content, int promptTokens, int completionTokens, long latencyMs,
                String requestBody, String responseBody, String finishReason) {
            return new SingleResult(true, content, "", 200, promptTokens, completionTokens, latencyMs,
                    requestBody, responseBody, finishReason, false, LlmRequestAccounting.DenyCode.NONE);
        }

        private static SingleResult failure(String error, int httpStatus, long latencyMs) {
            return failure(error, httpStatus, latencyMs, "", "");
        }

        private static SingleResult failure(String error, int httpStatus, long latencyMs, String requestBody,
                String responseBody) {
            return new SingleResult(false, "", error, httpStatus, 0, 0, latencyMs, requestBody, responseBody,
                    "error", false, LlmRequestAccounting.DenyCode.NONE);
        }

        private static SingleResult policyFailure(LlmRequestAccounting.DenyCode denyCode, String error) {
            return new SingleResult(false, "", "Billing denied: " + error,
                    0, 0, 0, 0L, "", "", "error", true, denyCode);
        }
    }

    private record StreamResult(boolean success, String content, String error, int httpStatus,
            int promptTokens, int completionTokens, long latencyMs,
            boolean emittedContent, String requestBody, String responseBody, String finishReason,
            boolean policyRejected, LlmRequestAccounting.DenyCode denyCode) {
        private static StreamResult success(String content, int promptTokens, int completionTokens,
                long latencyMs, String requestBody, String responseBody, String finishReason) {
            return new StreamResult(true, content, "", 200, promptTokens, completionTokens,
                    latencyMs, true, requestBody, responseBody,
                    finishReason, false, LlmRequestAccounting.DenyCode.NONE);
        }

        private static StreamResult failure(String error, int status, long latencyMs, boolean emitted) {
            return failure(error, status, latencyMs, emitted, "", "");
        }

        private static StreamResult failure(String error, int status, long latencyMs, boolean emitted,
                String requestBody, String responseBody) {
            return new StreamResult(false, "", error, status, 0, 0, latencyMs, emitted,
                    requestBody, responseBody, "error", false, LlmRequestAccounting.DenyCode.NONE);
        }

        private static StreamResult failure(String error, int status, long latencyMs, boolean emitted,
                String requestBody, String responseBody, String finishReason) {
            return new StreamResult(false, "", error, status, 0, 0, latencyMs, emitted, requestBody, responseBody,
                    finishReason == null ? "error" : finishReason, false,
                    LlmRequestAccounting.DenyCode.NONE);
        }

        private static StreamResult policyFailure(LlmRequestAccounting.DenyCode denyCode, String error) {
            return new StreamResult(false, "", "Billing denied: " + error, 0, 0, 0, 0L, false,
                    "", "", "error", true, denyCode);
        }
    }

    private static int saturatedAdd(int left, int right) {
        return right > 0 && left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedMultiply(long value, int multiplier) {
        if (value <= 0L || multiplier <= 0) return 0L;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }
}
