package vibe.liteming.llmjs.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vibe.liteming.llmcore.LlmMessage;
import vibe.liteming.llmcore.CapabilityPolicyStore;
import vibe.liteming.llmcore.LlmBillingContext;
import vibe.liteming.llmcore.LlmCallBudget;
import vibe.liteming.llmcore.LlmCapabilityPolicy;
import vibe.liteming.llmcore.LlmMessageFinalizer;
import vibe.liteming.llmcore.LlmRequestAccounting;
import vibe.liteming.llmcore.LlmOrchestrator;
import vibe.liteming.llmcore.LlmRequest;
import vibe.liteming.llmcore.LlmRequestContext;
import vibe.liteming.llmcore.LlmResolvedParameters;
import vibe.liteming.llmcore.LlmRouteOptions;
import vibe.liteming.llmcore.LlmRoute;
import vibe.liteming.llmcore.LlmTarget;
import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.ProviderConfigLoader;
import vibe.liteming.llmcore.ProviderCapabilities;
import vibe.liteming.llmcore.ProviderProfile;
import vibe.liteming.llmcore.LlmCostRate;
import vibe.liteming.llmcore.ProviderSpec;
import vibe.liteming.llmcore.PurposeMeta;
import vibe.liteming.llmcore.PurposeRegistry;
import vibe.liteming.llmcore.RoutingConfigStore;
import vibe.liteming.llmcore.SharedLlmRuntime;
import vibe.liteming.llmcore.mod.LlmCoreMod;
import vibe.liteming.llmjs.config.GlobalConfig;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.config.ProviderLoader;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.pipeline.LLMResponse;
import vibe.liteming.llmjs.test.ConsoleTestCodec;
import vibe.liteming.llmjs.test.ConsoleTestExecutor;
import vibe.liteming.llmjs.test.ConsoleTestRequest;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;
import java.util.function.Consumer;

public class ProviderManager {
    public static final ProviderManager INSTANCE = new ProviderManager();

