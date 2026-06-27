package com.liteming.llmjs.format;

import com.google.gson.*;
import com.liteming.llmjs.pipeline.LLMResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClaudeFormat implements ApiFormat {

    @Override
    public String buildUrl(String baseUrl, String model, String key) {
        return baseUrl;
    }

    @Override
    public Map<String, String> buildHeaders(String key) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", key);
        headers.put("anthropic-version", "2023-06-01");
        return headers;
    }

    @Override
    public String buildBody(String model, List<Message> messages, Double temperature, Integer maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        String systemPrompt = null;
        JsonArray msgArray = new JsonArray();
        for (Message msg : messages) {
            if ("system".equals(msg.role())) {
                systemPrompt = msg.content();
            } else {
                JsonObject m = new JsonObject();
                m.addProperty("role", msg.role());
                if (msg.hasImage()) {
                    JsonArray content = new JsonArray();
                    for (MessagePart part : msg.parts()) {
                        if (part instanceof MessagePart.TextPart textPart) {
                            JsonObject text = new JsonObject();
                            text.addProperty("type", "text");
                            text.addProperty("text", textPart.text());
                            content.add(text);
                        } else if (part instanceof MessagePart.ImagePart imagePart) {
                            JsonObject image = new JsonObject();
                            image.addProperty("type", "image");
                            JsonObject source = new JsonObject();
                            source.addProperty("type", "base64");
                            source.addProperty("media_type", imagePart.mimeType());
                            source.addProperty("data", imagePart.base64Data());
                            image.add("source", source);
                            content.add(image);
                        }
                    }
                    m.add("content", content);
                } else {
                    m.addProperty("content", msg.content());
                }
                msgArray.add(m);
            }
        }
        body.add("messages", msgArray);
        if (systemPrompt != null) body.addProperty("system", systemPrompt);
        body.addProperty("max_tokens", maxTokens != null ? maxTokens : 1000);
        if (temperature != null) body.addProperty("temperature", temperature);
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
            JsonArray content = json.getAsJsonArray("content");
            if (content == null || content.isEmpty()) {
                return LLMResponse.error("No content in response");
            }
            String text = content.get(0).getAsJsonObject().get("text").getAsString();
            String model = json.has("model") ? json.get("model").getAsString() : "unknown";
            int inputTokens = 0, outputTokens = 0;
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
                outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
            }
            return LLMResponse.success(text, model, providerName, inputTokens, outputTokens, latencyMs);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse Claude response: " + e.getMessage());
        }
    }
}
