package vibe.liteming.llmcore;

/**
 * A non-attempt routing decision made before any provider HTTP request or reservation.
 *
 * @since 1.4.1
 */
public record LlmRoutingDecision(String provider, Code code, String detail) {
    public enum Code {
        PROVIDER_NOT_FOUND,
        WEB_SEARCH_NOT_DECLARED,
        WEB_SEARCH_ADAPTER_UNAVAILABLE,
        WEB_SEARCH_ADAPTER_INCOMPATIBLE,
        NO_HEALTHY_CREDENTIAL,
        POLICY_DISABLED,
        DEGRADED_TO_TEXT
    }

    public LlmRoutingDecision {
        provider = provider == null ? "" : provider.trim();
        code = code == null ? Code.PROVIDER_NOT_FOUND : code;
        detail = detail == null ? "" : detail.trim();
    }
}
