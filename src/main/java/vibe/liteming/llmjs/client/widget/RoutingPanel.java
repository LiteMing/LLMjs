package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SStatusRequestPacket;
import vibe.liteming.llmjs.network.packet.C2SUpdateRoutingPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routing tab widget for the /llm console. Renders one row per registered purpose
 * (from {@link vibe.liteming.llmcore.PurposeRegistry}) plus a "default" row. Each row
 * shows the current provider chain; clicking a row opens an inline editor where the
 * admin cycles providers in/out of the chain and re-orders via up/down buttons.
 *
 * <p>UI is intentionally a Minecraft-flavored list editor (no text fields): each
 * purpose has a fixed-size pool of available providers (right column); the active
 * chain is the left column in priority order. Save broadcasts a C2SUpdateRoutingPacket;
 * Reset reloads from the last server snapshot.</p>
 */
@OnlyIn(Dist.CLIENT)
public class RoutingPanel extends AbstractWidget {
    public record PurposeRow(String id, String displayName, String description, String modId, boolean builtIn) {}
    public record ProviderName(String name) {}

    private static final int ROW_HEIGHT = 18;
    private static final int HEADER_Y_OFFSET = 6;

    private final Font font;
    private List<PurposeRow> purposes = new ArrayList<>();
    private List<String> providerNames = new ArrayList<>();
    /** edited chains: key = purpose id (or "$default"), value = ordered provider names */
    private Map<String, List<String>> edited = new LinkedHashMap<>();
    private List<String> editedDefault = new ArrayList<>();
    /** row index currently being edited (-1 = none). Header row 0 = default; subsequent = purposes. */
    private int editingRow = -1;
    private boolean dirty = false;

    public RoutingPanel(int x, int y, int width, int height, Font font, String statusJson) {
        super(x, y, width, height, Component.literal("Routing"));
        this.font = font;
        updateStatus(statusJson);
    }

