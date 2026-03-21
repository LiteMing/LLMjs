package com.liteming.llmjs.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.liteming.llmjs.config.LLMConfig;
import com.liteming.llmjs.format.ApiFormat;
import com.liteming.llmjs.json.FillMode;
import com.liteming.llmjs.json.JsonMode;
import com.liteming.llmjs.json.SchemaMode;
import com.liteming.llmjs.log.LLMLogger;
import com.liteming.llmjs.pipeline.*;
import com.liteming.llmjs.provider.Provider;
import com.liteming.llmjs.provider.ProviderManager;
import com.liteming.llmjs.session.ChatSession;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class LLMBinding {
    public static final LLMBinding INSTANCE = new LLMBinding();

    // === chat() overloads ===

    // Builder form: LLM.chat("prompt") -> LLMRequest
    public LLMRequest chat(String prompt) {
        return new LLMRequest(prompt);
    }

    // Builder form with options: LLM.chat("prompt", {provider: "x"}) -> LLMRequest
    public LLMRequest chat(String prompt, Map<String, Object> options) {
        return new LLMRequest(prompt,
                getStr(options, "system"),
                getStr(options, "provider"),
                getDbl(options, "temperature"),
                getInt(options, "maxTokens"),
                getStrList(options, "fallback"));
    }

    // Callback form: LLM.chat("prompt", result => {})
    public void chat(String prompt, Consumer<LLMResponse> callback) {
        executeDirect(prompt, null, null, null, null, null, callback);
    }

    // Callback form with options: LLM.chat("prompt", {provider: "x"}, result => {})
    public void chat(String prompt, Map<String, Object> options, Consumer<LLMResponse> callback) {
        executeDirect(prompt,
                getStr(options, "system"),
                getStr(options, "provider"),
                getDbl(options, "temperature"),
                getInt(options, "maxTokens"),
                getStrList(options, "fallback"),
                callback);
    }

    // === chatDetailed() ===

    public void chatDetailed(String prompt, Consumer<LLMResponse> callback) {
        executeDirect(prompt, null, null, null, null, null, callback);
    }

    public void chatDetailed(String prompt, Map<String, Object> options, Consumer<LLMResponse> callback) {
        executeDirect(prompt,
                getStr(options, "system"),
                getStr(options, "provider"),
                getDbl(options, "temperature"),
                getInt(options, "maxTokens"),
                getStrList(options, "fallback"),
                callback);
    }

    // === Session API ===

    public ChatSession session(String provider) {
        return new ChatSession(provider);
    }

    public ChatSession session() {
        return new ChatSession(LLMConfig.DEFAULT_PROVIDER.get());
    }

    // === JSON Workflow ===

    public void chatJson(String prompt, Consumer<Object> callback) {
        JsonMode.chatJson(prompt, null, null, null, callback);
    }

    public void chatJson(String prompt, Map<String, Object> options, Consumer<Object> callback) {
        String provider = getStr(options, "provider");
        Double temp = getDbl(options, "temperature");
        Integer maxTokens = getInt(options, "maxTokens");

        // Check for schema
        Object schemaObj = options.get("schema");
        if (schemaObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schemaMap = (Map<String, Object>) schemaObj;
            JsonObject schema = mapToJson(schemaMap);
            SchemaMode.chatWithSchema(prompt, schema, provider, temp, maxTokens, callback);
        } else {
            JsonMode.chatJson(prompt, provider, temp, maxTokens, callback);
        }
    }

    public void fill(Map<String, Object> template, String description, Consumer<Object> callback) {
        JsonObject templateJson = mapToJson(template);
        FillMode.fill(templateJson, description, null, null, null, callback);
    }

    public void fill(Map<String, Object> template, String description,
                     Map<String, Object> options, Consumer<Object> callback) {
        JsonObject templateJson = mapToJson(template);
        FillMode.fill(templateJson, description,
                getStr(options, "provider"),
                getDbl(options, "temperature"),
                getInt(options, "maxTokens"),
                callback);
    }

    // === Regex Presets ===

    public void regex(String name, List<Map<String, Object>> steps) {
        List<PostProcessor> processors = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            String type = String.valueOf(step.get("type"));
            String pattern = String.valueOf(step.get("pattern"));
            if ("extract".equals(type)) {
                processors.add(PostProcessor.extract(pattern));
            } else if ("replace".equals(type)) {
                String replacement = step.containsKey("replacement") ? String.valueOf(step.get("replacement")) : "";
                processors.add(PostProcessor.replace(pattern, replacement));
            }
        }
        RegexPreset.register(name, new RegexPreset(name, processors));
    }

    // === Management API ===

    public List<String> providers() {
        return ProviderManager.INSTANCE.getProviderNames();
    }

    public JsonObject status(String name) {
        var cached = ProviderManager.INSTANCE.getCachedStatus(name);
        return cached != null ? cached.toJson() : new JsonObject();
    }

    public JsonObject statusAll() {
        return ProviderManager.INSTANCE.getStatusJson();
    }

    public void test(String provider, Consumer<LLMResponse> callback) {
        ProviderManager.INSTANCE.testProvider(provider).thenAccept(status -> {
            LLMResponse response = status.connected()
                    ? LLMResponse.success("Connection successful", "", provider, 0, 0, status.latencyMs())
                    : LLMResponse.error(status.lastError() != null ? status.lastError() : "Connection failed");
            callback.accept(response);
        });
    }

    public JsonArray logs() {
        return LLMLogger.INSTANCE.toJsonArray(200);
    }

    public JsonArray logs(int count) {
        return LLMLogger.INSTANCE.toJsonArray(count);
    }

    public JsonArray logs(String level) {
        LLMLogger.Level lvl = LLMLogger.Level.valueOf(level.toUpperCase());
        JsonArray arr = new JsonArray();
        for (LLMLogger.LogEntry entry : LLMLogger.INSTANCE.getRecentByLevel(lvl, 200)) {
            arr.add(entry.toJson());
        }
        return arr;
    }

    public void log(String level, String message) {
        LLMLogger.Level lvl = LLMLogger.Level.valueOf(level.toUpperCase());
        LLMLogger.INSTANCE.log(lvl, "script", message, "manual", 0, 0, 0, null);
    }

    public void reload() {
        ProviderManager.INSTANCE.reload();
    }

    // === Helpers ===

    private void executeDirect(String prompt, @Nullable String system, @Nullable String provider,
                               @Nullable Double temperature, @Nullable Integer maxTokens,
                               @Nullable List<String> fallback, Consumer<LLMResponse> callback) {
        List<ApiFormat.Message> messages = new ArrayList<>();
        if (system != null && !system.isEmpty()) {
            messages.add(new ApiFormat.Message("system", system));
        }
        messages.add(new ApiFormat.Message("user", prompt));

        List<String> chain;
        if (fallback != null && !fallback.isEmpty()) {
            chain = fallback;
        } else if (provider != null) {
            chain = List.of(provider);
        } else {
            Provider defaultP = ProviderManager.INSTANCE.getDefaultProvider();
            chain = defaultP != null ? List.of(defaultP.getName()) : List.of();
        }

        int timeout = LLMConfig.TIMEOUT.get();
        ProviderManager.INSTANCE.sendWithFallback(messages, chain, temperature, maxTokens, timeout)
                .thenAccept(callback);
    }

    private static @Nullable String getStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private static @Nullable Double getDbl(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : null;
    }

    private static @Nullable Integer getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof Number ? ((Number) val).intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<String> getStrList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) val) result.add(item.toString());
            return result;
        }
        return null;
    }

    private static JsonObject mapToJson(Map<String, Object> map) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String) json.addProperty(entry.getKey(), (String) val);
            else if (val instanceof Number) json.addProperty(entry.getKey(), (Number) val);
            else if (val instanceof Boolean) json.addProperty(entry.getKey(), (Boolean) val);
            else if (val != null) json.addProperty(entry.getKey(), val.toString());
        }
        return json;
    }
}
