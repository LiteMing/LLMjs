package com.liteming.llmjs.format;

import com.google.gson.*;
import com.liteming.llmjs.pipeline.LLMResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GeminiFormat implements ApiFormat {

    @Override
    public String buildUrl(String baseUrl, String model, String key) {
        String url = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return url + model + ":generateContent?key=" + key;
    }

    @Override
    public Map<String, String> buildHeaders(String key) {
        return new LinkedHashMap<>();
    }

    @Override
    public String buildBody(String model, List<Message> messages, Double temperature, Integer maxTokens) {
        JsonObject body = new JsonObject();
        String systemPrompt = null;
        JsonArray contents = new JsonArray();
        for (Message msg : messages) {
            if ("system".equals(msg.role())) {
                systemPrompt = msg.content();
                continue;
            }
            JsonObject content = new JsonObject();
            content.addProperty("role", "assistant".equals(msg.role()) ? "model" : msg.role());
            JsonArray parts = new JsonArray();
            for (MessagePart part : msg.parts()) {
                if (part instanceof MessagePart.TextPart textPart) {
                    JsonObject text = new JsonObject();
                    text.addProperty("text", textPart.text());
                    parts.add(text);
                } else if (part instanceof MessagePart.ImagePart imagePart) {
                    JsonObject inline = new JsonObject();
                    JsonObject data = new JsonObject();
                    data.addProperty("mimeType", imagePart.mimeType());
                    data.addProperty("data", imagePart.base64Data());
                    inline.add("inlineData", data);
                    parts.add(inline);
                }
            }
            content.add("parts", parts);
            contents.add(content);
        }
        body.add("contents", contents);
        if (systemPrompt != null) {
            JsonObject sysInstruction = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", systemPrompt);
            parts.add(textPart);
            sysInstruction.add("parts", parts);
            body.add("systemInstruction", sysInstruction);
        }
        JsonObject genConfig = new JsonObject();
        if (temperature != null) genConfig.addProperty("temperature", temperature);
        if (maxTokens != null) genConfig.addProperty("maxOutputTokens", maxTokens);
        if (genConfig.size() > 0) body.add("generationConfig", genConfig);
        return body.toString();
    }

    @Override
    public LLMResponse parseResponse(String responseBody, String providerName, long latencyMs) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (json.has("error")) {
                JsonObject err = json.getAsJsonObject("error");
                String errMsg = err.has("message") ? err.get("message").getAsString() : err.toString();
                return LLMResponse.error("API error: " + errMsg);
            }
            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return LLMResponse.error("No candidates in response");
            }
            String text = candidates.get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts").get(0).getAsJsonObject()
                    .get("text").getAsString();
            int promptTokens = 0, completionTokens = 0;
            if (json.has("usageMetadata")) {
                JsonObject usage = json.getAsJsonObject("usageMetadata");
                promptTokens = usage.has("promptTokenCount") ? usage.get("promptTokenCount").getAsInt() : 0;
                completionTokens = usage.has("candidatesTokenCount") ? usage.get("candidatesTokenCount").getAsInt() : 0;
            }
            String modelName = json.has("modelVersion") ? json.get("modelVersion").getAsString() : "gemini";
            return LLMResponse.success(text, modelName, providerName, promptTokens, completionTokens, latencyMs);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse Gemini response: " + e.getMessage());
        }
    }
}
