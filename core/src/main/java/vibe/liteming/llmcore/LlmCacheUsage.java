// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

/** Provider-reported prompt/KV cache usage for one real transport attempt. */
public record LlmCacheUsage(
        Status status,
        Long cacheReadInputTokens,
        Long cacheWriteInputTokens,
        Long uncachedInputTokens,
        Long totalInputTokens,
        String reason) {

    public enum Status { REPORTED, UNKNOWN, UNSUPPORTED }

    public LlmCacheUsage {
        status = status == null ? Status.UNKNOWN : status;
        cacheReadInputTokens = nonNegative(cacheReadInputTokens);
        cacheWriteInputTokens = nonNegative(cacheWriteInputTokens);
        uncachedInputTokens = nonNegative(uncachedInputTokens);
        totalInputTokens = nonNegative(totalInputTokens);
        reason = reason == null ? "" : reason.trim();
    }

    public static LlmCacheUsage unknown(String reason) {
        return new LlmCacheUsage(Status.UNKNOWN, null, null, null, null, reason);
    }

    public static LlmCacheUsage unsupported(String reason) {
        return new LlmCacheUsage(Status.UNSUPPORTED, null, null, null, null, reason);
    }

    public static LlmCacheUsage reported(Long read, Long write, Long uncached, Long total) {
        return new LlmCacheUsage(Status.REPORTED, read, write, uncached, total, "");
    }

    public Double hitRatio() {
        if (status != Status.REPORTED || cacheReadInputTokens == null || totalInputTokens == null
                || totalInputTokens <= 0L) return null;
        return Math.min(1.0D, (double) cacheReadInputTokens / totalInputTokens);
    }

    private static Long nonNegative(Long value) {
        return value == null ? null : Math.max(0L, value);
    }
}
