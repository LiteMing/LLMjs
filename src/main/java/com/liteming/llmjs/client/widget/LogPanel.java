package com.liteming.llmjs.client.widget;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class LogPanel extends AbstractWidget {
    private final List<LogDisplayEntry> entries = new ArrayList<>();
    private int scrollOffset = 0;
    private static final int LINE_HEIGHT = 12;

    private record LogDisplayEntry(String text, int color) {}

    public LogPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Log"));
    }

    public void addEntry(String logEntryJson) {
        try {
            JsonObject obj = JsonParser.parseString(logEntryJson).getAsJsonObject();
            String level = obj.has("level") ? obj.get("level").getAsString() : "INFO";
            String provider = obj.has("provider") ? obj.get("provider").getAsString() : "?";
            String status = obj.has("status") ? obj.get("status").getAsString() : "";
            long latency = obj.has("latencyMs") ? obj.get("latencyMs").getAsLong() : 0;
            String summary = obj.has("requestSummary") ? obj.get("requestSummary").getAsString() : "";

            int color = switch (level) {
                case "ERROR" -> 0xFF5555;
                case "WARN" -> 0xFFFF55;
                default -> 0xFFFFFF;
            };

            String text = String.format("[%s] %s | %s | %dms | %s", level, provider, status, latency, summary);
            entries.add(new LogDisplayEntry(text, color));

            int maxVisible = (height - 4) / LINE_HEIGHT;
            if (entries.size() > maxVisible) {
                scrollOffset = entries.size() - maxVisible;
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x80000000);

        if (entries.isEmpty()) {
            graphics.drawString(Minecraft.getInstance().font, "No log entries yet...",
                    getX() + 4, getY() + 4, 0x888888, false);
            return;
        }

        int maxVisible = (height - 4) / LINE_HEIGHT;
        int startIdx = Math.max(0, scrollOffset);
        int endIdx = Math.min(entries.size(), startIdx + maxVisible);

        for (int i = startIdx; i < endIdx; i++) {
            LogDisplayEntry entry = entries.get(i);
            int drawY = getY() + 2 + (i - startIdx) * LINE_HEIGHT;
            graphics.drawString(Minecraft.getInstance().font,
                    entry.text, getX() + 4, drawY, entry.color, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible) return false;
        scrollOffset -= (int) delta * 3;
        int maxVisible = (height - 4) / LINE_HEIGHT;
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - maxVisible)));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
