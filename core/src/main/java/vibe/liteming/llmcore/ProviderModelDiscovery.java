// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Server-side model catalog lookup for the Console setup form. */
public final class ProviderModelDiscovery {
    public static final int MAX_MODELS = 512;
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record Result(List<String> models, String error) {
        public Result {
            models = models == null ? List.of() : List.copyOf(models);
            error = error == null ? "" : error;
        }
        public boolean success() { return error.isEmpty(); }
    }

    private ProviderModelDiscovery() { }

    public static CompletableFuture<Result> discover(String format, String chatUrl, String apiKey, int timeoutSeconds) {
        try {
            String normalized = format == null ? "openai" : format.trim().toLowerCase(java.util.Locale.ROOT);
            URI uri = modelUri(normalized, chatUrl);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                return CompletableFuture.completedFuture(new Result(List.of(), "Model API must use http or https"));
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(Math.max(1, Math.min(60, timeoutSeconds))))
                    .GET().header("Accept", "application/json");
            String key = apiKey == null ? "" : apiKey.trim();
            if ("claude".equals(normalized) || "anthropic".equals(normalized)) {
                request.header("x-api-key", key).header("anthropic-version", "2023-06-01");
            } else if ("gemini".equals(normalized)) {
                request.header("x-goog-api-key", key);
            } else {
                request.header("Authorization", "Bearer " + key);
            }
            return CLIENT.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> parse(normalized, response.statusCode(), response.body()))
                    .exceptionally(error -> new Result(List.of(), rootMessage(error)));
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(List.of(), invalid.getMessage()));
        }
    }

    static URI modelUri(String format, String chatUrl) {
        String value = chatUrl == null ? "" : chatUrl.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Endpoint URL is required");
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        if ("gemini".equals(format)) {
            int models = value.indexOf("/models/");
            if (models >= 0) value = value.substring(0, models + "/models".length());
            else if (!value.endsWith("/models")) value = stripLastPath(value) + "/models";
        } else if (value.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "/chat/completions".length()) + "/models";
        } else if (value.endsWith("/messages")) {
            value = value.substring(0, value.length() - "/messages".length()) + "/models";
        } else if (!value.endsWith("/models")) {
            value = stripLastPath(value) + "/models";
        }
        return URI.create(value);
    }

    static Result parse(String format, int status, byte[] bytes) {
        if (bytes == null) bytes = new byte[0];
        if (bytes.length > MAX_BODY_BYTES) return new Result(List.of(), "Model API response exceeds 1 MiB");
        String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) return new Result(List.of(), "Model API HTTP " + status);
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            LinkedHashSet<String> models = new LinkedHashSet<>();
            String arrayName = "gemini".equals(format) ? "models" : "data";
            if (root.has(arrayName) && root.get(arrayName).isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray(arrayName)) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();
                    String id = item.has("id") ? item.get("id").getAsString()
                            : item.has("name") ? item.get("name").getAsString() : "";
                    if (id.startsWith("models/")) id = id.substring("models/".length());
                    if (!id.isBlank()) models.add(id);
                    if (models.size() >= MAX_MODELS) break;
                }
            }
            return models.isEmpty() ? new Result(List.of(), "Model API returned no model IDs")
                    : new Result(new ArrayList<>(models), "");
        } catch (RuntimeException invalid) {
            return new Result(List.of(), "Invalid model API response: " + rootMessage(invalid));
        }
    }

    private static String stripLastPath(String value) {
        int scheme = value.indexOf("://");
        int slash = value.lastIndexOf('/');
        return slash > scheme + 2 ? value.substring(0, slash) : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
