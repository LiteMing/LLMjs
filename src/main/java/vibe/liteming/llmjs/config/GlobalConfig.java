package vibe.liteming.llmjs.config;

import com.google.gson.*;
import vibe.liteming.llmjs.LLMjs;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the global config directory: config/llmjs/
 * - providers.json: global provider presets (url + model, no keys)
 * - templates.json: test prompt templates
 *
 * This directory ships with modpacks. Server config can override providers.
 * Keys are NEVER stored here - they go in llmjs.secret only.
 */
public class GlobalConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path globalDir;

    public record Template(String name, String prompt) {}

    public static void init(Path gameRoot) {
        globalDir = gameRoot.resolve("config").resolve("llmjs");
        try {
            Files.createDirectories(globalDir);
            Path providersFile = globalDir.resolve("providers.json");
            Path templatesFile = globalDir.resolve("templates.json");

            if (!Files.exists(providersFile)) {
                Files.writeString(providersFile, getDefaultProviders());
                LLMjs.LOGGER.info("Created global config/llmjs/providers.json");
            }
            if (!Files.exists(templatesFile)) {
                Files.writeString(templatesFile, getDefaultTemplates());
                LLMjs.LOGGER.info("Created global config/llmjs/templates.json");
            }
        } catch (IOException e) {
            LLMjs.LOGGER.error("Failed to create global config directory", e);
        }
    }

    public static Path getGlobalDir() { return globalDir; }

    public static Path getGlobalProvidersFile() {
        return globalDir != null ? globalDir.resolve("providers.json") : null;
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
            LLMjs.LOGGER.error("Failed to load templates.json", e);
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
            LLMjs.LOGGER.error("Failed to save templates.json", e);
        }
    }

    // === Defaults ===

    private static String getDefaultProviders() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Global provider presets. Keys go in llmjs.secret, not here. Server config can override.");

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
