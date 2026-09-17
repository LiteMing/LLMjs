// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: AGPL-3.0-or-later
package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import vibe.liteming.llmcore.LlmCostRate;
import vibe.liteming.llmcore.LlmRoute;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SStatusRequestPacket;
import vibe.liteming.llmjs.network.packet.C2SUpdatePersonalRoutingPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;

/** Player-scoped ordering of server-enabled provider/model targets. */
@OnlyIn(Dist.CLIENT)
public final class PersonalRoutingPanel extends AbstractWidget {
    private record ProviderChoice(String id, String provider, String model, LlmCostRate rate) { }
    private record PurposeChoice(String id, String displayName) { }
    private record HitBox(int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private final Font font;
    private final List<ProviderChoice> providers = new ArrayList<>();
    private final List<PurposeChoice> purposes = new ArrayList<>();
    private final Map<String, List<String>> savedRoutes = new LinkedHashMap<>();
    private final List<String> draft = new ArrayList<>();
    private final ConsoleScrollBar providerScroll = new ConsoleScrollBar();
    private int purposeIndex;
    private boolean dirty;
    private String status = "";
    private int statusColor = 0xAAAAAA;

    private static final int PROVIDER_WIDTH = 116;
    private static final int ROW_HEIGHT = 20;

    public PersonalRoutingPanel(int x, int y, int width, int height, Font font, String statusJson) {
        super(x, y, width, height, text("tab.preferences"));
        this.font = font;
        updateStatus(statusJson);
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        setWidth(Math.max(1, width));
        setHeight(Math.max(1, height));
        updateScroll();
    }

    public void setPanelVisible(boolean value) {
        visible = value;
    }

    public void updateStatus(String statusJson) {
        String selected = currentPurposeId();
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            if (!root.has("personalRouting") || !root.get("personalRouting").isJsonObject()) return;
            JsonObject snapshot = root.getAsJsonObject("personalRouting");
            providers.clear();
            JsonArray providerArray = snapshot.getAsJsonArray("targets");
            if (providerArray != null) for (var element : providerArray) {
                JsonObject provider = element.getAsJsonObject();
                JsonObject billing = provider.has("billing") && provider.get("billing").isJsonObject()
                        ? provider.getAsJsonObject("billing") : new JsonObject();
                double input = billing.has("inputMultiplier") ? billing.get("inputMultiplier").getAsDouble() : 1.0D;
                double output = billing.has("outputMultiplier") ? billing.get("outputMultiplier").getAsDouble() : input;
                providers.add(new ProviderChoice(provider.get("id").getAsString(),
                        provider.get("provider").getAsString(),
                        provider.has("model") ? provider.get("model").getAsString() : "",
                        new LlmCostRate(input, output)));
            }
            purposes.clear();
            JsonArray purposeArray = snapshot.getAsJsonArray("purposes");
            if (purposeArray != null) for (var element : purposeArray) {
                JsonObject purpose = element.getAsJsonObject();
                purposes.add(new PurposeChoice(purpose.get("id").getAsString(),
                        purpose.has("displayName") ? purpose.get("displayName").getAsString()
                                : purpose.get("id").getAsString()));
            }
            savedRoutes.clear();
            if (snapshot.has("personalRoutes") && snapshot.get("personalRoutes").isJsonObject()) {
                for (var entry : snapshot.getAsJsonObject("personalRoutes").entrySet()) {
                    try {
                        JsonObject route = entry.getValue().getAsJsonObject();
                        savedRoutes.put(entry.getKey(), LlmRoute.parse(route.get("route").getAsString()).targetIds());
                    } catch (RuntimeException ignored) { }
                }
            }
            purposeIndex = 0;
            if (!selected.isEmpty()) {
                for (int index = 0; index < purposes.size(); index++) {
                    if (selected.equals(purposes.get(index).id())) purposeIndex = index;
                }
            }
            loadDraft();
            if (root.has("personalRoutingError")) {
                status = string("preferences.rejected", root.get("personalRoutingError").getAsString());
                statusColor = 0xFF5555;
            } else if (root.has("personalRoutingSaved")) {
                status = string("preferences.saved");
                statusColor = 0x55FF55;
            }
        } catch (RuntimeException invalid) {
            status = string("preferences.invalid_snapshot");
            statusColor = 0xFF5555;
        }
        updateScroll();
    }

