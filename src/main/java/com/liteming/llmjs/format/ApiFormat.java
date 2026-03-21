package com.liteming.llmjs.format;

import com.liteming.llmjs.pipeline.LLMResponse;

import java.util.List;
import java.util.Map;

public interface ApiFormat {

    String buildUrl(String baseUrl, String model, String key);
    Map<String, String> buildHeaders(String key);
    String buildBody(String model, List<Message> messages, Double temperature, Integer maxTokens);
    LLMResponse parseResponse(String responseBody, String providerName, long latencyMs);

    record Message(String role, String content) {}

    static ApiFormat byName(String name) {
        return switch (name.toLowerCase()) {
            case "openai" -> new OpenAiFormat();
            case "claude" -> new ClaudeFormat();
            case "gemini" -> new GeminiFormat();
            default -> throw new IllegalArgumentException("Unknown API format: " + name);
        };
    }
}
