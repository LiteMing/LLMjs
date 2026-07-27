package vibe.liteming.llmcore;

import java.util.List;

public record LlmRequest(
        List<LlmMessage> messages,
        List<String> providerChain,
        Double temperature,
        Integer maxTokens,
        int timeoutSeconds,
        LlmRequestContext context,
        LlmRouteOptions overrides,
        LlmBillingContext billingContext) {

    /** Binary-compatible request shape used before explicit billing was introduced. */
    public LlmRequest(List<LlmMessage> messages, List<String> providerChain, Double temperature,
            Integer maxTokens, int timeoutSeconds, LlmRequestContext context, LlmRouteOptions overrides) {
        this(messages, providerChain, temperature, maxTokens, timeoutSeconds, context, overrides,
                LlmBillingContext.unspecified());
    }

    public LlmRequest(List<LlmMessage> messages, List<String> providerChain, Double temperature,
            Integer maxTokens, int timeoutSeconds, LlmRequestContext context) {
        this(messages, providerChain, temperature, maxTokens, Math.max(1, timeoutSeconds), context,
                LlmRouteOptions.empty(), LlmBillingContext.unspecified());
    }

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        providerChain = providerChain == null ? List.of() : List.copyOf(providerChain);
        timeoutSeconds = Math.max(0, timeoutSeconds);
        context = context == null ? LlmRequestContext.chat() : context;
        overrides = overrides == null ? LlmRouteOptions.empty() : overrides;
        billingContext = billingContext == null ? LlmBillingContext.unspecified() : billingContext;
    }

    /** Purpose-routed production request with no one-shot parameter override. */
    public static LlmRequest routed(List<LlmMessage> messages, LlmRequestContext context) {
        return new LlmRequest(messages, List.of(), null, null, 0, context, LlmRouteOptions.empty());
    }

    public static LlmRequest routed(List<LlmMessage> messages, LlmRequestContext context,
            LlmBillingContext billingContext) {
        return new LlmRequest(messages, List.of(), null, null, 0, context,
                LlmRouteOptions.empty(), billingContext);
    }

    public LlmRequest withBillingContext(LlmBillingContext billing) {
        return new LlmRequest(messages, providerChain, temperature, maxTokens, timeoutSeconds,
                context, overrides, billing);
    }

    LlmRouteOptions requestOverrides() {
        LlmRouteOptions legacy = new LlmRouteOptions(temperature, maxTokens,
                timeoutSeconds > 0 ? timeoutSeconds : null, null, null);
        return legacy.overlay(overrides);
    }
}
