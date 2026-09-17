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
        requireNonNegative(cacheReadInputTokens, "cacheReadInputTokens");
        requireNonNegative(cacheWriteInputTokens, "cacheWriteInputTokens");
        requireNonNegative(uncachedInputTokens, "uncachedInputTokens");
        requireNonNegative(totalInputTokens, "totalInputTokens");
        reason = reason == null ? "" : reason.trim();
        if (status != Status.REPORTED) {
            cacheReadInputTokens = null;
            cacheWriteInputTokens = null;
            uncachedInputTokens = null;
            totalInputTokens = null;
            if (reason.isEmpty()) reason = status == Status.UNSUPPORTED
                    ? "cache usage is unsupported" : "cache usage is unknown";
        } else if (cacheReadInputTokens == null && cacheWriteInputTokens == null
                && uncachedInputTokens == null && totalInputTokens == null) {
            throw new IllegalArgumentException("reported cache usage needs at least one reported dimension");
        }
    }

    public static LlmCacheUsage unknown(String reason) {
        return new LlmCacheUsage(Status.UNKNOWN, null, null, null, null, reason);
    }

    public static LlmCacheUsage unsupported(String reason) {
        return new LlmCacheUsage(Status.UNSUPPORTED, null, null, null, null, reason);
    }

    public static LlmCacheUsage reported(Long read, Long write, Long uncached, Long total) {
        return reported(read, write, uncached, total, "provider reported cache usage");
    }

    public static LlmCacheUsage reported(Long read, Long write, Long uncached, Long total, String reason) {
        return new LlmCacheUsage(Status.REPORTED, read, write, uncached, total, reason);
    }

    /** Ratio exists only when provider-reported cache reads have a comparable positive total. */
    public Double hitRatio() {
        if (status != Status.REPORTED || cacheReadInputTokens == null || totalInputTokens == null
                || totalInputTokens <= 0L || cacheReadInputTokens > totalInputTokens) return null;
        return (double) cacheReadInputTokens / totalInputTokens;
    }

    private static void requireNonNegative(Long value, String field) {
        if (value != null && value < 0L) throw new IllegalArgumentException(field + " must be non-negative");
    }
}
