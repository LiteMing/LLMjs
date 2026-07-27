package vibe.liteming.llmcore;

import java.util.Locale;
import java.util.UUID;

/** Explicit billing identity and hard ceiling shared by one causal request chain. */
public record LlmBillingContext(
        PrincipalKind principalKind,
        String principalId,
        String causalRootRequestId,
        int maxCalls,
        long maxTokens) {

    public enum PrincipalKind {
        UNSPECIFIED,
        PLAYER,
        SERVER_AMBIENT,
        SERVER_MAINTENANCE,
        SCRIPT_SYSTEM
    }

    public LlmBillingContext {
        principalKind = principalKind == null ? PrincipalKind.UNSPECIFIED : principalKind;
        principalId = clean(principalId);
        causalRootRequestId = clean(causalRootRequestId);
        maxCalls = Math.max(0, maxCalls);
        maxTokens = Math.max(0L, maxTokens);
    }

    public static LlmBillingContext unspecified() {
        return new LlmBillingContext(PrincipalKind.UNSPECIFIED, "", "", 0, 0L);
    }

    public static LlmBillingContext player(String playerId, String causalRootRequestId,
            int maxCalls, long maxTokens) {
        return new LlmBillingContext(PrincipalKind.PLAYER, playerId, causalRootRequestId,
                maxCalls, maxTokens);
    }

    public static LlmBillingContext system(PrincipalKind kind, String causalRootRequestId,
            int maxCalls, long maxTokens) {
        if (kind == null || kind == PrincipalKind.UNSPECIFIED || kind == PrincipalKind.PLAYER) {
            throw new IllegalArgumentException("A system billing context requires a system principal kind");
        }
        return new LlmBillingContext(kind, "", causalRootRequestId, maxCalls, maxTokens);
    }

    public boolean specified() {
        return validationError().isEmpty();
    }

    /** Returns an empty string when the context is safe for provider access. */
    public String validationError() {
        if (principalKind == PrincipalKind.UNSPECIFIED) return "LLM billing context is unspecified";
        if (causalRootRequestId.isEmpty()) return "LLM billing causal root is missing";
        if (causalRootRequestId.length() > 128) return "LLM billing causal root is too long";
        if (maxCalls <= 0) return "LLM billing maxCalls must be positive";
        if (maxTokens <= 0L) return "LLM billing maxTokens must be positive";
        if (principalKind == PrincipalKind.PLAYER) {
            try {
                UUID.fromString(principalId);
            } catch (IllegalArgumentException failure) {
                return "LLM player billing principal must be a UUID";
            }
        } else if (!principalId.isEmpty()) {
            return "System billing principals cannot carry a player ID";
        }
        return "";
    }

    public String principalKey() {
        return principalKind.name().toLowerCase(Locale.ROOT)
                + (principalId.isEmpty() ? "" : ":" + principalId.toLowerCase(Locale.ROOT));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
