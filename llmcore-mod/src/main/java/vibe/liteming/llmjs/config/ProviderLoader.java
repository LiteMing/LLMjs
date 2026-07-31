package vibe.liteming.llmjs.config;

import com.google.gson.*;
import vibe.liteming.llmcore.mod.LlmCoreMod;
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
 * 1. config/llmcore/providers.json        (global presets, no keys)
 * 2. serverconfig/llmcore/providers.json   (server override, no keys)
 * 3. llmcore.secret                      (keys only)
 */
public class ProviderLoader {
    public static final String SECRET_FILE_NAME = "llmcore.secret";
    public static final String LEGACY_SECRET_FILE_NAME = "llmjs.secret";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path gameRootDir;
    private static Path secretFilePath;

    /**
     * Resolve the canonical secret path. A lone legacy secret is renamed once;
     * it is never retained as a readable compatibility path.
     */
    public static synchronized Path resolveSecretFile(Path gameRoot) {
        Path current = gameRoot.resolve(SECRET_FILE_NAME);
        if (Files.exists(current)) return current;
        Path legacy = gameRoot.resolve(LEGACY_SECRET_FILE_NAME);
        if (!Files.exists(legacy)) return current;
        try {
            Files.move(legacy, current);
            LlmCoreMod.LOGGER.info("Migrated {} to {}", LEGACY_SECRET_FILE_NAME, SECRET_FILE_NAME);
            return current;
        } catch (IOException error) {
            throw new IllegalStateException("Failed to rename " + LEGACY_SECRET_FILE_NAME + " to "
                    + SECRET_FILE_NAME, error);
        }
    }

    public static Map<String, Provider> loadAll(Path serverConfigDir, Path gameRoot) {
        gameRootDir = gameRoot;
        Map<String, Provider> providers = new LinkedHashMap<>();
        Path secretFile = resolveSecretFile(gameRoot);
        secretFilePath = secretFile;
        Path serverRawFile = serverConfigDir.resolve("providers_raw.json");

        try {
            Files.createDirectories(serverConfigDir);
            if (!Files.exists(serverRawFile)) {
                Files.writeString(serverRawFile, GSON.toJson(new JsonObject()));
                LlmCoreMod.LOGGER.info("Created default providers_raw.json");
            }
            if (!Files.exists(secretFile)) {
                Files.writeString(secretFile, getDefaultSecret());
                LlmCoreMod.LOGGER.info("Created {} - fill in your API keys here", SECRET_FILE_NAME);
            }
        } catch (IOException e) {
            LlmCoreMod.LOGGER.error("Failed to create config files", e);
        }

        // Load secrets: the ONLY source for keys
        JsonObject secrets = loadSecrets(secretFile);

        // Layer 1: global providers (config/llmcore/providers.json)
        Path globalFile = GlobalConfig.getGlobalProvidersFile();
        if (globalFile != null && Files.exists(globalFile)) {
            loadSimpleProviders(globalFile, providers, secrets);
            LlmCoreMod.LOGGER.debug("Loaded global providers from config/llmcore/providers.json");
        }

        // Layer 2: server providers override global (same name = replace)
        Path serverFile = serverConfigDir.resolve("providers.json");
        if (Files.exists(serverFile)) {
            loadSimpleProviders(serverFile, providers, secrets);
            LlmCoreMod.LOGGER.debug("Loaded server provider overrides from serverconfig/llmcore/providers.json");
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
            LlmCoreMod.LOGGER.error("Failed to load secret file {}", file, e);
        }
        return new JsonObject();
    }

    /**
     * Resolve key from the selected server-side secret file only.
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
                    LlmCoreMod.LOGGER.info("Loaded custom provider '{}' from {}", entry.getKey(),
                            secretFilePath == null ? SECRET_FILE_NAME : secretFilePath.getFileName());
                }
            } catch (Exception e) {
                LlmCoreMod.LOGGER.warn("Failed to load secret provider '{}': {}", entry.getKey(), e.getMessage());
            }
        }
    }

    // === Write operations (all write to the selected secret file) ===

    public static boolean setKey(String providerName, String apiKey) {
        return secretFilePath != null && ProviderFiles.setKey(secretFilePath, providerName, apiKey);
    }

    public static boolean setup(String name, String url, String model, String key) {
        return setup(name, url, model, key, "openai");
    }

    public static boolean setup(String name, String url, String model, String key, String format) {
        if (gameRootDir == null) return false;
        String safeFormat = format == null || format.isBlank() ? "openai" : format;
        vibe.liteming.llmcore.ProviderSpec spec = new vibe.liteming.llmcore.ProviderSpec(name, safeFormat, url,
                model, null, null, java.util.List.of(new vibe.liteming.llmcore.ProviderSpec.Credential(name + "#1", key, 1)));
        return ProviderFiles.setup(gameRootDir.resolve("config/llmcore/providers.json"),
                secretFilePath, spec);
    }

    public static boolean updateWithoutKey(String name, String url, String model, String format) {
        return gameRootDir != null && ProviderFiles.updateProvider(
                gameRootDir.resolve("config/llmcore/providers.json"), name, url, model, format);
    }

    public static boolean deleteProvider(String name) {
        if (gameRootDir == null || name == null || name.isBlank()) return false;
        boolean deleted = ProviderFiles.deleteProvider(
                gameRootDir.resolve("config/llmcore/providers.json"),
                secretFilePath,
                name);
        // Also drop from server override if present
        deleted |= ProviderFiles.deleteProvider(
                gameRootDir.resolve("serverconfig/llmcore/providers.json"),
                null,
                name);
        return deleted;
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
                    // Key comes only from the selected server-side secret file.
                    String key = resolveKey(entry.getKey(), secrets);
                    Double temp = obj.has("temperature") ? obj.get("temperature").getAsDouble() : null;
                    Integer maxTokens = obj.has("max_tokens") ? obj.get("max_tokens").getAsInt() : null;
                    SimpleProvider provider = new SimpleProvider(entry.getKey(), format, url, key, model, temp, maxTokens);
                    if (provider.isValid()) providers.put(entry.getKey(), provider);
                } catch (Exception e) {
                    LlmCoreMod.LOGGER.warn("Failed to load provider '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LlmCoreMod.LOGGER.error("Failed to load {}", file, e);
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
                    LlmCoreMod.LOGGER.warn("Failed to load raw provider '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LlmCoreMod.LOGGER.error("Failed to load {}", file, e);
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
