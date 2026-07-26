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
            if (arr == null) return;
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
    }

    public List<String> getProviderNames() {
        List<String> names = new ArrayList<>();
        for (ProviderEntry p : providers) names.add(p.name);
        return names;
    }

    private int deleteX() {
        return getX() + width - DELETE_W - 8;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x90000000);

        int y = getY() + 6;
        graphics.drawString(font, text("providers.name"), getX() + 8, y, 0xAAAAAA, false);
        graphics.drawString(font, text("providers.format"), getX() + 110, y, 0xAAAAAA, false);
        graphics.drawString(font, text("providers.model"), getX() + 170, y, 0xAAAAAA, false);
        graphics.drawString(font, text("providers.status"), getX() + 300, y, 0xAAAAAA, false);
        y += ROW_HEIGHT;
        graphics.fill(getX() + 4, y - 2, getX() + width - 4, y - 1, 0xFF555555);

        if (providers.isEmpty()) {
            graphics.drawString(font, text("providers.empty"),
                    getX() + 8, y + 6, 0xFF5555, false);
            graphics.drawString(font, text("providers.refresh_help"),
                    getX() + 8, getY() + height - 14, 0x666666, false);
            if (isMouseOver(mouseX, mouseY)) {
                graphics.renderTooltip(font, text("providers.empty.tip"), mouseX, mouseY);
            }
            return;
        }

        int headerH = getY() + 6 + ROW_HEIGHT;
        hoveredRow = -1;
        if (mouseX >= getX() && mouseX < getX() + width && mouseY > headerH) {
            int idx = (int) ((mouseY - headerH) / ROW_HEIGHT);
            if (idx >= 0 && idx < providers.size()) hoveredRow = idx;
        }

        for (int i = 0; i < providers.size(); i++) {
            ProviderEntry p = providers.get(i);
            int statusColor;
            if (!p.configured) statusColor = 0xFF8800;
            else if (p.statusKind.equals("ok")) statusColor = 0x55FF55;
            else if (p.statusKind.equals("untested")) statusColor = 0xFFFF55;
            else statusColor = 0xFF5555;

            if (i == hoveredRow) {
                graphics.fill(getX() + 4, y - 1, getX() + width - 4, y + ROW_HEIGHT - 2, 0x28FFFFFF);
            }

            int nameColor = p.configured ? 0xFFFFFF : 0x888888;
            graphics.drawString(font, font.plainSubstrByWidth(p.name, 100), getX() + 8, y + 2, nameColor, false);
            graphics.drawString(font, p.format == null ? "-" : p.format, getX() + 110, y + 2, 0xCCCCCC, false);
            graphics.drawString(font, font.plainSubstrByWidth(p.model, 120), getX() + 170, y + 2, 0xCCCCCC, false);
            graphics.drawString(font, p.status, getX() + 300, y + 2, statusColor, false);

            int dx = deleteX();
            boolean overDelete = mouseX >= dx && mouseX <= dx + DELETE_W && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            graphics.fill(dx, y, dx + DELETE_W, y + ROW_HEIGHT - 4, overDelete ? 0xAA883333 : 0x66883333);
            graphics.drawCenteredString(font, text("providers.delete"), dx + DELETE_W / 2, y + 3, 0xFFAAAA);

            y += ROW_HEIGHT;
        }

        graphics.drawString(font, text("providers.footer"),
                getX() + 8, getY() + height - 14, 0x666666, false);
        if (hoveredRow >= 0) {
            ProviderEntry hovered = providers.get(hoveredRow);
            int rowY = headerH + hoveredRow * ROW_HEIGHT;
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

        if (button == 1) {
            LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket());
            return true;
        }

        int headerH = getY() + 6 + ROW_HEIGHT;
        if (mouseY > headerH) {
            int rowIndex = (int) ((mouseY - headerH) / ROW_HEIGHT);
            if (rowIndex >= 0 && rowIndex < providers.size()) {
                ProviderEntry p = providers.get(rowIndex);
                int dx = deleteX();
                int rowY = headerH + rowIndex * ROW_HEIGHT;
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
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
