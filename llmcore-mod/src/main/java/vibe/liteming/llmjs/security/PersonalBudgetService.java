package vibe.liteming.llmjs.security;

import com.google.gson.JsonObject;
import vibe.liteming.llmcore.LlmBillingContext;
import vibe.liteming.llmcore.LlmRequest;
import vibe.liteming.llmcore.LlmRequestAccounting;
import vibe.liteming.llmcore.mod.LlmCoreMod;
import vibe.liteming.llmjs.config.LLMConfig;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Global chain ceilings plus persistent per-player token accounting. */
public final class PersonalBudgetService implements LlmRequestAccounting.Policy {
    public static final PersonalBudgetService INSTANCE =
            new PersonalBudgetService(() -> LLMConfig.PERSONAL_BUDGET_LIMIT.get());
    private static final long COMPLETED_CHAIN_RETENTION_MS = 60L * 60L * 1_000L;
    private static final int PRUNE_THRESHOLD = 4_096;
    private static final int MAX_CHAIN_STATES = 16_384;

    public record Status(UUID playerId, long promptTokens, long completionTokens,
            long estimatedTokens, long totalTokens, long reservedTokens, long limitTokens,
            boolean exhausted, boolean storageAvailable) {
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

    private record Pending(String rootId, UUID playerId, long reservedTokens) {
    }

    private final LongSupplier limitSupplier;
    private final Map<String, ChainState> chains = new HashMap<>();
    private final Map<String, Pending> pendingById = new HashMap<>();
    private final Map<UUID, Long> reservedByPlayer = new HashMap<>();
    private volatile PersonalBudgetLedger ledger;

    PersonalBudgetService(LongSupplier limitSupplier) {
        this.limitSupplier = limitSupplier;
    }

    public synchronized void open(Path file) {
        ledger = new PersonalBudgetLedger(file);
        chains.clear();
        pendingById.clear();
        reservedByPlayer.clear();
    }

    public synchronized void close() {
        ledger = null;
        chains.clear();
        pendingById.clear();
        reservedByPlayer.clear();
    }

    @Override
    public synchronized LlmRequestAccounting.Reservation reserve(
            LlmRequest request, LlmRequestAccounting.AttemptEstimate estimate) {
        LlmBillingContext billing = request == null
                ? LlmBillingContext.unspecified() : request.billingContext();
        String validationError = billing.validationError();
        if (!validationError.isEmpty()) return LlmRequestAccounting.Reservation.deny(validationError);
        if (estimate == null || estimate.outputTokens() <= 0L || estimate.totalTokens() <= 0L) {
            return LlmRequestAccounting.Reservation.deny(
                    "Provider attempt has no bounded output token reservation");
        }

        long nowMs = System.currentTimeMillis();
        pruneCompletedChains(nowMs);
        String rootId = billing.causalRootRequestId();
        if (!chains.containsKey(rootId) && chains.size() >= MAX_CHAIN_STATES) {
            return LlmRequestAccounting.Reservation.deny("Causal chain registry capacity reached");
        }
        ChainState chain = chains.computeIfAbsent(rootId, ignored -> new ChainState(billing, nowMs));
        chain.maxCalls = Math.min(chain.maxCalls, billing.maxCalls());
        chain.maxTokens = Math.min(chain.maxTokens, billing.maxTokens());
        chain.touchedAtMs = nowMs;
        long reservationTokens = estimate.totalTokens();
        if (chain.calls >= chain.maxCalls) {
            return LlmRequestAccounting.Reservation.deny("Causal chain call budget exhausted");
        }
        if (saturatedAdd(saturatedAdd(chain.usedTokens, chain.reservedTokens), reservationTokens)
                > chain.maxTokens) {
            return LlmRequestAccounting.Reservation.deny("Causal chain token budget exhausted");
        }

        UUID playerId = playerId(billing);
        long personalLimit = limit();
        PersonalBudgetLedger active = ledger;
        if (playerId != null && personalLimit > 0L) {
            if (active == null || !active.isWritable()) {
                return LlmRequestAccounting.Reservation.deny(
                        "Personal token budget storage is unavailable");
            }
            long settled = active.usage(playerId).totalTokens();
            long reserved = reservedByPlayer.getOrDefault(playerId, 0L);
            if (saturatedAdd(saturatedAdd(settled, reserved), reservationTokens) > personalLimit) {
                return LlmRequestAccounting.Reservation.deny("Personal token budget exhausted");
            }
        }

        String reservationId = UUID.randomUUID().toString();
        chain.calls++;
        chain.reservedTokens = saturatedAdd(chain.reservedTokens, reservationTokens);
        pendingById.put(reservationId, new Pending(rootId, playerId, reservationTokens));
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
        ChainState chain = chains.get(pending.rootId());
        if (chain != null) {
            chain.reservedTokens = Math.max(0L, chain.reservedTokens - pending.reservedTokens());
            chain.usedTokens = saturatedAdd(chain.usedTokens, actualTokens);
            chain.touchedAtMs = System.currentTimeMillis();
        }
        if (pending.playerId() == null) return;
        reducePlayerReservation(pending.playerId(), pending.reservedTokens());
        PersonalBudgetLedger active = ledger;
        if (active == null || usage == null || actualTokens <= 0L) return;
        try {
            active.record(pending.playerId(), usage.promptTokens(), usage.completionTokens(),
                    usage.estimatedTokens(), System.currentTimeMillis());
        } catch (RuntimeException failure) {
            LlmCoreMod.LOGGER.error("Failed to persist personal token usage for {}: {}",
                    pending.playerId(), failure.toString());
        }
    }

    public synchronized Status status(UUID playerId) {
        PersonalBudgetLedger active = ledger;
        long limit = limit();
        long reserved = playerId == null ? 0L : reservedByPlayer.getOrDefault(playerId, 0L);
        if (active == null || playerId == null) {
            return new Status(playerId, 0L, 0L, 0L, 0L, reserved, limit,
                    limit > 0L && active == null, false);
        }
        PersonalBudgetLedger.Usage usage = active.usage(playerId);
        long total = usage.totalTokens();
        return new Status(playerId, usage.promptTokens(), usage.completionTokens(),
                usage.estimatedTokens(), total, reserved, limit,
                limit > 0L && saturatedAdd(total, reserved) >= limit, active.isWritable());
    }

    public synchronized List<Status> list() {
        PersonalBudgetLedger active = ledger;
        if (active == null) return List.of();
        return active.list().stream().map(usage -> status(usage.playerId())).toList();
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

    public JsonObject statusJson(UUID playerId) {
        Status status = status(playerId);
        JsonObject json = new JsonObject();
        json.addProperty("promptTokens", status.promptTokens());
        json.addProperty("completionTokens", status.completionTokens());
        json.addProperty("estimatedTokens", status.estimatedTokens());
        json.addProperty("totalTokens", status.totalTokens());
        json.addProperty("reservedTokens", status.reservedTokens());
        json.addProperty("limitTokens", status.limitTokens());
        json.addProperty("unlimited", status.limitTokens() <= 0L);
        json.addProperty("exhausted", status.exhausted());
        json.addProperty("storageAvailable", status.storageAvailable());
        return json;
    }

    private long limit() {
        try {
            return Math.max(0L, limitSupplier.getAsLong());
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

    private void pruneCompletedChains(long nowMs) {
        if (chains.size() < PRUNE_THRESHOLD) return;
        Iterator<Map.Entry<String, ChainState>> iterator = chains.entrySet().iterator();
        while (iterator.hasNext()) {
            ChainState chain = iterator.next().getValue();
            if (chain.reservedTokens == 0L && nowMs - chain.touchedAtMs > COMPLETED_CHAIN_RETENTION_MS) {
                iterator.remove();
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
