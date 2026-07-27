package vibe.liteming.llmcore;

import java.util.Objects;

/** Trusted cross-mod bridge for server-authorized Console Test handoffs. */
public final class LlmConsoleTestBridge {
    @FunctionalInterface
    public interface Authorizer {
        boolean authorize(String playerId, String handoffJson);
    }

    private static final Authorizer UNAVAILABLE = (playerId, handoffJson) -> false;
    private static volatile Authorizer authorizer = UNAVAILABLE;

    private LlmConsoleTestBridge() {
    }

    public static boolean authorize(String playerId, String handoffJson) {
        String normalizedPlayerId = playerId == null ? "" : playerId.trim();
        if (normalizedPlayerId.isEmpty() || handoffJson == null || handoffJson.isBlank()) return false;
        return authorizer.authorize(normalizedPlayerId, handoffJson);
    }

    /** Installed by the server-side llmcore mod; consumer mods only call {@link #authorize}. */
    public static void install(Authorizer next) {
        authorizer = Objects.requireNonNullElse(next, UNAVAILABLE);
    }

    public static void clear() {
        authorizer = UNAVAILABLE;
    }
}
