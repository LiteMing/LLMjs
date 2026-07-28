package vibe.liteming.llmjs.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import vibe.liteming.llmcore.LlmBillingContext;
import vibe.liteming.llmcore.LlmRequest;
import vibe.liteming.llmcore.LlmRequestAccounting;
import vibe.liteming.llmcore.mod.LlmCoreMod;
import vibe.liteming.llmjs.config.LLMConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Global chain ceilings plus persistent per-player token accounting and limits. */
public final class PersonalBudgetService implements LlmRequestAccounting.Policy {
    public static final PersonalBudgetService INSTANCE =
            new PersonalBudgetService(() -> LLMConfig.PERSONAL_BUDGET_DEFAULT.get());
    private static final long COMPLETED_CHAIN_RETENTION_MS = 60L * 60L * 1_000L;
    private static final long DEFAULT_PENDING_TTL_MS = 10L * 60L * 1_000L;
    private static final int PRUNE_THRESHOLD = 4_096;
    private static final int MAX_CHAIN_STATES = 16_384;
    private static final int MAX_CHAIN_STATES_PER_PRINCIPAL = 1_024;

    public record Status(UUID playerId, long promptTokens, long completionTokens,
            long estimatedTokens, long totalTokens, long requestCount, long reservedTokens, long limitTokens,
            boolean inheritedLimit, boolean unlimited, boolean disabled,
            boolean exhausted, boolean storageAvailable) {
    }

    public record PrincipalStatus(LlmBillingContext.PrincipalKind principalKind,
            long promptTokens, long completionTokens, long estimatedTokens,
            long totalTokens, long requestCount) {
    }

    private static final class PrincipalTotals {
        private long promptTokens;
        private long completionTokens;
        private long estimatedTokens;
        private long requestCount;
    }

    private static final class ChainState {
        private int maxCalls;
        private long maxTokens;
        private int calls;
        private long usedTokens;
        private long reservedTokens;
        private long touchedAtMs;

        private ChainState(LlmBillingContext billing, long nowMs) {
            maxCalls = billing.maxCalls();
            maxTokens = billing.maxTokens();
            touchedAtMs = nowMs;
        }
    }

    private record PrincipalBucket(LlmBillingContext.PrincipalKind kind, String principalId) {
    }

    private record ChainKey(PrincipalBucket principal, String rootId) {
    }

    private record Pending(ChainKey chainKey, UUID playerId, long reservedTokens, long expiresAtMs) {
    }

    private final LongSupplier defaultLimitSupplier;
    private final LongSupplier clock;
    private final long pendingTtlMs;
    private final Map<ChainKey, ChainState> chains = new HashMap<>();
    private final Map<PrincipalBucket, Integer> chainCounts = new HashMap<>();
    private final Map<String, Pending> pendingById = new HashMap<>();
    private final Map<UUID, Long> reservedByPlayer = new HashMap<>();
    private final Map<LlmBillingContext.PrincipalKind, PrincipalTotals> usageByPrincipal = new HashMap<>();
    private volatile PersonalBudgetLedger ledger;

    PersonalBudgetService(LongSupplier defaultLimitSupplier) {
        this(defaultLimitSupplier, System::currentTimeMillis, DEFAULT_PENDING_TTL_MS);
    }

    PersonalBudgetService(LongSupplier defaultLimitSupplier, LongSupplier clock, long pendingTtlMs) {
        this.defaultLimitSupplier = defaultLimitSupplier;
        this.clock = clock;
        this.pendingTtlMs = Math.max(1L, pendingTtlMs);
    }

    public synchronized void open(Path file) {
        ledger = new PersonalBudgetLedger(file);
        chains.clear();
        chainCounts.clear();
        pendingById.clear();
        reservedByPlayer.clear();
        usageByPrincipal.clear();
    }

