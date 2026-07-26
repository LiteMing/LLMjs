package vibe.liteming.llmcore;

import java.util.List;

public record LlmRequest(
        List<LlmMessage> messages,
        List<String> providerChain,
        Double temperature,
        Integer maxTokens,
        int timeoutSeconds,
        LlmRequestContext context,
        LlmRouteOptions overrides) {

    public LlmRequest(List<LlmMessage> messages, List<String> providerChain, Double temperature,
            Integer maxTokens, int timeoutSeconds, LlmRequestContext context) {
        this(messages, providerChain, temperature, maxTokens, Math.max(1, timeoutSeconds), context,
                LlmRouteOptions.empty());
    }

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        providerChain = providerChain == null ? List.of() : List.copyOf(providerChain);
        timeoutSeconds = Math.max(0, timeoutSeconds);
        context = context == null ? LlmRequestContext.chat() : context;
        overrides = overrides == null ? LlmRouteOptions.empty() : overrides;
    }

    /** Purpose-routed production request with no one-shot parameter override. */
    public static LlmRequest routed(List<LlmMessage> messages, LlmRequestContext context) {
        return new LlmRequest(messages, List.of(), null, null, 0, context, LlmRouteOptions.empty());
    }

    LlmRouteOptions requestOverrides() {
        LlmRouteOptions legacy = new LlmRouteOptions(temperature, maxTokens,
                timeoutSeconds > 0 ? timeoutSeconds : null, null, null);
        return legacy.overlay(overrides);
    }
}
