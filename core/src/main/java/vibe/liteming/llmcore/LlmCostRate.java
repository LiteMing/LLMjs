// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

/**
 * Server-owned relative cost for one provider/model target.
 *
 * <p>The values are normalized multipliers rather than currency. Raw provider
 * usage remains available for diagnostics while the accounting policy can use
 * the weighted units to compare otherwise different models.</p>
 */
public record LlmCostRate(double inputMultiplier, double outputMultiplier,
        Double cacheReadInputMultiplier, Double cacheWriteInputMultiplier) {
    public static final LlmCostRate DEFAULT = new LlmCostRate(1.0D, 1.0D, null, null);
    private static final double MAX_MULTIPLIER = 1_000_000.0D;

    public LlmCostRate {
        inputMultiplier = validate(inputMultiplier, "inputMultiplier");
        outputMultiplier = validate(outputMultiplier, "outputMultiplier");
        cacheReadInputMultiplier = validateOptional(cacheReadInputMultiplier, "cacheReadInputMultiplier");
        cacheWriteInputMultiplier = validateOptional(cacheWriteInputMultiplier, "cacheWriteInputMultiplier");
    }

    public LlmCostRate(double inputMultiplier, double outputMultiplier) {
        this(inputMultiplier, outputMultiplier, null, null);
    }

    /** Converts raw usage into bounded integer cost-equivalent units. */
    public long weightedTokens(long inputTokens, long outputTokens) {
        return saturatedAdd(weight(inputTokens, inputMultiplier), weight(outputTokens, outputMultiplier));
    }

    public long weightedEstimate(long inputTokens, long outputTokens) {
        return weightedTokens(inputTokens, outputTokens);
    }

    /** Returns null unless reported dimensions and applicable cache rates are sufficient. */
    public Long weightedTokens(LlmCacheUsage usage, long outputTokens) {
        if (usage == null || usage.status() != LlmCacheUsage.Status.REPORTED) return null;
        Long read = usage.cacheReadInputTokens();
        Long write = usage.cacheWriteInputTokens();
        Long uncached = usage.uncachedInputTokens();
        if (read == null || uncached == null || usage.totalInputTokens() == null) return null;
        if (read > 0 && cacheReadInputMultiplier == null) return null;
        if (write != null && write > 0 && cacheWriteInputMultiplier == null) return null;
        long inputCost = saturatedAdd(weight(uncached, inputMultiplier),
                weight(read, cacheReadInputMultiplier == null ? inputMultiplier : cacheReadInputMultiplier));
        if (write != null) inputCost = saturatedAdd(inputCost,
                weight(write, cacheWriteInputMultiplier == null ? inputMultiplier : cacheWriteInputMultiplier));
        return saturatedAdd(inputCost, weight(outputTokens, outputMultiplier));
    }

    /** Compatibility overload retaining the explicit prompt-token argument used by 1.5.0 callers. */
    public Long weightedTokens(LlmCacheUsage usage, long fallbackInputTokens, long outputTokens) {
        return weightedTokens(usage, outputTokens);
    }

    private static double validate(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0D || value > MAX_MULTIPLIER) {
            throw new IllegalArgumentException(field + " must be finite and within 0.." + MAX_MULTIPLIER);
        }
        return value;
    }

    private static Double validateOptional(Double value, String field) {
        return value == null ? null : validate(value, field);
    }

    private static long weight(long tokens, double multiplier) {
        if (tokens <= 0L || multiplier <= 0.0D) return 0L;
        double weighted = tokens * multiplier;
        if (!Double.isFinite(weighted) || weighted >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, (long) Math.ceil(weighted));
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