    public synchronized void close() {
        PersonalBudgetLedger active = ledger;
        if (active != null && active.isWritable()) {
            try {
                active.flush();
            } catch (RuntimeException failure) {
                LlmCoreMod.LOGGER.error("Failed to flush personal token usage during shutdown: {}",
                        failure.toString());
            }
        }
        ledger = null;
        chains.clear();
        chainCounts.clear();
        pendingById.clear();
        reservedByPlayer.clear();
        usageByPrincipal.clear();
    }

    @Override
    public synchronized LlmRequestAccounting.Reservation preflight(LlmBillingContext billing) {
        LlmBillingContext safeBilling = billing == null
                ? LlmBillingContext.unspecified() : billing;
        String validationError = safeBilling.validationError();
        if (!validationError.isEmpty()) {
            return deny(LlmRequestAccounting.DenyCode.INVALID_CONTEXT, validationError);
        }
        long nowMs = now();
        pruneExpiredPending(nowMs);
        pruneCompletedChains(nowMs);
        if (safeBilling.principalKind() != LlmBillingContext.PrincipalKind.PLAYER) {
            return LlmRequestAccounting.Reservation.allow();
        }
        UUID playerId = playerId(safeBilling);
        if (playerId == null) {
            return deny(LlmRequestAccounting.DenyCode.INVALID_CONTEXT,
                    "Player billing principalId is not a UUID");
        }
        PersonalBudgetLedger active = ledger;
        if (active == null || !active.isWritable()) {
            return deny(LlmRequestAccounting.DenyCode.STORAGE_UNAVAILABLE,
                    "Personal token budget storage is unavailable");
        }
        PersonalBudgetLedger.Usage usage = active.usage(playerId);
        long effectiveLimit = effectiveLimit(usage.limitTokens());
        if (effectiveLimit == 0L) {
            return deny(LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED,
                    "Personal token budget is disabled");
        }
        long reserved = reservedByPlayer.getOrDefault(playerId, 0L);
        if (effectiveLimit > 0L && saturatedAdd(usage.totalTokens(), reserved) >= effectiveLimit) {
            return deny(LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED,
                    "Personal token budget exhausted");
        }
        return LlmRequestAccounting.Reservation.allow();
    }

    @Override
    public synchronized LlmRequestAccounting.Reservation reserve(
            LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
        LlmBillingContext billing = request == null
                ? LlmBillingContext.unspecified() : request.billingContext();
        LlmRequestAccounting.Reservation admission = preflight(billing);
        if (!admission.allowed()) return admission;
        if (estimate == null || estimate.outputTokens() <= 0L || estimate.totalTokens() <= 0L) {
            return deny(LlmRequestAccounting.DenyCode.INVALID_CONTEXT,
                    "Provider attempt has no bounded output token reservation");
        }

        long nowMs = now();
        pruneExpiredPending(nowMs);
        pruneCompletedChains(nowMs);

        UUID playerId = playerId(billing);
        PersonalBudgetLedger active = ledger;
        if (billing.principalKind() == LlmBillingContext.PrincipalKind.PLAYER) {
            PersonalBudgetLedger.Usage usage = active.usage(playerId);
            long effectiveLimit = effectiveLimit(usage.limitTokens());
            if (effectiveLimit > 0L) {
                long reserved = reservedByPlayer.getOrDefault(playerId, 0L);
                if (saturatedAdd(saturatedAdd(usage.totalTokens(), reserved), estimate.totalTokens())
                        > effectiveLimit) {
                    return deny(LlmRequestAccounting.DenyCode.BUDGET_EXHAUSTED,
                            "Personal token budget exhausted");
                }
            }
        }

        PrincipalBucket principal = principalBucket(billing);
        ChainKey chainKey = new ChainKey(principal, billing.causalRootRequestId());
        if (!chains.containsKey(chainKey)) {
            if (chainCounts.getOrDefault(principal, 0) >= MAX_CHAIN_STATES_PER_PRINCIPAL) {
                return deny(LlmRequestAccounting.DenyCode.CAPACITY,
                        "Causal chain capacity reached for billing principal");
            }
            if (chains.size() >= MAX_CHAIN_STATES) {
                return deny(LlmRequestAccounting.DenyCode.CAPACITY,
                        "Causal chain registry capacity reached");
            }
        }
        ChainState chain = chains.get(chainKey);
        if (chain == null) {
            chain = new ChainState(billing, nowMs);
            chains.put(chainKey, chain);
            chainCounts.merge(principal, 1, Integer::sum);
        }
        chain.maxCalls = Math.min(chain.maxCalls, billing.maxCalls());
        chain.maxTokens = Math.min(chain.maxTokens, billing.maxTokens());
        chain.touchedAtMs = nowMs;
        long reservationTokens = estimate.totalTokens();
        if (chain.calls >= chain.maxCalls) {
            return deny(LlmRequestAccounting.DenyCode.CHAIN_CALLS_EXHAUSTED,
                    "Causal chain call budget exhausted");
        }
        if (saturatedAdd(saturatedAdd(chain.usedTokens, chain.reservedTokens), reservationTokens)
                > chain.maxTokens) {
            return deny(LlmRequestAccounting.DenyCode.CHAIN_TOKENS_EXHAUSTED,
                    "Causal chain token budget exhausted");
        }

        String reservationId = UUID.randomUUID().toString();
        chain.calls++;
        chain.reservedTokens = saturatedAdd(chain.reservedTokens, reservationTokens);
        pendingById.put(reservationId, new Pending(chainKey, playerId, reservationTokens,
                saturatedAdd(nowMs, pendingTtlMs)));
        if (playerId != null) {
            reservedByPlayer.put(playerId,
                    saturatedAdd(reservedByPlayer.getOrDefault(playerId, 0L), reservationTokens));
        }
        return LlmRequestAccounting.Reservation.allow(reservationId, reservationTokens);
    }

