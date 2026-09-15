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

    @Test
    void expandsEscapedMessageTextForHumanCopy() {
        String formatted = LogPanel.formatBodyForDisplay(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"one\\n two\"}]}" );
        assertTrue(formatted.contains("one\n two"));
    }
    @Test
    void literalBackslashesAreNotInterpretedAsExtraEscapes() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("content", "长提示词".repeat(8_000) + "first\nsecond\r\n\tend");
        body.addProperty("path", "C:\\new\\test.txt");
        body.addProperty("literal", "\\n");
        String formatted = LogPanel.formatBodyForDisplay(body.toString());
        assertTrue(formatted.contains("first\nsecond\n    end"));
        assertTrue(formatted.contains("C:\\\\new\\\\test.txt"));
        assertTrue(formatted.contains("\"literal\": \"\\\\n\""));
    }
}
