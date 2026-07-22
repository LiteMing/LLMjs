package vibe.liteming.llmcore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ProviderFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ProviderFiles() {
    }

    public static synchronized boolean setKey(Path secretFile, String providerName, String apiKey) {
        return updateSecret(secretFile, providerName, object -> object.addProperty("key", apiKey));
    }

    public static synchronized boolean setup(Path providersFile, Path secretFile, ProviderSpec spec) {
        if (spec == null || !spec.isValid()) return false;
        try {
            JsonObject providers = loadObject(providersFile);
            JsonObject definition = new JsonObject();
            definition.addProperty("type", "simple");
            definition.addProperty("format", spec.format());
            definition.addProperty("url", spec.url());
            definition.addProperty("model", spec.model());
            if (spec.temperature() != null) definition.addProperty("temperature", spec.temperature());
            if (spec.maxTokens() != null) definition.addProperty("max_tokens", spec.maxTokens());
            providers.add(spec.name(), definition);
            writeAtomic(providersFile, providers);

            JsonObject root = loadObject(secretFile);
            JsonObject secrets = root.has("providers") && root.get("providers").isJsonObject()
                    ? root.getAsJsonObject("providers") : new JsonObject();
            JsonObject providerSecret = new JsonObject();
            if (spec.credentials().size() <= 1) {
                String key = spec.credentials().isEmpty() ? "" : spec.credentials().get(0).key();
                providerSecret.addProperty("key", key);
            } else {
                var keys = new com.google.gson.JsonArray();
                for (ProviderSpec.Credential credential : spec.credentials()) {
                    JsonObject key = new JsonObject();
                    key.addProperty("id", credential.id());
                    key.addProperty("key", credential.key());
                    key.addProperty("weight", credential.weight());
                    keys.add(key);
                }
                providerSecret.add("keys", keys);
            }
            secrets.add(spec.name(), providerSecret);
            root.add("providers", secrets);
            writeAtomic(secretFile, root);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized boolean updateProvider(Path providersFile, String name, String url, String model,
            String format) {
        return updateProvider(providersFile, name, url, model, format, true);
    }

    public static synchronized boolean updateProvider(Path providersFile, String name, String url, String model,
            String format, boolean enabled) {
        try {
            JsonObject root = loadObject(providersFile);
            JsonObject provider = root.has(name) && root.get(name).isJsonObject()
                    ? root.getAsJsonObject(name) : new JsonObject();
            provider.addProperty("type", "simple");
            provider.addProperty("url", url);
            provider.addProperty("model", model);
            provider.addProperty("format", format == null || format.isBlank() ? "openai" : format);
            provider.addProperty("enabled", enabled);
            root.add(name, provider);
            writeAtomic(providersFile, root);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized boolean deleteProvider(Path providersFile, Path secretFile, String name) {
        if (name == null || name.isBlank()) return false;
        boolean changed = false;
        try {
            if (providersFile != null && Files.exists(providersFile)) {
                JsonObject root = loadObject(providersFile);
                if (root.remove(name) != null) {
                    writeAtomic(providersFile, root);
                    changed = true;
                }
            }
            if (secretFile != null && Files.exists(secretFile)) {
                JsonObject root = loadObject(secretFile);
                JsonObject providers = root.has("providers") && root.get("providers").isJsonObject()
                        ? root.getAsJsonObject("providers") : root;
                if (providers.remove(name) != null) {
                    if (root.has("providers")) root.add("providers", providers);
                    writeAtomic(secretFile, root);
                    changed = true;
                }
            }
            return changed;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean updateSecret(Path secretFile, String providerName,
            java.util.function.Consumer<JsonObject> updater) {
        try {
            JsonObject root = loadObject(secretFile);
            JsonObject providers = root.has("providers") && root.get("providers").isJsonObject()
                    ? root.getAsJsonObject("providers") : new JsonObject();
            JsonObject provider = providers.has(providerName) && providers.get(providerName).isJsonObject()
                    ? providers.getAsJsonObject(providerName) : new JsonObject();
            updater.accept(provider);
            providers.add(providerName, provider);
            root.add("providers", providers);
            writeAtomic(secretFile, root);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonObject loadObject(Path file) throws Exception {
        if (file == null || !Files.exists(file)) return new JsonObject();
        String content = Files.readString(file, StandardCharsets.UTF_8).trim();
        return content.isEmpty() ? new JsonObject() : JsonParser.parseString(content).getAsJsonObject();
    }

    private static void writeAtomic(Path file, JsonObject value) throws Exception {
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(value), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
