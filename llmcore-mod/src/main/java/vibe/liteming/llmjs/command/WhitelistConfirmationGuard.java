package vibe.liteming.llmjs.command;

import java.util.HashMap;
import java.util.Map;

final class WhitelistConfirmationGuard {
    private final long windowMillis;
    private final Map<String, Pending> pendingByActor = new HashMap<>();

    WhitelistConfirmationGuard(long windowMillis) {
        if (windowMillis <= 0) throw new IllegalArgumentException("windowMillis must be positive");
        this.windowMillis = windowMillis;
    }

    synchronized boolean confirmOrArm(String actor, String operation, long nowMillis) {
        pendingByActor.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < nowMillis);
        Pending pending = pendingByActor.get(actor);
        if (pending != null
                && pending.expiresAtMillis >= nowMillis
                && pending.operation.equals(operation)) {
            pendingByActor.remove(actor);
            return true;
        }

        pendingByActor.put(actor, new Pending(operation, nowMillis + windowMillis));
        return false;
    }

    private record Pending(String operation, long expiresAtMillis) {
    }
}