    private volatile Map<String, LlmCostRate> costRates = Map.of();
    private volatile Map<String, Provider> providers = new ConcurrentHashMap<>();
    private volatile LlmOrchestrator orchestrator = new LlmOrchestrator(Map.of());
    private volatile PriorityRoutingConfig routingConfig = PriorityRoutingConfig.empty();
    private volatile LlmCapabilityPolicy capabilityPolicy = LlmCapabilityPolicy.empty();
    private final Map<String, ConnectionStatus> statusCache = new ConcurrentHashMap<>();
    private final Map<String, RuntimeMetrics> runtimeMetrics = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, vibe.liteming.llmcore.LlmRoute>> playerRoutes = new ConcurrentHashMap<>();
    private final Consumer<vibe.liteming.llmcore.LlmRequestLogger.AttemptEvent> metricsListener = event -> {
        if (event.provider() == null || event.provider().isBlank()) return;
        String targetId = LlmTarget.of(event.provider(), event.model()).id();
        RuntimeMetrics metrics = runtimeMetrics.computeIfAbsent(targetId, ignored -> new RuntimeMetrics());
        metrics.record(System.currentTimeMillis(), event.latencyMs(), event.success(), event.error(), event.cacheUsage());
    };
    private boolean metricsHookInstalled;
    private Path playerRoutingFile;
    private Path configDir;

    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    public record ConnectionStatus(boolean connected, long latencyMs, @Nullable String lastError, long testTime) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("connected", connected);
            obj.addProperty("latency", latencyMs);
            if (lastError != null) obj.addProperty("lastError", lastError);
            return obj;
        }
    }

    static final class RuntimeMetrics {
        private static final long WINDOW_MILLIS = 60L * 60L * 1_000L;
        private static final int MAX_SAMPLES = 4_096;
        private record Sample(long time, long latencyMs, boolean success,
                vibe.liteming.llmcore.LlmCacheUsage cacheUsage) { }
        private final Deque<Sample> samples = new ArrayDeque<>();
        volatile long lastSuccessTime;
        volatile long lastRequestTime;
        volatile boolean lastRequestSuccessful;
        volatile String lastError = "";

        synchronized void record(long now, long latencyMs, boolean success, String error,
                vibe.liteming.llmcore.LlmCacheUsage cacheUsage) {
            prune(now);
            samples.addLast(new Sample(now, Math.max(0L, latencyMs), success,
                    cacheUsage == null ? vibe.liteming.llmcore.LlmCacheUsage.unknown("missing") : cacheUsage));
            while (samples.size() > MAX_SAMPLES) samples.removeFirst();
            lastRequestTime = now;
            lastRequestSuccessful = success;
            if (success) lastSuccessTime = now;
            else lastError = error == null ? "" : error;
        }

        synchronized JsonObject toJson(long now) {
            return toJson(now, LlmCostRate.DEFAULT);
        }

        synchronized JsonObject toJson(long now, LlmCostRate rate) {
            prune(now);
            long count = samples.size();
            long ok = samples.stream().filter(Sample::success).count();
            long totalLatencyMs = samples.stream().filter(Sample::success).mapToLong(Sample::latencyMs).sum();
            JsonObject json = new JsonObject();
            json.addProperty("requests1h", count);
            json.addProperty("successes1h", ok);
            json.addProperty("failures1h", Math.max(0L, count - ok));
            json.addProperty("successRate1h", count == 0 ? 0.0D : (double) ok / count);
            json.addProperty("averageLatency1hMs", ok == 0 ? 0L : totalLatencyMs / ok);
            if (lastSuccessTime > 0) json.addProperty("lastSuccessTime", Instant.ofEpochMilli(lastSuccessTime).toString());
            if (lastRequestTime > 0) json.addProperty("lastRequestTime", Instant.ofEpochMilli(lastRequestTime).toString());
            if (lastRequestTime > 0) json.addProperty("lastRequestSuccessful", lastRequestSuccessful);
            if (!lastError.isBlank()) json.addProperty("lastError", lastError);
            long cacheReported = 0L, cacheRead = 0L, cacheWrite = 0L, uncached = 0L, cacheTotal = 0L;
            long pricedSamples = 0L, estimatedCost = 0L;
            for (Sample sample : samples) {
                var usage = sample.cacheUsage();
                if (usage.status() != vibe.liteming.llmcore.LlmCacheUsage.Status.REPORTED) continue;
                cacheReported++;
                cacheRead += usage.cacheReadInputTokens() == null ? 0L : usage.cacheReadInputTokens();
                cacheWrite += usage.cacheWriteInputTokens() == null ? 0L : usage.cacheWriteInputTokens();
                uncached += usage.uncachedInputTokens() == null ? 0L : usage.uncachedInputTokens();
                cacheTotal += usage.totalInputTokens() == null ? 0L : usage.totalInputTokens();
                Long cost = rate.weightedTokens(usage,
                        usage.totalInputTokens() == null ? 0L : usage.totalInputTokens(), 0L);
                if (cost != null) { pricedSamples++; estimatedCost += cost; }
            }
            json.addProperty("cacheReportedRequests1h", cacheReported);
            json.addProperty("cacheUnknownRequests1h", Math.max(0L, count - cacheReported));
            if (cacheReported > 0) {
                json.addProperty("cacheReadInputTokens1h", cacheRead);
                json.addProperty("cacheWriteInputTokens1h", cacheWrite);
                json.addProperty("uncachedInputTokens1h", uncached);
                if (cacheTotal > 0) json.addProperty("cacheHitRatio1h", (double) cacheRead / cacheTotal);
            }
            if (pricedSamples > 0) {
                json.addProperty("cachePricedRequests1h", pricedSamples);
                json.addProperty("estimatedInputCostUnits1h", estimatedCost);
            }
            return json;
        }

        private void prune(long now) {
            long cutoff = now - WINDOW_MILLIS;
            while (!samples.isEmpty() && samples.peekFirst().time() < cutoff) samples.removeFirst();
        }
    }

    private Path gameRoot;

    public void init(Path serverConfigDir, Path gameRoot) {
        this.configDir = GlobalConfig.resolveServerDirectory(serverConfigDir);
        this.gameRoot = gameRoot;
        this.playerRoutingFile = GlobalConfig.getGlobalProvidersFile().getParent().resolve("player-routing.json");
        loadPlayerRoutes();
        installMetricsHook();
        reload();
    }

    /** Retained for the released LLMjs script adapter ABI. */
    public void reload() {
        tryReload();
    }

    public synchronized boolean tryReload() {
        if (configDir == null || gameRoot == null) return false;
        Path globalProviders = GlobalConfig.getGlobalProvidersFile();
        Path serverProviders = configDir.resolve("providers.json");
        Path secretFile = ProviderLoader.resolveSecretFile(gameRoot);
        Map<String, Provider> loadedProviders = ProviderLoader.loadAll(configDir, gameRoot);
        Map<String, ProviderSpec> specs = ProviderConfigLoader.load(globalProviders, serverProviders, secretFile);
        Map<String, ProviderProfile> profiles;
        Map<String, LlmCostRate> rates = new LinkedHashMap<>();
        try {
            profiles = ProviderConfigLoader.loadProfiles(globalProviders, serverProviders, secretFile,
                    warning -> LlmCoreMod.LOGGER.warn("{}", warning));
            specs.forEach((name, spec) -> {
                ProviderProfile profile = profiles.getOrDefault(name, ProviderProfile.textOnly(name));
                spec.models().forEach(model -> rates.put(LlmTarget.of(name, model).id(), profile.costRateFor(model)));
            });
            Path rawFile = configDir.resolve("providers_raw.json");
            if (java.nio.file.Files.isRegularFile(rawFile)) {
                JsonObject raw = JsonParser.parseString(java.nio.file.Files.readString(rawFile)).getAsJsonObject();
                for (Provider provider : loadedProviders.values()) {
                    if ("raw".equals(provider.getType()) && raw.has(provider.getName())) {
                        rates.put(provider.getName(), ProviderConfigLoader.readCostRate(provider.getName(),
                                raw.getAsJsonObject(provider.getName())));
                    }
                }
            }
        } catch (Exception invalid) {
            LlmCoreMod.LOGGER.error("Provider reload rejected; keeping the previous runtime: {}", invalid.getMessage());
            return false;
        }
        LlmOrchestrator candidate = new LlmOrchestrator(specs);
        candidate.replaceProviderProfiles(profiles);
        candidate.setGlobalDefaults(new LlmRouteOptions(null, null, LLMConfig.TIMEOUT.get(), null, null));
        PriorityRoutingConfig candidateRouting;
        try {
            RouteMigration routingMigration = migrateLegacyTargets(RoutingConfigStore.load(getRoutingFile()), specs);
            candidateRouting = routingMigration.config();
            if (routingMigration.changed()) {
                backupBeforeTargetMigration(getRoutingFile());
                if (!RoutingConfigStore.save(getRoutingFile(), candidateRouting)) {
                    throw new IllegalStateException("failed to persist migrated routing.json");
                }
            }
        } catch (Exception migrationFailure) {
            LlmCoreMod.LOGGER.error("Provider/model route migration rejected; keeping the previous runtime: {}",
                    migrationFailure.getMessage());
            candidate.close();
            return false;
        }
        candidate.setRoutingConfig(candidateRouting);
        boolean preferencesChanged = false;
        for (var playerEntry : playerRoutes.entrySet()) {
            var routeIterator = playerEntry.getValue().entrySet().iterator();
            while (routeIterator.hasNext()) {
                var routeEntry = routeIterator.next();
                try {
                    LlmRoute migrated = migrateLegacyTargets(routeEntry.getValue(), specs);
                    if (!migrated.equals(routeEntry.getValue())) {
                        routeEntry.setValue(migrated);
                        preferencesChanged = true;
                    }
                    candidate.setPlayerRoutePreference(playerEntry.getKey(), routeEntry.getKey(), migrated);
                } catch (IllegalArgumentException invalid) {
                    routeIterator.remove();
                    preferencesChanged = true;
                }
            }
        }
        if (preferencesChanged) {
            backupBeforeTargetMigration(playerRoutingFile);
            savePlayerRoutes();
        }
        LlmCapabilityPolicy candidatePolicy = CapabilityPolicyStore.load(getCapabilityPolicyFile(), error ->
                LlmCoreMod.LOGGER.error("Invalid capability-policy.json; optional capabilities are disabled: {}",
                        error));
        candidate.setCapabilityPolicy(candidatePolicy);

        Map<String, Provider> newProviders = new LinkedHashMap<>();
        specs.forEach((name, spec) -> newProviders.put(name, new CoreProviderAdapter(spec, candidate)));
        loadedProviders.forEach((name, provider) -> {
            if ("raw".equals(provider.getType())) newProviders.put(name, provider);
        });

        LlmOrchestrator previous = this.orchestrator;
        this.orchestrator = candidate;
        this.providers = new ConcurrentHashMap<>(newProviders);
        this.costRates = Map.copyOf(rates);
        this.routingConfig = candidateRouting;
        this.capabilityPolicy = candidatePolicy;
        SharedLlmRuntime.install(candidate);
        previous.close();
        LlmCoreMod.LOGGER.info("Loaded and published {} providers", providers.size());
        return true;
    }

    public synchronized void close() {
        LlmOrchestrator expected = this.orchestrator;
        SharedLlmRuntime.clear(expected);
        expected.close();
        this.orchestrator = new LlmOrchestrator(Map.of());
        this.providers = new ConcurrentHashMap<>();
        this.costRates = Map.of();
        this.routingConfig = PriorityRoutingConfig.empty();
        this.capabilityPolicy = LlmCapabilityPolicy.empty();
        this.statusCache.clear();
        this.runtimeMetrics.clear();
        this.playerRoutes.clear();
        if (metricsHookInstalled) {
            vibe.liteming.llmcore.LlmRequestLogger.removeAttemptListener(metricsListener);
            metricsHookInstalled = false;
        }
        this.playerRoutingFile = null;
        this.configDir = null;
        this.gameRoot = null;
    }

    /** Path used for {@code routing.json} (lives next to global providers.json). */
    public Path getRoutingFile() {
        if (gameRoot == null) return null;
        return GlobalConfig.getGlobalProvidersFile().getParent().resolve("routing.json");
    }

    public PriorityRoutingConfig getRoutingConfig() {
        return routingConfig;
    }

    /** Path used for the separately versioned optional-capability policy. */
    public Path getCapabilityPolicyFile() {
        if (gameRoot == null) return null;
        return GlobalConfig.getGlobalProvidersFile().getParent().resolve("capability-policy.json");
    }

    public LlmCapabilityPolicy getCapabilityPolicy() {
        return capabilityPolicy;
    }

    /**
     * Atomically update + persist the global routing table. Returns false if the
     * write failed. The in-memory orchestrator is updated even when persistence
     * fails so the running server still picks up the change for the session.
     */
    public synchronized boolean updateRouting(PriorityRoutingConfig next) {
        validateRoutingTargets(next);
        this.routingConfig = next == null ? PriorityRoutingConfig.empty() : next;
        this.orchestrator.setRoutingConfig(this.routingConfig);
        Path file = getRoutingFile();
        if (file == null) return false;
        boolean ok = RoutingConfigStore.save(file, this.routingConfig);
        if (!ok) {
            LlmCoreMod.LOGGER.warn("Failed to persist routing.json (in-memory still updated)");
        }
        return ok;
    }

    /** Apply and persist routing plus capability authorization as one validated UI snapshot. */
    public synchronized boolean updateRoutingAndCapabilities(PriorityRoutingConfig nextRouting,
            LlmCapabilityPolicy nextPolicy) {
        validateRoutingTargets(nextRouting);
        this.routingConfig = nextRouting == null ? PriorityRoutingConfig.empty() : nextRouting;
        this.capabilityPolicy = nextPolicy == null ? LlmCapabilityPolicy.empty() : nextPolicy;
        this.orchestrator.setRoutingConfig(this.routingConfig);
        this.orchestrator.setCapabilityPolicy(this.capabilityPolicy);

        boolean routingSaved = RoutingConfigStore.save(getRoutingFile(), this.routingConfig);
        boolean policySaved = CapabilityPolicyStore.save(getCapabilityPolicyFile(), this.capabilityPolicy);
        if (!routingSaved || !policySaved) {
            LlmCoreMod.LOGGER.warn("Console policy applied for this session but persistence failed "
                    + "(routing={}, capabilities={})", routingSaved, policySaved);
        }
        return routingSaved && policySaved;
    }

    private void validateRoutingTargets(PriorityRoutingConfig config) {
        PriorityRoutingConfig value = config == null ? PriorityRoutingConfig.empty() : config;
        Set<String> enabled = new HashSet<>(orchestrator.getTargetIds());
        List<LlmRoute> routes = new ArrayList<>();
        routes.add(value.defaultRoute());
        routes.addAll(value.purposeRoutes().values());
        for (LlmRoute route : routes) for (LlmRoute.Stage stage : route.stages()) {
            for (LlmRoute.Target target : stage.candidates()) {
                if (!enabled.contains(target.id())) {
                    throw new IllegalArgumentException("route target is not enabled: " + target.id());
                }
            }
        }
    }

    /** Convenience: full status object including providers + current routing. */
    public JsonObject getStatusJson() {
        JsonObject result = new JsonObject();
        JsonArray providerArray = new JsonArray();
        for (Map.Entry<String, Provider> entry : providers.entrySet()) {
            JsonObject pJson = new JsonObject();
            pJson.addProperty("name", entry.getKey());
            pJson.addProperty("type", entry.getValue().getType());
            String format = entry.getValue().getFormat();
            if (format != null) pJson.addProperty("format", format);
            pJson.addProperty("model", entry.getValue().getModel());
            ProviderSpec coreSpec = orchestrator.getProviderSpec(entry.getKey());
            if (coreSpec != null) {
                JsonArray models = new JsonArray();
                coreSpec.models().forEach(models::add);
                pJson.add("models", models);
                pJson.addProperty("requestMode", coreSpec.requestMode().configValue());
                pJson.addProperty("keyCount", coreSpec.credentials().size());
            }
            pJson.addProperty("url", entry.getValue().getUrl());
            pJson.addProperty("maskedKey", entry.getValue().getMaskedKey());
            pJson.addProperty("configured", entry.getValue().isConfigured());
            ProviderProfile profile = orchestrator.getProviderProfile(entry.getKey());
            JsonObject billing = new JsonObject();
            LlmCostRate costRate = profile.costRate();
            billing.addProperty("inputMultiplier", costRate.inputMultiplier());
            billing.addProperty("outputMultiplier", costRate.outputMultiplier());
            if (costRate.cacheReadInputMultiplier() != null) {
                billing.addProperty("cacheReadInputMultiplier", costRate.cacheReadInputMultiplier());
            }
            if (costRate.cacheWriteInputMultiplier() != null) {
                billing.addProperty("cacheWriteInputMultiplier", costRate.cacheWriteInputMultiplier());
            }
            pJson.add("billing", billing);
            ProviderCapabilities capabilities = profile.capabilities();
            JsonObject capabilitiesJson = new JsonObject();
            JsonArray input = new JsonArray();
            capabilities.inputModalities().forEach(input::add);
            capabilitiesJson.add("input", input);
            JsonArray output = new JsonArray();
            capabilities.outputModalities().forEach(output::add);
            capabilitiesJson.add("output", output);
            JsonObject webSearch = new JsonObject();
            webSearch.addProperty("declared", capabilities.webSearch().enabled());
            webSearch.addProperty("capable", orchestrator.isProviderWebSearchCapable(entry.getKey()));
            if (capabilities.webSearch().enabled()) {
                webSearch.addProperty("adapter", capabilities.webSearch().adapterId());
            }
            capabilitiesJson.add("webSearch", webSearch);
            pJson.add("capabilities", capabilitiesJson);
            ConnectionStatus cached = statusCache.get(entry.getKey());
            JsonArray targets = new JsonArray();
            if (coreSpec != null) for (String model : coreSpec.models()) {
                String targetId = LlmTarget.of(entry.getKey(), model).id();
                LlmCostRate targetRate = costRates.getOrDefault(targetId, profile.costRateFor(model));
                JsonObject target = new JsonObject();
                target.addProperty("id", targetId);
                target.addProperty("provider", entry.getKey());
                target.addProperty("model", model);
                target.add("billing", costRateJson(targetRate));
                RuntimeMetrics metrics = runtimeMetrics.get(targetId);
                target.add("status", metrics == null ? new JsonObject()
                        : metrics.toJson(System.currentTimeMillis(), targetRate));
                targets.add(target);
            }
            pJson.add("targets", targets);
            JsonObject status = targets.size() == 0 ? new JsonObject()
                    : targets.get(0).getAsJsonObject().getAsJsonObject("status").deepCopy();
            if (cached != null) {
                status.addProperty("lastProbeConnected", cached.connected());
                status.addProperty("lastProbeLatencyMs", cached.latencyMs());
                if (cached.lastError() != null && !cached.lastError().isBlank()) status.addProperty("lastProbeError", cached.lastError());
            }
            pJson.add("status", status);
            providerArray.add(pJson);
        }
        result.add("providers", providerArray);
        result.addProperty("count", providers.size());
        // routing payload (default + per-purpose chains) consumed by the Routing tab
        result.add("routing", com.google.gson.JsonParser.parseString(
                RoutingConfigStore.toJsonString(routingConfig)).getAsJsonObject());
        result.addProperty("routingFingerprint", RoutingConfigStore.fingerprint(routingConfig));
        result.add("capabilityPolicy", com.google.gson.JsonParser.parseString(
                CapabilityPolicyStore.toJsonString(capabilityPolicy)).getAsJsonObject());
        result.addProperty("capabilityPolicyFingerprint",
                CapabilityPolicyStore.fingerprint(capabilityPolicy));
        JsonArray purposesArray = new JsonArray();
        List<String> allCoreProviders = orchestrator.getTargetIds();
        for (PurposeMeta meta : PurposeRegistry.snapshot()) {
            JsonObject pm = new JsonObject();
            pm.addProperty("id", meta.id());
            pm.addProperty("displayName", meta.displayName());
            pm.addProperty("description", meta.description());
            pm.addProperty("modId", meta.modId());
            pm.addProperty("builtIn", meta.builtIn());
            List<String> chain = routingConfig.resolveChain(meta.id(), allCoreProviders);
            String effectiveProvider = chain.isEmpty() ? "" : chain.get(0);
            pm.add("effective", effectiveParametersJson(
                    orchestrator.resolveParameters(meta.id(), effectiveProvider)));
            pm.getAsJsonObject("effective").addProperty("webSearchAllowed",
                    orchestrator.isWebSearchAllowed(meta.id()));
            purposesArray.add(pm);
        }
        result.add("purposes", purposesArray);
        return result;
    }

    /** Minimal status needed to render one immutable delegated Test handoff. */
    public JsonObject getTestStatusJson(String purpose) {
        JsonObject result = new JsonObject();
        JsonArray purposes = new JsonArray();
        String normalized = purpose == null ? "" : purpose.trim();
        PurposeMeta meta = PurposeRegistry.snapshot().stream()
                .filter(candidate -> candidate.id().equals(normalized))
                .findFirst().orElse(null);
        if (meta != null) {
            JsonObject item = new JsonObject();
            item.addProperty("id", meta.id());
            item.addProperty("displayName", meta.displayName());
            List<String> chain = routingConfig.resolveChain(meta.id(), orchestrator.getTargetIds());
            String effectiveProvider = chain.isEmpty() ? "" : chain.get(0);
            item.add("effective", effectiveParametersJson(
                    orchestrator.resolveParameters(meta.id(), effectiveProvider)));
            item.getAsJsonObject("effective").addProperty("webSearchAllowed",
                    orchestrator.isWebSearchAllowed(meta.id()));
            purposes.add(item);
        }
        result.add("purposes", purposes);
        return result;
    }

    /** Safe snapshot for a normal player: route choices and pricing metadata only. */
    public synchronized JsonObject getPersonalRoutingStatusJson(UUID playerId) {
        JsonObject result = new JsonObject();
        JsonArray targetArray = new JsonArray();
        for (Map.Entry<String, Provider> entry : providers.entrySet()) {
            ProviderSpec spec = orchestrator.getProviderSpec(entry.getKey());
            if (spec == null) continue;
            for (String model : spec.models()) {
                String targetId = LlmTarget.of(entry.getKey(), model).id();
                JsonObject target = new JsonObject();
                target.addProperty("id", targetId);
                target.addProperty("provider", entry.getKey());
                target.addProperty("model", model);
                target.addProperty("format", Objects.requireNonNullElse(entry.getValue().getFormat(), ""));
                target.add("billing", costRateJson(costRates.getOrDefault(targetId, LlmCostRate.DEFAULT)));
                targetArray.add(target);
            }
        }
        result.add("targets", targetArray);
        JsonObject routes = new JsonObject();
        Map<String, vibe.liteming.llmcore.LlmRoute> own = playerRoutes.getOrDefault(playerId, Map.of());
        for (Map.Entry<String, vibe.liteming.llmcore.LlmRoute> entry : own.entrySet()) {
            JsonObject route = new JsonObject();
            route.addProperty("route", entry.getValue().expression());
            if (entry.getValue().deadlineOverrideSeconds() != null) {
                route.addProperty("deadlineSeconds", entry.getValue().deadlineOverrideSeconds());
            }
            routes.add(entry.getKey(), route);
        }
        result.add("personalRoutes", routes);
        JsonArray purposes = new JsonArray();
        for (PurposeMeta meta : PurposeRegistry.snapshot()) {
            JsonObject purpose = new JsonObject();
            purpose.addProperty("id", meta.id());
            purpose.addProperty("displayName", meta.displayName());
            purposes.add(purpose);
        }
        result.add("purposes", purposes);
        return result;
    }

    public synchronized boolean setPlayerRoute(UUID playerId, String purpose, vibe.liteming.llmcore.LlmRoute route) {
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("purpose is required");
        boolean knownPurpose = PurposeRegistry.snapshot().stream().anyMatch(meta -> meta.id().equals(purpose.trim()));
        if (!knownPurpose) throw new IllegalArgumentException("unknown purpose: " + purpose);
        if (route == null || route.isUnset()) {
            orchestrator.clearPlayerRoutePreference(playerId, purpose.trim());
            Map<String, vibe.liteming.llmcore.LlmRoute> routes = playerRoutes.get(playerId);
            if (routes != null) {
                routes.remove(purpose.trim());
                if (routes.isEmpty()) playerRoutes.remove(playerId);
            }
        } else {
            orchestrator.setPlayerRoutePreference(playerId, purpose, route);
            playerRoutes.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>()).put(purpose.trim(), route);
        }
        return savePlayerRoutes();
    }

    private void installMetricsHook() {
        if (metricsHookInstalled) return;
        metricsHookInstalled = true;
        vibe.liteming.llmcore.LlmRequestLogger.addAttemptListener(metricsListener);
    }

    private void loadPlayerRoutes() {
        playerRoutes.clear();
        if (playerRoutingFile == null || !java.nio.file.Files.isRegularFile(playerRoutingFile)) return;
        try {
            JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(playerRoutingFile)).getAsJsonObject();
            JsonObject players = root.has("players") && root.get("players").isJsonObject()
                    ? root.getAsJsonObject("players") : new JsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> player : players.entrySet()) {
                UUID id;
                try { id = UUID.fromString(player.getKey()); } catch (IllegalArgumentException ignored) { continue; }
                if (!player.getValue().isJsonObject()) continue;
                Map<String, vibe.liteming.llmcore.LlmRoute> routes = new LinkedHashMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> route : player.getValue().getAsJsonObject().entrySet()) {
                    try {
                        String expression = route.getValue().isJsonObject() && route.getValue().getAsJsonObject().has("route")
                                ? route.getValue().getAsJsonObject().get("route").getAsString() : route.getValue().getAsString();
                        vibe.liteming.llmcore.LlmRoute parsed = vibe.liteming.llmcore.LlmRoute.parse(expression);
                        if (route.getValue().isJsonObject() && route.getValue().getAsJsonObject().has("deadlineSeconds")) {
                            parsed = parsed.withDeadline(route.getValue().getAsJsonObject().get("deadlineSeconds").getAsInt());
                        }
                        if (!parsed.isUnset()) routes.put(route.getKey(), parsed);
                    } catch (RuntimeException ignored) { }
                }
                if (!routes.isEmpty()) playerRoutes.put(id, routes);
            }
        } catch (Exception error) {
            LlmCoreMod.LOGGER.warn("Failed to load player-routing.json: {}", error.getMessage());
        }
    }

    private record RouteMigration(PriorityRoutingConfig config, boolean changed) { }

    private static RouteMigration migrateLegacyTargets(PriorityRoutingConfig config,
            Map<String, ProviderSpec> specs) {
        PriorityRoutingConfig source = config == null ? PriorityRoutingConfig.empty() : config;
        LlmRoute migratedDefault = migrateLegacyTargets(source.defaultRoute(), specs);
        Map<String, LlmRoute> purposes = new LinkedHashMap<>();
        source.purposeRoutes().forEach((purpose, route) ->
                purposes.put(purpose, migrateLegacyTargets(route, specs)));
        PriorityRoutingConfig migrated = new PriorityRoutingConfig(
                migratedDefault, purposes, source.purposeOptions());
        return new RouteMigration(migrated, !migrated.equals(source));
    }

    private static LlmRoute migrateLegacyTargets(LlmRoute route, Map<String, ProviderSpec> specs) {
        if (route == null || route.isUnset()) return route == null ? LlmRoute.empty() : route;
        List<LlmRoute.Stage> stages = new ArrayList<>();
        for (LlmRoute.Stage stage : route.stages()) {
            List<LlmRoute.Target> targets = new ArrayList<>();
            for (LlmRoute.Target target : stage.candidates()) {
                if (!target.model().isBlank()) {
                    targets.add(target);
                    continue;
                }
                ProviderSpec spec = specs.get(target.provider());
                if (spec == null || spec.models().isEmpty()) {
                    throw new IllegalArgumentException("legacy route provider is unavailable: " + target.provider());
                }
                targets.add(new LlmRoute.Target(target.provider(), spec.models().get(0), target.retries()));
            }
            stages.add(new LlmRoute.Stage(targets));
        }
        return new LlmRoute(stages, route.deadlineOverrideSeconds());
    }

    private static void backupBeforeTargetMigration(Path file) {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) return;
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".pre-1.5.1-provider-routes.bak");
            if (!java.nio.file.Files.exists(backup)) java.nio.file.Files.copy(file, backup);
        } catch (Exception error) {
            throw new IllegalStateException("failed to back up " + file.getFileName() + ": " + error.getMessage(), error);
        }
    }

    private boolean savePlayerRoutes() {
        if (playerRoutingFile == null) return false;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", 2);
            JsonObject players = new JsonObject();
            playerRoutes.forEach((id, routes) -> {
                JsonObject values = new JsonObject();
                routes.forEach((purpose, route) -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("route", route.expression());
                    if (route.deadlineOverrideSeconds() != null) value.addProperty("deadlineSeconds", route.deadlineOverrideSeconds());
                    values.add(purpose, value);
                });
                if (values.size() > 0) players.add(id.toString(), values);
            });
            root.add("players", players);
            java.nio.file.Files.createDirectories(playerRoutingFile.toAbsolutePath().normalize().getParent());
            java.nio.file.Path temp = playerRoutingFile.resolveSibling(playerRoutingFile.getFileName() + ".tmp");
            java.nio.file.Files.writeString(temp, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
            try { java.nio.file.Files.move(temp, playerRoutingFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { java.nio.file.Files.move(temp, playerRoutingFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (Exception error) {
            LlmCoreMod.LOGGER.warn("Failed to save player-routing.json: {}", error.getMessage());
            return false;
        }
    }

    private static JsonObject effectiveParametersJson(LlmResolvedParameters parameters) {
        JsonObject json = new JsonObject();
        json.addProperty("provider", parameters.provider());
        if (parameters.temperature() != null) json.addProperty("temperature", parameters.temperature());
        if (parameters.maxOutputTokens() != null) {
            json.addProperty("maxOutputTokens", parameters.maxOutputTokens());
        }
        json.addProperty("timeoutSeconds", parameters.timeoutSeconds());
        json.addProperty("outputReserveTokens", parameters.outputReserveTokens());
        if (parameters.hasBoundedInput()) {
            json.addProperty("inputBudgetTokens", parameters.inputBudgetTokens());
        } else {
            json.addProperty("inputBudgetUnbounded", true);
        }
        if (parameters.contextWindowTokens() != null) {
            json.addProperty("contextWindowTokens", parameters.contextWindowTokens());
        }
        return json;
    }

    private static JsonObject costRateJson(LlmCostRate rate) {
        LlmCostRate value = rate == null ? LlmCostRate.DEFAULT : rate;
        JsonObject billing = new JsonObject();
        billing.addProperty("inputMultiplier", value.inputMultiplier());
        billing.addProperty("outputMultiplier", value.outputMultiplier());
        if (value.cacheReadInputMultiplier() != null) {
            billing.addProperty("cacheReadInputMultiplier", value.cacheReadInputMultiplier());
        }
        if (value.cacheWriteInputMultiplier() != null) {
            billing.addProperty("cacheWriteInputMultiplier", value.cacheWriteInputMultiplier());
        }
        return billing;
    }

    public @Nullable Provider getProvider(String name) { return providers.get(name); }
    public @Nullable ProviderSpec getProviderSpec(String name) { return orchestrator.getProviderSpec(name); }

    public Provider getDefaultProvider() {
        String defaultName = LLMConfig.DEFAULT_PROVIDER.get();
        Provider p = providers.get(defaultName);
        if (p != null) return p;
        return providers.values().stream().findFirst().orElse(null);
    }

    public List<String> getProviderNames() { return new ArrayList<>(providers.keySet()); }
    public Map<String, Provider> getAllProviders() { return Collections.unmodifiableMap(providers); }

    /** Execute Console Test through the same core purpose, routing, fallback and budget path. */
    public CompletableFuture<String> executeConsoleTest(ConsoleTestRequest test) {
        return executeConsoleTest(test, null);
    }

    public CompletableFuture<String> executeConsoleTest(ConsoleTestRequest test, UUID principalId) {
        if (test == null) return CompletableFuture.completedFuture(ConsoleTestCodec.error("", "Missing test request"));
        if (!checkRateLimit()) {
            return CompletableFuture.completedFuture(
                    ConsoleTestCodec.error(test.requestId(), "Rate limit exceeded"));
        }
        return ConsoleTestExecutor.execute(orchestrator, routingConfig, test,
                principalId == null ? "" : principalId.toString());
    }

    public CompletableFuture<LLMResponse> sendWithFallback(
            List<ApiFormat.Message> messages, List<String> providerChain,
            @Nullable Double temperature, @Nullable Integer maxTokens, int timeoutSeconds) {
        return sendWithFallback(messages, providerChain, temperature, maxTokens, timeoutSeconds, null);
    }

    public CompletableFuture<LLMResponse> sendWithFallback(
            List<ApiFormat.Message> messages, List<String> providerChain,
            @Nullable Double temperature, @Nullable Integer maxTokens, int timeoutSeconds,
            @Nullable LlmBillingContext inheritedBilling) {
        if (providerChain.isEmpty()) return CompletableFuture.completedFuture(LLMResponse.error("No providers specified"));
        if (!checkRateLimit()) return CompletableFuture.completedFuture(LLMResponse.error("Rate limit exceeded"));
        LlmBillingContext billing = inheritedBilling == null
                ? createScriptBilling(messages, providerChain, temperature, maxTokens, timeoutSeconds, 1)
                : inheritedBilling;
        return sendWithFallbackRecursive(messages, providerChain, 0, temperature, maxTokens, timeoutSeconds,
                new ArrayList<>(), billing);
    }

    private CompletableFuture<LLMResponse> sendWithFallbackRecursive(
            List<ApiFormat.Message> messages, List<String> chain, int index,
            @Nullable Double temperature, @Nullable Integer maxTokens,
            int timeoutSeconds, List<LLMResponse.AttemptRecord> attempts, LlmBillingContext billing) {
        if (index >= chain.size()) {
            return CompletableFuture.completedFuture(LLMResponse.error("All providers failed").withAttempts(attempts));
        }
        String providerName = chain.get(index);
        Provider provider = providers.get(providerName);
        if (provider == null || !provider.isConfigured()) {
            attempts.add(new LLMResponse.AttemptRecord(providerName, false,
                    provider == null ? "Provider not found" : "Provider not configured", 0));
            return sendWithFallbackRecursive(messages, chain, index + 1, temperature, maxTokens, timeoutSeconds,
                    attempts, billing);
        }
        // CoreProviderAdapter routes through LlmOrchestrator which already emits
        // LlmRequestLogger events (captured by LLMLogger.installCoreHook).
        // Only log non-core providers here to avoid duplicate console entries.
        boolean coreRouted = provider instanceof CoreProviderAdapter;
        String promptSummary = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        CompletableFuture<LLMResponse> execution;
        LlmCostRate rate = costRates.getOrDefault(providerName, LlmCostRate.DEFAULT);
        LlmRequest rawAccountingRequest = null;
        LlmRequestAccounting.Reservation rawReservation = null;
        if (provider instanceof CoreProviderAdapter coreProvider) {
            execution = coreProvider.sendAsync(messages, temperature, maxTokens, timeoutSeconds, billing);
        } else {
            List<LlmMessage> coreMessages = CoreProviderAdapter.toCoreMessages(messages);
            rawAccountingRequest = new LlmRequest(coreMessages, List.of(providerName), temperature, maxTokens,
                    timeoutSeconds, LlmRequestContext.chat(), LlmRouteOptions.empty(), billing);
            long inputTokens = estimateInputTokens(coreMessages);
            Integer boundedOutput = maxTokens != null && maxTokens > 0 ? maxTokens : provider.getMaxTokens();
            long outputTokens = boundedOutput == null ? 0L : Math.max(0, boundedOutput);
            rawReservation = LlmRequestAccounting.reserve(rawAccountingRequest,
                    new LlmRequestAccounting.AttemptEstimate(providerName, inputTokens, outputTokens,
                            rate.weightedEstimate(inputTokens, outputTokens)));
            if (!rawReservation.allowed()) {
                String error = "Billing denied: " + rawReservation.reason();
                attempts.add(new LLMResponse.AttemptRecord(providerName, false, error, 0));
                return CompletableFuture.completedFuture(LLMResponse.denied(
                        rawReservation.denyCode(), error).withAttempts(attempts));
            }
            try {
                execution = provider.sendAsync(messages, temperature, maxTokens, timeoutSeconds);
            } catch (RuntimeException failure) {
                execution = CompletableFuture.completedFuture(LLMResponse.error(
                        "Provider request failed to start: "
                                + (failure.getMessage() == null
                                        ? failure.getClass().getSimpleName() : failure.getMessage())));
            }
        }
        LlmRequest settledRequest = rawAccountingRequest;
        LlmRequestAccounting.Reservation settledReservation = rawReservation;
        if (settledReservation != null) {
            execution.whenComplete((response, throwable) -> {
                if (throwable != null || response == null || !response.isSuccess()) {
                    LlmRequestAccounting.release(settledRequest, settledReservation);
                    return;
                }
                long estimated = response.getPromptTokens() > 0 || response.getCompletionTokens() > 0
                        ? 0L : settledReservation.reservedTokens();
                LlmRequestAccounting.settle(settledRequest, settledReservation,
                        new LlmRequestAccounting.AttemptUsage(response.getPromptTokens(),
                                response.getCompletionTokens(), estimated, estimated > 0L
                                        ? settledReservation.reservedCostUnits()
                                        : rate.weightedTokens(response.getPromptTokens(), response.getCompletionTokens())));
            });
        }
        return execution.handle((response, throwable) -> throwable == null
                ? response
                : LLMResponse.error("Provider request failed: "
                        + (throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                                : throwable.getMessage())))
                .thenCompose(response -> {
            attempts.add(new LLMResponse.AttemptRecord(providerName, response.isSuccess(), response.getError(),
                    response.getLatencyMs()));
            if (!coreRouted) {
                if (response.isSuccess()) {
                    LLMLogger.INSTANCE.logInfo(providerName, promptSummary, response.getLatencyMs(),
                            response.getPromptTokens(), response.getCompletionTokens());
                } else {
                    LLMLogger.INSTANCE.logError(providerName, promptSummary, response.getLatencyMs(), response.getError());
                }
            }
            if (response.isSuccess()) {
                return CompletableFuture.completedFuture(response.withAttempts(attempts));
            }
            if (response.getDenyCode() != LlmRequestAccounting.DenyCode.NONE) {
                return CompletableFuture.completedFuture(response.withAttempts(attempts));
            }
            return sendWithFallbackRecursive(messages, chain, index + 1, temperature, maxTokens, timeoutSeconds,
                    attempts, billing);
        });
    }

    public LlmBillingContext createScriptBilling(List<ApiFormat.Message> messages, List<String> providerChain,
            @Nullable Double temperature, @Nullable Integer maxTokens, int timeoutSeconds, int logicalCalls) {
        return createBilling(LlmBillingContext.PrincipalKind.SCRIPT_SYSTEM, "", messages, providerChain,
                temperature, maxTokens, timeoutSeconds, logicalCalls);
    }

    public LlmBillingContext createPlayerBilling(UUID playerId, List<ApiFormat.Message> messages,
            List<String> providerChain, @Nullable Double temperature, @Nullable Integer maxTokens,
            int timeoutSeconds, int logicalCalls) {
        if (playerId == null) throw new IllegalArgumentException("Player billing requires a UUID");
        return createBilling(LlmBillingContext.PrincipalKind.PLAYER, playerId.toString(), messages,
                providerChain, temperature, maxTokens, timeoutSeconds, logicalCalls);
    }

    private LlmBillingContext createBilling(LlmBillingContext.PrincipalKind kind, String principalId,
            List<ApiFormat.Message> messages, List<String> providerChain,
            @Nullable Double temperature, @Nullable Integer maxTokens, int timeoutSeconds, int logicalCalls) {
        List<LlmMessage> coreMessages = CoreProviderAdapter.toCoreMessages(messages);
        List<String> coreChain = providerChain.stream()
                .filter(name -> orchestrator.getProviderSpec(name) != null)
                .toList();
        LlmRequest template = new LlmRequest(coreMessages, coreChain, temperature, maxTokens,
                timeoutSeconds, LlmRequestContext.chat());
        LlmCallBudget coreBudget = orchestrator.estimateWorstCaseBudget(template);
        int calls = coreBudget.maxCalls();
        long tokens = coreBudget.maxTokens();
        long inputTokens = estimateInputTokens(coreMessages);
        for (String providerName : providerChain) {
            Provider provider = providers.get(providerName);
            if (provider == null || provider instanceof CoreProviderAdapter) continue;
            calls = saturatedAdd(calls, 1);
            Integer boundedOutput = maxTokens != null && maxTokens > 0 ? maxTokens : provider.getMaxTokens();
            tokens = saturatedAdd(tokens, saturatedAdd(inputTokens,
                    boundedOutput == null ? 0L : Math.max(0, boundedOutput)));
        }
        int multiplier = Math.max(1, logicalCalls);
        calls = saturatedMultiply(calls, multiplier);
        tokens = saturatedMultiply(tokens, multiplier);
        String rootId = UUID.randomUUID().toString();
        return kind == LlmBillingContext.PrincipalKind.PLAYER
                ? LlmBillingContext.player(principalId, rootId,
                        Math.max(1, calls), Math.max(1L, tokens))
                : LlmBillingContext.system(kind, rootId,
                        Math.max(1, calls), Math.max(1L, tokens));
    }

    private static long estimateInputTokens(List<LlmMessage> messages) {
        long total = 0L;
        for (LlmMessage message : messages) {
            total = saturatedAdd(total,
                    Math.max(0, LlmMessageFinalizer.CONSERVATIVE_ESTIMATOR.estimate(message)));
        }
        return total;
    }

    private static int saturatedAdd(int left, int right) {
        return right > 0 && left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static int saturatedMultiply(int value, int multiplier) {
        if (value <= 0 || multiplier <= 0) return 0;
        return value > Integer.MAX_VALUE / multiplier ? Integer.MAX_VALUE : value * multiplier;
    }

    private static long saturatedMultiply(long value, int multiplier) {
        if (value <= 0L || multiplier <= 0) return 0L;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    public CompletableFuture<ConnectionStatus> testProvider(String name) {
        return testProvider(name, null);
    }

    public CompletableFuture<ConnectionStatus> testProvider(String name, UUID principalId) {
        if (orchestrator.getProviderSpec(name) == null) {
            return CompletableFuture.completedFuture(
                    new ConnectionStatus(false, 0, "Provider not found", System.currentTimeMillis()));
        }
        int timeout = LLMConfig.TIMEOUT.get();
        LlmBillingContext.PrincipalKind kind = principalId == null
                ? LlmBillingContext.PrincipalKind.SCRIPT_SYSTEM
                : LlmBillingContext.PrincipalKind.PLAYER;
        return orchestrator.testProvider(name, timeout, kind,
                principalId == null ? "" : principalId.toString(), UUID.randomUUID().toString())
                .thenApply(response -> {
            ConnectionStatus status = response.success()
                    ? new ConnectionStatus(true, response.latencyMs(), null, System.currentTimeMillis())
                    : new ConnectionStatus(false, response.latencyMs(), response.error(), System.currentTimeMillis());
            statusCache.put(name, status);
            return status;
        });
    }

    public @Nullable ConnectionStatus getCachedStatus(String name) { return statusCache.get(name); }

    /**
     * Probe whether a provider's model accepts image input. Sends a tiny 1x1 PNG
     * and "describe this image" via the provider's normal chat path (single-element
     * chain so fallbacks are NOT used; we are testing this specific provider).
     * The future completes with {@code true} on any 2xx + parseable content, and
     * {@code false} with an error message otherwise (e.g. "image not supported").
     */
    public CompletableFuture<VisionProbeResult> testVision(String name) {
        return testVision(name, null);
    }

    public CompletableFuture<VisionProbeResult> testVision(String name, @Nullable UUID principalId) {
        Provider provider = providers.get(name);
        if (provider == null) return CompletableFuture.completedFuture(
                new VisionProbeResult(false, "Provider not found", 0));
        int timeout = LLMConfig.TIMEOUT.get();
        // Inline 1x1 transparent PNG (67 bytes) avoids file IO at probe time.
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC");
        String base64 = java.util.Base64.getEncoder().encodeToString(png);
        vibe.liteming.llmjs.format.MessagePart.ImagePart image =
                new vibe.liteming.llmjs.format.MessagePart.ImagePart("image/png", base64, "low", 1, 1, png.length);
        List<ApiFormat.Message> messages = List.of(ApiFormat.Message.userWithImage(
                "Reply with the single word OK.", image));
        LlmBillingContext billing = principalId == null
                ? createScriptBilling(messages, List.of(name), 0.0, 8, timeout, 1)
                : createPlayerBilling(principalId, messages, List.of(name), 0.0, 8, timeout, 1);
        long start = System.currentTimeMillis();
        return sendWithFallback(messages, List.of(name), 0.0, 8, timeout, billing).thenApply(response -> {
            long latency = System.currentTimeMillis() - start;
            if (response.isSuccess()) return new VisionProbeResult(true, null, latency);
            String err = response.getError();
            // Common phrasing from OpenAI-compatible backends when the model lacks vision.
            String canonical = err == null ? "model rejected image"
                    : (err.contains("image") || err.contains("vision") || err.contains("multimodal"))
                            ? err : "model does not support image input";
            return new VisionProbeResult(false, canonical, latency);
        });
    }

    public record VisionProbeResult(boolean supported, @Nullable String error, long latencyMs) {}

    private boolean checkRateLimit() {
        int limit = LLMConfig.RATE_LIMIT.get();
        if (limit <= 0) return true;
        long now = System.currentTimeMillis();
        // Reset window if expired (CAS to avoid race)
        long start = windowStart.get();
        if (now - start > 60000) {
            if (windowStart.compareAndSet(start, now)) {
                requestCount.set(0);
            }
        }
        return requestCount.incrementAndGet() <= limit;
    }
}
