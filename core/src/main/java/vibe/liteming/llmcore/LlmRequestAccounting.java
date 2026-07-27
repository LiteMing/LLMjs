package vibe.liteming.llmcore;

import java.util.Objects;

/** Process-wide provider-attempt admission and settlement hook. */
public final class LlmRequestAccounting {
    public record AttemptEstimate(String provider, long inputTokens, long outputTokens) {
        public AttemptEstimate {
            provider = provider == null ? "" : provider;
            inputTokens = Math.max(0L, inputTokens);
            outputTokens = Math.max(0L, outputTokens);
        }

        public long totalTokens() {
            return saturatedAdd(inputTokens, outputTokens);
        }
    }

    public record AttemptUsage(long promptTokens, long completionTokens, long estimatedTokens) {
        public AttemptUsage {
            promptTokens = Math.max(0L, promptTokens);
            completionTokens = Math.max(0L, completionTokens);
            estimatedTokens = Math.max(0L, estimatedTokens);
        }

        public long totalTokens() {
            return saturatedAdd(saturatedAdd(promptTokens, completionTokens), estimatedTokens);
        }
    }

    public record Reservation(boolean allowed, String reason, String reservationId, long reservedTokens) {
        public Reservation {
            reason = reason == null ? "" : reason;
            reservationId = reservationId == null ? "" : reservationId;
            reservedTokens = Math.max(0L, reservedTokens);
        }

        public static Reservation allow() {
            return new Reservation(true, "", "", 0L);
        }

        public static Reservation allow(String reservationId, long reservedTokens) {
            return new Reservation(true, "", reservationId, reservedTokens);
        }

        public static Reservation deny(String reason) {
            return new Reservation(false, reason == null || reason.isBlank()
                    ? "LLM request denied by billing policy" : reason, "", 0L);
        }
    }

    public interface Policy {
        Reservation reserve(LlmRequest request, AttemptEstimate estimate);

        void settle(LlmRequest request, Reservation reservation, AttemptUsage usage);
    }

    private static final Policy ALLOW_ALL = new Policy() {
        @Override
        public Reservation reserve(LlmRequest request, AttemptEstimate estimate) {
            return Reservation.allow();
        }

        @Override
        public void settle(LlmRequest request, Reservation reservation, AttemptUsage usage) {
        }
    };
    private static volatile Policy policy = ALLOW_ALL;

    private LlmRequestAccounting() {
    }

    public static void install(Policy next) {
        policy = Objects.requireNonNullElse(next, ALLOW_ALL);
    }

    public static void clear() {
        policy = ALLOW_ALL;
    }

    public static Reservation reserve(LlmRequest request, AttemptEstimate estimate) {
        try {
            Reservation reservation = policy.reserve(request, estimate);
            return reservation == null
                    ? Reservation.deny("LLM billing policy returned no decision") : reservation;
        } catch (RuntimeException failure) {
            return Reservation.deny("LLM billing policy unavailable");
        }
    }

    public static void settle(LlmRequest request, Reservation reservation, AttemptUsage usage) {
        try {
            policy.settle(request, reservation, usage);
        } catch (RuntimeException ignored) {
        }
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