    private void loadDraft() {
        draft.clear();
        draft.addAll(savedRoutes.getOrDefault(currentPurposeId(), List.of()));
        dirty = false;
    }

    private String currentPurposeId() {
        return purposes.isEmpty() || purposeIndex < 0 || purposeIndex >= purposes.size()
                ? "" : purposes.get(purposeIndex).id();
    }

    private int columns() {
        return Math.max(1, (getWidth() - 24) / (PROVIDER_WIDTH + 6));
    }

    private int providerTop() { return getY() + 90; }
    private int providerBottom() { return Math.max(providerTop() + 1, getY() + getHeight() - 34); }

    private void updateScroll() {
        int rows = Math.max(1, (providers.size() + columns() - 1) / columns());
        providerScroll.setTrack(getX() + getWidth() - 7, providerTop(), providerBottom());
        providerScroll.update(rows * ROW_HEIGHT, Math.max(1, providerBottom() - providerTop()));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateScroll();
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x90000000);
        graphics.drawString(font, text("preferences.title"), getX() + 8, getY() + 8, 0xFFFFFF, false);
        if (purposes.isEmpty()) {
            graphics.drawString(font, text("preferences.no_purpose"), getX() + 8, getY() + 28, 0xFF5555, false);
            return;
        }

        PurposeChoice purpose = purposes.get(purposeIndex);
        drawButton(graphics, previousPurpose(), "<", mouseX, mouseY, 0x88CCFF);
        drawButton(graphics, nextPurpose(), ">", mouseX, mouseY, 0x88CCFF);
        graphics.drawCenteredString(font, purpose.displayName() + " [" + purpose.id() + "]",
                getX() + getWidth() / 2, getY() + 29, 0xFFFFFF);

        graphics.drawString(font, text("preferences.route"), getX() + 8, getY() + 50, 0xAAAAAA, false);
        String route = draft.isEmpty() ? string("preferences.server_default") : String.join(" > ", draft);
        graphics.drawString(font, font.plainSubstrByWidth(route, Math.max(8, getWidth() - 16)),
                getX() + 8, getY() + 64, dirty ? 0xFFFF55 : 0xDDDDDD, false);
        graphics.drawString(font, text("preferences.available"), getX() + 8, getY() + 79, 0xAAAAAA, false);

