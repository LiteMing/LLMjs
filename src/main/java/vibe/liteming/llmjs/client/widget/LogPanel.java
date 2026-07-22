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
    private int selectionAnchor = -1;
    private int selectionEnd = -1;
    private int detailScroll = 0;
    private boolean draggingSelect = false;
    private long copyFlashUntilMs = 0L;
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
        selectionAnchor = -1;
        selectionEnd = -1;
        scrollOffset = 0;
        detailScroll = 0;
        draggingSelect = false;
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
                if (selectionAnchor >= 0) selectionAnchor = Math.max(-1, selectionAnchor - 1);
                if (selectionEnd >= 0) selectionEnd = Math.max(-1, selectionEnd - 1);
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

    private int selectionLow() {
        if (selectionAnchor < 0 || selectionEnd < 0) return -1;
        return Math.min(selectionAnchor, selectionEnd);
    }

    private int selectionHigh() {
        if (selectionAnchor < 0 || selectionEnd < 0) return -1;
        return Math.max(selectionAnchor, selectionEnd);
    }

    private boolean isSelected(int index) {
        int low = selectionLow();
        int high = selectionHigh();
        return low >= 0 && index >= low && index <= high;
    }

    private int rowIndexAt(double mouseY) {
        int listH = listHeight();
        int contentY = getY() + 14;
        if (mouseY < contentY || mouseY >= getY() + listH || entries.isEmpty()) return -1;
        int maxVisible = Math.max(1, (listH - 16) / LINE_HEIGHT);
        int row = (int) ((mouseY - contentY) / LINE_HEIGHT);
        if (row < 0 || row >= maxVisible) return -1;
        int idx = scrollOffset + row;
        return (idx >= 0 && idx < entries.size()) ? idx : -1;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x90000000);

        int listH = listHeight();
        graphics.fill(getX(), getY(), getX() + width, getY() + listH, 0x40000000);
        graphics.drawString(font, "Logs  (hold LMB select, RMB copy)", getX() + 4, getY() + 2, 0xAAAAAA, false);

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
                if (isSelected(i)) {
                    graphics.fill(getX() + 1, drawY - 1, getX() + width - 1, drawY + LINE_HEIGHT - 1, 0x553388FF);
                }
                String line = font.plainSubstrByWidth(entry.text, width - 10);
                graphics.drawString(font, line, getX() + 4, drawY, entry.color, false);
            }
        }

        int dTop = detailTop();
        graphics.fill(getX(), dTop, getX() + width, getY() + height, 0x50000000);
        int low = selectionLow();
        int high = selectionHigh();
        String title = low < 0 ? "Raw request / response"
                : (low == high ? "Raw request / response"
                : "Selection " + (low + 1) + "-" + (high + 1) + " (" + (high - low + 1) + " rows)");
        graphics.drawString(font, title, getX() + 4, dTop + 2, 0x88CCFF, false);

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
        if (low < 0) {
            graphics.drawString(font, "Hold left mouse to select rows, right-click to copy JSON.",
                    getX() + 4, dContentY, 0x666666, false);
        } else if (System.currentTimeMillis() < copyFlashUntilMs) {
            graphics.drawString(font, "Copied to clipboard", getX() + width - 120, dTop + 2, 0x55FF55, false);
        }
    }

    private List<String> buildDetailLines() {
        List<String> lines = new ArrayList<>();
        int low = selectionLow();
        int high = selectionHigh();
        if (low < 0 || high >= entries.size()) return lines;
        if (low == high) {
            appendEntryDetail(lines, entries.get(low), low);
        } else {
            for (int i = low; i <= high; i++) {
                lines.add("===== ENTRY " + (i + 1) + " =====");
                appendEntryDetail(lines, entries.get(i), i);
            }
        }
        return lines;
    }

    private void appendEntryDetail(List<String> lines, LogDisplayEntry e, int index) {
        lines.add(e.text);
        if (e.purpose != null && !e.purpose.isBlank()) lines.add("purpose: " + e.purpose);
        if (e.error != null && !e.error.isBlank()) lines.add("error: " + e.error);
        lines.add("--- REQUEST ---");
        wrapInto(lines, e.requestBody == null || e.requestBody.isBlank() ? "(empty)" : e.requestBody);
        lines.add("--- RESPONSE ---");
        wrapInto(lines, e.responseBody == null || e.responseBody.isBlank() ? "(empty)" : e.responseBody);
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
        if (!visible || !isMouseOver(mouseX, mouseY)) return false;

        // Right-click: copy current selection (or row under cursor)
        if (button == 1) {
            int under = rowIndexAt(mouseY);
            if (under >= 0 && !isSelected(under)) {
                selectionAnchor = under;
                selectionEnd = under;
                detailScroll = 0;
            }
            copySelectionToClipboard();
            return true;
        }

        if (button != 0) return false;
        int listH = listHeight();
        if (mouseY >= getY() + 14 && mouseY < getY() + listH) {
            int idx = rowIndexAt(mouseY);
            if (idx >= 0) {
                selectionAnchor = idx;
                selectionEnd = idx;
                detailScroll = 0;
                draggingSelect = true;
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!visible || !draggingSelect || button != 0) return false;
        int idx = rowIndexAt(mouseY);
        if (idx < 0) {
            // clamp to visible list edges while dragging outside
            int listH = listHeight();
            if (mouseY < getY() + 14) {
                idx = scrollOffset;
            } else if (mouseY >= getY() + listH) {
                int maxVisible = Math.max(1, (listH - 16) / LINE_HEIGHT);
                idx = Math.min(entries.size() - 1, scrollOffset + maxVisible - 1);
            }
        }
        if (idx >= 0 && idx < entries.size()) {
            selectionEnd = idx;
            // auto-scroll while dragging near edges
            int listH = listHeight();
            int maxVisible = Math.max(1, (listH - 16) / LINE_HEIGHT);
            if (mouseY < getY() + 20 && scrollOffset > 0) {
                scrollOffset--;
            } else if (mouseY > getY() + listH - 6 && scrollOffset + maxVisible < entries.size()) {
                scrollOffset++;
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) draggingSelect = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void copySelectionToClipboard() {
        int low = selectionLow();
        int high = selectionHigh();
        if (low < 0 || high >= entries.size()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = low; i <= high; i++) {
            if (i > low) sb.append("\n\n");
            LogDisplayEntry e = entries.get(i);
            sb.append("===== ENTRY ").append(i + 1).append(" =====\n");
            sb.append(e.text).append('\n');
            if (e.purpose != null && !e.purpose.isBlank()) sb.append("purpose: ").append(e.purpose).append('\n');
            if (e.error != null && !e.error.isBlank()) sb.append("error: ").append(e.error).append('\n');
            sb.append("--- REQUEST ---\n");
            sb.append(e.requestBody == null || e.requestBody.isBlank() ? "(empty)" : e.requestBody);
            sb.append("\n--- RESPONSE ---\n");
            sb.append(e.responseBody == null || e.responseBody.isBlank() ? "(empty)" : e.responseBody);
        }
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
            copyFlashUntilMs = System.currentTimeMillis() + 1500L;
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