    @Override
    public synchronized void settle(LlmRequest request, LlmRequestAccounting.Reservation reservation,
            LlmRequestAccounting.AttemptUsage usage) {
        if (reservation == null || !reservation.allowed() || reservation.reservationId().isEmpty()) return;
        Pending pending = pendingById.remove(reservation.reservationId());
        if (pending == null) return;
        long actualTokens = usage == null ? pending.reservedTokens() : usage.totalTokens();
        ChainState chain = chains.get(pending.chainKey());
        if (chain != null) {
            chain.reservedTokens = Math.max(0L, chain.reservedTokens - pending.reservedTokens());
            chain.usedTokens = saturatedAdd(chain.usedTokens, actualTokens);
            chain.touchedAtMs = now();
        }
        if (usage != null && actualTokens > 0L) {
            recordPrincipalUsage(pending.chainKey().principal().kind(), usage);
        }
        if (pending.playerId() == null) return;
        reducePlayerReservation(pending.playerId(), pending.reservedTokens());
        PersonalBudgetLedger active = ledger;
        if (active == null || usage == null || actualTokens <= 0L) return;
        try {
            active.record(pending.playerId(), usage.promptTokens(), usage.completionTokens(),
                    usage.estimatedTokens(), now());
        } catch (RuntimeException failure) {
            LlmCoreMod.LOGGER.error("Failed to persist personal token usage for {}: {}",
                    pending.playerId(), failure.toString());
        }
    }

    public synchronized Status status(UUID playerId) {
        pruneExpiredPending(now());
        PersonalBudgetLedger active = ledger;
        long reserved = playerId == null ? 0L : reservedByPlayer.getOrDefault(playerId, 0L);
        if (active == null || playerId == null) {
            long limit = defaultLimit();
            return new Status(playerId, 0L, 0L, 0L, 0L, 0L, reserved, limit,
                    true, limit == -1L, limit == 0L, limit == 0L, false);
        }
        PersonalBudgetLedger.Usage usage = active.usage(playerId);
        boolean inherited = usage.limitTokens() == null;
        long limit = effectiveLimit(usage.limitTokens());
        long total = usage.totalTokens();
        boolean exhausted = limit == 0L
                || (limit > 0L && saturatedAdd(total, reserved) >= limit);
        return new Status(playerId, usage.promptTokens(), usage.completionTokens(),
                usage.estimatedTokens(), total, usage.requestCount(), reserved, limit, inherited,
                limit == -1L, limit == 0L, exhausted, active.isWritable());
    }

