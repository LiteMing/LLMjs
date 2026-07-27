package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vibe.liteming.llmjs.client.screen.LLMConsoleScreen;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SDeleteProviderPacket;
import vibe.liteming.llmjs.network.packet.C2SStatusRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;

@OnlyIn(Dist.CLIENT)
public class ProviderListPanel extends AbstractWidget {
    public record ProviderEntry(String name, String type, String format, String model, String url,
                                 String maskedKey, String status, String statusKind, boolean configured) {}

    private final List<ProviderEntry> providers = new ArrayList<>();
    private final ConsoleScrollBar rowScroll = new ConsoleScrollBar();
    private int hoveredRow = -1;
    private static final int ROW_HEIGHT = 18;
    private static final int DELETE_W = 52;

    public ProviderListPanel(int x, int y, int width, int height, String statusJson) {
        super(x, y, width, height, text("tab.providers"));
        updateStatus(statusJson);
    }

    public void updateStatus(String statusJson) {
        providers.clear();
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("providers");
            if (arr == null) {
                updateScrollRange();
                return;
            }
            for (var el : arr) {
                JsonObject p = el.getAsJsonObject();
                String name = p.get("name").getAsString();
                String type = p.has("type") ? p.get("type").getAsString() : "?";
                String format = p.has("format") ? p.get("format").getAsString() : "-";
                String model = p.has("model") ? p.get("model").getAsString() : "?";
                String url = p.has("url") ? p.get("url").getAsString() : "";
                String maskedKey = p.has("maskedKey") ? p.get("maskedKey").getAsString() : "***";
                boolean configured = !p.has("configured") || p.get("configured").getAsBoolean();

                String status;
                String statusKind;
                if (!configured) {
                    status = string("providers.status.no_key");
                    statusKind = "no_key";
                } else if (p.has("status") && p.get("status").isJsonObject()) {
                    JsonObject st = p.getAsJsonObject("status");
                    boolean connected = st.get("connected").getAsBoolean();
                    status = connected ? string("providers.status.ok", st.get("latency").getAsLong())
                            : string("providers.status.error");
                    statusKind = connected ? "ok" : "error";
                } else {
                    status = string("providers.status.untested");
                    statusKind = "untested";
                }
                providers.add(new ProviderEntry(name, type, format, model, url, maskedKey,
                        status, statusKind, configured));
            }
        } catch (Exception ignored) {}
        updateScrollRange();
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        setWidth(Math.max(1, width));
        setHeight(Math.max(1, height));
        updateScrollRange();
    }

    public List<String> getProviderNames() {
        List<String> names = new ArrayList<>();
        for (ProviderEntry p : providers) names.add(p.name);
        return names;
    }

    private int deleteX() {
        return getX() + width - DELETE_W - 16;
    }

    private int listTop() {
        return getY() + 6 + ROW_HEIGHT;
    }

    private int listBottom() {
        return Math.max(listTop() + 1, getY() + height - 20);
    }

    private void updateScrollRange() {
        int viewport = Math.max(1, listBottom() - listTop());
        rowScroll.setTrack(getX() + width - 7, listTop(), listBottom());
        rowScroll.update(providers.size() * ROW_HEIGHT, viewport);
    }

    private int rowIndexAt(double mouseY) {
        if (mouseY < listTop() || mouseY >= listBottom()) return -1;
        int index = (int) ((mouseY - listTop() + rowScroll.offset()) / ROW_HEIGHT);
        return index >= 0 && index < providers.size() ? index : -1;
    }

    private int rowY(int index) {
        return listTop() + index * ROW_HEIGHT - rowScroll.offset();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        updateScrollRange();
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x90000000);

        int y = getY() + 6;
        graphics.drawString(font, text("providers.name"), getX() + 8, y, 0xAAAAAA, false);
        if (width >= 230) graphics.drawString(font, text("providers.format"), getX() + 110, y, 0xAAAAAA, false);
        if (width >= 330) graphics.drawString(font, text("providers.model"), getX() + 170, y, 0xAAAAAA, false);
        if (width >= 470) graphics.drawString(font, text("providers.status"), getX() + 300, y, 0xAAAAAA, false);
        y += ROW_HEIGHT;
        graphics.fill(getX() + 4, y - 2, getX() + width - 4, y - 1, 0xFF555555);

        if (providers.isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(string("providers.empty"), Math.max(8, width - 16)),
                    getX() + 8, y + 6, 0xFF5555, false);
            graphics.drawString(font, font.plainSubstrByWidth(string("providers.refresh_help"), Math.max(8, width - 16)),
                    getX() + 8, getY() + height - 14, 0x666666, false);
            if (isMouseOver(mouseX, mouseY)) {
                graphics.renderTooltip(font, text("providers.empty.tip"), mouseX, mouseY);
            }
            return;
        }

        int headerH = listTop();
        hoveredRow = mouseX >= getX() && mouseX < getX() + width ? rowIndexAt(mouseY) : -1;

        graphics.enableScissor(getX(), listTop(), getX() + width, listBottom());
        int start = Math.max(0, rowScroll.offset() / ROW_HEIGHT);
        for (int i = start; i < providers.size(); i++) {
            ProviderEntry p = providers.get(i);
            int rowY = rowY(i);
            if (rowY >= listBottom()) break;
            if (rowY + ROW_HEIGHT <= listTop()) continue;
            int statusColor;
            if (!p.configured) statusColor = 0xFF8800;
            else if (p.statusKind.equals("ok")) statusColor = 0x55FF55;
            else if (p.statusKind.equals("untested")) statusColor = 0xFFFF55;
            else statusColor = 0xFF5555;

            if (i == hoveredRow) {
                graphics.fill(getX() + 4, rowY - 1, getX() + width - 8, rowY + ROW_HEIGHT - 2, 0x28FFFFFF);
            }

            int nameColor = p.configured ? 0xFFFFFF : 0x888888;
            graphics.drawString(font, font.plainSubstrByWidth(p.name, 100), getX() + 8, rowY + 2, nameColor, false);
            if (width >= 230) {
                graphics.drawString(font, font.plainSubstrByWidth(p.format == null ? "-" : p.format, 54),
                        getX() + 110, rowY + 2, 0xCCCCCC, false);
            }
            if (width >= 330) {
                int modelWidth = Math.max(30, Math.min(120, deleteX() - (getX() + 170) - 70));
                graphics.drawString(font, font.plainSubstrByWidth(p.model, modelWidth),
                        getX() + 170, rowY + 2, 0xCCCCCC, false);
            }
            if (width >= 470) {
                graphics.drawString(font, font.plainSubstrByWidth(p.status, Math.max(20, deleteX() - getX() - 304)),
                        getX() + 300, rowY + 2, statusColor, false);
            }

            int dx = deleteX();
            boolean overDelete = mouseX >= dx && mouseX <= dx + DELETE_W
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2;
            graphics.fill(dx, rowY, dx + DELETE_W, rowY + ROW_HEIGHT - 4,
                    overDelete ? 0xAA883333 : 0x66883333);
            graphics.drawCenteredString(font, text("providers.delete"), dx + DELETE_W / 2, rowY + 3, 0xFFAAAA);
        }
        graphics.disableScissor();
        rowScroll.render(graphics, mouseX, mouseY);

        graphics.drawString(font, font.plainSubstrByWidth(string("providers.footer"), Math.max(8, width - 18)),
                getX() + 8, getY() + height - 14, 0x666666, false);
        if (hoveredRow >= 0) {
            ProviderEntry hovered = providers.get(hoveredRow);
            int rowY = rowY(hoveredRow);
            boolean overDelete = mouseX >= deleteX() && mouseX <= deleteX() + DELETE_W
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2;
            if (overDelete) {
                graphics.renderTooltip(font, text("providers.delete.tip", hovered.name), mouseX, mouseY);
            } else {
                graphics.renderTooltip(font, text("providers.row.tip", hovered.name, hovered.format,
                        hovered.model, font.plainSubstrByWidth(hovered.url, 300), hovered.maskedKey), mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false;
        if (rowScroll.mouseClicked(mouseX, mouseY, button)) return true;

        if (button == 1) {
            LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket());
            return true;
        }

        int headerH = listTop();
        if (mouseY > headerH && mouseY < listBottom()) {
            int rowIndex = rowIndexAt(mouseY);
            if (rowIndex >= 0 && rowIndex < providers.size()) {
                ProviderEntry p = providers.get(rowIndex);
                int dx = deleteX();
                int rowY = rowY(rowIndex);
                if (mouseX >= dx && mouseX <= dx + DELETE_W && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2) {
                    LLMNetwork.CHANNEL.sendToServer(new C2SDeleteProviderPacket(p.name));
                    return true;
                }
                var screen = Minecraft.getInstance().screen;
                if (screen instanceof LLMConsoleScreen console) {
                    console.openSetupFor(p.name, p.format, p.url, p.model, p.maskedKey);
                    return true;
                }
            }
        }

        LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false;
        return rowScroll.scroll(delta, ROW_HEIGHT * 3);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (rowScroll.mouseDragged(mouseX, mouseY, button)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (rowScroll.mouseReleased(button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
