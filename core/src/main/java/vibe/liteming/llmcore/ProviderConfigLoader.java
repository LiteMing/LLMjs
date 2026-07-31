package vibe.liteming.llmcore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ProviderConfigLoader {
    private ProviderConfigLoader() {
    }

    public static Map<String, ProviderSpec> load(Path globalProviders, Path serverProviders, Path secretFile) {
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        loadDefinitions(globalProviders, definitions);
        loadDefinitions(serverProviders, definitions);
        JsonObject secrets = loadObject(secretFile);
        if (secrets.has("providers") && secrets.get("providers").isJsonObject()) {
            secrets = secrets.getAsJsonObject("providers");
        }

        Map<String, ProviderSpec> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> entry : definitions.entrySet()) {
            JsonObject definition = entry.getValue();
            if (definition.has("enabled") && !definition.get("enabled").getAsBoolean()) {
                continue;
            }
            if (!"simple".equals(getString(definition, "type", "simple"))) {
                continue;
            }
            ProviderSpec spec = new ProviderSpec(
                    entry.getKey(),
                    getString(definition, "format", "openai"),
                    getString(definition, "url", ""),
                    getString(definition, "model", ""),
                    getDouble(definition, "temperature"),
                    getInteger(definition, "max_tokens"),
                    getContextWindow(definition),
                    readCredentials(entry.getKey(), secrets));
            if (spec.isValid()) {
                result.put(spec.name(), spec);
            }
        }

        for (Map.Entry<String, JsonElement> entry : secrets.entrySet()) {
            if (result.containsKey(entry.getKey()) || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject secretDefinition = entry.getValue().getAsJsonObject();
            if (!secretDefinition.has("url") || !secretDefinition.has("model")) {
                continue;
            }
            ProviderSpec spec = new ProviderSpec(
                    entry.getKey(),
                    getString(secretDefinition, "format", "openai"),
                    getString(secretDefinition, "url", ""),
                    getString(secretDefinition, "model", ""),
                    getDouble(secretDefinition, "temperature"),
                    getInteger(secretDefinition, "max_tokens"),
                    getContextWindow(secretDefinition),
                    readCredentials(entry.getKey(), secrets));
            if (spec.isValid()) {
                result.put(spec.name(), spec);
            }
        }
        return result;
    }

    /**
     * Load optional provider/model capability profiles without changing the
     * legacy {@link ProviderSpec} shape. Invalid optional metadata fails closed
     * to text-only and is reported to the supplied diagnostic sink.
     */
    public static Map<String, ProviderProfile> loadProfiles(Path globalProviders, Path serverProviders,
            Path secretFile, Consumer<String> diagnostics) {
        Consumer<String> sink = diagnostics == null ? ignored -> { } : diagnostics;
        Map<String, ProviderSpec> specs = load(globalProviders, serverProviders, secretFile);
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        loadDefinitions(globalProviders, definitions);
        loadDefinitions(serverProviders, definitions);
        JsonObject secrets = loadObject(secretFile);
        if (secrets.has("providers") && secrets.get("providers").isJsonObject()) {
            secrets = secrets.getAsJsonObject("providers");
        }

        Map<String, ProviderProfile> result = new LinkedHashMap<>();
        for (String provider : specs.keySet()) {
            JsonObject definition = definitions.get(provider);
            if (definition == null && secrets.has(provider) && secrets.get(provider).isJsonObject()) {
                definition = secrets.getAsJsonObject(provider);
            }
            result.put(provider, new ProviderProfile(provider, readCapabilities(provider, definition, sink)));
        }
        return result;
    }

    public static Map<String, ProviderProfile> loadProfiles(Path globalProviders, Path serverProviders,
            Path secretFile) {
        return loadProfiles(globalProviders, serverProviders, secretFile, null);
    }

    private static ProviderCapabilities readCapabilities(String provider, JsonObject definition,
            Consumer<String> diagnostics) {
        if (definition == null || !definition.has("capabilities")) {
            return ProviderCapabilities.textOnly();
        }
        try {
            JsonElement capabilitiesElement = definition.get("capabilities");
            if (!capabilitiesElement.isJsonObject()) {
                throw new IllegalArgumentException("capabilities must be an object");
            }
            JsonObject capabilities = capabilitiesElement.getAsJsonObject();
            rejectUnknownFields(capabilities, Set.of("input", "output", "webSearch"), "capabilities");
            Set<String> input = readStringSet(capabilities, "input", Set.of(ProviderCapabilities.INPUT_TEXT));
            Set<String> output = readStringSet(capabilities, "output", Set.of(ProviderCapabilities.OUTPUT_TEXT));
            ProviderCapabilities.HostedWebSearch webSearch = readWebSearch(capabilities);
            return new ProviderCapabilities(input, output, webSearch);
        } catch (RuntimeException error) {
            diagnostics.accept("Provider '" + provider + "' capability profile disabled: " + error.getMessage());
            return ProviderCapabilities.textOnly();
        }
    }

    private static ProviderCapabilities.HostedWebSearch readWebSearch(JsonObject capabilities) {
        if (!capabilities.has("webSearch")) return ProviderCapabilities.HostedWebSearch.disabled();
        JsonElement value = capabilities.get("webSearch");
        if (!value.isJsonObject()) throw new IllegalArgumentException("capabilities.webSearch must be an object");
        JsonObject search = value.getAsJsonObject();
        rejectUnknownFields(search, Set.of("enabled", "adapter"), "capabilities.webSearch");
        if (!search.has("enabled") || !search.get("enabled").isJsonPrimitive()
                || !search.getAsJsonPrimitive("enabled").isBoolean()) {
            throw new IllegalArgumentException("capabilities.webSearch.enabled must be a boolean");
        }
        if (!search.get("enabled").getAsBoolean()) {
            return ProviderCapabilities.HostedWebSearch.disabled();
        }
        if (!search.has("adapter") || !search.get("adapter").isJsonPrimitive()
                || !search.getAsJsonPrimitive("adapter").isString()) {
            throw new IllegalArgumentException("capabilities.webSearch.adapter must be a string");
        }
        String adapter = search.get("adapter").getAsString().trim();
        if (adapter.isEmpty()) {
            throw new IllegalArgumentException("capabilities.webSearch.adapter must not be blank");
        }
        return ProviderCapabilities.HostedWebSearch.using(adapter);
    }

    private static Set<String> readStringSet(JsonObject object, String field, Set<String> fallback) {
        if (!object.has(field)) return fallback;
        JsonElement value = object.get(field);
        if (!value.isJsonArray()) throw new IllegalArgumentException("capabilities." + field + " must be an array");
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || element.getAsString().isBlank()) {
                throw new IllegalArgumentException("capabilities." + field + " must contain non-blank strings");
            }
            result.add(element.getAsString());
        }
        if (result.isEmpty()) throw new IllegalArgumentException("capabilities." + field + " must not be empty");
        return result;
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String path) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(path + " contains unknown field '" + field + "'");
            }
        }
    }

    private static void loadDefinitions(Path file, Map<String, JsonObject> output) {
        JsonObject root = loadObject(file);
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!entry.getKey().startsWith("_") && entry.getValue().isJsonObject()) {
                output.put(entry.getKey(), entry.getValue().getAsJsonObject());
            }
        }
    }

    private static List<ProviderSpec.Credential> readCredentials(String provider, JsonObject secrets) {
        if (!secrets.has(provider)) {
            return List.of();
        }
        JsonElement value = secrets.get(provider);
        List<ProviderSpec.Credential> credentials = new ArrayList<>();
        if (value.isJsonPrimitive()) {
            credentials.add(new ProviderSpec.Credential(provider + "#1", value.getAsString(), 1));
            return credentials;
        }
        if (!value.isJsonObject()) {
            return List.of();
        }
        JsonObject object = value.getAsJsonObject();
        if (object.has("keys") && object.get("keys").isJsonArray()) {
            int index = 1;
            for (JsonElement keyValue : object.getAsJsonArray("keys")) {
                if (keyValue.isJsonPrimitive()) {
                    credentials.add(new ProviderSpec.Credential(provider + "#" + index++, keyValue.getAsString(), 1));
                } else if (keyValue.isJsonObject()) {
                    JsonObject keyObject = keyValue.getAsJsonObject();
                    credentials.add(new ProviderSpec.Credential(
                            getString(keyObject, "id", provider + "#" + index++),
                            getString(keyObject, "key", ""),
                            getInteger(keyObject, "weight") == null ? 1 : getInteger(keyObject, "weight")));
                }
            }
        } else if (object.has("key")) {
            credentials.add(new ProviderSpec.Credential(provider + "#1", object.get("key").getAsString(), 1));
        }
        return credentials;
    }

    private static JsonObject loadObject(Path file) {
        if (file == null || !Files.exists(file)) {
            return new JsonObject();
        }
        try {
            String content = Files.readString(file).trim();
            return content.isEmpty() ? new JsonObject() : JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static Double getDouble(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsDouble() : null;
    }

    private static Integer getInteger(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : null;
    }

    private static Integer getContextWindow(JsonObject object) {
        Integer value = getInteger(object, "context_window_tokens");
        return value != null ? value : getInteger(object, "context_window");
    }
}
