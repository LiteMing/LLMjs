package vibe.liteming.llmcore;

import java.util.Objects;
import java.util.UUID;

public record LlmRequestContext(
        String requestId,
        String purpose,
        String entityId,
        String entityType,
        String customName,
        String sessionId,
        String explicitRoute,
        boolean structured) {

    public LlmRequestContext {
        requestId = clean(requestId).isEmpty() ? UUID.randomUUID().toString() : clean(requestId);
        purpose = clean(purpose).isEmpty() ? "CHAT" : clean(purpose);
        entityId = clean(entityId);
        entityType = clean(entityType);
        customName = clean(customName);
        sessionId = clean(sessionId);
        explicitRoute = clean(explicitRoute);
    }

    public static LlmRequestContext chat() {
        return new LlmRequestContext("", "CHAT", "", "", "", "", "", false);
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
