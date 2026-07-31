package vibe.liteming.llmjs.config;

import com.google.gson.*;
import vibe.liteming.llmcore.mod.LlmCoreMod;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the global config directory: config/llmcore/
 * - providers.json: global provider presets (url + model, no keys)
 * - templates.json: test prompt templates
 *
 * This directory ships with modpacks. Server config can override providers.
 * Keys are NEVER stored here - they go in llmcore.secret only.
 */
public class GlobalConfig {
    public static final String CONFIG_DIRECTORY = "llmcore";
    public static final String LEGACY_CONFIG_DIRECTORY = "llmjs";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> GLOBAL_OWNED_FILES = List.of(
            "providers.json", "templates.json", "routing.json", "capability-policy.json");
    private static final List<String> SERVER_OWNED_FILES = List.of(
            "providers.json", "providers_raw.json");
    private static Path globalDir;

    public record Template(String name, String prompt) {}

    public static void init(Path gameRoot) {
        Path configRoot = gameRoot.resolve("config");
        globalDir = configRoot.resolve(CONFIG_DIRECTORY);
        migrateOwnedFiles(configRoot.resolve(LEGACY_CONFIG_DIRECTORY), globalDir, GLOBAL_OWNED_FILES);
        try {
            Files.createDirectories(globalDir);
            Path providersFile = globalDir.resolve("providers.json");
            Path templatesFile = globalDir.resolve("templates.json");

            if (!Files.exists(providersFile)) {
                Files.writeString(providersFile, getDefaultProviders());
                LlmCoreMod.LOGGER.info("Created global config/llmcore/providers.json");
            }
            if (!Files.exists(templatesFile)) {
                Files.writeString(templatesFile, getDefaultTemplates());
                LlmCoreMod.LOGGER.info("Created global config/llmcore/templates.json");
            }
        } catch (IOException e) {
            LlmCoreMod.LOGGER.error("Failed to create global config directory", e);
        }
    }

    public static Path getGlobalDir() { return globalDir; }

    public static Path getGlobalProvidersFile() {
        return globalDir != null ? globalDir.resolve("providers.json") : null;
    }

    public static Path resolveServerDirectory(Path serverConfigRoot) {
        Path canonical = serverConfigRoot.resolve(CONFIG_DIRECTORY);
        migrateOwnedFiles(serverConfigRoot.resolve(LEGACY_CONFIG_DIRECTORY), canonical, SERVER_OWNED_FILES);
        return canonical;
    }

    private static void migrateOwnedFiles(Path legacyDir, Path canonicalDir, List<String> fileNames) {
        for (String fileName : fileNames) {
            Path legacy = legacyDir.resolve(fileName);
            Path canonical = canonicalDir.resolve(fileName);
            if (!Files.exists(legacy) || Files.exists(canonical)) continue;
            try {
                Files.createDirectories(canonicalDir);
                Files.move(legacy, canonical);
                LlmCoreMod.LOGGER.info("Migrated llm-core config {} to {}", legacy, canonical);
            } catch (IOException error) {
                throw new IllegalStateException("Failed to migrate llm-core config " + legacy + " to " + canonical,
                        error);
            }
        }
    }

    // === Templates ===

    public static List<Template> loadTemplates() {
        List<Template> templates = new ArrayList<>();
        if (globalDir == null) return templates;
        Path file = globalDir.resolve("templates.json");
        try {
            if (Files.exists(file)) {
                String json = Files.readString(file);
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                for (var el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    String name = obj.get("name").getAsString();
                    String prompt = obj.get("prompt").getAsString();
                    templates.add(new Template(name, prompt));
                }
            }
        } catch (Exception e) {
            LlmCoreMod.LOGGER.error("Failed to load templates.json", e);
        }
        return templates;
    }

    public static void saveTemplates(List<Template> templates) {
        if (globalDir == null) return;
        Path file = globalDir.resolve("templates.json");
        try {
            JsonArray arr = new JsonArray();
            for (Template t : templates) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", t.name);
                obj.addProperty("prompt", t.prompt);
                arr.add(obj);
            }
            Files.writeString(file, GSON.toJson(arr));
        } catch (Exception e) {
            LlmCoreMod.LOGGER.error("Failed to save templates.json", e);
        }
    }

    // === Defaults ===

    private static String getDefaultProviders() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Global provider presets. Keys go in llmcore.secret, not here. Server config can override.");

        JsonObject openai = new JsonObject();
        openai.addProperty("type", "simple");
        openai.addProperty("format", "openai");
        openai.addProperty("url", "https://api.openai.com/v1/chat/completions");
        openai.addProperty("model", "gpt-4o");
        openai.addProperty("temperature", 0.7);
        openai.addProperty("max_tokens", 1000);
        root.add("openai", openai);

        return GSON.toJson(root);
    }

    private static String getDefaultTemplates() {
        JsonArray arr = new JsonArray();
        addTemplate(arr, "connection", "Say 'ok' to confirm connection.");
        addTemplate(arr, "simple", "Hello, this is a test.");
        addTemplate(arr, "json", "Return a JSON object with keys: name, age. Values can be anything.");
        addTemplate(arr, "long", "Write a short paragraph about Minecraft.");
        return GSON.toJson(arr);
    }

    private static void addTemplate(JsonArray arr, String name, String prompt) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("prompt", prompt);
        arr.add(obj);
    }
}
