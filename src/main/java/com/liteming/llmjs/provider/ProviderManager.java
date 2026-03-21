package com.liteming.llmjs.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.liteming.llmjs.LLMjs;
import com.liteming.llmjs.config.LLMConfig;
import com.liteming.llmjs.config.ProviderLoader;
import com.liteming.llmjs.format.ApiFormat;
import com.liteming.llmjs.log.LLMLogger;
import com.liteming.llmjs.pipeline.LLMResponse;
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
        Map<String, Provider> newProviders = ProviderLoader.loadAll(configDir, gameRoot);
        this.providers = new ConcurrentHashMap<>(newProviders);
        LLMjs.LOGGER.info("Loaded {} providers", providers.size());
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
        return sendWithFallbackRecursive(messages, providerChain, 0, temperature, maxTokens, timeoutSeconds, new ArrayList<>());
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
        if (provider == null) {
            attempts.add(new LLMResponse.AttemptRecord(providerName, false, "Provider not found", 0));
            return sendWithFallbackRecursive(messages, chain, index + 1, temperature, maxTokens, timeoutSeconds, attempts);
        }
        if (!provider.isConfigured()) {
            attempts.add(new LLMResponse.AttemptRecord(providerName, false, "Provider not configured (missing API key)", 0));
            return sendWithFallbackRecursive(messages, chain, index + 1, temperature, maxTokens, timeoutSeconds, attempts);
        }
        String promptSummary = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        return provider.sendAsync(messages, temperature, maxTokens, timeoutSeconds)
                .thenCompose(response -> {
                    if (response.isSuccess()) {
                        attempts.add(new LLMResponse.AttemptRecord(providerName, true, null, response.getLatencyMs()));
                        LLMLogger.INSTANCE.logInfo(providerName, promptSummary, response.getLatencyMs(), response.getPromptTokens(), response.getCompletionTokens());
                        return CompletableFuture.completedFuture(response.withAttempts(attempts));
                    } else {
                        attempts.add(new LLMResponse.AttemptRecord(providerName, false, response.getError(), response.getLatencyMs()));
                        LLMLogger.INSTANCE.logError(providerName, promptSummary, response.getLatencyMs(), response.getError());
                        return sendWithFallbackRecursive(messages, chain, index + 1, temperature, maxTokens, timeoutSeconds, attempts);
                    }
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
            pJson.addProperty("maskedKey", entry.getValue().getMaskedKey());
            pJson.addProperty("configured", entry.getValue().isConfigured());
            ConnectionStatus cached = statusCache.get(entry.getKey());
            if (cached != null) pJson.add("status", cached.toJson());
            else pJson.addProperty("status", "untested");
            providerArray.add(pJson);
        }
        result.add("providers", providerArray);
        result.addProperty("count", providers.size());
        return result;
    }

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
