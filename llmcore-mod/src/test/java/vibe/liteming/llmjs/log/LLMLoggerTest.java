package vibe.liteming.llmjs.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMLoggerTest {
    private final LLMLogger logger = LLMLogger.INSTANCE;

    @BeforeEach
    @AfterEach
    void clearLogger() {
        logger.clear();
    }

    @Test
    void removesEntriesByStableRequestIdentityAndPreservesNeighbors() {
        log("request-a");
        log("request-b");
        log("request-c");

        assertTrue(logger.removeByRequestId("request-b"));
        assertEquals(2, logger.getRecentEntries().length);
        assertEquals("request-a", logger.getRecentEntries()[0].requestId());
        assertEquals("request-c", logger.getRecentEntries()[1].requestId());
        assertFalse(logger.removeByRequestId("request-b"));
    }

    @Test
    void clearResetsTheAuthoritativeHistory() {
        log("request-a");
        log("request-b");

        logger.clear();

        assertEquals(0, logger.getRecentEntries().length);
        log("request-c");
        assertEquals("request-c", logger.getRecentEntries()[0].requestId());
    }

    @Test
    void serializesAddresseeSeparatelyFromTriggerAndAudience() {
        logger.log(LLMLogger.Level.INFO, "provider", "summary", "success",
                1L, 2, 3, null, "CHAT", "request", "test", "request", "response",
                "stop", 8, "preview", "npc", "Reimu", "player-event",
                "PLAYER:alex", "PUBLIC", "chat", "PLAYER", "player-id", "root");

        var json = logger.getRecentEntries()[0].toJson();
        assertEquals("player-event", json.get("triggerSource").getAsString());
        assertEquals("PLAYER:alex", json.get("addressee").getAsString());
        assertEquals("PUBLIC", json.get("audience").getAsString());
    }

    private void log(String requestId) {
        logger.logExternal("test", "CHAT", requestId, "provider", true,
                1L, 2, 3, "summary", "request", "response", null);
    }
}
