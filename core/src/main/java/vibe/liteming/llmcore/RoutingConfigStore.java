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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Atomic load/save of {@link PriorityRoutingConfig} to {@code config/llmjs/routing.json}.
 * Schema (JSON):
 * <pre>
 * {
 *   "schemaVersion": 2,
 *   "default": { "providers": ["dialogue_primary", "fallback_stable"] },
 *   "purposes": {
 *     "MEMORY_SUMMARY": {
 *       "providers": ["summary_cheap", "dialogue_primary"],
 *       "temperature": 0.2,
 *       "maxOutputTokens": 500,
 *       "timeoutSeconds": 60,
 *       "inputBudgetTokens": 12000,
 *       "outputReserveTokens": 1000
 *     }
 *   }
 * }
 * </pre>
 * Legacy array-valued default/purpose routes remain readable. {@link #parse(String)}
 * is strict so UI/network callers can report invalid or out-of-range fields.
 */
public final class RoutingConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA_VERSION = 2;

    private RoutingConfigStore() {
    }

    public static PriorityRoutingConfig load(Path routingFile) {
        if (routingFile == null || !Files.exists(routingFile)) return PriorityRoutingConfig.empty();
        try {
            String content = Files.readString(routingFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return PriorityRoutingConfig.empty();
            return parse(content);
        } catch (Exception e) {
            return PriorityRoutingConfig.empty();
        }
    }

    /** Parse a complete routing snapshot or throw a user-facing validation error. */
    public static PriorityRoutingConfig parse(String json) {
        if (json == null || json.isBlank()) return PriorityRoutingConfig.empty();
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("routing JSON is malformed: " + rootMessage(e), e);
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("routing root must be an object");
        JsonObject root = parsed.getAsJsonObject();
        if (root.has("schemaVersion")) {
            int version = readInteger(root, "schemaVersion", 1, SCHEMA_VERSION);
            if (version != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported routing schemaVersion: " + version);
            }
        }

        List<String> defaultChain = root.has("default")
                ? readRouteChain(root.get("default"), "default") : List.of();
        Map<String, List<String>> purposeChains = new LinkedHashMap<>();
        Map<String, LlmRouteOptions> purposeOptions = new LinkedHashMap<>();
        if (root.has("purposes")) {
            if (!root.get("purposes").isJsonObject()) {
                throw new IllegalArgumentException("purposes must be an object");
            }
            for (var entry : root.getAsJsonObject("purposes").entrySet()) {
                String purpose = entry.getKey() == null ? "" : entry.getKey().trim();
                if (purpose.isEmpty()) throw new IllegalArgumentException("purpose id must not be blank");
                JsonElement value = entry.getValue();
                List<String> chain = readRouteChain(value, "purposes." + purpose);
                if (!chain.isEmpty()) purposeChains.put(purpose, chain);
                if (value.isJsonObject()) {
                    LlmRouteOptions options = readOptions(value.getAsJsonObject(), "purposes." + purpose);
                    if (!options.isEmpty()) purposeOptions.put(purpose, options);
                }
            }
        }
        return new PriorityRoutingConfig(purposeChains, defaultChain, purposeOptions);
    }

    public static synchronized boolean save(Path routingFile, PriorityRoutingConfig config) {
        if (routingFile == null) return false;
        PriorityRoutingConfig cfg = config == null ? PriorityRoutingConfig.empty() : config;
        try {
            JsonObject root = toJson(cfg);
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
        return GSON.toJson(toJson(config));
    }

    public static String fingerprint(PriorityRoutingConfig config) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(toJsonString(config).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static JsonObject toJson(PriorityRoutingConfig config) {
        PriorityRoutingConfig cfg = config == null ? PriorityRoutingConfig.empty() : config;
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject defaultRoute = new JsonObject();
        defaultRoute.add("providers", toArray(cfg.defaultChain()));
        root.add("default", defaultRoute);
        JsonObject purposes = new JsonObject();
        Set<String> purposeIds = new LinkedHashSet<>(cfg.purposeChains().keySet());
        purposeIds.addAll(cfg.purposeOptions().keySet());
        for (String purpose : purposeIds) {
            JsonObject route = new JsonObject();
            route.add("providers", toArray(cfg.purposeChains().getOrDefault(purpose, List.of())));
            writeOptions(route, cfg.purposeOptions().get(purpose));
            purposes.add(purpose, route);
        }
        root.add("purposes", purposes);
        return root;
    }

    private static List<String> readRouteChain(JsonElement route, String path) {
        if (route == null || route.isJsonNull()) return List.of();
        if (route.isJsonArray()) return readStrings(route.getAsJsonArray(), path);
        if (route.isJsonObject()) {
            JsonObject object = route.getAsJsonObject();
            if (!object.has("providers")) return List.of();
            if (!object.get("providers").isJsonArray()) {
                throw new IllegalArgumentException(path + ".providers must be an array");
            }
            return readStrings(object.getAsJsonArray("providers"), path + ".providers");
        }
        throw new IllegalArgumentException(path + " must be an array or object");
    }

    private static List<String> readStrings(JsonArray arr, String path) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < arr.size(); index++) {
            JsonElement el = arr.get(index);
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(path + "[" + index + "] must be a provider name");
            }
            String provider = el.getAsString().trim();
            if (provider.isEmpty()) throw new IllegalArgumentException(path + "[" + index + "] is blank");
            if (!seen.add(provider)) throw new IllegalArgumentException(path + " contains duplicate " + provider);
            out.add(provider);
        }
        return out;
    }

    private static LlmRouteOptions readOptions(JsonObject route, String path) {
        JsonObject options = route.has("parameters") ? requireObject(route, "parameters", path) : route;
        try {
            return new LlmRouteOptions(
                    readDouble(options, "temperature", path),
                    readNullableInteger(options, "maxOutputTokens", path),
                    readNullableInteger(options, "timeoutSeconds", path),
                    readNullableInteger(options, "inputBudgetTokens", path),
                    readNullableInteger(options, "outputReserveTokens", path));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    private static JsonObject requireObject(JsonObject parent, String key, String path) {
        if (!parent.get(key).isJsonObject()) {
            throw new IllegalArgumentException(path + "." + key + " must be an object");
        }
        return parent.getAsJsonObject(key);
    }

    private static Double readDouble(JsonObject object, String key, String path) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + "." + key + " must be a number or null");
        }
        return value.getAsDouble();
    }

    private static Integer readNullableInteger(JsonObject object, String key, String path) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + "." + key + " must be an integer or null");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE
                || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + "." + key + " must be an integer or null");
        }
        return (int) number;
    }

    private static int readInteger(JsonObject object, String key, int min, int max) {
        Integer value = readNullableInteger(object, key, "routing");
        if (value == null || value < min || value > max) {
            throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        }
        return value;
    }

    private static void writeOptions(JsonObject target, LlmRouteOptions options) {
        if (options == null) return;
        if (options.temperature() != null) target.addProperty("temperature", options.temperature());
        if (options.maxOutputTokens() != null) target.addProperty("maxOutputTokens", options.maxOutputTokens());
        if (options.timeoutSeconds() != null) target.addProperty("timeoutSeconds", options.timeoutSeconds());
        if (options.inputBudgetTokens() != null) target.addProperty("inputBudgetTokens", options.inputBudgetTokens());
        if (options.outputReserveTokens() != null) target.addProperty("outputReserveTokens", options.outputReserveTokens());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static JsonArray toArray(List<String> list) {
        JsonArray arr = new JsonArray();
        if (list != null) for (String s : list) arr.add(s);
        return arr;
    }
}
