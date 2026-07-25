package vibe.liteming.llmcore;

import java.util.List;

public record LlmResponse(
        boolean success,
        String content,
        String error,
        String provider,
        String model,
        String credentialId,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        List<Attempt> attempts,
        String requestBody,
        String responseBody,
        String finishReason) {

    public LlmResponse {
        content = content == null ? "" : content;
        error = error == null ? "" : error;
        provider = provider == null ? "" : provider;
        model = model == null ? "" : model;
        credentialId = credentialId == null ? "" : credentialId;
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        requestBody = requestBody == null ? "" : requestBody;
        responseBody = responseBody == null ? "" : responseBody;
        finishReason = finishReason == null ? "" : finishReason;
    }

    public LlmResponse(boolean success, String content, String error, String provider, String model,
            String credentialId, int promptTokens, int completionTokens, long latencyMs, List<Attempt> attempts) {
        this(success, content, error, provider, model, credentialId, promptTokens, completionTokens, latencyMs,
                attempts, "", "", "");
    }

    /** Backward-compatible body-carrying constructor used by older callers. */
    public LlmResponse(boolean success, String content, String error, String provider, String model,
            String credentialId, int promptTokens, int completionTokens, long latencyMs, List<Attempt> attempts,
            String requestBody, String responseBody) {
        this(success, content, error, provider, model, credentialId, promptTokens, completionTokens, latencyMs,
                attempts, requestBody, responseBody, "");
    }

    public static LlmResponse failure(String error, List<Attempt> attempts) {
        return new LlmResponse(false, "", error, "", "", "", 0, 0, 0L, attempts, "", "", "error");
    }

    public LlmResponse withBodies(String request, String response) {
        return new LlmResponse(success, content, error, provider, model, credentialId, promptTokens,
                completionTokens, latencyMs, attempts, request, response, finishReason);
    }

    public record Attempt(String provider, String credentialId, boolean success, String error, long latencyMs,
            String finishReason) {
        public Attempt(String provider, String credentialId, boolean success, String error, long latencyMs) {
            this(provider, credentialId, success, error, latencyMs, "");
        }

        public Attempt {
            provider = provider == null ? "" : provider;
            credentialId = credentialId == null ? "" : credentialId;
            error = error == null ? "" : error;
            finishReason = finishReason == null ? "" : finishReason;
        }
    }
}
