package com.liteming.llmjs.config;

import com.google.gson.*;
import com.liteming.llmjs.LLMjs;
import com.liteming.llmjs.provider.Provider;
import com.liteming.llmjs.provider.RawProvider;
import com.liteming.llmjs.provider.SimpleProvider;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProviderLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Map<String, Provider> loadAll(Path configDir) {
        Map<String, Provider> providers = new LinkedHashMap<>();
        Path simpleFile = configDir.resolve("providers.json");
        Path rawFile = configDir.resolve("providers_raw.json");

        try {
            Files.createDirectories(configDir);
            if (!Files.exists(simpleFile)) {
                Files.writeString(simpleFile, getDefaultSimpleConfig());
                LLMjs.LOGGER.info("Created default providers.json");
            }
            if (!Files.exists(rawFile)) {
                Files.writeString(rawFile, getDefaultRawConfig());
                LLMjs.LOGGER.info("Created default providers_raw.json");
            }
        } catch (IOException e) {
            LLMjs.LOGGER.error("Failed to create config directory/files", e);
        }

        loadSimpleProviders(simpleFile, providers);
        loadRawProviders(rawFile, providers);
        return providers;
    }

    private static void loadSimpleProviders(Path file, Map<String, Provider> providers) {
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    String type = obj.has("type") ? obj.get("type").getAsString() : "simple";
                    if (!"simple".equals(type)) continue;
                    String format = obj.has("format") ? obj.get("format").getAsString() : "openai";
                    String url = obj.get("url").getAsString();
                    String key = obj.get("key").getAsString();
                    String model = obj.get("model").getAsString();
                    Double temp = obj.has("temperature") ? obj.get("temperature").getAsDouble() : null;
                    Integer maxTokens = obj.has("max_tokens") ? obj.get("max_tokens").getAsInt() : null;
                    SimpleProvider provider = new SimpleProvider(entry.getKey(), format, url, key, model, temp, maxTokens);
                    if (provider.isValid()) providers.put(entry.getKey(), provider);
                } catch (Exception e) {
                    LLMjs.LOGGER.warn("Failed to load provider '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LLMjs.LOGGER.error("Failed to load providers.json", e);
        }
    }

    private static void loadRawProviders(Path file, Map<String, Provider> providers) {
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    String url = obj.get("url").getAsString();
                    String method = obj.has("method") ? obj.get("method").getAsString() : "POST";
                    String responsePath = obj.get("response_path").getAsString();
                    String key = obj.has("key") ? obj.get("key").getAsString() : "";
                    String model = obj.has("model") ? obj.get("model").getAsString() : "";
                    Map<String, String> headers = new LinkedHashMap<>();
                    if (obj.has("headers")) {
                        for (Map.Entry<String, JsonElement> h : obj.getAsJsonObject("headers").entrySet()) {
                            headers.put(h.getKey(), h.getValue().getAsString());
                        }
                    }
                    JsonObject bodyTemplate = obj.has("body_template") ? obj.getAsJsonObject("body_template") : new JsonObject();
                    RawProvider provider = new RawProvider(entry.getKey(), url, method, headers, bodyTemplate, responsePath, key, model);
                    if (provider.isValid()) providers.put(entry.getKey(), provider);
                } catch (Exception e) {
                    LLMjs.LOGGER.warn("Failed to load raw provider '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LLMjs.LOGGER.error("Failed to load providers_raw.json", e);
        }
    }

    private static String getDefaultSimpleConfig() {
        JsonObject root = new JsonObject();
        JsonObject openai = new JsonObject();
        openai.addProperty("type", "simple");
        openai.addProperty("format", "openai");
        openai.addProperty("url", "https://api.openai.com/v1/chat/completions");
        openai.addProperty("key", "your-api-key-here");
        openai.addProperty("model", "gpt-4o");
        openai.addProperty("temperature", 0.7);
        openai.addProperty("max_tokens", 1000);
        root.add("openai", openai);
        return GSON.toJson(root);
    }

    private static String getDefaultRawConfig() {
        return GSON.toJson(new JsonObject());
    }
}