    public synchronized List<Status> list() {
        pruneExpiredPending(now());
        PersonalBudgetLedger active = ledger;
        if (active == null) return List.of();
        Set<UUID> playerIds = new LinkedHashSet<>();
        active.list().forEach(usage -> playerIds.add(usage.playerId()));
        playerIds.addAll(reservedByPlayer.keySet());
        List<Status> statuses = new ArrayList<>(playerIds.size());
        playerIds.stream().sorted().forEach(playerId -> statuses.add(status(playerId)));
        return List.copyOf(statuses);
    }

    public synchronized List<PrincipalStatus> principalUsage() {
        List<PrincipalStatus> statuses = new ArrayList<>();
        for (LlmBillingContext.PrincipalKind kind : LlmBillingContext.PrincipalKind.values()) {
            PrincipalTotals totals = usageByPrincipal.get(kind);
            if (totals == null || totals.requestCount == 0L) continue;
            statuses.add(new PrincipalStatus(kind, totals.promptTokens, totals.completionTokens,
                    totals.estimatedTokens,
                    saturatedAdd(saturatedAdd(totals.promptTokens, totals.completionTokens),
                            totals.estimatedTokens),
                    totals.requestCount));
        }
        return List.copyOf(statuses);
    }

    public boolean storageAvailable() {
        PersonalBudgetLedger active = ledger;
        return active != null && active.isWritable();
    }

    public synchronized boolean reset(UUID playerId) {
        PersonalBudgetLedger active = ledger;
        if (active == null || playerId == null) return false;
        try {
            return active.reset(playerId);
        } catch (RuntimeException failure) {
            LlmCoreMod.LOGGER.error("Failed to reset personal token usage for {}: {}",
                    playerId, failure.toString());
            return false;
        }
    }

    /** null inherits the server default; -1 is unlimited; 0 disables; positive values are finite. */
    public synchronized boolean setLimit(UUID playerId, Long limitTokens) {
        PersonalBudgetLedger active = ledger;
        if (active == null || playerId == null || (limitTokens != null && limitTokens < -1L)) return false;
        try {
            return active.setLimit(playerId, limitTokens, now());
        } catch (RuntimeException failure) {
            LlmCoreMod.LOGGER.error("Failed to update personal token limit for {}: {}",
                    playerId, failure.toString());
            return false;
        }
    }

    public JsonObject statusJson(UUID playerId) {
        Status status = status(playerId);
        JsonObject json = new JsonObject();
        json.addProperty("promptTokens", status.promptTokens());
        json.addProperty("completionTokens", status.completionTokens());
        json.addProperty("estimatedTokens", status.estimatedTokens());
        json.addProperty("totalTokens", status.totalTokens());
        json.addProperty("requestCount", status.requestCount());
        json.addProperty("averageTokens", status.requestCount() == 0L
                ? 0L : status.totalTokens() / status.requestCount());
        json.addProperty("reservedTokens", status.reservedTokens());
        json.addProperty("limitTokens", status.limitTokens());
        json.addProperty("limitInherited", status.inheritedLimit());
        json.addProperty("unlimited", status.unlimited());
        json.addProperty("disabled", status.disabled());
        json.addProperty("exhausted", status.exhausted());
        json.addProperty("storageAvailable", status.storageAvailable());
        return json;
    }

