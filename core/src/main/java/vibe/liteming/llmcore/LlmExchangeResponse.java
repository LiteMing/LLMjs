package vibe.liteming.llmcore;

import java.util.List;

/**
 * Typed response for optional capabilities and normalized provenance.
 *
 * @since 1.4.1
 */
public record LlmExchangeResponse(
        LlmResponse legacyResponse,
        WebSearchStatus webSearchStatus,
        int webSearchUses,
        List<LlmSource> sources,
        List<LlmRoutingDecision> routingDecisions,
        List<String> degradedFeatures,
        ErrorCode errorCode,
        String error) {

    public enum WebSearchStatus {
        NOT_REQUESTED,
        USED,
        POLICY_DISABLED,
        NO_CAPABLE_PROVIDER,
        REQUESTED_NOT_USED,
        DEGRADED
    }

    public enum ErrorCode {
        NONE,
        FEATURE_DISABLED,
        NO_CAPABLE_PROVIDER,
        REQUIRED_FEATURE_NOT_USED,
        PROVIDER_FAILURE
    }

    public LlmExchangeResponse {
        legacyResponse = legacyResponse == null
                ? LlmResponse.failure("Missing exchange response", List.of())
                : legacyResponse;
        webSearchStatus = webSearchStatus == null ? WebSearchStatus.NOT_REQUESTED : webSearchStatus;
        webSearchUses = Math.max(0, webSearchUses);
        sources = sources == null ? List.of() : List.copyOf(sources);
        routingDecisions = routingDecisions == null ? List.of() : List.copyOf(routingDecisions);
        degradedFeatures = degradedFeatures == null ? List.of() : List.copyOf(degradedFeatures);
        errorCode = errorCode == null ? ErrorCode.NONE : errorCode;
        error = error == null ? "" : error;
    }

    public boolean success() {
        return errorCode == ErrorCode.NONE && legacyResponse.success();
    }

    public boolean webSearchUsed() {
        return webSearchStatus == WebSearchStatus.USED;
    }
}
