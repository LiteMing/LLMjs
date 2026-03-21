package com.liteming.llmjs.provider;

import com.google.gson.*;
import com.liteming.llmjs.format.ApiFormat;
import com.liteming.llmjs.http.HttpService;
import com.liteming.llmjs.pipeline.LLMResponse;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RawProvider implements Provider {
    private final String name;
    private final String url;
    private final String method;
    private final Map<String, String> headerTemplates;
    private final JsonObject bodyTemplate;
    private final String responsePath;
    private final String key;
    private final String model;

    public RawProvider(String name, String url, String method,
                       Map<String, String> headerTemplates, JsonObject bodyTemplate,
                       String responsePath, String key, String model) {
        this.name = name;
        this.url = url;
        this.method = method;
        this.headerTemplates = headerTemplates;
        this.bodyTemplate = bodyTemplate;
        this.responsePath = responsePath;
        this.key = key;
        this.model = model;
    }

    @Override public String getName() { return name; }
    @Override public String getType() { return "raw"; }
    @Override public @Nullable String getFormat() { return null; }
    @Override public String getModel() { return model; }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(List<ApiFormat.Message> messages,
                                                      @Nullable Double temperature,
                                                      @Nullable Integer maxTokens,
                                                      int timeoutSeconds) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("key", key);
        vars.put("model", model);
        vars.put("temperature", temperature != null ? temperature.toString() : "0.7");
        vars.put("max_tokens", maxTokens != null ? maxTokens.toString() : "1000");

        JsonArray msgArray = new JsonArray();
        String systemPrompt = "";
        for (ApiFormat.Message msg : messages) {
            if ("system".equals(msg.role())) systemPrompt = msg.content();
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.role());
            m.addProperty("content", msg.content());
            msgArray.add(m);
        }
        vars.put("messages", msgArray.toString());
        vars.put("system", systemPrompt);

        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headerTemplates.entrySet()) {
            headers.put(entry.getKey(), substitute(entry.getValue(), vars));
        }

        String body = substituteJson(bodyTemplate.deepCopy(), vars).toString();
        String resolvedUrl = substitute(url, vars);

        return HttpService.INSTANCE.postAsync(resolvedUrl, body, headers, timeoutSeconds)
                .thenApply(result -> {
                    if (!result.isSuccess()) {
                        return LLMResponse.error("HTTP " + result.statusCode() + ": " + result.body());
                    }
                    return parseByPath(result.body(), result.latencyMs());
                });
    }

    private LLMResponse parseByPath(String responseBody, long latencyMs) {
        try {
            JsonElement current = JsonParser.parseString(responseBody);
            String[] segments = responsePath.split("\\.");
            for (String seg : segments) {
                Pattern arrayPattern = Pattern.compile("(.+)\\[(\\d+)]");
                Matcher matcher = arrayPattern.matcher(seg);
                if (matcher.matches()) {
                    current = current.getAsJsonObject().get(matcher.group(1));
                    current = current.getAsJsonArray().get(Integer.parseInt(matcher.group(2)));
                } else {
                    current = current.getAsJsonObject().get(seg);
                }
                if (current == null) {
                    return LLMResponse.error("Response path '" + responsePath + "' not found at '" + seg + "'");
                }
            }
            return LLMResponse.success(current.getAsString(), model, name, 0, 0, latencyMs);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse response at path '" + responsePath + "': " + e.getMessage());
        }
    }

    @Override
    public CompletableFuture<LLMResponse> testConnection(int timeoutSeconds) {
        return sendAsync(List.of(new ApiFormat.Message("user", "Say 'ok' to confirm connection.")), 0.1, 10, timeoutSeconds);
    }

    @Override
    public boolean isValid() {
        return url != null && !url.isEmpty() && responsePath != null && !responsePath.isEmpty();
    }

    @Override
    public String getMaskedKey() {
        if (key == null || key.length() < 4) return "***";
        return key.substring(0, 4) + "***";
    }

    private static String substitute(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static JsonElement substituteJson(JsonElement element, Map<String, String> vars) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String val = element.getAsString();
            if (val.matches("^\\$\\{\\w+}$")) {
                String varName = val.substring(2, val.length() - 1);
                String replacement = vars.getOrDefault(varName, val);
                try { return JsonParser.parseString(replacement); }
                catch (Exception e) { return new JsonPrimitive(replacement); }
            }
            return new JsonPrimitive(substitute(val, vars));
        } else if (element.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                result.add(entry.getKey(), substituteJson(entry.getValue(), vars));
            }
            return result;
        } else if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                result.add(substituteJson(item, vars));
            }
            return result;
        }
        return element;
    }
}
