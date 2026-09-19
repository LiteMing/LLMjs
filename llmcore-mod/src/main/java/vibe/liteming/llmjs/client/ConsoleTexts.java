package vibe.liteming.llmjs.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import vibe.liteming.llmcore.LlmTarget;

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

    /** Converts a persisted provider/model target id into text suitable for Console display. */
    public static String targetDisplayName(String targetId) {
        if (targetId == null || targetId.isBlank()) return targetId == null ? "" : targetId;
        try {
            return LlmTarget.parse(targetId).displayName();
        } catch (RuntimeException invalidTarget) {
            return targetId;
        }
    }

    public static <T extends AbstractWidget> T tooltip(T widget, String key, Object... args) {
        widget.setTooltip(Tooltip.create(text(key, args)));
        return widget;
    }
}
