package vibe.liteming.llmcore;

/**
 * Explicit, request-scoped access to provider-hosted Web Search.
 *
 * @since 1.4.1
 */
public record LlmHostedWebSearchRequest(Requirement requirement) {
    public enum Requirement {
        DISABLED,
        PREFERRED,
        REQUIRED
    }

    public LlmHostedWebSearchRequest {
        requirement = requirement == null ? Requirement.DISABLED : requirement;
    }

    public static LlmHostedWebSearchRequest disabled() {
        return new LlmHostedWebSearchRequest(Requirement.DISABLED);
    }

    public static LlmHostedWebSearchRequest preferred() {
        return new LlmHostedWebSearchRequest(Requirement.PREFERRED);
    }

    public static LlmHostedWebSearchRequest required() {
        return new LlmHostedWebSearchRequest(Requirement.REQUIRED);
    }

    public boolean requested() {
        return requirement != Requirement.DISABLED;
    }
}
