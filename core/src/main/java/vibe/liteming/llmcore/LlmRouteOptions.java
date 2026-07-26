package vibe.liteming.llmcore;

/**
 * Generic request controls that may be inherited from a purpose route or supplied
 * as a one-shot request override. A {@code null} field means inherit.
 */
public record LlmRouteOptions(
        Double temperature,
        Integer maxOutputTokens,
        Integer timeoutSeconds,
        Integer inputBudgetTokens,
        Integer outputReserveTokens) {

    public static final double MIN_TEMPERATURE = 0.0;
    public static final double MAX_TEMPERATURE = 2.0;
    public static final int MAX_OUTPUT_TOKENS = 1_000_000;
    public static final int MAX_TIMEOUT_SECONDS = 3_600;
    public static final int MAX_INPUT_BUDGET_TOKENS = 2_000_000;
    public static final int MAX_OUTPUT_RESERVE_TOKENS = 1_000_000;

    public LlmRouteOptions {
        requireFiniteRange("temperature", temperature, MIN_TEMPERATURE, MAX_TEMPERATURE);
        requireRange("maxOutputTokens", maxOutputTokens, 1, MAX_OUTPUT_TOKENS);
        requireRange("timeoutSeconds", timeoutSeconds, 1, MAX_TIMEOUT_SECONDS);
        requireRange("inputBudgetTokens", inputBudgetTokens, 1, MAX_INPUT_BUDGET_TOKENS);
        requireRange("outputReserveTokens", outputReserveTokens, 0, MAX_OUTPUT_RESERVE_TOKENS);
    }

    public static LlmRouteOptions empty() {
        return new LlmRouteOptions(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return temperature == null && maxOutputTokens == null && timeoutSeconds == null
                && inputBudgetTokens == null && outputReserveTokens == null;
    }

    /** Non-null values from {@code higherPriority} replace this instance. */
    public LlmRouteOptions overlay(LlmRouteOptions higherPriority) {
        LlmRouteOptions high = higherPriority == null ? empty() : higherPriority;
        return new LlmRouteOptions(
                high.temperature != null ? high.temperature : temperature,
                high.maxOutputTokens != null ? high.maxOutputTokens : maxOutputTokens,
                high.timeoutSeconds != null ? high.timeoutSeconds : timeoutSeconds,
                high.inputBudgetTokens != null ? high.inputBudgetTokens : inputBudgetTokens,
                high.outputReserveTokens != null ? high.outputReserveTokens : outputReserveTokens);
    }

    private static void requireRange(String name, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(name + " must be " + min + ".." + max);
        }
    }

    private static void requireFiniteRange(String name, Double value, double min, double max) {
        if (value != null && (!Double.isFinite(value) || value < min || value > max)) {
            throw new IllegalArgumentException(name + " must be " + min + ".." + max);
        }
    }
}
