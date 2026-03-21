package com.liteming.llmjs.client.widget;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.liteming.llmjs.client.screen.LLMConsoleScreen;
import com.liteming.llmjs.network.LLMNetwork;
import com.liteming.llmjs.network.packet.C2SStatusRequestPacket;
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
public class ProviderListPanel extends AbstractWidget {
    public record ProviderEntry(String name, String type, String format, String model,
                                 String maskedKey, String status, boolean configured) {}

    private final List<ProviderEntry> providers = new ArrayList<>();
    private int hoveredRow = -1;
    private static final int ROW_HEIGHT = 16;

    public ProviderListPanel(int x, int y, int width, int height, String statusJson) {
        super(x, y, width, height, Component.literal("Providers"));
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
                String maskedKey = p.has("maskedKey") ? p.get("maskedKey").getAsString() : "***";
                boolean configured = !p.has("configured") || p.get("configured").getAsBoolean();

                String status;
                if (!configured) {
                    status = "NO KEY";
                } else if (p.has("status") && p.get("status").isJsonObject()) {
                    JsonObject st = p.getAsJsonObject("status");
                    status = st.get("connected").getAsBoolean()
                            ? "OK (" + st.get("latency").getAsLong() + "ms)"
                            : "ERROR";
                } else {
                    status = "untested";
                }
                providers.add(new ProviderEntry(name, type, format, model, maskedKey, status, configured));
            }
        } catch (Exception ignored) {}
    }

    /** Get provider names for auto-complete in other panels. */
    public List<String> getProviderNames() {
        List<String> names = new ArrayList<>();
        for (ProviderEntry p : providers) names.add(p.name);
        return names;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x80000000);

        int y = getY() + 4;
        graphics.drawString(font, "Name", getX() + 4, y, 0xAAAAAA, false);
        graphics.drawString(font, "Type", getX() + 120, y, 0xAAAAAA, false);
        graphics.drawString(font, "Format", getX() + 180, y, 0xAAAAAA, false);
        graphics.drawString(font, "Model", getX() + 250, y, 0xAAAAAA, false);
        graphics.drawString(font, "Status", getX() + 380, y, 0xAAAAAA, false);
        y += ROW_HEIGHT;

        graphics.fill(getX() + 2, y - 2, getX() + width - 2, y - 1, 0xFF555555);

        if (providers.isEmpty()) {
            graphics.drawString(font, "No providers loaded. Check config.", getX() + 4, y + 4, 0xFF5555, false);
            return;
        }

        // Calculate hovered row
        int headerH = getY() + 4 + ROW_HEIGHT;
        hoveredRow = -1;
        if (mouseX >= getX() && mouseX < getX() + width && mouseY > headerH) {
            int idx = (int) ((mouseY - headerH) / ROW_HEIGHT);
            if (idx >= 0 && idx < providers.size()) hoveredRow = idx;
        }

        for (int i = 0; i < providers.size(); i++) {
            ProviderEntry p = providers.get(i);
            int statusColor;
            if (!p.configured) {
                statusColor = 0xFF8800;
            } else if (p.status.startsWith("OK")) {
                statusColor = 0x55FF55;
            } else if (p.status.equals("untested")) {
                statusColor = 0xFFFF55;
            } else {
                statusColor = 0xFF5555;
            }

            // Highlight hovered row
            if (i == hoveredRow) {
                graphics.fill(getX() + 2, y - 1, getX() + width - 2, y + ROW_HEIGHT - 1, 0x30FFFFFF);
            }

            int nameColor = p.configured ? 0xFFFFFF : 0x888888;
            graphics.drawString(font, p.name, getX() + 4, y, nameColor, false);
            graphics.drawString(font, p.type, getX() + 120, y, 0xCCCCCC, false);
            graphics.drawString(font, p.format, getX() + 180, y, 0xCCCCCC, false);
            graphics.drawString(font, p.model, getX() + 250, y, 0xCCCCCC, false);
            graphics.drawString(font, p.status, getX() + 380, y, statusColor, false);

            // Action hint on hover
            if (i == hoveredRow) {
                String hint = p.configured ? "[click to edit]" : "[click to setup key]";
                int hintColor = p.configured ? 0x5599FF : 0x5555FF;
                graphics.drawString(font, hint, getX() + 470, y, hintColor, false);
            }

            y += ROW_HEIGHT;
        }

        // Footer
        graphics.drawString(font, "Click provider to edit | Right-click to refresh list",
                getX() + 4, getY() + height - 14, 0x666666, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false;

        // Right-click: refresh
        if (button == 1) {
            LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket());
            return true;
        }

        // Left-click: check if clicking on a provider row
        int headerH = getY() + 4 + ROW_HEIGHT;
        if (mouseY > headerH) {
            int rowIndex = (int) ((mouseY - headerH) / ROW_HEIGHT);
            if (rowIndex >= 0 && rowIndex < providers.size()) {
                ProviderEntry p = providers.get(rowIndex);
                var screen = Minecraft.getInstance().screen;
                if (screen instanceof LLMConsoleScreen console) {
                    // Jump to Setup with pre-filled data; key is masked
                    console.openSetupFor(p.name, p.format, "", p.model, p.maskedKey);
                    return true;
                }
            }
        }

        // Click on empty area: refresh
        LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
