package com.liteming.llmjs.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.liteming.llmjs.config.LLMConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class LLMUtil {
    public static final LLMUtil INSTANCE = new LLMUtil();

    // 缓存已配置的提供商
    private final Map<String, ProviderConfig> providerCache = new ConcurrentHashMap<>();

    // 刷新提供商缓存
    private void refreshProviderCache() {
        providerCache.clear();

        // 添加默认提供商
        ProviderConfig defaultProvider = ProviderConfig.getDefault();
        if (defaultProvider.isValid()) {
            providerCache.put("default", defaultProvider);
        }

        // 添加预定义提供商
        ProviderConfig openai = ProviderConfig.getOpenAI();
        if (openai.isValid()) {
            providerCache.put("openai", openai);
        }

        ProviderConfig claude = ProviderConfig.getClaude();
        if (claude.isValid()) {
            providerCache.put("claude", claude);
        }

        ProviderConfig local = ProviderConfig.getLocal();
        if (local.isValid()) {
            providerCache.put("local", local);
        }

        ProviderConfig custom1 = ProviderConfig.getCustom1();
        if (custom1.isValid()) {
            providerCache.put(custom1.name, custom1);
        }

        ProviderConfig custom2 = ProviderConfig.getCustom2();
        if (custom2.isValid()) {
            providerCache.put(custom2.name, custom2);
        }
    }

    // 获取可用的提供商
    public List<String> getAvailableProviders() {
        refreshProviderCache();
        return new ArrayList<>(providerCache.keySet());
    }

    // 简单对话 - 使用默认提供商
    public String chat(@NotNull String prompt) {
        return chat(prompt, null, null);
    }

    // 带系统提示的对话 - 使用默认提供商
    public String chat(@NotNull String prompt, @Nullable String systemPrompt) {
        return chat(prompt, systemPrompt, null);
    }

    // 指定提供商的对话
    public String chat(@NotNull String prompt, @Nullable String systemPrompt, @Nullable String provider) {
        ProviderConfig config = getProviderConfig(provider);
        return LLMApiClient.simpleChat(prompt, systemPrompt, config);
    }

    // 异步对话 - 使用默认提供商
    public CompletableFuture<String> chatAsync(@NotNull String prompt) {
        return chatAsync(prompt, null, null);
    }

    public CompletableFuture<String> chatAsync(@NotNull String prompt, @Nullable String systemPrompt) {
        return chatAsync(prompt, systemPrompt, null);
    }

    // 指定提供商的异步对话
    public CompletableFuture<String> chatAsync(@NotNull String prompt, @Nullable String systemPrompt, @Nullable String provider) {
        ProviderConfig config = getProviderConfig(provider);
        String model = config != null ? config.model : LLMConfig.DEFAULT_MODEL.get();

        List<LLMApiClient.Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new LLMApiClient.Message("system", systemPrompt));
        }
        messages.add(new LLMApiClient.Message("user", prompt));

        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(model, messages)
                .temperature(LLMConfig.DEFAULT_TEMPERATURE.get())
                .maxTokens(LLMConfig.DEFAULT_MAX_TOKENS.get());

        return LLMApiClient.chatAsync(request, config).thenApply(response -> {
            if (response.success) {
                return response.content;
            } else {
                return "Error: " + response.error;
            }
        });
    }

    // 多提供商并行请求
    public CompletableFuture<Map<String, String>> chatMultiple(@NotNull String prompt, @Nullable String systemPrompt, @NotNull List<String> providers) {
        Map<String, CompletableFuture<String>> futures = new HashMap<>();

        for (String provider : providers) {
            futures.put(provider, chatAsync(prompt, systemPrompt, provider));
        }

        // 等待所有请求完成
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.values().toArray(new CompletableFuture[0])
        );

        return allFutures.thenApply(v -> {
            Map<String, String> results = new HashMap<>();
            for (Map.Entry<String, CompletableFuture<String>> entry : futures.entrySet()) {
                try {
                    results.put(entry.getKey(), entry.getValue().get());
                } catch (Exception e) {
                    results.put(entry.getKey(), "Error: " + e.getMessage());
                }
            }
            return results;
        });
    }

    // 多提供商并行请求（使用所有可用提供商）
    public CompletableFuture<Map<String, String>> chatMultipleAll(@NotNull String prompt, @Nullable String systemPrompt) {
        return chatMultiple(prompt, systemPrompt, getAvailableProviders());
    }

    // 高级对话 - 返回详细信息
    public JsonObject chatDetailed(@NotNull String prompt) {
        return chatDetailed(prompt, null, null);
    }

    public JsonObject chatDetailed(@NotNull String prompt, @Nullable String systemPrompt) {
        return chatDetailed(prompt, systemPrompt, null);
    }

    public JsonObject chatDetailed(@NotNull String prompt, @Nullable String systemPrompt, @Nullable String provider) {
        ProviderConfig config = getProviderConfig(provider);
        String model = config != null ? config.model : LLMConfig.DEFAULT_MODEL.get();

        List<LLMApiClient.Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new LLMApiClient.Message("system", systemPrompt));
        }
        messages.add(new LLMApiClient.Message("user", prompt));

        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(model, messages)
                .temperature(LLMConfig.DEFAULT_TEMPERATURE.get())
                .maxTokens(LLMConfig.DEFAULT_MAX_TOKENS.get());

        LLMApiClient.ChatResponse response = LLMApiClient.chat(request, config);
        JsonObject result = response.toJson();

        // 添加提供商信息
        if (config != null) {
            result.addProperty("provider", config.name);
        }

        return result;
    }

    // 多轮对话
    public String chatWithHistory(@NotNull List<JsonObject> messageHistory, @NotNull String newMessage) {
        return chatWithHistory(messageHistory, newMessage, null);
    }

    public String chatWithHistory(@NotNull List<JsonObject> messageHistory, @NotNull String newMessage, @Nullable String provider) {
        ProviderConfig config = getProviderConfig(provider);

        List<LLMApiClient.Message> messages = new ArrayList<>();

        // 转换历史消息
        for (JsonObject msgObj : messageHistory) {
            String role = msgObj.get("role").getAsString();
            String content = msgObj.get("content").getAsString();
            messages.add(new LLMApiClient.Message(role, content));
        }

        // 添加新消息
        messages.add(new LLMApiClient.Message("user", newMessage));

        String model = config != null ? config.model : LLMConfig.DEFAULT_MODEL.get();
        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(model, messages)
                .temperature(LLMConfig.DEFAULT_TEMPERATURE.get())
                .maxTokens(LLMConfig.DEFAULT_MAX_TOKENS.get());

        LLMApiClient.ChatResponse response = LLMApiClient.chat(request, config);

        if (response.success) {
            return response.content;
        } else {
            return "Error: " + response.error;
        }
    }

    // 自定义参数对话
    public String chatCustom(@NotNull String prompt, @Nullable String systemPrompt,
                             @Nullable String provider, @Nullable String model,
                             @Nullable Double temperature, @Nullable Integer maxTokens) {
        ProviderConfig config = getProviderConfig(provider);

        List<LLMApiClient.Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new LLMApiClient.Message("system", systemPrompt));
        }
        messages.add(new LLMApiClient.Message("user", prompt));

        String useModel = model != null ? model :
                (config != null ? config.model : LLMConfig.DEFAULT_MODEL.get());

        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(useModel, messages);

        if (temperature != null) {
            request.temperature(temperature);
        } else {
            request.temperature(LLMConfig.DEFAULT_TEMPERATURE.get());
        }

        if (maxTokens != null) {
            request.maxTokens(maxTokens);
        } else {
            request.maxTokens(LLMConfig.DEFAULT_MAX_TOKENS.get());
        }

        LLMApiClient.ChatResponse response = LLMApiClient.chat(request, config);

        if (response.success) {
            return response.content;
        } else {
            return "Error: " + response.error;
        }
    }

    // 获取提供商配置
    private ProviderConfig getProviderConfig(@Nullable String provider) {
        if (provider == null) {
            return null;
        }

        refreshProviderCache();
        return providerCache.get(provider);
    }

    // 获取所有提供商配置
    public JsonObject getAllConfigs() {
        refreshProviderCache();

        JsonObject configs = new JsonObject();

        // 默认配置
        JsonObject defaultConfig = new JsonObject();
        defaultConfig.addProperty("api_url", LLMConfig.DEFAULT_API_URL.get());
        defaultConfig.addProperty("model", LLMConfig.DEFAULT_MODEL.get());
        defaultConfig.addProperty("timeout", LLMConfig.DEFAULT_TIMEOUT.get());
        defaultConfig.addProperty("max_tokens", LLMConfig.DEFAULT_MAX_TOKENS.get());
        defaultConfig.addProperty("temperature", LLMConfig.DEFAULT_TEMPERATURE.get());
        defaultConfig.addProperty("has_api_key", !LLMConfig.DEFAULT_API_KEY.get().equals("your-api-key-here") && !LLMConfig.DEFAULT_API_KEY.get().isEmpty());
        configs.add("default", defaultConfig);

        // 各提供商配置
        for (Map.Entry<String, ProviderConfig> entry : providerCache.entrySet()) {
            ProviderConfig config = entry.getValue();
            JsonObject providerConfig = new JsonObject();
            providerConfig.addProperty("name", config.name);
            providerConfig.addProperty("url", config.url);
            providerConfig.addProperty("model", config.model);
            providerConfig.addProperty("has_api_key", config.isValid());
            configs.add(entry.getKey(), providerConfig);
        }

        // 可用提供商列表
        JsonArray availableProviders = new JsonArray();
        for (String provider : getAvailableProviders()) {
            availableProviders.add(provider);
        }
        configs.add("available_providers", availableProviders);

        return configs;
    }

    // 测试指定提供商的连接
    public JsonObject testConnection(@Nullable String provider) {
        String testPrompt = "Hello! This is a connection test. Please respond with 'Connection successful'.";

        ProviderConfig config = getProviderConfig(provider);
        String model = config != null ? config.model : LLMConfig.DEFAULT_MODEL.get();

        List<LLMApiClient.Message> messages = new ArrayList<>();
        messages.add(new LLMApiClient.Message("user", testPrompt));

        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(model, messages)
                .temperature(0.1)
                .maxTokens(50);

        LLMApiClient.ChatResponse response = LLMApiClient.chat(request, config);

        JsonObject result = new JsonObject();
        result.addProperty("provider", provider != null ? provider : "default");
        result.addProperty("success", response.success);
        if (response.success) {
            result.addProperty("response", response.content);
            result.addProperty("model", response.model);
            result.addProperty("tokens_used", response.totalTokens);
        } else {
            result.addProperty("error", response.error);
        }

        return result;
    }

    // 测试所有提供商的连接
    public CompletableFuture<JsonObject> testAllConnections() {
        List<String> providers = getAvailableProviders();
        Map<String, CompletableFuture<JsonObject>> futures = new HashMap<>();

        for (String provider : providers) {
            futures.put(provider, CompletableFuture.supplyAsync(() -> testConnection(provider)));
        }

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.values().toArray(new CompletableFuture[0])
        );

        return allFutures.thenApply(v -> {
            JsonObject results = new JsonObject();
            for (Map.Entry<String, CompletableFuture<JsonObject>> entry : futures.entrySet()) {
                try {
                    results.add(entry.getKey(), entry.getValue().get());
                } catch (Exception e) {
                    JsonObject errorResult = new JsonObject();
                    errorResult.addProperty("provider", entry.getKey());
                    errorResult.addProperty("success", false);
                    errorResult.addProperty("error", "Test failed: " + e.getMessage());
                    results.add(entry.getKey(), errorResult);
                }
            }
            return results;
        });
    }
}