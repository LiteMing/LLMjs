package vibe.liteming.llmjs.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Shared translation and tooltip helpers for the LLM Console. */
public final class ConsoleTexts {
    private static final String PREFIX = "gui.llmjs.";

    private ConsoleTexts() {
    }

    public static Component text(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }

    public static String string(String key, Object... args) {
        return text(key, args).getString();
    }

    public static <T extends AbstractWidget> T tooltip(T widget, String key, Object... args) {
        widget.setTooltip(Tooltip.create(text(key, args)));
        return widget;
    }
}
