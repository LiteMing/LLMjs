package com.liteming.llmjs.json;

import com.google.gson.*;
import com.liteming.llmjs.config.LLMConfig;
import com.liteming.llmjs.format.ApiFormat;
import com.liteming.llmjs.provider.ProviderManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class SchemaMode {

    public static void chatWithSchema(String prompt, JsonObject schema,
                                       @Nullable String provider, @Nullable Double temperature,
                                       @Nullable Integer maxTokens, Consumer<Object> callback) {
        String schemaDesc = buildSchemaDescription(schema);
        String fullPrompt = prompt + "\n\nRespond with a JSON object matching this schema:\n" + schemaDesc
                + "\n\nRespond with ONLY the JSON object. No markdown, no explanation.";

        List<ApiFormat.Message> messages = new ArrayList<>();
        messages.add(new ApiFormat.Message("system", "You generate JSON matching exact schemas. Respond with valid JSON only."));
        messages.add(new ApiFormat.Message("user", fullPrompt));

        String providerName = provider != null ? provider : LLMConfig.DEFAULT_PROVIDER.get();
        int timeout = LLMConfig.TIMEOUT.get();

        ProviderManager.INSTANCE.sendWithFallback(messages, List.of(providerName), temperature, maxTokens, timeout)
                .thenAccept(response -> {
                    if (!response.isSuccess()) { callback.accept(null); return; }
                    String content = JsonMode.cleanJsonResponse(response.getContent());
                    try {
                        JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
                        if (validateSchema(parsed, schema)) callback.accept(parsed);
                        else callback.accept(null);
                    } catch (Exception e) { callback.accept(null); }
                });
    }

    private static String buildSchemaDescription(JsonObject schema) {
        StringBuilder sb = new StringBuilder("{\n");
        for (Map.Entry<String, JsonElement> entry : schema.entrySet()) {
            sb.append("  \"").append(entry.getKey()).append("\": ");
            JsonElement val = entry.getValue();
            if (val.isJsonPrimitive()) sb.append(val.getAsString()).append(" (type)");
            else if (val.isJsonArray()) sb.append("one of ").append(val);
            sb.append(",\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private static boolean validateSchema(JsonObject obj, JsonObject schema) {
        for (Map.Entry<String, JsonElement> entry : schema.entrySet()) {
            if (!obj.has(entry.getKey())) return false;
        }
        return true;
    }
}
