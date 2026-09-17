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
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class LlmOrchestrator implements AutoCloseable {
    private static final long RATE_LIMIT_COOLDOWN_MS = 60_000L;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_000;
    static final int ADAPTIVE_OUTPUT_STEP = 1_000;
    static final int MAX_ADAPTIVE_OUTPUT_TOKENS = 32_000;
    static final int DEFAULT_ADAPTIVE_OUTPUT_RETRIES = 3;
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final Set<CompletableFuture<?>> activeRequests = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final Map<String, ProviderRuntime> providers = new ConcurrentHashMap<>();
    private final Map<String, ProviderProfile> providerProfiles = new ConcurrentHashMap<>();
    private volatile PriorityRoutingConfig routingConfig = PriorityRoutingConfig.empty();
    private volatile LlmCapabilityPolicy capabilityPolicy = LlmCapabilityPolicy.empty();
    private volatile LlmRouteOptions globalDefaults = new LlmRouteOptions(null, null, 30, null, null);
    private final Map<UUID, Map<String, LlmRoute>> playerRoutePreferences = new ConcurrentHashMap<>();

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
        providerProfiles.keySet().retainAll(replacement.keySet());
        replacement.keySet().forEach(name -> providerProfiles.putIfAbsent(name, ProviderProfile.textOnly(name)));
    }

    /** Replace explicit provider/model capability profiles. Missing providers remain text-only. */
    public void replaceProviderProfiles(Map<String, ProviderProfile> profiles) {
        Map<String, ProviderProfile> replacement = new LinkedHashMap<>();
        for (String name : providers.keySet()) {
            ProviderProfile profile = profiles == null ? null : profiles.get(name);
            replacement.put(name, profile == null ? ProviderProfile.textOnly(name)
                    : new ProviderProfile(name, profile.capabilities(), profile.costRate(), profile.modelCostRates()));
        }
        providerProfiles.clear();
        providerProfiles.putAll(replacement);
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

    /**
     * Install administrator authorization for optional capabilities. Legacy
     * {@link #send(LlmRequest)} calls never request or activate these features.
     */
    public void setCapabilityPolicy(LlmCapabilityPolicy policy) {
        this.capabilityPolicy = policy == null ? LlmCapabilityPolicy.empty() : policy;
    }

    public LlmCapabilityPolicy getCapabilityPolicy() {
        return capabilityPolicy;
    }

    public boolean isWebSearchAllowed(String purpose) {
        return capabilityPolicy.allowsWebSearch(purpose);
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
        return resolveRoute(request).targetIds();
    }

    public LlmRoute resolveRoute(LlmRequest request) {
        if (!request.providerChain().isEmpty()) {
            return LlmRoute.sequential(request.providerChain())
                    .withDeadline(Math.max(LlmRoute.DEFAULT_DEADLINE_SECONDS, request.timeoutSeconds()));
        }
        LlmBillingContext billing = request.billingContext();
        if (billing != null && billing.principalKind() == LlmBillingContext.PrincipalKind.PLAYER) {
            try {
                UUID playerId = UUID.fromString(billing.principalId());
                String purpose = request.context() == null ? "CHAT" : request.context().purpose();
                LlmRoute preferred = getPlayerRoutePreference(playerId, purpose);
                if (preferred != null && routeProvidersExist(preferred)) return preferred;
            } catch (IllegalArgumentException ignored) {
                // Billing validation owns invalid player identities; route resolution falls back safely.
            }
        }
        return routingConfig.resolveRoute(request.context().purpose(), enabledTargetIds());
    }

    /** Install one player's route preference. Provider membership remains server-owned. */
    public void setPlayerRoutePreference(UUID playerId, String purpose, LlmRoute route) {
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        String key = purpose == null ? "" : purpose.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("purpose is required");
        if (route == null || route.isUnset()) {
            clearPlayerRoutePreference(playerId, key);
            return;
        }
        if (!routeProvidersExist(route)) throw new IllegalArgumentException("route contains an unavailable provider/model target");
        playerRoutePreferences.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>()).put(key, route);
    }

    public void clearPlayerRoutePreference(UUID playerId, String purpose) {
        if (playerId == null) return;
        Map<String, LlmRoute> routes = playerRoutePreferences.get(playerId);
        if (routes == null) return;
        routes.remove(purpose == null ? "" : purpose.trim());
        if (routes.isEmpty()) playerRoutePreferences.remove(playerId, routes);
    }

    public LlmRoute getPlayerRoutePreference(UUID playerId, String purpose) {
        if (playerId == null) return null;
        Map<String, LlmRoute> routes = playerRoutePreferences.get(playerId);
        return routes == null ? null : routes.get(purpose == null ? "" : purpose.trim());
    }

    private boolean routeProvidersExist(LlmRoute route) {
        if (route == null) return false;
        Set<String> seen = new java.util.HashSet<>();
        return route.stages().stream().allMatch(stage -> stage.candidates().stream().allMatch(target ->
                target.model() != null && !target.model().isBlank()
                        && providers.containsKey(target.provider())
                        && providers.get(target.provider()).spec.models().contains(target.model())
                        && seen.add(target.id())));
    }

    public List<String> getTargetIds() {
        return Collections.unmodifiableList(enabledTargetIds());
    }

    private List<String> enabledTargetIds() {
        return providers.values().stream()
                .sorted(java.util.Comparator.comparing(runtime -> runtime.spec.name()))
                .flatMap(runtime -> runtime.spec.models().stream()
                        .map(model -> LlmTarget.of(runtime.spec.name(), model).id()))
                .toList();
    }

    Map<String, ProviderRuntime> providerSnapshot() { return Map.copyOf(providers); }

    @Override public void close() {
        closed = true;
        activeRequests.forEach(future -> future.cancel(true));
        activeRequests.clear();
        providers.values().forEach(ProviderRuntime::clearOutputBudgets);
    }

    public Set<String> getProviderNames() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    public ProviderSpec getProviderSpec(String name) {
        ProviderRuntime runtime = providers.get(name);
        return runtime == null ? null : runtime.spec;
    }

    public ProviderProfile getProviderProfile(String name) {
        return providerProfiles.getOrDefault(name, ProviderProfile.textOnly(name));
    }

    public boolean isProviderWebSearchCapable(String name) {
        ProviderRuntime provider = providers.get(name);
        if (provider == null) return false;
        ProviderCapabilities.HostedWebSearch declared = getProviderProfile(name).capabilities().webSearch();
        HostedWebSearchAdapters.HostedWebSearchAdapter adapter =
                HostedWebSearchAdapters.find(declared.adapterId());
        return declared.enabled() && adapter != null && adapter.supportsFormat(provider.spec.format());
    }

    /** Resolve one provider attempt using test > purpose > provider/global precedence. */
    public LlmResolvedParameters resolveParameters(LlmRequest request, String providerName) {
        String purpose = request == null ? "CHAT" : request.context().purpose();
        return resolveParameters(request, providerName, routingConfig.resolveOptions(purpose));
    }

    private LlmResolvedParameters resolveParameters(LlmRequest request, String providerName,
            LlmRouteOptions purposeOverrides) {
        LlmRequest safeRequest = request == null ? LlmRequest.routed(List.of(), LlmRequestContext.chat()) : request;
        String resolvedProvider = providerName == null ? "" : providerName.trim();
        String selectedModel = "";
        if (!resolvedProvider.isEmpty()) {
            try {
                LlmTarget target = LlmTarget.parse(resolvedProvider);
                resolvedProvider = target.provider();
                selectedModel = target.model();
            }
            catch (IllegalArgumentException ignored) { }
        }
        ProviderSpec provider = getProviderSpec(resolvedProvider);
        if (provider != null && !selectedModel.isBlank()) provider = provider.forModel(selectedModel);
        LlmRouteOptions effective = effectiveOptions(safeRequest, provider, purposeOverrides);

        int timeout = effective.timeoutSeconds() == null ? 30 : effective.timeoutSeconds();
        int maxOutput = effective.maxOutputTokens() == null
                ? adaptiveOutputTokens(safeRequest, provider) : effective.maxOutputTokens();
        int reserve = effective.outputReserveTokens() == null
                ? maxOutput : effective.outputReserveTokens();
        int inputBudget = effective.inputBudgetTokens() == null
                ? LlmResolvedParameters.UNBOUNDED_INPUT : effective.inputBudgetTokens();
        Integer contextWindow = provider == null ? null : provider.contextWindowTokens();
        if (contextWindow != null) {
            inputBudget = Math.min(inputBudget, Math.max(0, contextWindow - Math.max(reserve, maxOutput)));
        }
        return new LlmResolvedParameters(resolvedProvider, effective.temperature(), maxOutput,
                timeout, inputBudget, reserve, contextWindow);
    }

    private LlmRouteOptions effectiveOptions(LlmRequest request, ProviderSpec provider,
            LlmRouteOptions purposeOptions) {
        LlmRouteOptions providerDefaults = provider == null ? LlmRouteOptions.empty()
                : new LlmRouteOptions(provider.temperature(), provider.maxTokens(), null, null, null);
        return globalDefaults.overlay(providerDefaults).overlay(purposeOptions).overlay(request.requestOverrides());
    }

    boolean usesAdaptiveOutputBudget(LlmRequest request, ProviderSpec provider) {
        return effectiveOptions(request, provider,
                routingConfig.resolveOptions(request.context().purpose())).maxOutputTokens() == null;
    }

    private int adaptiveOutputTokens(LlmRequest request, ProviderSpec provider) {
        ProviderRuntime runtime = provider == null ? null : providers.get(provider.name());
        int learned = runtime == null ? DEFAULT_MAX_OUTPUT_TOKENS
                : runtime.outputBudget(request.context().purpose(), provider.model());
        int seed = request.adaptiveOutputSeedTokens() == null
                ? DEFAULT_MAX_OUTPUT_TOKENS : request.adaptiveOutputSeedTokens();
        return Math.max(1, Math.min(Math.max(seed, learned), adaptiveOutputCeiling(request, provider)));
    }

    int adaptiveOutputCeiling(LlmRequest request, ProviderSpec provider) {
        if (provider == null || provider.contextWindowTokens() == null) return MAX_ADAPTIVE_OUTPUT_TOKENS;
        long inputTokens = estimateInputTokens(request);
        return (int) Math.max(0L, Math.min(MAX_ADAPTIVE_OUTPUT_TOKENS,
                provider.contextWindowTokens().longValue() - inputTokens));
    }

    void recordTruncatedOutputBudget(LlmRequest request, ProviderRuntime runtime, String model,
            int attemptedTokens) {
        if (!closed && providers.get(runtime.spec.name()) == runtime) runtime.recordTruncatedOutputBudget(
                request.context().purpose(), model, attemptedTokens);
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

    /** Finalize once against every possible recipient; all retries/racers receive the same messages. */
    public LlmMessageFinalization finalizeDraftForRoute(LlmMessageDraft draft, LlmRequest request,
            LlmMessageFinalizer.TokenEstimator estimator) {
        int budget = resolveParameters(request, "").inputBudgetTokens();
        for (LlmRoute.Stage stage : resolveRoute(request).stages()) {
            for (LlmRoute.Target target : stage.candidates()) {
                ProviderRuntime runtime = providers.get(target.provider());
                if (runtime == null) continue;
                String model = target.model().isBlank() ? runtime.spec.model() : target.model();
                ProviderSpec selected = runtime.spec.forModel(model);
                LlmResolvedParameters parameters = resolveParameters(request, target.id());
                int inputBudget = parameters.inputBudgetTokens();
                if (usesAdaptiveOutputBudget(request, selected) && parameters.contextWindowTokens() != null) {
                    int retries = target.retries() == null ? DEFAULT_ADAPTIVE_OUTPUT_RETRIES : target.retries();
                    int outputHeadroom = Math.min(MAX_ADAPTIVE_OUTPUT_TOKENS,
                            parameters.maxOutputTokens() + retries * ADAPTIVE_OUTPUT_STEP);
                    inputBudget = Math.min(inputBudget, Math.max(0,
                            parameters.contextWindowTokens() - Math.max(parameters.outputReserveTokens(), outputHeadroom)));
                }
                budget = Math.min(budget, inputBudget);
            }
        }
        return LlmMessageFinalizer.finalize(draft, budget, estimator);
    }

    public CompletableFuture<LlmResponse> send(LlmRequest request) {
        return sendRouted(request, null);
    }

    private CompletableFuture<LlmResponse> sendRouted(LlmRequest request, Consumer<String> onDelta) {
        return mapCancellable(executeRoute(request, onDelta, null, false), result -> {
            LlmRequestLogger.publish("llm-core", request, result.response());
            return result.response();
        });
    }

    private CompletableFuture<LlmRouteExecutor.Result> executeRoute(LlmRequest request, Consumer<String> onDelta,
            Map<String, HostedWebSearchAdapters.HostedWebSearchAdapter> searchAdapters, boolean requireSearch) {
        if (closed) return CompletableFuture.completedFuture(new LlmRouteExecutor.Result(
                LlmResponse.failure("LLM runtime is closed", List.of()), HostedWebSearchAdapters.Evidence.none(), false));
        CompletableFuture<LlmRouteExecutor.Result> future = new LlmRouteExecutor(
                this, request, resolveRoute(request), onDelta, searchAdapters, requireSearch).start();
        activeRequests.add(future);
        future.whenComplete((result, failure) -> activeRequests.remove(future));
        if (closed) future.cancel(true);
        return future;
    }

    static <T, U> CompletableFuture<U> mapCancellable(CompletableFuture<T> source,
            java.util.function.Function<T, U> mapper) {
        CompletableFuture<U> mapped = source.thenApply(mapper);
        mapped.whenComplete((result, failure) -> { if (mapped.isCancelled()) source.cancel(true); });
        return mapped;
    }

    /**
     * Execute an additive typed exchange. Hosted Web Search is admitted only when
     * explicitly requested, allowed for the purpose, and declared by a compatible
     * provider profile. Capability skips occur before credential selection, HTTP,
     * attempt creation, or accounting reservation.
     */
    public CompletableFuture<LlmExchangeResponse> exchange(LlmExchangeRequest exchangeRequest) {
        LlmExchangeRequest safe = exchangeRequest == null
                ? LlmExchangeRequest.text(null) : exchangeRequest;
        LlmRequest request = safe.request();
        LlmHostedWebSearchRequest search = safe.webSearch();
        if (!search.requested()) {
            return mapCancellable(send(request), response -> new LlmExchangeResponse(response,
                    LlmExchangeResponse.WebSearchStatus.NOT_REQUESTED, 0, List.of(), List.of(), List.of(),
                    response.success() ? LlmExchangeResponse.ErrorCode.NONE
                            : LlmExchangeResponse.ErrorCode.PROVIDER_FAILURE,
                    response.success() ? "" : response.error()));
        }

        String purpose = request.context() == null ? "" : request.context().purpose();
        List<LlmRoutingDecision> decisions = new ArrayList<>();
        if (!capabilityPolicy.allowsWebSearch(purpose)) {
            decisions.add(new LlmRoutingDecision("", LlmRoutingDecision.Code.POLICY_DISABLED,
                    "Web Search is disabled for purpose '" + purpose + "'"));
            if (search.requirement() == LlmHostedWebSearchRequest.Requirement.REQUIRED) {
                return completedExchangeFailure(request, LlmExchangeResponse.WebSearchStatus.POLICY_DISABLED,
                        LlmExchangeResponse.ErrorCode.FEATURE_DISABLED, "Web Search is disabled for this purpose",
                        decisions, List.of());
            }
            decisions.add(new LlmRoutingDecision("", LlmRoutingDecision.Code.DEGRADED_TO_TEXT,
                    "Preferred Web Search degraded to text-only because policy disabled it"));
            return textExchange(request, decisions);
        }

        List<String> originalChain = resolveChain(request);
        Map<String, HostedWebSearchAdapters.HostedWebSearchAdapter> capable = new LinkedHashMap<>();
        for (String targetId : new java.util.LinkedHashSet<>(originalChain)) {
            LlmTarget target = LlmTarget.parse(targetId);
            String providerName = target.provider();
            ProviderRuntime runtime = providers.get(providerName);
            if (runtime == null) {
                decisions.add(new LlmRoutingDecision(providerName, LlmRoutingDecision.Code.PROVIDER_NOT_FOUND,
                        "Provider is not registered"));
                continue;
            }
            ProviderCapabilities.HostedWebSearch declared = getProviderProfile(providerName)
                    .capabilities().webSearch();
            if (!declared.enabled()) {
                decisions.add(new LlmRoutingDecision(providerName,
                        LlmRoutingDecision.Code.WEB_SEARCH_NOT_DECLARED,
                        "Provider profile does not declare Hosted Web Search"));
                continue;
            }
            HostedWebSearchAdapters.HostedWebSearchAdapter adapter =
                    HostedWebSearchAdapters.find(declared.adapterId());
            if (adapter == null) {
                decisions.add(new LlmRoutingDecision(providerName,
                        LlmRoutingDecision.Code.WEB_SEARCH_ADAPTER_UNAVAILABLE,
                        "Unknown Hosted Web Search adapter '" + declared.adapterId() + "'"));
                continue;
            }
            if (!adapter.supportsFormat(runtime.spec.format())) {
                decisions.add(new LlmRoutingDecision(providerName,
                        LlmRoutingDecision.Code.WEB_SEARCH_ADAPTER_INCOMPATIBLE,
                        "Adapter '" + declared.adapterId() + "' is incompatible with format '"
                                + runtime.spec.format() + "'"));
                continue;
            }
            capable.put(providerName, adapter);
        }

        if (capable.isEmpty()) {
            if (search.requirement() == LlmHostedWebSearchRequest.Requirement.REQUIRED) {
                return completedExchangeFailure(request, LlmExchangeResponse.WebSearchStatus.NO_CAPABLE_PROVIDER,
                        LlmExchangeResponse.ErrorCode.NO_CAPABLE_PROVIDER,
                        "No routed provider explicitly supports Hosted Web Search", decisions, List.of());
            }
            decisions.add(new LlmRoutingDecision("", LlmRoutingDecision.Code.DEGRADED_TO_TEXT,
                    "Preferred Web Search degraded because no routed provider declared it"));
            return textExchange(request, decisions);
        }

        boolean required = search.requirement() == LlmHostedWebSearchRequest.Requirement.REQUIRED;
        return mapCancellable(executeRoute(request, null, capable, required), result -> {
            LlmResponse response = result.response();
            boolean used = response.success() && result.evidence().used();
            boolean degraded = response.success() && !used;
            if (degraded) decisions.add(new LlmRoutingDecision(response.provider(),
                    LlmRoutingDecision.Code.DEGRADED_TO_TEXT, "Preferred Web Search returned a text-only result"));
            LlmExchangeResponse.WebSearchStatus status = used ? LlmExchangeResponse.WebSearchStatus.USED
                    : degraded ? LlmExchangeResponse.WebSearchStatus.DEGRADED
                    : LlmExchangeResponse.WebSearchStatus.REQUESTED_NOT_USED;
            LlmExchangeResponse.ErrorCode error = response.success() ? LlmExchangeResponse.ErrorCode.NONE
                    : required && result.missingSearch() ? LlmExchangeResponse.ErrorCode.REQUIRED_FEATURE_NOT_USED
                    : LlmExchangeResponse.ErrorCode.PROVIDER_FAILURE;
            return finishExchange(request, new LlmExchangeResponse(response, status,
                    used ? result.evidence().uses() : 0, used ? result.evidence().sources() : List.of(),
                    decisions, degraded ? List.of("web_search") : List.of(), error,
                    response.success() ? "" : response.error()));
        });
    }

    public CompletableFuture<LlmResponse> sendStreaming(LlmRequest request, Consumer<String> onDelta) {
        return sendRouted(request, onDelta == null ? delta -> { } : onDelta);
    }

    /** Includes every race candidate and retry; later stages may revisit the same provider. */
    public LlmCallBudget estimateWorstCaseBudget(LlmRequest request) {
        return estimateWorstCaseBudget(request, resolveRoute(request),
                routingConfig.resolveOptions(request.context().purpose()));
    }

    private LlmCallBudget estimateWorstCaseBudget(LlmRequest request, LlmRoute route,
            LlmRouteOptions purposeOptions) {
        int calls = 0;
        long tokens = 0L;
        for (LlmRoute.Stage stage : route.stages()) {
            for (LlmRoute.Target target : stage.candidates()) {
                ProviderRuntime provider = providers.get(target.provider());
                if (provider == null || provider.credentials.isEmpty()) continue;
                int attempts = provider.attemptCount(target);
                String model = target.model().isBlank() ? provider.spec.model() : target.model();
                if (!provider.spec.models().contains(model)) continue;
                ProviderSpec selected = provider.spec.forModel(model);
                boolean adaptive = effectiveOptions(request, selected, purposeOptions).maxOutputTokens() == null;
                if (adaptive && target.retries() == null) {
                    int variants = provider.spec.requestMode() == ProviderSpec.RequestMode.PARALLEL
                            ? Math.min(LlmRoute.MAX_PARALLEL, provider.credentials.size()) : 1;
                    attempts = Math.min(LlmRoute.MAX_ATTEMPTS,
                            attempts + variants * DEFAULT_ADAPTIVE_OUTPUT_RETRIES);
                }
                LlmResolvedParameters parameters = resolveParameters(request, target.id(), purposeOptions);
                if (adaptive) {
                    // Concurrent requests may learn a higher budget after the host declares its causal ceiling.
                    parameters = new LlmResolvedParameters(parameters.provider(), parameters.temperature(),
                            parameters.maxOutputTokens(), parameters.timeoutSeconds(), parameters.inputBudgetTokens(),
                            Math.max(parameters.outputReserveTokens(), adaptiveOutputCeiling(request, selected)),
                            parameters.contextWindowTokens());
                }
                LlmRequestAccounting.AttemptEstimate estimate = attemptEstimate(
                        request, selected, parameters, costRate(selected));
                calls = saturatedAdd(calls, attempts);
                tokens = saturatedAdd(tokens, saturatedMultiply(estimate.totalTokens(), attempts));
            }
        }
        return new LlmCallBudget(Math.min(LlmRoute.MAX_ATTEMPTS, calls), tokens);
    }

    /** Conservative per-call bound for a consumer whose causal root crosses multiple purposes. */
    public LlmCallBudget estimateMaximumRouteBudget(LlmRequest template) {
        LlmCallBudget maximum = estimateWorstCaseBudget(template);
        Set<String> purposes = new java.util.LinkedHashSet<>(routingConfig.purposeRoutes().keySet());
        purposes.addAll(routingConfig.purposeOptions().keySet());
        // Resolve the default directly: an empty request purpose would normalize to CHAT.
        purposes.add(null);
        LlmRequest configuredRequest = new LlmRequest(template.messages(), List.of(), null, null, 0,
                template.context(), LlmRouteOptions.empty(), template.billingContext(),
                template.adaptiveOutputSeedTokens());
        for (String purpose : purposes) {
            LlmRoute route = routingConfig.resolveRoute(purpose, enabledTargetIds());
            LlmRouteOptions options = routingConfig.resolveOptions(purpose);
            LlmCallBudget budget = estimateWorstCaseBudget(template, route, options);
            LlmCallBudget configured = estimateWorstCaseBudget(configuredRequest, route, options);
            maximum = new LlmCallBudget(Math.max(maximum.maxCalls(), Math.max(budget.maxCalls(), configured.maxCalls())),
                    Math.max(maximum.maxTokens(), Math.max(budget.maxTokens(), configured.maxTokens())));
        }
        return maximum;
    }

    /** Estimate using the same player preference that execution will resolve after billing is attached. */
    public LlmCallBudget estimateMaximumRouteBudget(LlmRequest template,
            LlmBillingContext.PrincipalKind principalKind, String principalId) {
        LlmBillingContext.PrincipalKind kind = principalKind == null
                ? LlmBillingContext.PrincipalKind.UNSPECIFIED : principalKind;
        LlmBillingContext routingIdentity = kind == LlmBillingContext.PrincipalKind.PLAYER
                ? LlmBillingContext.player(principalId, "route-estimate", 1, 1L)
                : kind == LlmBillingContext.PrincipalKind.UNSPECIFIED
                        ? LlmBillingContext.unspecified()
                        : LlmBillingContext.system(kind, "route-estimate", 1, 1L);
        return estimateMaximumRouteBudget(template.withBillingContext(routingIdentity));
    }

    /** Preferred search may consume the route again as text, sharing one deadline and attempt ceiling. */
    public LlmCallBudget estimateWorstCaseBudget(LlmExchangeRequest exchange) {
        LlmCallBudget budget = estimateWorstCaseBudget(exchange.request());
        if (exchange.webSearch().requested()
                && exchange.webSearch().requirement() == LlmHostedWebSearchRequest.Requirement.PREFERRED) {
            return new LlmCallBudget(Math.min(LlmRoute.MAX_ATTEMPTS, saturatedAdd(budget.maxCalls(), budget.maxCalls())),
                    saturatedAdd(budget.maxTokens(), budget.maxTokens()));
        }
        return budget;
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

    private CompletableFuture<LlmExchangeResponse> textExchange(LlmRequest request, List<LlmRoutingDecision> decisions) {
        return mapCancellable(executeRoute(request, null, null, false), result -> {
            LlmResponse response = result.response();
            return finishExchange(request, new LlmExchangeResponse(response, LlmExchangeResponse.WebSearchStatus.DEGRADED,
                    0, List.of(), decisions, List.of("web_search"),
                    response.success() ? LlmExchangeResponse.ErrorCode.NONE : LlmExchangeResponse.ErrorCode.PROVIDER_FAILURE,
                    response.success() ? "" : response.error()));
        });
    }

    private CompletableFuture<LlmExchangeResponse> completedExchangeFailure(LlmRequest request,
            LlmExchangeResponse.WebSearchStatus status, LlmExchangeResponse.ErrorCode code, String error,
            List<LlmRoutingDecision> decisions, List<LlmResponse.Attempt> attempts) {
        LlmResponse failure = LlmResponse.failure(error, attempts);
        return CompletableFuture.completedFuture(finishExchange(request, new LlmExchangeResponse(failure, status, 0,
                List.of(), decisions, List.of(), code, error)));
    }

    private static LlmExchangeResponse finishExchange(LlmRequest request, LlmExchangeResponse response) {
        LlmRequestLogger.publish("llm-core", request, response.legacyResponse());
        return response;
    }

    private LlmRequestAccounting.AttemptEstimate attemptEstimate(LlmRequest request,
            ProviderSpec provider, LlmResolvedParameters parameters, LlmCostRate rate) {
        long inputTokens = estimateInputTokens(request);
        long outputTokens = Math.max(parameters.maxOutputTokens(), parameters.outputReserveTokens());
        return new LlmRequestAccounting.AttemptEstimate(provider.name(), inputTokens,
                outputTokens, rate.weightedEstimate(inputTokens, outputTokens));
    }

    private static long estimateInputTokens(LlmRequest request) {
        long inputTokens = 0L;
        for (LlmMessage message : request.messages()) {
            inputTokens = saturatedAdd(inputTokens,
                    Math.max(0, LlmMessageFinalizer.CONSERVATIVE_ESTIMATOR.estimate(message)));
        }
        return inputTokens;
    }

    private LlmCostRate costRate(ProviderSpec provider) {
        ProviderProfile profile = providerProfiles.get(provider.name());
        return profile == null ? LlmCostRate.DEFAULT : profile.costRateFor(provider.model());
    }

    private static LlmRequestAccounting.AttemptUsage attemptUsage(
            LlmRequestAccounting.Reservation reservation, LlmCostRate rate,
            int promptTokens, int completionTokens, LlmCacheUsage cacheUsage) {
        if (promptTokens > 0 || completionTokens > 0) {
            Long cacheWeighted = rate.weightedTokens(cacheUsage, promptTokens, completionTokens);
            return new LlmRequestAccounting.AttemptUsage(promptTokens, completionTokens, 0L,
                    cacheWeighted == null ? rate.weightedTokens(promptTokens, completionTokens) : cacheWeighted);
        }
        return new LlmRequestAccounting.AttemptUsage(0L, 0L, reservation.reservedTokens(),
                reservation.reservedCostUnits());
    }

    private static void finishAccounting(LlmRequest request,
            LlmRequestAccounting.Reservation reservation, boolean success,
            LlmCostRate rate, int promptTokens, int completionTokens, LlmCacheUsage cacheUsage) {
        if (!success) {
            LlmRequestAccounting.release(request, reservation);
            return;
        }
        LlmRequestAccounting.settle(request, reservation,
                attemptUsage(reservation, rate, promptTokens, completionTokens, cacheUsage));
    }

    record AttemptResult(SingleResult result, HostedWebSearchAdapters.Evidence evidence,
            boolean emittedContent, long retryAfterMs) {
        static AttemptResult failure(String error) {
            return new AttemptResult(SingleResult.failure(error == null ? "Provider request failed" : error, 0, 0),
                    HostedWebSearchAdapters.Evidence.none(), false, -1);
        }
    }

    CompletableFuture<AttemptResult> sendRouteAttempt(LlmRequest request, ProviderSpec provider,
            ProviderSpec.Credential credential, Consumer<String> onDelta,
            HostedWebSearchAdapters.HostedWebSearchAdapter adapter, boolean racing) {
        if (adapter != null) {
            return mapCancellable(sendSearchSingle(request, provider, credential, adapter), value ->
                    new AttemptResult(value.result, value.evidence, false, value.result.retryAfterMs));
        }
        if (onDelta != null && "openai".equals(provider.format())) {
            return mapCancellable(sendSingleStreaming(request, provider, credential, onDelta), value ->
                    new AttemptResult(new SingleResult(value.success, value.content, value.error, value.httpStatus,
                            value.promptTokens, value.completionTokens, value.latencyMs, value.requestBody,
                            value.responseBody, value.finishReason, value.policyRejected, value.denyCode,
                            value.retryAfterMs, value.cacheUsage),
                            HostedWebSearchAdapters.Evidence.none(), value.emittedContent, value.retryAfterMs));
        }
        return mapCancellable(sendSingle(request, provider, credential, racing), value -> {
            if (onDelta != null && value.success && !value.content.isBlank()
                    && !"length".equals(value.finishReason)) onDelta.accept(value.content);
            return new AttemptResult(value, HostedWebSearchAdapters.Evidence.none(), false, value.retryAfterMs);
        });
    }

    private static boolean uncertainUsage(Throwable failure) {
        if (failure == null) return false;
        boolean uncertain = false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.net.http.HttpConnectTimeoutException) return false;
            uncertain |= current instanceof CancellationException || current instanceof TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException;
        }
        return uncertain;
    }

    private static void closeStream(InputStream stream) {
        if (stream == null) return;
        try { stream.close(); } catch (Exception ignored) { }
    }

    private static long retryAfterMs(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (value.isEmpty()) return -1;
        try {
            double seconds = Double.parseDouble(value);
            if (Double.isFinite(seconds) && seconds >= 0) return (long) (Math.min(3600, seconds) * 1000);
        } catch (NumberFormatException ignored) { }
        try {
            long millis = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - System.currentTimeMillis();
            return Math.max(0, Math.min(3_600_000L, millis));
        } catch (RuntimeException ignored) { return -1; }
    }

    private CompletableFuture<SingleResult> sendSingle(LlmRequest request, ProviderSpec provider,
            ProviderSpec.Credential credential, boolean racing) {
        String format = provider.format();
        String url = resolveUrl(provider, credential.key());
        LlmResolvedParameters parameters = resolveParameters(request, new LlmTarget(provider.name(), provider.model()).id());
        JsonObject body = buildBody(format, provider, request, parameters);
        if (racing && !"gemini".equals(format)) body.addProperty("stream", false);
        String requestBody = GSON.toJson(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(parameters.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        applyHeaders(builder, format, credential.key());
        LlmCostRate rate = costRate(provider);
        LlmRequestAccounting.Reservation reservation = LlmRequestAccounting.reserve(request,
                attemptEstimate(request, provider, parameters, rate));
        if (!reservation.allowed()) {
            return CompletableFuture.completedFuture(
                    SingleResult.policyFailure(reservation.denyCode(), reservation.reason()));
        }
        long startedAt = System.currentTimeMillis();
        CompletableFuture<SingleResult> execution;
        CompletableFuture<HttpResponse<String>> http = null;
        try {
            http = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
            execution = http.thenApply(response -> parseResponse(format, response.statusCode(), response.body(),
                            System.currentTimeMillis() - startedAt, requestBody).withRetryAfter(retryAfterMs(response)));
        } catch (RuntimeException failure) {
            execution = CompletableFuture.completedFuture(SingleResult.failure(
                    rootMessage(failure), 0, System.currentTimeMillis() - startedAt, requestBody, ""));
        }
        CompletableFuture<?> transport = http;
        CompletableFuture<SingleResult> source = execution;
        CompletableFuture<SingleResult> settled = new CompletableFuture<>();
        source.orTimeout(parameters.timeoutSeconds(), TimeUnit.SECONDS);
        source.whenComplete((result, throwable) -> {
            if (throwable != null && transport != null) transport.cancel(true);
            finishAccounting(request, reservation,
                    throwable == null && result != null && result.success
                            || transport != null && uncertainUsage(throwable),
                    rate,
                    result == null ? 0 : result.promptTokens, result == null ? 0 : result.completionTokens,
                    result == null ? LlmCacheUsage.unknown("request did not complete") : result.cacheUsage);
            settled.complete(throwable == null ? result : SingleResult.failure(rootMessage(throwable), 0,
                        System.currentTimeMillis() - startedAt, requestBody, ""));
        });
        settled.whenComplete((result, failure) -> { if (settled.isCancelled()) source.cancel(true); });
        return settled;
    }

    private CompletableFuture<SearchSingleResult> sendSearchSingle(LlmRequest request, ProviderSpec provider,
            ProviderSpec.Credential credential, HostedWebSearchAdapters.HostedWebSearchAdapter adapter) {
        String format = provider.format();
        String url = resolveUrl(provider, credential.key());
        LlmResolvedParameters parameters = resolveParameters(request, new LlmTarget(provider.name(), provider.model()).id());
        JsonObject body = buildBody(format, provider, request, parameters);
        adapter.apply(body);
        if (!"gemini".equals(format)) body.addProperty("stream", false);
        String requestBody = GSON.toJson(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(parameters.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        applyHeaders(builder, format, credential.key());
        LlmCostRate rate = costRate(provider);
        LlmRequestAccounting.Reservation reservation = LlmRequestAccounting.reserve(request,
                attemptEstimate(request, provider, parameters, rate));
        if (!reservation.allowed()) {
            return CompletableFuture.completedFuture(new SearchSingleResult(
                    SingleResult.policyFailure(reservation.denyCode(), reservation.reason()),
                    HostedWebSearchAdapters.Evidence.none()));
        }
        long startedAt = System.currentTimeMillis();
        CompletableFuture<SearchSingleResult> execution;
        CompletableFuture<HttpResponse<String>> http = null;
        try {
            http = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
            execution = http.thenApply(response -> {
                        SingleResult result = parseResponse(format, response.statusCode(), response.body(),
                                System.currentTimeMillis() - startedAt, requestBody).withRetryAfter(retryAfterMs(response));
                        if (!result.success) return new SearchSingleResult(result,
                                HostedWebSearchAdapters.Evidence.none());
                        try {
                            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
                            return new SearchSingleResult(result, adapter.parse(responseJson, provider.name()));
                        } catch (RuntimeException malformedEvidence) {
                            return new SearchSingleResult(result, HostedWebSearchAdapters.Evidence.none());
                        }
                    });
        } catch (RuntimeException failure) {
            execution = CompletableFuture.completedFuture(SearchSingleResult.failure(
                    rootMessage(failure), System.currentTimeMillis() - startedAt, requestBody));
        }
        CompletableFuture<?> transport = http;
        CompletableFuture<SearchSingleResult> source = execution;
        CompletableFuture<SearchSingleResult> settled = new CompletableFuture<>();
        source.orTimeout(parameters.timeoutSeconds(), TimeUnit.SECONDS);
        source.whenComplete((result, throwable) -> {
            if (throwable != null && transport != null) transport.cancel(true);
            finishAccounting(request, reservation,
                    throwable == null && result != null && result.result.success
                            || transport != null && uncertainUsage(throwable),
                    rate,
                    result == null ? 0 : result.result.promptTokens, result == null ? 0 : result.result.completionTokens,
                    result == null ? LlmCacheUsage.unknown("request did not complete") : result.result.cacheUsage);
            settled.complete(throwable == null ? result : SearchSingleResult.failure(rootMessage(throwable),
                        System.currentTimeMillis() - startedAt, requestBody));
        });
        settled.whenComplete((result, failure) -> { if (settled.isCancelled()) source.cancel(true); });
        return settled;
    }

    private CompletableFuture<StreamResult> sendSingleStreaming(LlmRequest request, ProviderSpec provider,
            ProviderSpec.Credential credential, Consumer<String> onDelta) {
        LlmResolvedParameters parameters = resolveParameters(request, new LlmTarget(provider.name(), provider.model()).id());
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
        LlmCostRate rate = costRate(provider);
        LlmRequestAccounting.Reservation reservation = LlmRequestAccounting.reserve(request,
                attemptEstimate(request, provider, parameters, rate));
        if (!reservation.allowed()) {
            return CompletableFuture.completedFuture(
                    StreamResult.policyFailure(reservation.denyCode(), reservation.reason()));
        }
        long startedAt = System.currentTimeMillis();
        CompletableFuture<StreamResult> execution;
        AtomicReference<InputStream> activeStream = new AtomicReference<>();
        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();
        AtomicReference<LlmCacheUsage> cacheUsage = new AtomicReference<>(
                LlmCacheUsage.unknown("stream did not report cache usage"));
        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<HttpResponse<InputStream>> http = null;
        try {
            http = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            http.whenComplete((response, failure) -> {
                if (response != null) {
                    activeStream.set(response.body());
                    if (stopped.get()) closeStream(response.body());
                }
            });
            execution = http.thenApplyAsync(response -> {
            long latency;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try {
                    String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    latency = System.currentTimeMillis() - startedAt;
                    return StreamResult.failure(extractError(error), response.statusCode(), latency, false,
                            requestBody, error).withRetryAfter(retryAfterMs(response));
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
            boolean emitted = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) {
                        streamDone = true;
                        break;
                    }
                    chunkCount++;
                    JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                    if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
                        JsonObject usage = chunk.getAsJsonObject("usage");
                        if (usage.has("prompt_tokens") && !usage.get("prompt_tokens").isJsonNull()) {
                            promptTokens.accumulateAndGet(usage.get("prompt_tokens").getAsInt(), Math::max);
                        }
                        if (usage.has("completion_tokens") && !usage.get("completion_tokens").isJsonNull()) {
                            completionTokens.accumulateAndGet(usage.get("completion_tokens").getAsInt(), Math::max);
                        }
                        LlmCacheUsage parsedCache = parseCacheUsage("openai", usage,
                                promptTokens.get());
                        if (parsedCache.status() == LlmCacheUsage.Status.REPORTED) cacheUsage.set(parsedCache);
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
                        ? StreamResult.failure("LLM stream produced no content", -1, latency, emitted, requestBody,
                                responseBody, finishReason).withUsage(promptTokens.get(), completionTokens.get(), cacheUsage.get())
                        : StreamResult.success(accumulated.toString(), promptTokens.get(), completionTokens.get(),
                                latency, requestBody, responseBody, finishReason);
            } catch (Exception e) {
                String responseBody = buildStreamLogBody(provider.model(), accumulated.toString(),
                        reasoning.toString(), chunkCount, finishReason.isBlank() ? "error" : finishReason);
                return StreamResult.failure(rootMessage(e), e instanceof java.io.IOException ? 0 : -1,
                        System.currentTimeMillis() - startedAt, emitted,
                        requestBody, responseBody, "error").withUsage(promptTokens.get(), completionTokens.get(), cacheUsage.get());
            }
            });
        } catch (RuntimeException failure) {
            execution = CompletableFuture.completedFuture(StreamResult.failure(
                    rootMessage(failure), 0, System.currentTimeMillis() - startedAt, false, requestBody, ""));
        }
        CompletableFuture<?> transport = http;
        CompletableFuture<StreamResult> source = execution;
        CompletableFuture<StreamResult> settled = new CompletableFuture<>();
        source.orTimeout(parameters.timeoutSeconds(), TimeUnit.SECONDS);
        source.whenComplete((result, throwable) -> {
            stopped.set(true);
            closeStream(activeStream.get());
            if (throwable != null && transport != null) transport.cancel(true);
            finishAccounting(request, reservation,
                    promptTokens.get() > 0 || completionTokens.get() > 0
                            || throwable == null && result != null && (result.success || result.emittedContent
                                    || "length".equals(result.finishReason))
                            || transport != null && uncertainUsage(throwable),
                    rate,
                    promptTokens.get(), completionTokens.get(), cacheUsage.get());
            settled.complete(throwable == null ? result : StreamResult.failure(rootMessage(throwable), 0,
                        System.currentTimeMillis() - startedAt, false, requestBody, "")
                    .withUsage(promptTokens.get(), completionTokens.get(), cacheUsage.get()));
        });
        settled.whenComplete((result, failure) -> { if (settled.isCancelled()) source.cancel(true); });
        return settled;
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
                content = concatenateTextParts(candidate.getAsJsonObject("content").getAsJsonArray("parts"));
                finishReason = candidate.has("finishReason")
                        ? normalizeFinishReason(candidate.get("finishReason").getAsString()) : "";
                if (root.has("usageMetadata")) {
                    JsonObject usage = root.getAsJsonObject("usageMetadata");
                    promptTokens = getInt(usage, "promptTokenCount");
                    completionTokens = getInt(usage, "candidatesTokenCount");
                }
            } else if ("claude".equals(format) || "anthropic".equals(format)) {
                content = concatenateTextParts(root.getAsJsonArray("content"));
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
            JsonObject usage = "gemini".equals(format)
                    ? root.has("usageMetadata") ? root.getAsJsonObject("usageMetadata") : null
                    : root.has("usage") ? root.getAsJsonObject("usage") : null;
            return SingleResult.success(content, promptTokens, completionTokens, latencyMs, requestBody, body,
                    finishReason, parseCacheUsage(format, usage, promptTokens));
        } catch (Exception e) {
            return SingleResult.failure("Invalid provider response: " + rootMessage(e), -1, latencyMs, requestBody, body);
        }
    }

    private static String concatenateTextParts(JsonArray parts) {
        StringBuilder content = new StringBuilder();
        for (JsonElement element : parts) {
            if (!element.isJsonObject()) continue;
            JsonObject part = element.getAsJsonObject();
            if (part.has("thought") && part.get("thought").isJsonPrimitive()
                    && part.getAsJsonPrimitive("thought").isBoolean()
                    && part.get("thought").getAsBoolean()) continue;
            if (!part.has("text") || part.get("text").isJsonNull()
                    || !part.get("text").isJsonPrimitive()) continue;
            String text = part.get("text").getAsString();
            if (!text.isEmpty()) content.append(text);
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Provider response contains no text part");
        }
        return content.toString();
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

    static void applyFailure(CredentialRuntime credential, int status, long retryAfterMs) {
        int failures = credential.consecutiveFailures.incrementAndGet();
        long now = System.currentTimeMillis();
        if (status == 401 || status == 403) {
            credential.disabled = true;
        } else if (status == 429) {
            credential.cooldownUntil = now + (retryAfterMs >= 0 ? retryAfterMs : RATE_LIMIT_COOLDOWN_MS);
        } else if (status >= 500 || status == 0 || status == 408 || failures >= 3) {
            credential.cooldownUntil = now + Math.max(retryAfterMs, Math.min(5000L, 500L << Math.min(failures - 1, 4)));
        } else {
            credential.cooldownUntil = now + 1_000L;
        }
    }

    static boolean isCredentialRetryable(int status) {
        return status == 0 || status == 408 || status == 401 || status == 403 || status == 429 || status >= 500;
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

    static LlmCacheUsage parseCacheUsage(String format, JsonObject usage, int promptTokens) {
        if (usage == null) return LlmCacheUsage.unknown("provider response did not include usage");
        String normalized = format == null ? "" : format.toLowerCase(java.util.Locale.ROOT);
        if ("claude".equals(normalized) || "anthropic".equals(normalized)) {
            Long read = getLongOrNull(usage, "cache_read_input_tokens");
            Long write = getLongOrNull(usage, "cache_creation_input_tokens");
            if (read == null && write == null) return LlmCacheUsage.unknown("Anthropic cache fields were absent");
            long uncached = Math.max(0L, getInt(usage, "input_tokens"));
            long total = uncached + (read == null ? 0L : read) + (write == null ? 0L : write);
            return LlmCacheUsage.reported(read == null ? 0L : read, write == null ? 0L : write,
                    uncached, total);
        }
        if ("gemini".equals(normalized)) {
            Long read = getLongOrNull(usage, "cachedContentTokenCount");
            if (read == null) return LlmCacheUsage.unknown("Gemini cachedContentTokenCount was absent");
            long total = Math.max(promptTokens, getInt(usage, "promptTokenCount"));
            return LlmCacheUsage.reported(read, null, Math.max(0L, total - read), total);
        }
        Long hit = getLongOrNull(usage, "prompt_cache_hit_tokens");
        Long miss = getLongOrNull(usage, "prompt_cache_miss_tokens");
        if (hit != null || miss != null) {
            long read = hit == null ? 0L : hit;
            long uncached = miss == null ? Math.max(0L, promptTokens - read) : miss;
            return LlmCacheUsage.reported(read, null, uncached, Math.max(promptTokens, read + uncached));
        }
        if (usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()) {
            Long read = getLongOrNull(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
            if (read != null) {
                long total = Math.max(0L, promptTokens);
                return LlmCacheUsage.reported(read, null, Math.max(0L, total - read), total);
            }
        }
        return LlmCacheUsage.unknown("OpenAI-compatible cache fields were absent");
    }

    private static Long getLongOrNull(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        try { return Math.max(0L, object.get(key).getAsLong()); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    static final class ProviderRuntime {
        private static final long HEALTH_WINDOW_MS = 60L * 60L * 1000L;
        final ProviderSpec spec;
        final List<CredentialRuntime> credentials;
        private final AtomicInteger cursor = new AtomicInteger();
        private final Map<String, java.util.ArrayDeque<LatencySample>> successfulLatencies = new LinkedHashMap<>();
        private final Map<OutputBudgetKey, Integer> learnedOutputBudgets = new LinkedHashMap<>();

        private ProviderRuntime(ProviderSpec spec) {
            this.spec = spec;
            this.credentials = spec.credentials().stream()
                    .filter(ProviderSpec.Credential::isConfigured)
                    .map(CredentialRuntime::new)
                    .toList();
        }

        CredentialRuntime selectAvailable(long nowMs, Set<CredentialRuntime> excluded) {
            if (credentials.isEmpty()) return null;
            int start = Math.floorMod(cursor.getAndIncrement(), credentials.size());
            CredentialRuntime best = null;
            for (int offset = 0; offset < credentials.size(); offset++) {
                CredentialRuntime candidate = credentials.get((start + offset) % credentials.size());
                if (excluded.contains(candidate) || candidate.disabled || candidate.cooldownUntil > nowMs) continue;
                if (best == null || candidate.inflight.get() < best.inflight.get()) best = candidate;
            }
            return best;
        }

        List<CredentialRuntime> parallelCredentials() {
            if (credentials.isEmpty()) return List.of();
            int count = Math.min(LlmRoute.MAX_PARALLEL, credentials.size());
            List<CredentialRuntime> result = new ArrayList<>(count);
            int keyStart = Math.floorMod(cursor.getAndAdd(count), credentials.size());
            for (int index = 0; index < count; index++) {
                result.add(credentials.get((keyStart + index) % credentials.size()));
            }
            return List.copyOf(result);
        }

        int attemptCount(LlmRoute.Target target) {
            if (spec.requestMode() != ProviderSpec.RequestMode.PARALLEL) {
                return target.maxAttempts(credentials.size());
            }
            int variants = Math.min(LlmRoute.MAX_PARALLEL, credentials.size());
            int perVariant = target.retries() == null ? 1 : target.retries() + 1;
            return Math.min(LlmRoute.MAX_ATTEMPTS, variants * perVariant);
        }

        synchronized void recordSuccessfulLatency(String model, long nowMs, long latencyMs) {
            java.util.ArrayDeque<LatencySample> samples = successfulLatencies.computeIfAbsent(
                    model == null ? "" : model, ignored -> new java.util.ArrayDeque<>());
            pruneLatencies(samples, nowMs);
            samples.addLast(new LatencySample(nowMs, Math.max(0L, latencyMs)));
            while (samples.size() > 4096) samples.removeFirst();
        }

        synchronized Long averageSuccessfulLatency1h(String model) {
            java.util.ArrayDeque<LatencySample> samples = successfulLatencies.get(model == null ? "" : model);
            if (samples == null) return null;
            pruneLatencies(samples, System.currentTimeMillis());
            if (samples.isEmpty()) return null;
            return samples.stream().mapToLong(LatencySample::latencyMs).sum() / samples.size();
        }

        synchronized int outputBudget(String purpose, String model) {
            return learnedOutputBudgets.getOrDefault(new OutputBudgetKey(purpose, model), DEFAULT_MAX_OUTPUT_TOKENS);
        }

        synchronized void clearOutputBudgets() { learnedOutputBudgets.clear(); }

        synchronized void recordTruncatedOutputBudget(String purpose, String model, int attemptedTokens) {
            OutputBudgetKey key = new OutputBudgetKey(purpose, model);
            int next = Math.min(MAX_ADAPTIVE_OUTPUT_TOKENS, attemptedTokens + ADAPTIVE_OUTPUT_STEP);
            learnedOutputBudgets.merge(key, next, Math::max);
            while (learnedOutputBudgets.size() > 256) {
                learnedOutputBudgets.remove(learnedOutputBudgets.keySet().iterator().next());
            }
        }

        private record OutputBudgetKey(String purpose, String model) { }

        private void pruneLatencies(java.util.ArrayDeque<LatencySample> samples, long nowMs) {
            long cutoff = nowMs - HEALTH_WINDOW_MS;
            while (!samples.isEmpty() && samples.peekFirst().timeMs() < cutoff) {
                samples.removeFirst();
            }
        }

        private record LatencySample(long timeMs, long latencyMs) { }

    }

    static final class CredentialRuntime {
        final ProviderSpec.Credential spec;
        final AtomicInteger inflight = new AtomicInteger();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        volatile long cooldownUntil;
        volatile boolean disabled;

        private CredentialRuntime(ProviderSpec.Credential spec) {
            this.spec = spec;
        }
    }

    record SingleResult(boolean success, String content, String error, int httpStatus,
            int promptTokens, int completionTokens, long latencyMs, String requestBody, String responseBody,
            String finishReason, boolean policyRejected, LlmRequestAccounting.DenyCode denyCode, long retryAfterMs,
            LlmCacheUsage cacheUsage) {
        private SingleResult withRetryAfter(long millis) {
            return new SingleResult(success, content, error, httpStatus, promptTokens, completionTokens, latencyMs,
                    requestBody, responseBody, finishReason, policyRejected, denyCode, millis, cacheUsage);
        }
        private static SingleResult success(String content, int promptTokens, int completionTokens, long latencyMs,
                String requestBody, String responseBody, String finishReason, LlmCacheUsage cacheUsage) {
            return new SingleResult(true, content, "", 200, promptTokens, completionTokens, latencyMs,
                    requestBody, responseBody, finishReason, false, LlmRequestAccounting.DenyCode.NONE, -1,
                    cacheUsage);
        }

        private static SingleResult failure(String error, int httpStatus, long latencyMs) {
            return failure(error, httpStatus, latencyMs, "", "");
        }

        private static SingleResult failure(String error, int httpStatus, long latencyMs, String requestBody,
                String responseBody) {
            return new SingleResult(false, "", error, httpStatus, 0, 0, latencyMs, requestBody, responseBody,
                    "error", false, LlmRequestAccounting.DenyCode.NONE, -1,
                    LlmCacheUsage.unknown("request failed before cache usage was reported"));
        }

        private static SingleResult policyFailure(LlmRequestAccounting.DenyCode denyCode, String error) {
            return new SingleResult(false, "", "Billing denied: " + error,
                    0, 0, 0, 0L, "", "", "error", true, denyCode, -1,
                    LlmCacheUsage.unknown("request was denied before provider access"));
        }
    }

    private record SearchSingleResult(SingleResult result, HostedWebSearchAdapters.Evidence evidence) {
        private SearchSingleResult {
            evidence = evidence == null ? HostedWebSearchAdapters.Evidence.none() : evidence;
        }

        private static SearchSingleResult failure(String error, long latencyMs) {
            return failure(error, latencyMs, "");
        }

        private static SearchSingleResult failure(String error, long latencyMs, String requestBody) {
            return new SearchSingleResult(SingleResult.failure(error, 0, latencyMs, requestBody, ""),
                    HostedWebSearchAdapters.Evidence.none());
        }
    }

    private record StreamResult(boolean success, String content, String error, int httpStatus,
            int promptTokens, int completionTokens, long latencyMs,
            boolean emittedContent, String requestBody, String responseBody, String finishReason,
            boolean policyRejected, LlmRequestAccounting.DenyCode denyCode, long retryAfterMs,
            LlmCacheUsage cacheUsage) {
        private StreamResult withUsage(int prompt, int completion, LlmCacheUsage usage) {
            return new StreamResult(success, content, error, httpStatus, prompt, completion, latencyMs,
                    emittedContent, requestBody, responseBody, finishReason, policyRejected, denyCode, retryAfterMs,
                    usage);
        }
        private StreamResult withRetryAfter(long millis) {
            return new StreamResult(success, content, error, httpStatus, promptTokens, completionTokens, latencyMs,
                    emittedContent, requestBody, responseBody, finishReason, policyRejected, denyCode, millis,
                    cacheUsage);
        }
        private static StreamResult success(String content, int promptTokens, int completionTokens,
                long latencyMs, String requestBody, String responseBody, String finishReason) {
            return new StreamResult(true, content, "", 200, promptTokens, completionTokens,
                    latencyMs, true, requestBody, responseBody,
                    finishReason, false, LlmRequestAccounting.DenyCode.NONE, -1,
                    LlmCacheUsage.unknown("stream did not report cache usage"));
        }

        private static StreamResult failure(String error, int status, long latencyMs, boolean emitted) {
            return failure(error, status, latencyMs, emitted, "", "");
        }

        private static StreamResult failure(String error, int status, long latencyMs, boolean emitted,
                String requestBody, String responseBody) {
            return new StreamResult(false, "", error, status, 0, 0, latencyMs, emitted,
                    requestBody, responseBody, "error", false, LlmRequestAccounting.DenyCode.NONE, -1,
                    LlmCacheUsage.unknown("request failed before cache usage was reported"));
        }

        private static StreamResult failure(String error, int status, long latencyMs, boolean emitted,
                String requestBody, String responseBody, String finishReason) {
            return new StreamResult(false, "", error, status, 0, 0, latencyMs, emitted, requestBody, responseBody,
                    finishReason == null ? "error" : finishReason, false,
                    LlmRequestAccounting.DenyCode.NONE, -1,
                    LlmCacheUsage.unknown("request failed before cache usage was reported"));
        }

        private static StreamResult policyFailure(LlmRequestAccounting.DenyCode denyCode, String error) {
            return new StreamResult(false, "", "Billing denied: " + error, 0, 0, 0, 0L, false,
                    "", "", "error", true, denyCode, -1,
                    LlmCacheUsage.unknown("request was denied before provider access"));
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
