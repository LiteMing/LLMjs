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
        String finishReason,
        LlmRequestAccounting.DenyCode denyCode,
        LlmCacheUsage cacheUsage,
        LlmWireDiagnostics wireDiagnostics) {

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
        denyCode = denyCode == null ? LlmRequestAccounting.DenyCode.NONE : denyCode;
        cacheUsage = cacheUsage == null ? LlmCacheUsage.unknown("attempt did not report cache usage") : cacheUsage;
        wireDiagnostics = wireDiagnostics == null
                ? new LlmWireDiagnostics("", "", 0, "", "") : wireDiagnostics;
    }

    /** Binary-compatible full response shape used before structured denial codes. */
    public LlmResponse(boolean success, String content, String error, String provider, String model,
            String credentialId, int promptTokens, int completionTokens, long latencyMs, List<Attempt> attempts,
            String requestBody, String responseBody, String finishReason) {
        this(success, content, error, provider, model, credentialId, promptTokens, completionTokens, latencyMs,
                attempts, requestBody, responseBody, finishReason, LlmRequestAccounting.DenyCode.NONE,
                LlmCacheUsage.unknown("legacy response did not carry cache usage"), null);
    }

    public LlmResponse(boolean success, String content, String error, String provider, String model,
            String credentialId, int promptTokens, int completionTokens, long latencyMs, List<Attempt> attempts) {
        this(success, content, error, provider, model, credentialId, promptTokens, completionTokens, latencyMs,
                attempts, "", "", "", LlmRequestAccounting.DenyCode.NONE,
                LlmCacheUsage.unknown("legacy response did not carry cache usage"), null);
    }

    /** Backward-compatible body-carrying constructor used by older callers. */
    public LlmResponse(boolean success, String content, String error, String provider, String model,
            String credentialId, int promptTokens, int completionTokens, long latencyMs, List<Attempt> attempts,
            String requestBody, String responseBody) {
        this(success, content, error, provider, model, credentialId, promptTokens, completionTokens, latencyMs,
                attempts, requestBody, responseBody, "", LlmRequestAccounting.DenyCode.NONE,
                LlmCacheUsage.unknown("legacy response did not carry cache usage"), null);
    }

    public LlmResponse(boolean success, String content, String error, String provider, String model,
            String credentialId, int promptTokens, int completionTokens, long latencyMs, List<Attempt> attempts,
            String requestBody, String responseBody, String finishReason,
            LlmRequestAccounting.DenyCode denyCode, LlmCacheUsage cacheUsage) {
        this(success, content, error, provider, model, credentialId, promptTokens, completionTokens, latencyMs,
                attempts, requestBody, responseBody, finishReason, denyCode, cacheUsage, null);
    }

    public static LlmResponse failure(String error, List<Attempt> attempts) {
        return failure(error, attempts, LlmRequestAccounting.DenyCode.NONE);
    }

    public static LlmResponse failure(String error, List<Attempt> attempts,
            LlmRequestAccounting.DenyCode denyCode) {
        return new LlmResponse(false, "", error, "", "", "", 0, 0, 0L, attempts,
                "", "", "error", denyCode, LlmCacheUsage.unknown(error), null);
    }

    public LlmResponse withBodies(String request, String response) {
        return new LlmResponse(success, content, error, provider, model, credentialId, promptTokens,
                completionTokens, latencyMs, attempts, request, response, finishReason, denyCode, cacheUsage,
                wireDiagnostics);
    }

    public record Attempt(String provider, String credentialId, boolean success, String error, long latencyMs,
            String finishReason, String model, LlmCacheUsage cacheUsage, LlmWireDiagnostics wireDiagnostics) {
        public Attempt(String provider, String credentialId, boolean success, String error, long latencyMs,
                String finishReason, String model, LlmCacheUsage cacheUsage) {
            this(provider, credentialId, success, error, latencyMs, finishReason, model, cacheUsage, null);
        }

        public Attempt(String provider, String credentialId, boolean success, String error, long latencyMs,
                String finishReason) {
            this(provider, credentialId, success, error, latencyMs, finishReason, "",
                    LlmCacheUsage.unknown("legacy attempt did not carry cache usage"));
        }

        public Attempt(String provider, String credentialId, boolean success, String error, long latencyMs) {
            this(provider, credentialId, success, error, latencyMs, "");
        }

        public Attempt {
            provider = provider == null ? "" : provider;
            credentialId = credentialId == null ? "" : credentialId;
            error = error == null ? "" : error;
            finishReason = finishReason == null ? "" : finishReason;
            model = model == null ? "" : model;
            cacheUsage = cacheUsage == null ? LlmCacheUsage.unknown("attempt did not report cache usage") : cacheUsage;
            wireDiagnostics = wireDiagnostics == null
                    ? new LlmWireDiagnostics("", "", 0, "", "") : wireDiagnostics;
        }
    }
}
