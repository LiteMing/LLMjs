package vibe.liteming.llmjs.format;

import com.google.gson.*;
import vibe.liteming.llmjs.pipeline.LLMResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiFormat implements ApiFormat {

    @Override
    public String buildUrl(String baseUrl, String model, String key) {
        return baseUrl;
    }

    @Override
    public Map<String, String> buildHeaders(String key) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + key);
        return headers;
    }

    @Override
    public String buildBody(String model, List<Message> messages, Double temperature, Integer maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray msgArray = new JsonArray();
        for (Message msg : messages) {
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
                        image.addProperty("type", "image_url");
                        JsonObject imageUrl = new JsonObject();
                        imageUrl.addProperty("url", imagePart.dataUrl());
                        imageUrl.addProperty("detail", imagePart.detail());
                        image.add("image_url", imageUrl);
                        content.add(image);
                    }
                }
                m.add("content", content);
            } else {
                m.addProperty("content", msg.content());
            }
            msgArray.add(m);
        }
        body.add("messages", msgArray);
        if (temperature != null) body.addProperty("temperature", temperature);
        if (maxTokens != null) body.addProperty("max_tokens", maxTokens);
        return body.toString();
    }

    @Override
    public LLMResponse parseResponse(String responseBody, String providerName, long latencyMs) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (json.has("error")) {
                String errMsg = json.getAsJsonObject("error").get("message").getAsString();
                return LLMResponse.error("API error: " + errMsg);
            }
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return LLMResponse.error("No choices in response");
            }
            String content = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            String model = json.has("model") ? json.get("model").getAsString() : "unknown";
            int promptTokens = 0, completionTokens = 0;
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
                completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
            }
            return LLMResponse.success(content, model, providerName, promptTokens, completionTokens, latencyMs);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse response: " + e.getMessage());
        }
    }
}
