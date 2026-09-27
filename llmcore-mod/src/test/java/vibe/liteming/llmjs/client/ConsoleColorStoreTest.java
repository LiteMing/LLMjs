package vibe.liteming.llmjs.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConsoleColorStoreTest {
    @Test
    void acceptsRgbAndArgbForms() {
        assertEquals(0xFFFFFFFF, ConsoleColorStore.parseColor("FFFFFF"));
        assertEquals(0xFF55AAFF, ConsoleColorStore.parseColor("#55AAFF"));
        assertEquals(0x8055AAFF, ConsoleColorStore.parseColor("0x8055aaff"));
        assertEquals("8055AAFF", ConsoleColorStore.formatColor(0x8055AAFF));
    }

    @Test
    void rejectsIncompleteOrNonHexColors() {
        assertNull(ConsoleColorStore.parseColor("FFF"));
        assertNull(ConsoleColorStore.parseColor("GG55AA"));
        assertNull(ConsoleColorStore.parseColor(""));
    }

    @Test
    void automaticColorsAreStableAndOpaque() {
        int first = ConsoleColorStore.automaticColor("CHAT", 0);
        assertEquals(first, ConsoleColorStore.automaticColor("CHAT", 0));
        assertEquals(0xFF000000, first & 0xFF000000);
        assertEquals(0xFF55AAFF, ConsoleColorStore.automaticColor("", 0xFF55AAFF));
    }
}
