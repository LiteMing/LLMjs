package vibe.liteming.llmcore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Atomic load/save of {@link PriorityRoutingConfig} to {@code config/llmjs/routing.json}.
 * Schema (JSON):
 * <pre>
 * {
 *   "default": ["dialogue_primary", "fallback_stable"],
 *   "purposes": {
 *     "MEMORY_SUMMARY": ["summary_cheap", "dialogue_primary"],
 *     "CHARACTER_GENERATION": ["character_curated", "dialogue_primary"]
 *   }
 * }
 * </pre>
 * Missing file -> empty config. Malformed JSON -> empty config (logged by caller).
 */
public final class RoutingConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RoutingConfigStore() {
    }

    public static PriorityRoutingConfig load(Path routingFile) {
        if (routingFile == null || !Files.exists(routingFile)) return PriorityRoutingConfig.empty();
        try {
            String content = Files.readString(routingFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return PriorityRoutingConfig.empty();
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            List<String> defaultChain = readChain(root, "default");
            Map<String, List<String>> purposes = new LinkedHashMap<>();
            if (root.has("purposes") && root.get("purposes").isJsonObject()) {
                JsonObject obj = root.getAsJsonObject("purposes");
                for (var entry : obj.entrySet()) {
                    if (entry.getValue().isJsonArray()) {
                        purposes.put(entry.getKey(), readStrings(entry.getValue().getAsJsonArray()));
                    }
                }
            }
            return new PriorityRoutingConfig(purposes, defaultChain);
        } catch (Exception e) {
            return PriorityRoutingConfig.empty();
        }
    }

    public static synchronized boolean save(Path routingFile, PriorityRoutingConfig config) {
        if (routingFile == null) return false;
        PriorityRoutingConfig cfg = config == null ? PriorityRoutingConfig.empty() : config;
        try {
            JsonObject root = new JsonObject();
            if (!cfg.defaultChain().isEmpty()) {
                root.add("default", toArray(cfg.defaultChain()));
            }
            if (!cfg.purposeChains().isEmpty()) {
                JsonObject purposes = new JsonObject();
                for (var entry : cfg.purposeChains().entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        purposes.add(entry.getKey(), toArray(entry.getValue()));
                    }
                }
                root.add("purposes", purposes);
            }
            Files.createDirectories(routingFile.toAbsolutePath().normalize().getParent());
            Path temp = routingFile.resolveSibling(routingFile.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, routingFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, routingFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String toJsonString(PriorityRoutingConfig config) {
        if (config == null) return "{}";
        JsonObject root = new JsonObject();
        root.add("default", toArray(config.defaultChain()));
        JsonObject purposes = new JsonObject();
        for (var entry : config.purposeChains().entrySet()) {
            purposes.add(entry.getKey(), toArray(entry.getValue()));
        }
        root.add("purposes", purposes);
        return GSON.toJson(root);
    }

    private static List<String> readChain(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonArray()) {
            return readStrings(root.getAsJsonArray(key));
        }
        return List.of();
    }

    private static List<String> readStrings(JsonArray arr) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) out.add(el.getAsString());
        }
        return out;
    }

    private static JsonArray toArray(List<String> list) {
        JsonArray arr = new JsonArray();
        if (list != null) for (String s : list) arr.add(s);
        return arr;
    }
}
