package vibe.liteming.llmcore;

import java.util.List;

public record LlmRequest(
        List<LlmMessage> messages,
        List<String> providerChain,
        Double temperature,
        Integer maxTokens,
        int timeoutSeconds,
        LlmRequestContext context) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        providerChain = providerChain == null ? List.of() : List.copyOf(providerChain);
        timeoutSeconds = Math.max(1, timeoutSeconds);
        context = context == null ? LlmRequestContext.chat() : context;
    }
}
