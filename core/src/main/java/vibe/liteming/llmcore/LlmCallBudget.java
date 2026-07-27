package vibe.liteming.llmcore;

/** Worst-case provider-attempt count and token reservation for one logical request. */
public record LlmCallBudget(int maxCalls, long maxTokens) {
    public LlmCallBudget {
        maxCalls = Math.max(0, maxCalls);
        maxTokens = Math.max(0L, maxTokens);
    }
}