        graphics.enableScissor(getX(), providerTop(), getX() + getWidth(), providerBottom());
        int columns = columns();
        int usableWidth = Math.max(1, getWidth() - 18);
        int slotWidth = Math.max(42, Math.min(PROVIDER_WIDTH, (usableWidth - (columns - 1) * 6) / columns));
        for (int index = 0; index < providers.size(); index++) {
            HitBox box = providerBox(index, columns, slotWidth);
            if (box.y() + box.height() <= providerTop() || box.y() >= providerBottom()) continue;
            ProviderChoice provider = providers.get(index);
            boolean active = draft.contains(provider.id());
            int color = active ? 0xAA235A78 : box.contains(mouseX, mouseY) ? 0xAA444444 : 0xAA303030;
            graphics.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), color);
            String prefix = active ? (draft.indexOf(provider.id()) + 1) + ". " : "+ ";
            graphics.drawCenteredString(font, ellipsize(prefix + provider.provider() + "/" + provider.model(), box.width() - 6),
                    box.x() + box.width() / 2, box.y() + 5, active ? 0xFFFFFF : 0xCCCCCC);
            if (box.contains(mouseX, mouseY)) {
                graphics.renderTooltip(font, text("preferences.provider.tip", provider.provider(), provider.model(),
                        provider.rate().inputMultiplier(), provider.rate().outputMultiplier()), mouseX, mouseY);
            }
        }
        graphics.disableScissor();
        providerScroll.render(graphics, mouseX, mouseY);

        drawButton(graphics, undoButton(), string("preferences.undo"), mouseX, mouseY, 0xDDDDDD);
        drawButton(graphics, costButton(), string("preferences.low_cost"), mouseX, mouseY, 0x88CCFF);
        drawButton(graphics, defaultButton(), string("preferences.use_default"), mouseX, mouseY, 0xFFCC66);
        drawButton(graphics, saveButton(), string("preferences.save"), mouseX, mouseY, 0x55FF55);
        if (!status.isEmpty()) graphics.drawString(font, ellipsize(status, Math.max(8, getWidth() - 16)),
                getX() + 8, getY() + getHeight() - 12, statusColor, false);
    }

    private HitBox providerBox(int index, int columns, int slotWidth) {
        int column = index % columns;
        int row = index / columns;
        return new HitBox(getX() + 8 + column * (slotWidth + 6),
                providerTop() + row * ROW_HEIGHT - providerScroll.offset(), slotWidth, 17);
    }

    private HitBox previousPurpose() { return new HitBox(getX() + 8, getY() + 25, 22, 18); }
    private HitBox nextPurpose() { return new HitBox(getX() + getWidth() - 30, getY() + 25, 22, 18); }
    private int footerY() { return getY() + getHeight() - 30; }
    private HitBox undoButton() { return new HitBox(getX() + 8, footerY(), 48, 17); }
    private HitBox costButton() { return new HitBox(getX() + 62, footerY(), 82, 17); }
    private HitBox defaultButton() { return new HitBox(getX() + 150, footerY(), 92, 17); }
    private HitBox saveButton() { return new HitBox(getX() + getWidth() - 64, footerY(), 56, 17); }

    private void drawButton(GuiGraphics graphics, HitBox box, String label, int mouseX, int mouseY, int color) {
        graphics.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(),
                box.contains(mouseX, mouseY) ? 0xFF4A4A4A : 0xFF303030);
        graphics.drawCenteredString(font, ellipsize(label, box.width() - 4),
                box.x() + box.width() / 2, box.y() + 5, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        if (providerScroll.mouseClicked(mouseX, mouseY, button)) return true;
        if (previousPurpose().contains(mouseX, mouseY)) { changePurpose(-1); return true; }
        if (nextPurpose().contains(mouseX, mouseY)) { changePurpose(1); return true; }
        if (undoButton().contains(mouseX, mouseY)) {
            if (!draft.isEmpty()) { draft.remove(draft.size() - 1); dirty = true; }
            return true;
        }
        if (costButton().contains(mouseX, mouseY)) {
            List<String> base = draft.isEmpty() ? providers.stream().map(ProviderChoice::id).toList() : List.copyOf(draft);
            draft.clear();
            draft.addAll(base.stream().sorted(Comparator.comparingDouble(name -> providers.stream()
                    .filter(provider -> provider.id().equals(name)).findFirst()
                    .map(provider -> provider.rate().inputMultiplier() + provider.rate().outputMultiplier())
                    .orElse(Double.MAX_VALUE))).toList());
            dirty = true;
            return true;
        }
        if (defaultButton().contains(mouseX, mouseY)) {
            draft.clear();
            dirty = true;
            save();
            return true;
        }
        if (saveButton().contains(mouseX, mouseY)) { save(); return true; }
        int columns = columns();
        int usableWidth = Math.max(1, getWidth() - 18);
        int slotWidth = Math.max(42, Math.min(PROVIDER_WIDTH, (usableWidth - (columns - 1) * 6) / columns));
        for (int index = 0; index < providers.size(); index++) {
            if (!providerBox(index, columns, slotWidth).contains(mouseX, mouseY)) continue;
            String name = providers.get(index).id();
            if (draft.contains(name)) draft.remove(name);
            else if (draft.size() < LlmRoute.MAX_STAGES) draft.add(name);
            dirty = true;
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return visible && isMouseOver(mouseX, mouseY) && providerScroll.scroll(delta, ROW_HEIGHT * 2);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return providerScroll.mouseDragged(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return providerScroll.mouseReleased(button) || super.mouseReleased(mouseX, mouseY, button);
    }

    private void changePurpose(int offset) {
        if (purposes.isEmpty()) return;
        purposeIndex = Math.floorMod(purposeIndex + offset, purposes.size());
        loadDraft();
        providerScroll.setOffset(0);
    }

    private void save() {
        String purpose = currentPurposeId();
        if (purpose.isEmpty()) return;
        LLMNetwork.CHANNEL.sendToServer(new C2SUpdatePersonalRoutingPacket(purpose, String.join(" > ", draft)));
        status = string("preferences.saving");
        statusColor = 0xFFFF55;
    }

    private String ellipsize(String value, int maxWidth) {
        if (value == null || maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        String suffix = "...";
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(suffix))) + suffix;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) { }
}
