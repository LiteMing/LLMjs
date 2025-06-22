package com.liteming.llmjs.util;

import com.google.gson.JsonObject;
import com.liteming.llmjs.config.LLMConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LLMUtil {
    public static final LLMUtil INSTANCE = new LLMUtil();

    // 简单对话
    public String chat(@NotNull String prompt) {
        return LLMApiClient.simpleChat(prompt);
    }

    // 带系统提示的对话
    public String chat(@NotNull String prompt, @Nullable String systemPrompt) {
        return LLMApiClient.simpleChat(prompt, systemPrompt);
    }

    // 异步对话
    public CompletableFuture<String> chatAsync(@NotNull String prompt) {
        return chatAsync(prompt, null);
    }

    public CompletableFuture<String> chatAsync(@NotNull String prompt, @Nullable String systemPrompt) {
        String model = LLMConfig.MODEL.get();

        List<LLMApiClient.Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new LLMApiClient.Message("system", systemPrompt));
        }
        messages.add(new LLMApiClient.Message("user", prompt));

        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(model, messages)
                .temperature(LLMConfig.TEMPERATURE.get())
                .maxTokens(LLMConfig.MAX_TOKENS.get());

        return LLMApiClient.chatAsync(request).thenApply(response -> {
            if (response.success) {
                return response.content;
            } else {
                return "Error: " + response.error;
            }
        });
    }

    // 高级对话 - 返回详细信息
    public JsonObject chatDetailed(@NotNull String prompt) {
        return chatDetailed(prompt, null);
    }

    public JsonObject chatDetailed(@NotNull String prompt, @Nullable String systemPrompt) {
        String model = LLMConfig.MODEL.get();

        List<LLMApiClient.Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new LLMApiClient.Message("system", systemPrompt));
        }
        messages.add(new LLMApiClient.Message("user", prompt));

        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(model, messages)
                .temperature(LLMConfig.TEMPERATURE.get())
                .maxTokens(LLMConfig.MAX_TOKENS.get());

        LLMApiClient.ChatResponse response = LLMApiClient.chat(request);
        return response.toJson();
    }

    // 多轮对话
    public String chatWithHistory(@NotNull List<JsonObject> messageHistory, @NotNull String newMessage) {
        List<LLMApiClient.Message> messages = new ArrayList<>();

        // 转换历史消息
        for (JsonObject msgObj : messageHistory) {
            String role = msgObj.get("role").getAsString();
            String content = msgObj.get("content").getAsString();
            messages.add(new LLMApiClient.Message(role, content));
        }

        // 添加新消息
        messages.add(new LLMApiClient.Message("user", newMessage));

        String model = LLMConfig.MODEL.get();
        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(model, messages)
                .temperature(LLMConfig.TEMPERATURE.get())
                .maxTokens(LLMConfig.MAX_TOKENS.get());

        LLMApiClient.ChatResponse response = LLMApiClient.chat(request);

        if (response.success) {
            return response.content;
        } else {
            return "Error: " + response.error;
        }
    }

    // 自定义参数对话
    public String chatCustom(@NotNull String prompt, @Nullable String systemPrompt,
                             @Nullable String model, @Nullable Double temperature, @Nullable Integer maxTokens) {
        List<LLMApiClient.Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new LLMApiClient.Message("system", systemPrompt));
        }
        messages.add(new LLMApiClient.Message("user", prompt));

        String useModel = model != null ? model : LLMConfig.MODEL.get();
        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(useModel, messages);

        if (temperature != null) {
            request.temperature(temperature);
        } else {
            request.temperature(LLMConfig.TEMPERATURE.get());
        }

        if (maxTokens != null) {
            request.maxTokens(maxTokens);
        } else {
            request.maxTokens(LLMConfig.MAX_TOKENS.get());
        }

        LLMApiClient.ChatResponse response = LLMApiClient.chat(request);

        if (response.success) {
            return response.content;
        } else {
            return "Error: " + response.error;
        }
    }

    // 获取当前配置
    public JsonObject getConfig() {
        JsonObject config = new JsonObject();
        config.addProperty("api_url", LLMConfig.API_URL.get());
        config.addProperty("model", LLMConfig.MODEL.get());
        config.addProperty("timeout", LLMConfig.TIMEOUT.get());
        config.addProperty("max_tokens", LLMConfig.MAX_TOKENS.get());
        config.addProperty("temperature", LLMConfig.TEMPERATURE.get());
        config.addProperty("has_api_key", !LLMConfig.API_KEY.get().equals("your-api-key-here") && !LLMConfig.API_KEY.get().isEmpty());
        return config;
    }

    // 测试连接
    public JsonObject testConnection() {
        String testPrompt = "Hello! This is a connection test. Please respond with 'Connection successful'.";

        List<LLMApiClient.Message> messages = new ArrayList<>();
        messages.add(new LLMApiClient.Message("user", testPrompt));

        LLMApiClient.ChatRequest request = new LLMApiClient.ChatRequest(LLMConfig.MODEL.get(), messages)
                .temperature(0.1)
                .maxTokens(50);

        LLMApiClient.ChatResponse response = LLMApiClient.chat(request);

        JsonObject result = new JsonObject();
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
}