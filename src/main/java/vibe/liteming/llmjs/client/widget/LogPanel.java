package vibe.liteming.llmjs.client.widget;

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
    private int selectedIndex = -1;
    private int detailScroll = 0;
    private static final int LINE_HEIGHT = 12;
    private static final int MAX_ENTRIES = 300;

    private record LogDisplayEntry(
            String text,
            int color,
            String purpose,
            String requestBody,
            String responseBody,
            String error) {}

    public LogPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Log"));
    }

    public void clear() {
        entries.clear();
        selectedIndex = -1;
        scrollOffset = 0;
        detailScroll = 0;
    }

    public void setHistory(List<String> history) {
        clear();
        if (history == null) return;
        for (String json : history) {
            addEntry(json, false);
        }
        autoScrollToBottom();
    }

    public void addEntry(String logEntryJson) {
        addEntry(logEntryJson, true);
    }

    private void addEntry(String logEntryJson, boolean autoScroll) {
        try {
            JsonObject obj = JsonParser.parseString(logEntryJson).getAsJsonObject();
            String level = obj.has("level") ? obj.get("level").getAsString() : "INFO";
            String provider = obj.has("provider") ? obj.get("provider").getAsString() : "?";
            String status = obj.has("status") ? obj.get("status").getAsString() : "";
            long latency = obj.has("latencyMs") ? obj.get("latencyMs").getAsLong() : 0;
            String summary = obj.has("requestSummary") ? obj.get("requestSummary").getAsString() : "";
            String purpose = obj.has("purpose") ? obj.get("purpose").getAsString() : "";
            String source = obj.has("source") ? obj.get("source").getAsString() : "";
            String requestBody = obj.has("requestBody") ? obj.get("requestBody").getAsString() : "";
            String responseBody = obj.has("responseBody") ? obj.get("responseBody").getAsString() : "";
            String error = obj.has("error") ? obj.get("error").getAsString() : "";

            int color = switch (level) {
                case "ERROR" -> 0xFF5555;
                case "WARN" -> 0xFFFF55;
                default -> 0xFFFFFF;
            };

            String tag = purpose.isBlank() ? "" : purpose + " ";
            String src = source.isBlank() ? "" : source + " ";
            String text = String.format("[%s] %s%s%s | %s | %dms | %s", level, src, tag, provider, status, latency, summary);
            entries.add(new LogDisplayEntry(text, color, purpose, requestBody, responseBody, error));
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
                if (selectedIndex >= 0) selectedIndex--;
            }
            if (autoScroll) autoScrollToBottom();
        } catch (Exception ignored) {}
    }

    private void autoScrollToBottom() {
        int listH = listHeight();
        int maxVisible = Math.max(1, listH / LINE_HEIGHT);
        if (entries.size() > maxVisible) {
            scrollOffset = entries.size() - maxVisible;
        }
    }

    private int listHeight() {
        return Math.max(40, (int) (height * 0.42f));
    }

    private int detailTop() {
        return getY() + listHeight() + 2;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x90000000);

        int listH = listHeight();
        graphics.fill(getX(), getY(), getX() + width, getY() + listH, 0x40000000);
        graphics.drawString(font, "Logs  (click row = view + copy raw JSON)", getX() + 4, getY() + 2, 0xAAAAAA, false);

        int contentY = getY() + 14;
        int contentH = listH - 16;
        if (entries.isEmpty()) {
            graphics.drawString(font, "No log entries yet. CreatureChat / LLMjs requests will appear here.",
                    getX() + 4, contentY, 0x888888, false);
        } else {
            int maxVisible = Math.max(1, contentH / LINE_HEIGHT);
            int startIdx = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - maxVisible)));
            scrollOffset = startIdx;
            int endIdx = Math.min(entries.size(), startIdx + maxVisible);
            for (int i = startIdx; i < endIdx; i++) {
                LogDisplayEntry entry = entries.get(i);
                int drawY = contentY + (i - startIdx) * LINE_HEIGHT;
                if (i == selectedIndex) {
                    graphics.fill(getX() + 1, drawY - 1, getX() + width - 1, drawY + LINE_HEIGHT - 1, 0x553388FF);
                }
                String line = font.plainSubstrByWidth(entry.text, width - 10);
                graphics.drawString(font, line, getX() + 4, drawY, entry.color, false);
            }
        }

        // Detail pane
        int dTop = detailTop();
        graphics.fill(getX(), dTop, getX() + width, getY() + height, 0x50000000);
        graphics.drawString(font, "Raw request / response", getX() + 4, dTop + 2, 0x88CCFF, false);
        List<String> detailLines = buildDetailLines();
        int dContentY = dTop + 14;
        int dContentH = getY() + height - dContentY - 2;
        int maxDetail = Math.max(1, dContentH / LINE_HEIGHT);
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, detailLines.size() - maxDetail)));
        int end = Math.min(detailLines.size(), detailScroll + maxDetail);
        for (int i = detailScroll; i < end; i++) {
            String line = font.plainSubstrByWidth(detailLines.get(i), width - 10);
            graphics.drawString(font, line, getX() + 4, dContentY + (i - detailScroll) * LINE_HEIGHT, 0xDDDDDD, false);
        }
        if (selectedIndex < 0) {
            graphics.drawString(font, "Select a log row above to view + auto-copy full JSON.", getX() + 4, dContentY, 0x666666, false);
        } else {
            graphics.drawString(font, "Copied to clipboard", getX() + width - 110, dTop + 2, 0x55FF55, false);
        }
    }

    private List<String> buildDetailLines() {
        List<String> lines = new ArrayList<>();
        if (selectedIndex < 0 || selectedIndex >= entries.size()) return lines;
        LogDisplayEntry e = entries.get(selectedIndex);
        if (e.purpose != null && !e.purpose.isBlank()) lines.add("purpose: " + e.purpose);
        if (e.error != null && !e.error.isBlank()) {
            lines.add("error: " + e.error);
        }
        lines.add("--- REQUEST ---");
        wrapInto(lines, e.requestBody == null || e.requestBody.isBlank() ? "(empty)" : e.requestBody);
        lines.add("--- RESPONSE ---");
        wrapInto(lines, e.responseBody == null || e.responseBody.isBlank() ? "(empty)" : e.responseBody);
        return lines;
    }

    private void wrapInto(List<String> lines, String text) {
        String[] raw = text.replace("\r", "").split("\n", -1);
        for (String row : raw) {
            if (row.length() <= 180) {
                lines.add(row);
            } else {
                for (int i = 0; i < row.length(); i += 180) {
                    lines.add(row.substring(i, Math.min(row.length(), i + 180)));
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !isMouseOver(mouseX, mouseY) || button != 0) return false;
        int listH = listHeight();
        if (mouseY >= getY() + 14 && mouseY < getY() + listH && !entries.isEmpty()) {
            int contentY = getY() + 14;
            int maxVisible = Math.max(1, (listH - 16) / LINE_HEIGHT);
            int row = (int) ((mouseY - contentY) / LINE_HEIGHT);
            int idx = scrollOffset + row;
            if (idx >= 0 && idx < entries.size() && row < maxVisible) {
                selectedIndex = idx;
                detailScroll = 0;
                copySelectedToClipboard();
                return true;
            }
        }
        return true;
    }

    private void copySelectedToClipboard() {
        if (selectedIndex < 0 || selectedIndex >= entries.size()) return;
        LogDisplayEntry e = entries.get(selectedIndex);
        StringBuilder sb = new StringBuilder();
        if (e.purpose != null && !e.purpose.isBlank()) sb.append("purpose: ").append(e.purpose).append('\n');
        if (e.error != null && !e.error.isBlank()) sb.append("error: ").append(e.error).append('\n');
        sb.append("--- REQUEST ---\n");
        sb.append(e.requestBody == null || e.requestBody.isBlank() ? "(empty)" : e.requestBody);
        sb.append("\n--- RESPONSE ---\n");
        sb.append(e.responseBody == null || e.responseBody.isBlank() ? "(empty)" : e.responseBody);
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
        } catch (Exception ignored) {}
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false;
        int listH = listHeight();
        if (mouseY < getY() + listH) {
            int maxVisible = Math.max(1, (listH - 16) / LINE_HEIGHT);
            scrollOffset -= (int) delta * 3;
            scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - maxVisible)));
        } else {
            detailScroll -= (int) delta * 3;
            detailScroll = Math.max(0, detailScroll);
        }
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
