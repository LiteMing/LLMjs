package vibe.liteming.llmjs.config;

import com.google.gson.*;
import vibe.liteming.llmjs.LLMjs;
import vibe.liteming.llmjs.provider.Provider;
import vibe.liteming.llmjs.provider.RawProvider;
import vibe.liteming.llmjs.provider.SimpleProvider;
import vibe.liteming.llmcore.ProviderFiles;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider loading with 3-layer merge:
 * 1. config/llmjs/providers.json        (global presets, no keys)
 * 2. serverconfig/llmjs/providers.json   (server override, no keys)
 * 3. llmjs.secret                        (keys only)
 */
public class ProviderLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path gameRootDir;

    public static Map<String, Provider> loadAll(Path serverConfigDir, Path gameRoot) {
        gameRootDir = gameRoot;
        Map<String, Provider> providers = new LinkedHashMap<>();
        Path secretFile = gameRoot.resolve("llmjs.secret");
        Path serverRawFile = serverConfigDir.resolve("providers_raw.json");

        try {
            Files.createDirectories(serverConfigDir);
            if (!Files.exists(serverRawFile)) {
                Files.writeString(serverRawFile, GSON.toJson(new JsonObject()));
                LLMjs.LOGGER.info("Created default providers_raw.json");
            }
            if (!Files.exists(secretFile)) {
                Files.writeString(secretFile, getDefaultSecret());
                LLMjs.LOGGER.info("Created llmjs.secret - fill in your API keys here");
            }
        } catch (IOException e) {
            LLMjs.LOGGER.error("Failed to create config files", e);
        }

        // Load secrets: the ONLY source for keys
        JsonObject secrets = loadSecrets(secretFile);

        // Layer 1: global providers (config/llmjs/providers.json)
        Path globalFile = GlobalConfig.getGlobalProvidersFile();
        if (globalFile != null && Files.exists(globalFile)) {
            loadSimpleProviders(globalFile, providers, secrets);
            LLMjs.LOGGER.debug("Loaded global providers from config/llmjs/providers.json");
        }

        // Layer 2: server providers override global (same name = replace)
        Path serverFile = serverConfigDir.resolve("providers.json");
        if (Files.exists(serverFile)) {
            loadSimpleProviders(serverFile, providers, secrets);
            LLMjs.LOGGER.debug("Loaded server provider overrides from serverconfig/llmjs/providers.json");
        }

        // RAW providers (server-level only, advanced usage)
        loadRawProviders(serverRawFile, providers, secrets);

        // Layer 3: providers defined entirely in secret file (player custom)
        loadSecretProviders(secrets, providers);

        return providers;
    }

    // === Secret file loading ===

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
     * Resolve key from llmjs.secret only.
     */
    private static String resolveKey(String providerName, JsonObject secrets) {
        if (secrets.has(providerName)) {
            JsonElement el = secrets.get(providerName);
            if (el.isJsonPrimitive()) {
                // Simple: { "openai": "sk-xxx" }
                String k = el.getAsString();
                if (!k.isEmpty()) return k;
            } else if (el.isJsonObject()) {
                // Object: { "openai": { "key": "sk-xxx", ... } }
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("key")) {
                    String k = obj.get("key").getAsString();
                    if (!k.isEmpty()) return k;
                }
            }
        }
        return "";
    }

    /**
     * Providers defined entirely in secret file (not in any providers.json).
     */
    private static void loadSecretProviders(JsonObject secrets, Map<String, Provider> providers) {
        for (Map.Entry<String, JsonElement> entry : secrets.entrySet()) {
            if (providers.containsKey(entry.getKey())) continue;
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject obj = entry.getValue().getAsJsonObject();
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

    // === Write operations (all write to llmjs.secret) ===

    public static boolean setKey(String providerName, String apiKey) {
        return gameRootDir != null && ProviderFiles.setKey(gameRootDir.resolve("llmjs.secret"), providerName, apiKey);
    }

    public static boolean setup(String name, String url, String model, String key) {
        return setup(name, url, model, key, "openai");
    }

    public static boolean setup(String name, String url, String model, String key, String format) {
        if (gameRootDir == null) return false;
        String safeFormat = format == null || format.isBlank() ? "openai" : format;
        vibe.liteming.llmcore.ProviderSpec spec = new vibe.liteming.llmcore.ProviderSpec(name, safeFormat, url,
                model, null, null, java.util.List.of(new vibe.liteming.llmcore.ProviderSpec.Credential(name + "#1", key, 1)));
        return ProviderFiles.setup(gameRootDir.resolve("config/llmjs/providers.json"),
                gameRootDir.resolve("llmjs.secret"), spec);
    }

    public static boolean updateWithoutKey(String name, String url, String model, String format) {
        return gameRootDir != null && ProviderFiles.updateProvider(
                gameRootDir.resolve("config/llmjs/providers.json"), name, url, model, format);
    }

    public static boolean deleteProvider(String name) {
        if (gameRootDir == null || name == null || name.isBlank()) return false;
        boolean deleted = ProviderFiles.deleteProvider(
                gameRootDir.resolve("config/llmjs/providers.json"),
                gameRootDir.resolve("llmjs.secret"),
                name);
        // Also drop from server override if present
        deleted |= ProviderFiles.deleteProvider(
                gameRootDir.resolve("serverconfig/llmjs/providers.json"),
                null,
                name);
        return deleted;
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

    // === Config file loading ===

    private static void loadSimpleProviders(Path file, Map<String, Provider> providers,
                                             JsonObject secrets) {
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getKey().startsWith("_")) continue; // skip _comment etc.
                try {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    String type = obj.has("type") ? obj.get("type").getAsString() : "simple";
                    if (!"simple".equals(type)) continue;
                    String format = obj.has("format") ? obj.get("format").getAsString() : "openai";
                    String url = obj.get("url").getAsString();
                    String model = obj.get("model").getAsString();
                    // Key comes ONLY from llmjs.secret
                    String key = resolveKey(entry.getKey(), secrets);
                    Double temp = obj.has("temperature") ? obj.get("temperature").getAsDouble() : null;
                    Integer maxTokens = obj.has("max_tokens") ? obj.get("max_tokens").getAsInt() : null;
                    SimpleProvider provider = new SimpleProvider(entry.getKey(), format, url, key, model, temp, maxTokens);
                    if (provider.isValid()) providers.put(entry.getKey(), provider);
                } catch (Exception e) {
                    LLMjs.LOGGER.warn("Failed to load provider '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LLMjs.LOGGER.error("Failed to load {}", file, e);
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
                    String key = resolveKey(entry.getKey(), secrets);
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
            LLMjs.LOGGER.error("Failed to load {}", file, e);
        }
    }

    // === Defaults ===

    private static String getDefaultSecret() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Your API keys. This file is NOT distributed with modpacks.");
        JsonObject providers = new JsonObject();
        providers.addProperty("openai", "");
        root.add("providers", providers);
        return GSON.toJson(root);
    }
}
