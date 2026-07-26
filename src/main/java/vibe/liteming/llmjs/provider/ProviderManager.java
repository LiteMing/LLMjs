package vibe.liteming.llmjs.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import vibe.liteming.llmcore.LlmMessage;
import vibe.liteming.llmcore.LlmOrchestrator;
import vibe.liteming.llmcore.LlmRequest;
import vibe.liteming.llmcore.LlmRequestContext;
import vibe.liteming.llmcore.LlmResolvedParameters;
import vibe.liteming.llmcore.LlmRouteOptions;
import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.ProviderConfigLoader;
import vibe.liteming.llmcore.ProviderSpec;
import vibe.liteming.llmcore.PurposeMeta;
import vibe.liteming.llmcore.PurposeRegistry;
import vibe.liteming.llmcore.RoutingConfigStore;
import vibe.liteming.llmjs.LLMjs;
import vibe.liteming.llmjs.config.GlobalConfig;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.config.ProviderLoader;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.pipeline.LLMResponse;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ProviderManager {
    public static final ProviderManager INSTANCE = new ProviderManager();

    private volatile Map<String, Provider> providers = new ConcurrentHashMap<>();
    private volatile LlmOrchestrator orchestrator = new LlmOrchestrator(Map.of());
    private volatile PriorityRoutingConfig routingConfig = PriorityRoutingConfig.empty();
    private final Map<String, ConnectionStatus> statusCache = new ConcurrentHashMap<>();
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

    private Path gameRoot;

    public void init(Path serverConfigDir, Path gameRoot) {
        this.configDir = serverConfigDir.resolve("llmjs");
        this.gameRoot = gameRoot;
        reload();
    }

    public void reload() {
        if (configDir == null || gameRoot == null) return;
        Map<String, ProviderSpec> specs = ProviderConfigLoader.load(
                GlobalConfig.getGlobalProvidersFile(),
                configDir.resolve("providers.json"),
                gameRoot.resolve("llmjs.secret"));
        this.orchestrator = new LlmOrchestrator(specs);
        this.orchestrator.setGlobalDefaults(new LlmRouteOptions(null, null, LLMConfig.TIMEOUT.get(), null, null));
        Map<String, Provider> newProviders = new LinkedHashMap<>();
        specs.forEach((name, spec) -> newProviders.put(name, new CoreProviderAdapter(spec, orchestrator)));
        ProviderLoader.loadAll(configDir, gameRoot).forEach((name, provider) -> {
            if ("raw".equals(provider.getType())) newProviders.put(name, provider);
        });
        this.providers = new ConcurrentHashMap<>(newProviders);
        LLMjs.LOGGER.info("Loaded {} providers", providers.size());
        // Load/reload the shared priority-routing table and push it into the orchestrator.
        this.routingConfig = RoutingConfigStore.load(getRoutingFile());
        this.orchestrator.setRoutingConfig(routingConfig);
    }

    /** Path used for {@code routing.json} (lives next to global providers.json). */
    public Path getRoutingFile() {
        if (gameRoot == null) return null;
        return GlobalConfig.getGlobalProvidersFile().getParent().resolve("routing.json");
    }

    public PriorityRoutingConfig getRoutingConfig() {
        return routingConfig;
    }

    /**
     * Atomically update + persist the global routing table. Returns false if the
     * write failed. The in-memory orchestrator is updated even when persistence
     * fails so the running server still picks up the change for the session.
     */
    public synchronized boolean updateRouting(PriorityRoutingConfig next) {
        this.routingConfig = next == null ? PriorityRoutingConfig.empty() : next;
        this.orchestrator.setRoutingConfig(this.routingConfig);
        Path file = getRoutingFile();
        if (file == null) return false;
        boolean ok = RoutingConfigStore.save(file, this.routingConfig);
        if (!ok) {
            LLMjs.LOGGER.warn("Failed to persist routing.json (in-memory still updated)");
        }
        return ok;
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
            pJson.addProperty("url", entry.getValue().getUrl());
            pJson.addProperty("maskedKey", entry.getValue().getMaskedKey());
            pJson.addProperty("configured", entry.getValue().isConfigured());
            ConnectionStatus cached = statusCache.get(entry.getKey());
            if (cached != null) pJson.add("status", cached.toJson());
            else pJson.addProperty("status", "untested");
            providerArray.add(pJson);
        }
        result.add("providers", providerArray);
        result.addProperty("count", providers.size());
        // routing payload (default + per-purpose chains) consumed by the Routing tab
        result.add("routing", com.google.gson.JsonParser.parseString(
                RoutingConfigStore.toJsonString(routingConfig)).getAsJsonObject());
        result.addProperty("routingFingerprint", RoutingConfigStore.fingerprint(routingConfig));
        JsonArray purposesArray = new JsonArray();
        List<String> allCoreProviders = new ArrayList<>(orchestrator.getProviderNames());
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
            purposesArray.add(pm);
        }
        result.add("purposes", purposesArray);
        return result;
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

    public @Nullable Provider getProvider(String name) { return providers.get(name); }

    public Provider getDefaultProvider() {
        String defaultName = LLMConfig.DEFAULT_PROVIDER.get();
        Provider p = providers.get(defaultName);
        if (p != null) return p;
        return providers.values().stream().findFirst().orElse(null);
    }

    public List<String> getProviderNames() { return new ArrayList<>(providers.keySet()); }
    public Map<String, Provider> getAllProviders() { return Collections.unmodifiableMap(providers); }

    public CompletableFuture<LLMResponse> sendWithFallback(
            List<ApiFormat.Message> messages, List<String> providerChain,
            @Nullable Double temperature, @Nullable Integer maxTokens, int timeoutSeconds) {
        if (providerChain.isEmpty()) return CompletableFuture.completedFuture(LLMResponse.error("No providers specified"));
        if (!checkRateLimit()) return CompletableFuture.completedFuture(LLMResponse.error("Rate limit exceeded"));
        return sendWithFallbackRecursive(messages, providerChain, 0, temperature, maxTokens, timeoutSeconds,
                new ArrayList<>());
    }

    private CompletableFuture<LLMResponse> sendWithFallbackRecursive(
            List<ApiFormat.Message> messages, List<String> chain, int index,
            @Nullable Double temperature, @Nullable Integer maxTokens,
            int timeoutSeconds, List<LLMResponse.AttemptRecord> attempts) {
        if (index >= chain.size()) {
            return CompletableFuture.completedFuture(LLMResponse.error("All providers failed").withAttempts(attempts));
        }
        String providerName = chain.get(index);
        Provider provider = providers.get(providerName);
        if (provider == null || !provider.isConfigured()) {
            attempts.add(new LLMResponse.AttemptRecord(providerName, false,
                    provider == null ? "Provider not found" : "Provider not configured", 0));
            return sendWithFallbackRecursive(messages, chain, index + 1, temperature, maxTokens, timeoutSeconds,
                    attempts);
        }
        // CoreProviderAdapter routes through LlmOrchestrator which already emits
        // LlmRequestLogger events (captured by LLMLogger.installCoreHook).
        // Only log non-core providers here to avoid duplicate console entries.
        boolean coreRouted = provider instanceof CoreProviderAdapter;
        String promptSummary = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        return provider.sendAsync(messages, temperature, maxTokens, timeoutSeconds).thenCompose(response -> {
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
            return sendWithFallbackRecursive(messages, chain, index + 1, temperature, maxTokens, timeoutSeconds,
                    attempts);
        });
    }

    public CompletableFuture<ConnectionStatus> testProvider(String name) {
        Provider provider = providers.get(name);
        if (provider == null) return CompletableFuture.completedFuture(new ConnectionStatus(false, 0, "Provider not found", System.currentTimeMillis()));
        int timeout = LLMConfig.TIMEOUT.get();
        return provider.testConnection(timeout).thenApply(response -> {
            ConnectionStatus status = response.isSuccess()
                    ? new ConnectionStatus(true, response.getLatencyMs(), null, System.currentTimeMillis())
                    : new ConnectionStatus(false, response.getLatencyMs(), response.getError(), System.currentTimeMillis());
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
        long start = System.currentTimeMillis();
        return provider.sendAsync(messages, 0.0, 8, timeout).thenApply(response -> {
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
