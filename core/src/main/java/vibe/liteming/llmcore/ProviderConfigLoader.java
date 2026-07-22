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
                    readCredentials(entry.getKey(), secrets));
            if (spec.isValid()) {
                result.put(spec.name(), spec);
            }
        }
        return result;
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
}
