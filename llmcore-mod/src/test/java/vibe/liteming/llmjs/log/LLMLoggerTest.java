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

    private void log(String requestId) {
        logger.logExternal("test", "CHAT", requestId, "provider", true,
                1L, 2, 3, "summary", "request", "response", null);
    }
}
