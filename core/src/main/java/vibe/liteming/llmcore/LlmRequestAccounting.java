package vibe.liteming.llmcore;

import java.util.Objects;

/** Process-wide provider-attempt admission and settlement hook. */
public final class LlmRequestAccounting {
    /** Stable machine-readable admission result for cross-mod consumers. */
    public enum DenyCode {
        NONE,
        BUDGET_EXHAUSTED,
        CHAIN_CALLS_EXHAUSTED,
        CHAIN_TOKENS_EXHAUSTED,
        STORAGE_UNAVAILABLE,
        INVALID_CONTEXT,
        CAPACITY,
        POLICY_UNAVAILABLE
    }

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

    public record Reservation(boolean allowed, DenyCode denyCode, String reason,
            String reservationId, long reservedTokens) {
        public Reservation {
            denyCode = denyCode == null ? DenyCode.NONE : denyCode;
            reason = reason == null ? "" : reason;
            reservationId = reservationId == null ? "" : reservationId;
            reservedTokens = Math.max(0L, reservedTokens);
            if (allowed) denyCode = DenyCode.NONE;
        }

        /** Binary-compatible reservation shape used before structured denial codes. */
        public Reservation(boolean allowed, String reason, String reservationId, long reservedTokens) {
            this(allowed, allowed ? DenyCode.NONE : DenyCode.POLICY_UNAVAILABLE,
                    reason, reservationId, reservedTokens);
        }

        public static Reservation allow() {
            return new Reservation(true, DenyCode.NONE, "", "", 0L);
        }

        public static Reservation allow(String reservationId, long reservedTokens) {
            return new Reservation(true, DenyCode.NONE, "", reservationId, reservedTokens);
        }

        public static Reservation deny(String reason) {
            return deny(DenyCode.POLICY_UNAVAILABLE, reason);
        }

        public static Reservation deny(DenyCode denyCode, String reason) {
            return new Reservation(false,
                    denyCode == null || denyCode == DenyCode.NONE
                            ? DenyCode.POLICY_UNAVAILABLE : denyCode,
                    reason == null || reason.isBlank()
                            ? "LLM request denied by billing policy" : reason,
                    "", 0L);
        }
    }

    public interface Policy {
        default Reservation preflight(LlmBillingContext billing) {
            return Reservation.allow();
        }

        Reservation reserve(LlmRequest request, AttemptEstimate estimate);

        void settle(LlmRequest request, Reservation reservation, AttemptUsage usage);
    }

    private static final Policy NO_POLICY = new Policy() {
        @Override
        public Reservation preflight(LlmBillingContext billing) {
            LlmBillingContext safe = billing == null ? LlmBillingContext.unspecified() : billing;
            if (safe.principalKind() == LlmBillingContext.PrincipalKind.PLAYER) {
                return Reservation.deny(DenyCode.POLICY_UNAVAILABLE,
                        "LLM billing policy is not installed");
            }
            return Reservation.allow();
        }

        @Override
        public Reservation reserve(LlmRequest request, AttemptEstimate estimate) {
            LlmBillingContext billing = request == null
                    ? LlmBillingContext.unspecified() : request.billingContext();
            return preflight(billing);
        }

        @Override
        public void settle(LlmRequest request, Reservation reservation, AttemptUsage usage) {
        }
    };
    private static volatile Policy policy = NO_POLICY;
    private static volatile boolean installed;

    private LlmRequestAccounting() {
    }

    public static void install(Policy next) {
        if (next == null) {
            clear();
            return;
        }
        policy = Objects.requireNonNull(next, "next");
        installed = true;
    }

    public static void clear() {
        policy = NO_POLICY;
        installed = false;
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static Reservation reserve(LlmRequest request, AttemptEstimate estimate) {
        try {
            Reservation reservation = policy.reserve(request, estimate);
            return reservation == null
                    ? Reservation.deny(DenyCode.POLICY_UNAVAILABLE,
                            "LLM billing policy returned no decision") : reservation;
        } catch (RuntimeException failure) {
            return Reservation.deny(DenyCode.POLICY_UNAVAILABLE,
                    "LLM billing policy unavailable");
        }
    }

    public static void settle(LlmRequest request, Reservation reservation, AttemptUsage usage) {
        try {
            policy.settle(request, reservation, usage);
        } catch (RuntimeException ignored) {
        }
    }

    /** Read-only authorization check for callers that must reject before mutating host state. */
    public static Reservation preflight(LlmBillingContext billing) {
        try {
            Reservation reservation = policy.preflight(
                    billing == null ? LlmBillingContext.unspecified() : billing);
            return reservation == null
                    ? Reservation.deny(DenyCode.POLICY_UNAVAILABLE,
                            "LLM billing policy returned no preflight decision")
                    : reservation;
        } catch (RuntimeException failure) {
            return Reservation.deny(DenyCode.POLICY_UNAVAILABLE,
                    "LLM billing policy unavailable");
        }
    }

    /** Release a reservation when no provider usage can be charged. */
    public static void release(LlmRequest request, Reservation reservation) {
        settle(request, reservation, null);
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
