package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;

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

    // Detail (raw JSON) area character-level selection.
    // Each point is (logicalLine, charOffset) into unwrappedDetailLines.
    private List<DetailSeg> cachedDetailSegs = List.of();
    private List<String> unwrappedDetailLines = List.of();
    private int detailAnchorLine = -1;
    private int detailAnchorChar = -1;
    private int detailEndLine = -1;
    private int detailEndChar = -1;
    private boolean draggingDetailSelect = false;
    private boolean detailSelectActive = false;
    private int lastDetailListLow = -2;
    private int lastDetailListHigh = -2;

    private record DetailSeg(String text, int logicalLine, int startChar, int endChar) {}

    private record LogDisplayEntry(
            String text,
            int color,
            String purpose,
            String requestBody,
            String responseBody,
            String error,
            String responder,
            String triggerSource,
            String audience,
            String inputKind,
            String finishReason,
            int contentLength) {}

    public LogPanel(int x, int y, int width, int height) {
        super(x, y, width, height, text("tab.log"));
    }

    public void clear() {
        entries.clear();
        selectionAnchor = -1;
        selectionEnd = -1;
        scrollOffset = 0;
        detailScroll = 0;
        draggingSelect = false;
        detailAnchorLine = -1;
        detailAnchorChar = -1;
        detailEndLine = -1;
        detailEndChar = -1;
        draggingDetailSelect = false;
        detailSelectActive = false;
        cachedDetailSegs = List.of();
        unwrappedDetailLines = List.of();
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
        // New entry: detail cache invalid; clear detail selection.
        detailAnchorLine = -1;
        detailAnchorChar = -1;
        detailEndLine = -1;
        detailEndChar = -1;
        detailSelectActive = false;
        cachedDetailSegs = List.of();
        unwrappedDetailLines = List.of();
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
            String responderName = obj.has("responderName") ? obj.get("responderName").getAsString() : "";
            String responderId = obj.has("responderEntityId") ? obj.get("responderEntityId").getAsString() : "";
            String responder = responderName.isBlank() ? responderId : responderName;
            String triggerSource = obj.has("triggerSource") ? obj.get("triggerSource").getAsString() : "";
            String audience = obj.has("audience") ? obj.get("audience").getAsString() : "";
            String inputKind = obj.has("inputKind") ? obj.get("inputKind").getAsString() : "";
            String finishReason = obj.has("finishReason") ? obj.get("finishReason").getAsString() : "";
            int contentLength = obj.has("contentLength") ? obj.get("contentLength").getAsInt() : 0;

            int color = switch (level) {
                case "ERROR" -> 0xFF5555;
                case "WARN" -> 0xFFFF55;
                default -> 0xFFFFFF;
            };

            String tag = purpose.isBlank() ? "" : purpose + " ";
            String src = source.isBlank() ? "" : source + " ";
            String actor = responder.isBlank() ? "" : "[Responder: " + responder + "] ";
            String text = String.format("[%s] %s%s%s%s | %s | %dms | %s", level, src, tag, actor,
                    provider, status, latency, summary);
            entries.add(new LogDisplayEntry(text, color, purpose, requestBody, responseBody, error,
                    responder, triggerSource, audience, inputKind, finishReason, contentLength));
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

    private boolean detailSelectionEmpty() {
        return detailAnchorLine < 0 || detailEndLine < 0
                || (detailAnchorLine == detailEndLine && detailAnchorChar == detailEndChar);
    }

    // Normalized start point (smaller).
    private int[] detailSelStart() {
        if (detailSelectionEmpty()) return null;
        if (detailAnchorLine < detailEndLine
                || (detailAnchorLine == detailEndLine && detailAnchorChar < detailEndChar)) {
            return new int[]{detailAnchorLine, detailAnchorChar};
        }
        return new int[]{detailEndLine, detailEndChar};
    }

    private int[] detailSelEnd() {
        if (detailSelectionEmpty()) return null;
        if (detailAnchorLine < detailEndLine
                || (detailAnchorLine == detailEndLine && detailAnchorChar < detailEndChar)) {
            return new int[]{detailEndLine, detailEndChar};
        }
        return new int[]{detailAnchorLine, detailAnchorChar};
    }

    private void invalidateDetailCache() {
        cachedDetailSegs = List.of();
        unwrappedDetailLines = List.of();
        detailAnchorLine = -1;
        detailAnchorChar = -1;
        detailEndLine = -1;
        detailEndChar = -1;
        detailSelectActive = false;
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

    private int detailSegAt(double mouseY) {
        int dTop = detailTop();
        int dContentY = dTop + 14;
        if (mouseY < dContentY || mouseY >= getY() + height) return -1;
        int total = cachedDetailSegs.size();
        if (total == 0) return -1;
        int row = (int) ((mouseY - dContentY) / LINE_HEIGHT);
        int idx = detailScroll + row;
        return (idx >= 0 && idx < total) ? idx : -1;
    }

    private int detailSegAtClamped(double mouseY) {
        int total = cachedDetailSegs.size();
        if (total == 0) return -1;
        int dTop = detailTop();
        int dContentY = dTop + 14;
        if (mouseY < dContentY) return Math.max(0, detailScroll);
        if (mouseY >= getY() + height) return total - 1;
        int row = (int) ((mouseY - dContentY) / LINE_HEIGHT);
        int idx = detailScroll + row;
        return Math.max(0, Math.min(total - 1, idx));
    }

    // Find char offset within a display text closest to dx (pixels from text start).
    private int charOffsetAtX(String text, double dx) {
        var font = Minecraft.getInstance().font;
        if (dx <= 0) return 0;
        if (font.width(text) <= dx) return text.length();
        int best = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (font.width(text.substring(0, i)) <= dx) best = i; else break;
        }
        return best;
    }

    // Returns {logicalLine, charOffset} for a mouse position (clamped to detail edges),
    // or null if the detail area is empty.
    private int[] detailPointAt(double mouseX, double mouseY) {
        int si = detailSegAtClamped(mouseY);
        if (si < 0) return null;
        DetailSeg seg = cachedDetailSegs.get(si);
        int textX = getX() + 4;
        int local = charOffsetAtX(seg.text(), mouseX - textX);
        return new int[]{seg.logicalLine(), seg.startChar() + local};
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x90000000);

        int listH = listHeight();
        graphics.fill(getX(), getY(), getX() + width, getY() + listH, 0x40000000);
        graphics.drawString(font, text("log.title"), getX() + 4, getY() + 2, 0xAAAAAA, false);

        int contentY = getY() + 14;
        int contentH = listH - 16;
        if (entries.isEmpty()) {
            graphics.drawString(font, text("log.empty"),
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
        String title = low < 0 ? string("log.raw")
                : (low == high ? string("log.raw")
                : string("log.selection", low + 1, high + 1, high - low + 1));
        graphics.drawString(font, title, getX() + 4, dTop + 2, 0x88CCFF, false);

        DetailBuild build = buildDetailLinesBoth();
        List<DetailSeg> segs = build.segs();
        // If the underlying list selection changed, the cached detail is stale;
        // any detail selection indices no longer map correctly, so drop it.
        int curListLow = selectionLow();
        int curListHigh = selectionHigh();
        if (curListLow != lastDetailListLow || curListHigh != lastDetailListHigh) {
            lastDetailListLow = curListLow;
            lastDetailListHigh = curListHigh;
            if (!draggingDetailSelect) {
                detailAnchorLine = -1;
                detailAnchorChar = -1;
                detailEndLine = -1;
                detailEndChar = -1;
                detailSelectActive = false;
            }
        }
        cachedDetailSegs = segs;
        unwrappedDetailLines = build.unwrapped();
        int dContentY = dTop + 14;
        int dContentH = getY() + height - dContentY - 2;
        int maxDetail = Math.max(1, dContentH / LINE_HEIGHT);
        if (!draggingDetailSelect) {
            detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, segs.size() - maxDetail)));
        }
        int end = Math.min(segs.size(), detailScroll + maxDetail);
        int textX = getX() + 4;
        for (int i = detailScroll; i < end; i++) {
            DetailSeg seg = segs.get(i);
            int drawY = dContentY + (i - detailScroll) * LINE_HEIGHT;
            // Visible (pixel-clipped) text actually drawn.
            String visible = font.plainSubstrByWidth(seg.text(), width - 10);
            renderSegHighlight(graphics, font, seg, visible, textX, drawY);
            graphics.drawString(font, visible, textX, drawY, 0xDDDDDD, false);
        }
        if (System.currentTimeMillis() < copyFlashUntilMs) {
            graphics.drawString(font, text("common.copied"), getX() + width - 120, dTop + 2, 0x55FF55, false);
        }
        if (mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + 14) {
            graphics.renderTooltip(font, text("log.title.tip"), mouseX, mouseY);
        } else if (mouseX >= getX() && mouseX < getX() + width
                && mouseY >= dTop && mouseY < dTop + 14) {
            graphics.renderTooltip(font, text("log.raw.tip"), mouseX, mouseY);
        }
    }

    // Draw the highlight for this display segment based on the current char selection.
    private void renderSegHighlight(GuiGraphics graphics, Font font, DetailSeg seg, String visible,
                                    int textX, int drawY) {
        if (detailSelectionEmpty()) return;
        int[] start = detailSelStart();
        int[] end = detailSelEnd();
        int sLine = start[0], sChar = start[1];
        int eLine = end[0], eChar = end[1];
        int li = seg.logicalLine();
        // No overlap with selection range.
        if (li < sLine || li > eLine) return;
        int segLo = seg.startChar();
        int segHi = seg.endChar();
        // Compute overlap [lo, hi) within this logical line in char offsets.
        int lo, hi;
        if (li == sLine && li == eLine) {
            lo = Math.max(segLo, sChar);
            hi = Math.min(segHi, eChar);
        } else if (li == sLine) {
            lo = Math.max(segLo, sChar);
            hi = segHi;
        } else if (li == eLine) {
            lo = segLo;
            hi = Math.min(segHi, eChar);
        } else {
            lo = segLo;
            hi = segHi;
        }
        if (hi <= lo) return;
        // Map to local offsets within the seg's text, clamped to visible length.
        int localLo = lo - segLo;
        int localHi = Math.min(hi - segLo, visible.length());
        if (localHi <= localLo) return;
        int x1 = textX + font.width(seg.text().substring(0, localLo));
        int x2 = textX + font.width(seg.text().substring(0, localHi));
        graphics.fill(x1, drawY - 1, x2, drawY + LINE_HEIGHT - 1, 0x5533AA33);
    }

    private record DetailBuild(List<DetailSeg> segs, List<String> unwrapped) {}

    private DetailBuild buildDetailLinesBoth() {
        List<DetailSeg> segs = new ArrayList<>();
        List<String> unwrapped = new ArrayList<>();
        int low = selectionLow();
        int high = selectionHigh();
        if (low < 0 || high >= entries.size()) return new DetailBuild(segs, unwrapped);
        if (low == high) {
            appendEntryDetailSegs(segs, unwrapped, entries.get(low));
        } else {
            for (int i = low; i <= high; i++) {
                addLineSeg(segs, unwrapped, "===== ENTRY " + (i + 1) + " =====");
                appendEntryDetailSegs(segs, unwrapped, entries.get(i));
            }
        }
        return new DetailBuild(segs, unwrapped);
    }

    private void appendEntryDetailSegs(List<DetailSeg> segs, List<String> unwrapped, LogDisplayEntry e) {
        addLineSeg(segs, unwrapped, e.text);
        if (e.purpose != null && !e.purpose.isBlank()) addLineSeg(segs, unwrapped, "purpose: " + e.purpose);
        if (e.responder != null && !e.responder.isBlank()) addLineSeg(segs, unwrapped, "responder: " + e.responder);
        if (e.triggerSource != null && !e.triggerSource.isBlank()) addLineSeg(segs, unwrapped, "trigger: " + e.triggerSource);
        if (e.audience != null && !e.audience.isBlank()) addLineSeg(segs, unwrapped, "audience: " + e.audience);
        if (e.inputKind != null && !e.inputKind.isBlank()) addLineSeg(segs, unwrapped, "inputKind: " + e.inputKind);
        if (e.finishReason != null && !e.finishReason.isBlank()) addLineSeg(segs, unwrapped, "finishReason: " + e.finishReason);
        addLineSeg(segs, unwrapped, "contentLength: " + e.contentLength);
        if (e.error != null && !e.error.isBlank()) addLineSeg(segs, unwrapped, "error: " + e.error);
        addLineSeg(segs, unwrapped, "--- REQUEST ---");
        addBodySegs(segs, unwrapped, e.requestBody == null || e.requestBody.isBlank() ? "(empty)" : e.requestBody);
        addLineSeg(segs, unwrapped, "--- RESPONSE ---");
        addBodySegs(segs, unwrapped, e.responseBody == null || e.responseBody.isBlank() ? "(empty)" : e.responseBody);
    }

    private void addLineSeg(List<DetailSeg> segs, List<String> unwrapped, String line) {
        int li = unwrapped.size();
        unwrapped.add(line);
        segs.add(new DetailSeg(line, li, 0, line.length()));
    }

    private void addBodySegs(List<DetailSeg> segs, List<String> unwrapped, String text) {
        String[] rows = text.replace("\r", "").split("\n", -1);
        for (String row : rows) {
            int li = unwrapped.size();
            unwrapped.add(row);
            if (row.length() <= 180) {
                segs.add(new DetailSeg(row, li, 0, row.length()));
            } else {
                for (int s = 0; s < row.length(); s += 180) {
                    int e = Math.min(row.length(), s + 180);
                    segs.add(new DetailSeg(row.substring(s, e), li, s, e));
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false;

        int listH = listHeight();
        boolean inDetailArea = mouseY >= detailTop() + 14 && mouseY < getY() + height;

        // Right-click: copy whichever region the cursor is in.
        if (button == 1) {
            if (inDetailArea) {
                // If there is no active char selection, place caret under cursor (no range).
                if (!detailSelectActive || detailSelectionEmpty()) {
                    int[] p = detailPointAt(mouseX, mouseY);
                    if (p != null) {
                        detailAnchorLine = p[0];
                        detailAnchorChar = p[1];
                        detailEndLine = p[0];
                        detailEndChar = p[1];
                    }
                    detailSelectActive = true;
                }
                copyDetailSelectionToClipboard();
            } else {
                int under = rowIndexAt(mouseY);
                if (under >= 0 && !isSelected(under)) {
                    selectionAnchor = under;
                    selectionEnd = under;
                    detailScroll = 0;
                    invalidateDetailCache();
                }
                copySelectionToClipboard();
            }
            return true;
        }

        if (button != 0) return false;

        // Left-click: detail area starts a char selection drag; list area starts row drag.
        if (inDetailArea) {
            int[] p = detailPointAt(mouseX, mouseY);
            if (p != null) {
                detailAnchorLine = p[0];
                detailAnchorChar = p[1];
                detailEndLine = p[0];
                detailEndChar = p[1];
                detailSelectActive = true;
                draggingDetailSelect = true;
            } else {
                detailAnchorLine = -1;
                detailAnchorChar = -1;
                detailEndLine = -1;
                detailEndChar = -1;
                detailSelectActive = false;
            }
            return true;
        }

        if (mouseY >= getY() + 14 && mouseY < getY() + listH) {
            // Clicking the list clears detail selection.
            detailSelectActive = false;
            detailAnchorLine = -1;
            detailEndLine = -1;
            int idx = rowIndexAt(mouseY);
            if (idx >= 0) {
                selectionAnchor = idx;
                selectionEnd = idx;
                detailScroll = 0;
                draggingSelect = true;
                invalidateDetailCache();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!visible || button != 0) return false;

        if (draggingDetailSelect) {
            int[] p = detailPointAt(mouseX, mouseY);
            if (p != null) {
                detailEndLine = p[0];
                detailEndChar = p[1];
                // auto-scroll detail area near edges
                int dTop = detailTop();
                int dContentY = dTop + 14;
                int dContentH = getY() + height - dContentY - 2;
                int maxDetail = Math.max(1, dContentH / LINE_HEIGHT);
                if (mouseY < dContentY + 6 && detailScroll > 0) {
                    detailScroll--;
                } else if (mouseY > getY() + height - 6
                        && detailScroll + maxDetail < cachedDetailSegs.size()) {
                    detailScroll++;
                }
            }
            return true;
        }

        if (!draggingSelect) return false;
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
            invalidateDetailCache();
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
        if (button == 0) {
            draggingSelect = false;
            draggingDetailSelect = false;
        }
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
            if (e.responder != null && !e.responder.isBlank()) sb.append("responder: ").append(e.responder).append('\n');
            if (e.triggerSource != null && !e.triggerSource.isBlank()) sb.append("trigger: ").append(e.triggerSource).append('\n');
            if (e.audience != null && !e.audience.isBlank()) sb.append("audience: ").append(e.audience).append('\n');
            if (e.inputKind != null && !e.inputKind.isBlank()) sb.append("inputKind: ").append(e.inputKind).append('\n');
            if (e.finishReason != null && !e.finishReason.isBlank()) sb.append("finishReason: ").append(e.finishReason).append('\n');
            sb.append("contentLength: ").append(e.contentLength).append('\n');
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

    private void copyDetailSelectionToClipboard() {
        if (detailSelectionEmpty() || unwrappedDetailLines.isEmpty()) return;
        int[] start = detailSelStart();
        int[] end = detailSelEnd();
        int sLine = start[0], sChar = start[1];
        int eLine = end[0], eChar = end[1];
        StringBuilder sb = new StringBuilder();
        for (int li = sLine; li <= eLine; li++) {
            String line = unwrappedDetailLines.get(li);
            int lo = (li == sLine) ? sChar : 0;
            int hi = (li == eLine) ? Math.min(eChar, line.length()) : line.length();
            if (hi < lo) hi = lo;
            sb.append(line, lo, hi);
            if (li < eLine) sb.append('\n');
        }
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
            copyFlashUntilMs = System.currentTimeMillis() + 1500L;
        } catch (Exception ignored) {}
    }

    /**
     * Handle Ctrl+C (and Cmd+C on mac via GLFW). Returns true if a copy happened.
     * Detailed selection takes priority when active; otherwise falls back to list selection.
     */
    public boolean handleCopyShortcut() {
        if (!visible) return false;
        boolean ctrl = Screen.hasControlDown() || (Minecraft.ON_OSX && Screen.hasAltDown());
        if (!ctrl) return false;
        // Detail char-selection takes priority; only copy detail when a non-empty range exists.
        if (detailSelectActive && !detailSelectionEmpty()) {
            copyDetailSelectionToClipboard();
            return true;
        }
        if (selectionLow() >= 0) {
            copySelectionToClipboard();
            return true;
        }
        return false;
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