    public synchronized JsonArray principalUsageJson() {
        JsonArray entries = new JsonArray();
        for (PrincipalStatus status : principalUsage()) {
            JsonObject json = new JsonObject();
            json.addProperty("principalKind", status.principalKind().name());
            json.addProperty("promptTokens", status.promptTokens());
            json.addProperty("completionTokens", status.completionTokens());
            json.addProperty("estimatedTokens", status.estimatedTokens());
            json.addProperty("totalTokens", status.totalTokens());
            json.addProperty("requestCount", status.requestCount());
            json.addProperty("estimatedRatio", status.totalTokens() == 0L
                    ? 0.0D : (double) status.estimatedTokens() / (double) status.totalTokens());
            entries.add(json);
        }
        return entries;
    }

    private long effectiveLimit(Long configuredLimit) {
        return configuredLimit == null ? defaultLimit() : configuredLimit;
    }

    private long defaultLimit() {
        try {
            long value = defaultLimitSupplier.getAsLong();
            return value < -1L ? 0L : value;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private long now() {
        try {
            return Math.max(0L, clock.getAsLong());
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static UUID playerId(LlmBillingContext billing) {
        if (billing.principalKind() != LlmBillingContext.PrincipalKind.PLAYER) return null;
        try {
            return UUID.fromString(billing.principalId());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void reducePlayerReservation(UUID playerId, long amount) {
        long remaining = Math.max(0L, reservedByPlayer.getOrDefault(playerId, 0L) - amount);
        if (remaining == 0L) reservedByPlayer.remove(playerId);
        else reservedByPlayer.put(playerId, remaining);
    }

    private void recordPrincipalUsage(LlmBillingContext.PrincipalKind kind,
            LlmRequestAccounting.AttemptUsage usage) {
        PrincipalTotals totals = usageByPrincipal.computeIfAbsent(kind, ignored -> new PrincipalTotals());
        totals.promptTokens = saturatedAdd(totals.promptTokens, usage.promptTokens());
        totals.completionTokens = saturatedAdd(totals.completionTokens, usage.completionTokens());
        totals.estimatedTokens = saturatedAdd(totals.estimatedTokens, usage.estimatedTokens());
        totals.requestCount = saturatedAdd(totals.requestCount, 1L);
    }

    private void pruneExpiredPending(long nowMs) {
        Iterator<Map.Entry<String, Pending>> iterator = pendingById.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next().getValue();
            if (nowMs < pending.expiresAtMs()) continue;
            iterator.remove();
            ChainState chain = chains.get(pending.chainKey());
            if (chain != null) {
                chain.reservedTokens = Math.max(0L, chain.reservedTokens - pending.reservedTokens());
                chain.usedTokens = saturatedAdd(chain.usedTokens, pending.reservedTokens());
                chain.touchedAtMs = nowMs;
            }
            if (pending.playerId() != null) {
                reducePlayerReservation(pending.playerId(), pending.reservedTokens());
            }
            LlmCoreMod.LOGGER.warn("Released expired LLM token reservation for causal root {}",
                    pending.chainKey().rootId());
        }
    }

    private void pruneCompletedChains(long nowMs) {
        if (chains.size() < PRUNE_THRESHOLD) return;
        Iterator<Map.Entry<ChainKey, ChainState>> iterator = chains.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChainKey, ChainState> entry = iterator.next();
            ChainState chain = entry.getValue();
            if (chain.reservedTokens == 0L && nowMs - chain.touchedAtMs > COMPLETED_CHAIN_RETENTION_MS) {
                iterator.remove();
                PrincipalBucket principal = entry.getKey().principal();
                int remaining = chainCounts.getOrDefault(principal, 1) - 1;
                if (remaining <= 0) chainCounts.remove(principal);
                else chainCounts.put(principal, remaining);
            }
        }
    }

    private static PrincipalBucket principalBucket(LlmBillingContext billing) {
        String principalId = billing.principalKind() == LlmBillingContext.PrincipalKind.PLAYER
                ? billing.principalId() : "";
        return new PrincipalBucket(billing.principalKind(), principalId);
    }

    private static LlmRequestAccounting.Reservation deny(
            LlmRequestAccounting.DenyCode code, String reason) {
        return LlmRequestAccounting.Reservation.deny(code, reason);
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
