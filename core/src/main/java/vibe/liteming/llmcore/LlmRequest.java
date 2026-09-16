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
        LlmBillingContext billingContext,
        List<LlmMessageFinalization.FinalEntry> typedEntries,
        LlmWireDiagnostics wireDiagnostics) {

    public LlmRequest(List<LlmMessage> messages, List<String> providerChain, Double temperature,
            Integer maxTokens, int timeoutSeconds, LlmRequestContext context, LlmRouteOptions overrides,
            LlmBillingContext billingContext) {
        this(messages, providerChain, temperature, maxTokens, timeoutSeconds, context, overrides, billingContext,
                List.of(), null);
    }

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
        typedEntries = typedEntries == null || typedEntries.isEmpty()
                ? legacyEntries(messages) : List.copyOf(typedEntries);
        wireDiagnostics = wireDiagnostics == null
                ? LlmWireDiagnostics.fromEntries(typedEntries, LlmMessageFinalizer.CONSERVATIVE_ESTIMATOR)
                : wireDiagnostics;
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

    public static LlmRequest routed(LlmMessageFinalization finalization, LlmRequestContext context) {
        return routed(finalization, context, LlmBillingContext.unspecified());
    }

    public static LlmRequest routed(LlmMessageFinalization finalization, LlmRequestContext context,
            LlmBillingContext billingContext) {
        LlmMessageFinalization safe = finalization == null
                ? new LlmMessageFinalization(List.of(), List.of(), 0, 0, true) : finalization;
        return new LlmRequest(safe.messages(), List.of(), null, null, 0, context,
                LlmRouteOptions.empty(), billingContext, safe.entries(), safe.wireDiagnostics());
    }

    public LlmRequest withBillingContext(LlmBillingContext billing) {
        return new LlmRequest(messages, providerChain, temperature, maxTokens, timeoutSeconds,
                context, overrides, billing, typedEntries, wireDiagnostics);
    }

    LlmRouteOptions requestOverrides() {
        LlmRouteOptions legacy = new LlmRouteOptions(temperature, maxTokens,
                timeoutSeconds > 0 ? timeoutSeconds : null, null, null);
        return legacy.overlay(overrides);
    }

    private static List<LlmMessageFinalization.FinalEntry> legacyEntries(List<LlmMessage> messages) {
        return java.util.stream.IntStream.range(0, messages.size())
                .mapToObj(index -> new LlmMessageFinalization.FinalEntry(index, "message-" + index, "legacy",
                        messages.get(index), true, 0, LlmPromptStability.TURN_DYNAMIC))
                .toList();
    }
}
