package vibe.liteming.llmcore;

/** Provider-specific effective controls after request and purpose inheritance. */
public record LlmResolvedParameters(
        String provider,
        Double temperature,
        Integer maxOutputTokens,
        int timeoutSeconds,
        int inputBudgetTokens,
        int outputReserveTokens,
        Integer contextWindowTokens) {

    public static final int UNBOUNDED_INPUT = Integer.MAX_VALUE;

    public LlmResolvedParameters {
        provider = provider == null ? "" : provider;
        timeoutSeconds = Math.max(1, timeoutSeconds);
        inputBudgetTokens = Math.max(0, inputBudgetTokens);
        outputReserveTokens = Math.max(0, outputReserveTokens);
    }

    public boolean hasBoundedInput() {
        return inputBudgetTokens != UNBOUNDED_INPUT;
    }
}
