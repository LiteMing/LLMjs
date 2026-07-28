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
        boolean structured,
        String responderEntityId,
        String responderName,
        String triggerSource,
        String addressee,
        String audience,
        String inputKind) {

    /** Backward-compatible context shape used by llm-core 1.0.0 callers. */
    public LlmRequestContext(String requestId, String purpose, String entityId, String entityType,
            String customName, String sessionId, String explicitRoute, boolean structured) {
        this(requestId, purpose, entityId, entityType, customName, sessionId, explicitRoute, structured,
                entityId, customName, "", "", "", purpose);
    }

    /** Backward-compatible diagnostic shape used before addressee became independent. */
    public LlmRequestContext(String requestId, String purpose, String entityId, String entityType,
            String customName, String sessionId, String explicitRoute, boolean structured,
            String responderEntityId, String responderName, String triggerSource,
            String audience, String inputKind) {
        this(requestId, purpose, entityId, entityType, customName, sessionId, explicitRoute, structured,
                responderEntityId, responderName, triggerSource, "", audience, inputKind);
    }

    public LlmRequestContext {
        requestId = clean(requestId).isEmpty() ? UUID.randomUUID().toString() : clean(requestId);
        purpose = clean(purpose).isEmpty() ? "CHAT" : clean(purpose);
        entityId = clean(entityId);
        entityType = clean(entityType);
        customName = clean(customName);
        sessionId = clean(sessionId);
        explicitRoute = clean(explicitRoute);
        responderEntityId = clean(responderEntityId);
        responderName = clean(responderName);
        triggerSource = clean(triggerSource);
        addressee = clean(addressee);
        audience = clean(audience);
        inputKind = clean(inputKind).isEmpty() ? purpose : clean(inputKind);
    }

    public static LlmRequestContext chat() {
        return new LlmRequestContext("", "CHAT", "", "", "", "", "", false,
                "", "", "", "", "", "player");
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