    public void updateStatus(String statusJson) {
        List<PurposeRow> newPurposes = new ArrayList<>();
        List<String> newProviders = new ArrayList<>();
        Map<String, List<String>> serverChains = new LinkedHashMap<>();
        List<String> serverDefault = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            if (root.has("providers") && root.get("providers").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("providers")) {
                    JsonObject p = el.getAsJsonObject();
                    newProviders.add(p.get("name").getAsString());
                }
            }
            if (root.has("purposes") && root.get("purposes").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("purposes")) {
                    JsonObject pm = el.getAsJsonObject();
                    newPurposes.add(new PurposeRow(
                            pm.get("id").getAsString(),
                            pm.has("displayName") ? pm.get("displayName").getAsString() : pm.get("id").getAsString(),
                            pm.has("description") ? pm.get("description").getAsString() : "",
                            pm.has("modId") ? pm.get("modId").getAsString() : "",
                            pm.has("builtIn") && pm.get("builtIn").getAsBoolean()));
                }
            }
            if (root.has("routing") && root.get("routing").isJsonObject()) {
                JsonObject r = root.getAsJsonObject("routing");
                if (r.has("default") && r.get("default").isJsonArray()) {
                    for (JsonElement el : r.getAsJsonArray("default")) serverDefault.add(el.getAsString());
                }
                if (r.has("purposes") && r.get("purposes").isJsonObject()) {
                    JsonObject obj = r.getAsJsonObject("purposes");
                    for (var entry : obj.entrySet()) {
                        if (entry.getValue().isJsonArray()) {
                            List<String> chain = new ArrayList<>();
                            for (JsonElement el : entry.getValue().getAsJsonArray()) chain.add(el.getAsString());
                            serverChains.put(entry.getKey(), chain);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        this.purposes = newPurposes;
        this.providerNames = newProviders;
        // Always reset local edits to the freshly-arrived server snapshot.
        this.edited = new LinkedHashMap<>(serverChains);
        this.editedDefault = new ArrayList<>(serverDefault);
        this.editingRow = -1;
        this.dirty = false;
    }

    public boolean isDirty() { return dirty; }

    private List<String> getRowChain(int row) {
        if (row == 0) return editedDefault;
        if (row >= 1 && row <= purposes.size()) return edited.get(purposes.get(row - 1).id());
        return null;
    }

    private void setRowChain(int row, List<String> chain) {
        if (row == 0) editedDefault = new ArrayList<>(chain);
        else if (row >= 1 && row <= purposes.size()) edited.put(purposes.get(row - 1).id(), new ArrayList<>(chain));
        dirty = true;
    }

    private String getRowLabel(int row) {
        if (row == 0) return "default";
        if (row >= 1 && row <= purposes.size()) return purposes.get(row - 1).displayName();
        return "?";
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x90000000);
        int y = getY() + HEADER_Y_OFFSET;
        graphics.drawString(font, "Purpose", getX() + 8, y, 0xAAAAAA, false);
        graphics.drawString(font, "Provider Chain (priority order)", getX() + 150, y, 0xAAAAAA, false);
        graphics.drawString(font, dirty ? "* unsaved" : "saved", getX() + width - 70, y, dirty ? 0xFFAA00 : 0x55FF55, false);
        y += ROW_HEIGHT;
        graphics.fill(getX() + 4, y - 2, getX() + width - 4, y - 1, 0xFF555555);

        int headerH = getY() + HEADER_Y_OFFSET + ROW_HEIGHT;
        int hoveredRow = -1;
        if (mouseX >= getX() && mouseX < getX() + width && mouseY > headerH) {
            int idx = (int) ((mouseY - headerH) / ROW_HEIGHT);
            int totalRows = 1 + purposes.size();
            if (idx >= 0 && idx < totalRows) hoveredRow = idx;
        }

        // Default row + per-purpose rows
        for (int row = 0; row < 1 + purposes.size(); row++) {
            if (y + ROW_HEIGHT > getY() + height - 30) break; // leave footer space
            int rowY = y;
            boolean isEditing = (row == editingRow);
            if (row == hoveredRow || isEditing) {
                graphics.fill(getX() + 4, rowY - 1, getX() + width - 4, rowY + ROW_HEIGHT - 2, isEditing ? 0x4055AAFF : 0x28FFFFFF);
            }
            graphics.drawString(font, font.plainSubstrByWidth(getRowLabel(row), 140), getX() + 8, rowY + 2, 0xFFFFFF, false);
            List<String> chain = getRowChain(row);
            String chainText = chain == null || chain.isEmpty() ? "(none -> all providers)" : String.join("  >  ", chain);
            graphics.drawString(font, font.plainSubstrByWidth(chainText, width - 320), getX() + 150, rowY + 2, 0xCCCCCC, false);
            graphics.drawCenteredString(font, isEditing ? "Done" : "Edit", getX() + width - 56, rowY + 3, 0xFFAAAA);
            y += ROW_HEIGHT;
        }

        // Footer: action buttons (text labels; clicked via mouseClicked)
        int footerY = getY() + height - 22;
        graphics.drawString(font, "[Save]", getX() + 8, footerY, dirty ? 0x55FF55 : 0x666666, false);
        graphics.drawString(font, "[Reset]", getX() + 60, footerY, 0xAAAAFF, false);
        graphics.drawString(font, "[Refresh]", getX() + 110, footerY, 0xAAAAFF, false);
        if (providerNames.isEmpty()) {
            graphics.drawString(font, "Add providers in Providers tab first.", getX() + 200, footerY, 0xFF5555, false);
        }

        // Inline editor row when editing
        if (editingRow >= 0) {
            renderInlineEditor(graphics, mouseX, mouseY);
        }
    }

    private void renderInlineEditor(GuiGraphics graphics, int mouseX, int mouseY) {
        List<String> chain = getRowChain(editingRow);
        if (chain == null) chain = new ArrayList<>();
        int ey = getY() + height - 60;
        graphics.fill(getX() + 4, ey - 2, getX() + width - 4, ey + 50, 0xC0222222);
        graphics.drawString(font, "Editing: " + getRowLabel(editingRow), getX() + 8, ey, 0xFFFFFF, false);
        // Active chain column (click to remove, click arrows to reorder)
        int cx = getX() + 8;
        int cy = ey + 14;
        graphics.drawString(font, "Active:", cx, cy, 0xAAAAFF, false);
        int slotX = cx + 40;
        for (int i = 0; i < chain.size(); i++) {
            graphics.fill(slotX, cy - 1, slotX + 90, cy + 11, 0xFF333355);
            String label = (i == 0 ? "[1] " : "[fb] ") + font.plainSubstrByWidth(chain.get(i), 70);
            graphics.drawString(font, label, slotX + 2, cy + 1, 0xFFFFFF, false);
            slotX += 96;
            if (slotX + 96 > getX() + width / 2) break;
        }
        // Available providers column (click to append)
        int ax = getX() + width / 2 + 8;
        int ay = cy;
        graphics.drawString(font, "Available:", ax, ay, 0xAAAAFF, false);
        int colX = ax + 50;
        for (String name : providerNames) {
            boolean inChain = chain.contains(name);
            int color = inChain ? 0x666666 : 0x55FF55;
            graphics.fill(colX, ay - 1, colX + 110, ay + 11, inChain ? 0xFF222222 : 0xFF223333);
            graphics.drawString(font, font.plainSubstrByWidth((inChain ? "[x] " : "[+] ") + name, 100), colX + 2, ay + 1, color, false);
            colX += 116;
            if (colX + 116 > getX() + width - 8) { colX = ax + 50; ay += 14; if (ay > ey + 46) break; }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false;
        int footerY = getY() + height - 22;
        // Footer actions
        if (mouseY >= footerY && mouseY < footerY + 12) {
            if (inLabel(mouseX, 8, 44)) { save(); return true; }
            if (inLabel(mouseX, 60, 96)) { LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket()); return true; }
            if (inLabel(mouseX, 110, 168)) { LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket()); return true; }
        }
        // Inline editor hit-testing
        if (editingRow >= 0 && mouseY >= getY() + height - 60 && mouseY < getY() + height - 8) {
            return handleEditorClick(mouseX, mouseY);
        }
        // Row click -> open/close editor (the Edit button area or anywhere in the row)
        int headerH = getY() + HEADER_Y_OFFSET + ROW_HEIGHT;
        if (mouseY > headerH) {
            int row = (int) ((mouseY - headerH) / ROW_HEIGHT);
            if (row >= 0 && row < 1 + purposes.size()) {
                int editBtnX = getX() + width - 70;
                int rowY = headerH + row * ROW_HEIGHT;
                if (mouseX >= editBtnX && mouseX <= editBtnX + 40 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2) {
                    editingRow = (editingRow == row) ? -1 : row;
                    return true;
                }
                // Anywhere else on the row toggles editor too (UX nicety)
                editingRow = (editingRow == row) ? -1 : row;
                return true;
            }
        }
        return true;
    }

    private boolean handleEditorClick(double mouseX, double mouseY) {
        List<String> chain = getRowChain(editingRow);
        if (chain == null) chain = new ArrayList<>();
        int ey = getY() + height - 60;
        int cy = ey + 14;
        // Active chain slots: click removes from chain
        int slotX = getX() + 48;
        for (int i = 0; i < chain.size(); i++) {
            if (mouseX >= slotX && mouseX < slotX + 90 && mouseY >= cy - 1 && mouseY < cy + 11) {
                List<String> next = new ArrayList<>(chain);
                next.remove(i);
                setRowChain(editingRow, next);
                return true;
            }
            slotX += 96;
            if (slotX + 96 > getX() + width / 2) break;
        }
        // Available providers: click appends
        int ax = getX() + width / 2 + 8;
        int colX = ax + 50;
        int ay = cy;
        for (String name : providerNames) {
            if (!chain.contains(name) && mouseX >= colX && mouseX < colX + 110 && mouseY >= ay - 1 && mouseY < ay + 11) {
                List<String> next = new ArrayList<>(chain);
                next.add(name);
                setRowChain(editingRow, next);
                return true;
            }
            colX += 116;
            if (colX + 116 > getX() + width - 8) { colX = ax + 50; ay += 14; if (ay > ey + 46) break; }
        }
        return true;
    }

    private boolean inLabel(double mouseX, int startXRel, int endXRel) {
        return mouseX >= getX() + startXRel && mouseX < getX() + endXRel;
    }

    private void save() {
        if (!dirty) return;
        String json = C2SUpdateRoutingPacket.toJson(editedDefault, edited);
        LLMNetwork.CHANNEL.sendToServer(new C2SUpdateRoutingPacket(json));
        dirty = false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
