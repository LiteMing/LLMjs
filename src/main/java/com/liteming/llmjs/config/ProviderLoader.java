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
    private static Path gameRootDir;

    /**
     * Load all providers from config + secret file.
     * @param configDir serverconfig/llmjs/ directory (ships with modpack)
     * @param gameRoot  game root directory (where mods/ lives) - for llmjs.secret
     */
    public static Map<String, Provider> loadAll(Path configDir, Path gameRoot) {
        gameRootDir = gameRoot;
        Map<String, Provider> providers = new LinkedHashMap<>();
        Path simpleFile = configDir.resolve("providers.json");
        Path rawFile = configDir.resolve("providers_raw.json");
        Path secretFile = gameRoot.resolve("llmjs.secret");

        try {
            Files.createDirectories(configDir);
            if (!Files.exists(simpleFile)) {
                Files.writeString(simpleFile, getDefaultSimpleConfig());
                LLMjs.LOGGER.info("Created default providers.json with recommended presets");
            }
            if (!Files.exists(rawFile)) {
                Files.writeString(rawFile, getDefaultRawConfig());
                LLMjs.LOGGER.info("Created default providers_raw.json");
            }
            if (!Files.exists(secretFile)) {
                Files.writeString(secretFile, getDefaultSecret());
                LLMjs.LOGGER.info("Created llmjs.secret - fill in your API keys here");
            }
        } catch (IOException e) {
            LLMjs.LOGGER.error("Failed to create config directory/files", e);
        }

        // Load secret overrides (provider_name -> {key, url?, model?})
        JsonObject secrets = loadSecrets(secretFile);

        loadSimpleProviders(simpleFile, providers, secrets);
        loadRawProviders(rawFile, providers, secrets);

        // Load providers defined entirely in secret file (player custom entries)
        loadSecretProviders(secrets, providers);

        return providers;
    }

    private static JsonObject loadSecrets(Path file) {
        try {
            if (Files.exists(file)) {
                String content = Files.readString(file).strip();
                if (content.isEmpty()) return new JsonObject();
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                return root.has("providers") ? root.getAsJsonObject("providers") : root;
            }
        } catch (Exception e) {
            LLMjs.LOGGER.error("Failed to load llmjs.secret", e);
        }
        return new JsonObject();
    }

    /**
     * Providers defined only in secret file (not in providers.json).
     * These are fully player-defined custom providers.
     */
    private static void loadSecretProviders(JsonObject secrets, Map<String, Provider> providers) {
        for (Map.Entry<String, JsonElement> entry : secrets.entrySet()) {
            if (providers.containsKey(entry.getKey())) continue; // already loaded from config
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject obj = entry.getValue().getAsJsonObject();
            // Must have at least url and key to be a custom provider
            if (!obj.has("url") || !obj.has("key")) continue;
            try {
                String format = obj.has("format") ? obj.get("format").getAsString() : "openai";
                String url = obj.get("url").getAsString();
                String key = obj.get("key").getAsString();
                String model = obj.has("model") ? obj.get("model").getAsString() : "";
                Double temp = obj.has("temperature") ? obj.get("temperature").getAsDouble() : null;
                Integer maxTokens = obj.has("max_tokens") ? obj.get("max_tokens").getAsInt() : null;
                SimpleProvider provider = new SimpleProvider(entry.getKey(), format, url, key, model, temp, maxTokens);
                if (provider.isValid()) {
                    providers.put(entry.getKey(), provider);
                    LLMjs.LOGGER.info("Loaded custom provider '{}' from llmjs.secret", entry.getKey());
                }
            } catch (Exception e) {
                LLMjs.LOGGER.warn("Failed to load secret provider '{}': {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Resolve a provider's key from secret file, falling back to config value.
     */
    private static String resolveKey(String name, String keyInConfig, JsonObject secrets) {
        if (secrets.has(name)) {
            JsonElement el = secrets.get(name);
            if (el.isJsonPrimitive()) {
                // Simple form: { "openai": "sk-xxx" }
                String k = el.getAsString();
                if (!k.isEmpty()) return k;
            } else if (el.isJsonObject()) {
                // Object form: { "openai": { "key": "sk-xxx" } }
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("key")) {
                    String k = obj.get("key").getAsString();
                    if (!k.isEmpty()) return k;
                }
            }
        }
        // Fall back to config value, skip placeholders
        if (keyInConfig != null && !keyInConfig.isEmpty() && !isPlaceholder(keyInConfig)) {
            return keyInConfig;
        }
        return "";
    }

    private static boolean isPlaceholder(String key) {
        String lower = key.toLowerCase();
        return lower.contains("your-") || lower.contains("your_")
                || lower.contains("-here") || lower.contains("_here")
                || lower.equals("xxx") || lower.equals("sk-xxx");
    }

    // === setkey / setup commands write to llmjs.secret ===

    /**
     * Set only the API key for an existing provider slot.
     */
    public static boolean setKey(String providerName, String apiKey) {
        return updateSecret(providerName, secret -> {
            if (secret.isJsonObject()) {
                secret.getAsJsonObject().addProperty("key", apiKey);
            }
        }, () -> {
            // Create minimal entry with just the key
            return new JsonPrimitive(apiKey);
        });
    }

    /**
     * Create or overwrite a full provider entry in the secret file.
     */
    public static boolean setup(String name, String url, String model, String key) {
        return setup(name, url, model, key, "openai");
    }

    public static boolean setup(String name, String url, String model, String key, String format) {
        return updateSecret(name, secret -> {
            JsonObject obj = secret.isJsonObject() ? secret.getAsJsonObject() : new JsonObject();
            obj.addProperty("url", url);
            obj.addProperty("model", model);
            obj.addProperty("key", key);
            if (format != null && !format.isEmpty()) obj.addProperty("format", format);
        }, () -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("url", url);
            obj.addProperty("model", model);
            obj.addProperty("key", key);
            if (format != null && !format.isEmpty()) obj.addProperty("format", format);
            return obj;
        });
    }

    private static boolean updateSecret(String name,
                                         java.util.function.Consumer<JsonElement> updater,
                                         java.util.function.Supplier<JsonElement> creator) {
        if (gameRootDir == null) return false;
        Path secretFile = gameRootDir.resolve("llmjs.secret");
        try {
            JsonObject root;
            if (Files.exists(secretFile)) {
                String content = Files.readString(secretFile).strip();
                root = content.isEmpty() ? new JsonObject() : JsonParser.parseString(content).getAsJsonObject();
            } else {
                root = new JsonObject();
            }

            if (!root.has("providers")) {
                root.add("providers", new JsonObject());
            }
            JsonObject providers = root.getAsJsonObject("providers");

            if (providers.has(name)) {
                updater.accept(providers.get(name));
            } else {
                providers.add(name, creator.get());
            }

            Files.writeString(secretFile, GSON.toJson(root));
            return true;
        } catch (Exception e) {
            LLMjs.LOGGER.error("Failed to write llmjs.secret for '{}'", name, e);
            return false;
        }
    }

    // === Config loading ===

    private static void loadSimpleProviders(Path file, Map<String, Provider> providers,
                                             JsonObject secrets) {
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
                    String keyInFile = obj.has("key") ? obj.get("key").getAsString() : "";
                    String key = resolveKey(entry.getKey(), keyInFile, secrets);
                    String model = obj.get("model").getAsString();
                    Double temp = obj.has("temperature") ? obj.get("temperature").getAsDouble() : null;
                    Integer maxTokens = obj.has("max_tokens") ? obj.get("max_tokens").getAsInt() : null;
                    SimpleProvider provider = new SimpleProvider(entry.getKey(), format, url, key, model, temp, maxTokens);
                    // Load even without key (shown as unconfigured preset)
                    if (provider.isValid()) providers.put(entry.getKey(), provider);
                } catch (Exception e) {
                    LLMjs.LOGGER.warn("Failed to load provider '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LLMjs.LOGGER.error("Failed to load providers.json", e);
        }
    }

    private static void loadRawProviders(Path file, Map<String, Provider> providers,
                                          JsonObject secrets) {
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    String url = obj.get("url").getAsString();
                    String method = obj.has("method") ? obj.get("method").getAsString() : "POST";
                    String responsePath = obj.get("response_path").getAsString();
                    String keyInFile = obj.has("key") ? obj.get("key").getAsString() : "";
                    String key = resolveKey(entry.getKey(), keyInFile, secrets);
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

    // === Default configs ===

    private static String getDefaultSimpleConfig() {
        JsonObject root = new JsonObject();
        // Recommended presets - modpack authors can add more here
        JsonObject openai = new JsonObject();
        openai.addProperty("type", "simple");
        openai.addProperty("format", "openai");
        openai.addProperty("url", "https://api.openai.com/v1/chat/completions");
        openai.addProperty("key", "");
        openai.addProperty("model", "gpt-4o");
        openai.addProperty("temperature", 0.7);
        openai.addProperty("max_tokens", 1000);
        root.add("openai", openai);
        return GSON.toJson(root);
    }

    private static String getDefaultRawConfig() {
        return GSON.toJson(new JsonObject());
    }

    private static String getDefaultSecret() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Fill in your API keys below. This file is NOT distributed with modpacks.");
        JsonObject providers = new JsonObject();
        providers.addProperty("openai", "");
        root.add("providers", providers);
        return GSON.toJson(root);
    }
}
