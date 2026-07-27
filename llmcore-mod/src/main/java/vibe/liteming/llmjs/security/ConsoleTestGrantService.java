package vibe.liteming.llmjs.security;

import vibe.liteming.llmjs.test.ConsoleTestCodec;
import vibe.liteming.llmjs.test.ConsoleTestRequest;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived server grants for immutable Test handoffs issued by trusted mods. */
public final class ConsoleTestGrantService {
    public static final ConsoleTestGrantService INSTANCE = new ConsoleTestGrantService();
    public static final long DEFAULT_TTL_MS = 5 * 60_000L;

    public record Grant(UUID playerId, ConsoleTestRequest request, long expiresAtMs) {
    }

    private final Map<UUID, Grant> grants = new ConcurrentHashMap<>();
    private final long ttlMs;

    public ConsoleTestGrantService() {
        this(DEFAULT_TTL_MS);
    }

    ConsoleTestGrantService(long ttlMs) {
        if (ttlMs <= 0L) throw new IllegalArgumentException("ttlMs must be positive");
        this.ttlMs = ttlMs;
    }

    public boolean issue(String playerId, String handoffJson) {
        return issue(playerId, handoffJson, System.currentTimeMillis());
    }

    boolean issue(String playerId, String handoffJson, long nowMs) {
        final UUID playerUuid;
        final ConsoleTestRequest request;
        try {
            playerUuid = UUID.fromString(playerId);
            request = ConsoleTestCodec.parseRequest(handoffJson, false);
        } catch (RuntimeException invalid) {
            return false;
        }
        grants.put(playerUuid, new Grant(playerUuid, request, nowMs + ttlMs));
        return true;
    }

    public boolean hasActiveGrant(UUID playerId) {
        return activeGrant(playerId, System.currentTimeMillis()).isPresent();
    }

    public Optional<Grant> activeGrant(UUID playerId) {
        return activeGrant(playerId, System.currentTimeMillis());
    }

    Optional<Grant> activeGrant(UUID playerId, long nowMs) {
        if (playerId == null) return Optional.empty();
        Grant grant = grants.get(playerId);
        if (grant == null) return Optional.empty();
        if (nowMs <= grant.expiresAtMs()) return Optional.of(grant);
        grants.remove(playerId, grant);
        return Optional.empty();
    }

    public Optional<ConsoleTestRequest> authorizedRequest(UUID playerId, UUID requestId) {
        return activeGrant(playerId)
                .filter(grant -> grant.request().requestUuid().equals(requestId))
                .map(Grant::request);
    }

    public void clear() {
        grants.clear();
    }
}
