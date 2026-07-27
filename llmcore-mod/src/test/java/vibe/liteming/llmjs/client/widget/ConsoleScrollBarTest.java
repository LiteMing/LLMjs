package vibe.liteming.llmjs.client.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleScrollBarTest {
    @Test
    void noOverflowUsesWholeTrack() {
        assertEquals(0, ConsoleScrollBar.maxOffset(180, 220));
        assertEquals(120, ConsoleScrollBar.thumbHeight(120, 220, 180));
    }

    @Test
    void overflowUsesProportionalThumbAndPixelRange() {
        assertEquals(300, ConsoleScrollBar.maxOffset(500, 200));
        assertEquals(48, ConsoleScrollBar.thumbHeight(120, 200, 500));
    }

    @Test
    void veryLongContentKeepsThumbUsable() {
        assertEquals(16, ConsoleScrollBar.thumbHeight(100, 100, 10_000));
    }
}
