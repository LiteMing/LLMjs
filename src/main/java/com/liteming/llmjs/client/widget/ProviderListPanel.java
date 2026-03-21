package com.liteming.llmjs.client.widget;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private record ProviderEntry(String name, String type, String format, String model, String status) {}

    private final List<ProviderEntry> providers = new ArrayList<>();
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

                String status = "untested";
                if (p.has("status") && p.get("status").isJsonObject()) {
                    JsonObject st = p.getAsJsonObject("status");
                    status = st.get("connected").getAsBoolean()
                            ? "OK (" + st.get("latency").getAsLong() + "ms)"
                            : "ERROR";
                }
                providers.add(new ProviderEntry(name, type, format, model, status));
            }
        } catch (Exception ignored) {}
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

        for (ProviderEntry p : providers) {
            int statusColor = p.status.startsWith("OK") ? 0x55FF55
                    : (p.status.equals("untested") ? 0xFFFF55 : 0xFF5555);
            graphics.drawString(font, p.name, getX() + 4, y, 0xFFFFFF, false);
            graphics.drawString(font, p.type, getX() + 120, y, 0xCCCCCC, false);
            graphics.drawString(font, p.format, getX() + 180, y, 0xCCCCCC, false);
            graphics.drawString(font, p.model, getX() + 250, y, 0xCCCCCC, false);
            graphics.drawString(font, p.status, getX() + 380, y, statusColor, false);
            y += ROW_HEIGHT;
        }

        // Footer hint
        graphics.drawString(font, "Click to refresh", getX() + 4, getY() + height - 14, 0x666666, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (visible && isMouseOver(mouseX, mouseY)) {
            LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket());
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
