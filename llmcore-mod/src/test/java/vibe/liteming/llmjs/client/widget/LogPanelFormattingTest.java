package vibe.liteming.llmjs.client.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogPanelFormattingTest {
    @Test
    void compactRequestJsonBecomesReadableWithoutDroppingMessages() {
        String body = "{\"model\":\"demo\",\"messages\":[{\"role\":\"system\",\"content\":\"alpha\"},"
                + "{\"role\":\"user\",\"content\":\"beta\"}]}";

        String formatted = LogPanel.formatBodyForDisplay(body);

        assertTrue(formatted.contains("\n"));
        assertTrue(formatted.contains("\"content\": \"alpha\""));
        assertTrue(formatted.contains("\"content\": \"beta\""));
    }

    @Test
    void invalidOrPlainBodiesRemainByteForByteReadable() {
        assertEquals("not-json\nsecond line", LogPanel.formatBodyForDisplay("not-json\nsecond line"));
        assertEquals("{broken", LogPanel.formatBodyForDisplay("{broken"));
        assertEquals("(empty)", LogPanel.formatBodyForDisplay("  "));
    }
}
