package vibe.liteming.llmjs.client.widget;

import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SSetupProviderPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class SetupPanel {
    private final int x, y, width, height;
    private final EditBox nameInput;
    private final EditBox formatInput;
    private final EditBox urlInput;
    private final EditBox modelInput;
    private final EditBox keyInput;
    private final Button saveButton;
    private @Nullable String statusMessage;
    private int statusColor = 0xFFFFFF;
    private boolean visible = true;

    private static final int LABEL_W = 60;
    private static final int ROW_H = 24;

    public SetupPanel(int x, int y, int width, int height, Font font) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        int inputX = x + LABEL_W + 8;
        int inputW = Math.min(width - LABEL_W - 20, 300);
        int row = y + 4;

        nameInput = new EditBox(font, inputX, row, inputW, 18, Component.literal("Name"));
        nameInput.setMaxLength(64);
        nameInput.setValue("");
        row += ROW_H;

        formatInput = new EditBox(font, inputX, row, inputW, 18, Component.literal("Format"));
        formatInput.setMaxLength(32);
        formatInput.setValue("openai");
        row += ROW_H;

        urlInput = new EditBox(font, inputX, row, inputW, 18, Component.literal("URL"));
        urlInput.setMaxLength(512);
        urlInput.setValue("https://api.openai.com/v1/chat/completions");
        row += ROW_H;

        modelInput = new EditBox(font, inputX, row, inputW, 18, Component.literal("Model"));
        modelInput.setMaxLength(128);
        modelInput.setValue("");
        row += ROW_H;

        keyInput = new EditBox(font, inputX, row, inputW, 18, Component.literal("API Key"));
        keyInput.setMaxLength(256);
        keyInput.setValue("");
        row += ROW_H + 4;

        saveButton = Button.builder(Component.literal("Save to llmjs.secret"), b -> save())
                .pos(inputX, row).size(160, 20).build();
    }

    /** Return all interactive widgets for the Screen to register. */
    public List<net.minecraft.client.gui.components.AbstractWidget> getWidgets() {
        return List.of(nameInput, formatInput, urlInput, modelInput, keyInput, saveButton);
    }

    public void setVisible(boolean v) {
        this.visible = v;
        nameInput.visible = v;
        formatInput.visible = v;
        urlInput.visible = v;
        modelInput.visible = v;
        keyInput.visible = v;
        saveButton.visible = v;
    }

    public boolean isVisible() { return visible; }

    /** Whether we are editing an existing provider (key optional). */
    private boolean editMode = false;

    public void prefill(String name, String format, String url, String model, @Nullable String maskedKey) {
        editMode = true;
        nameInput.setValue(name);
        if (format != null && !format.isEmpty() && !"-".equals(format)) formatInput.setValue(format);
        if (url != null && !url.isEmpty()) urlInput.setValue(url);
        if (model != null && !model.isEmpty()) modelInput.setValue(model);
        keyInput.setValue("");
        if (maskedKey != null && !maskedKey.equals("***")) {
            statusMessage = "Current key: " + maskedKey + " | Enter new key to change, or leave empty to keep";
            statusColor = 0x55FF55;
        } else {
            statusMessage = "Fill in your API key for '" + name + "'";
            statusColor = 0xFFFF55;
            editMode = false;
        }
    }

    private void save() {
        String name = nameInput.getValue().strip();
        String format = formatInput.getValue().strip();
        String url = urlInput.getValue().strip();
        String model = modelInput.getValue().strip();
        String key = keyInput.getValue().strip();

        if (name.isEmpty()) {
            statusMessage = "Name is required";
            statusColor = 0xFF5555;
            return;
        }
        if (url.isEmpty()) {
            statusMessage = "URL is required";
            statusColor = 0xFF5555;
            return;
        }
        if (key.isEmpty() && !editMode) {
            statusMessage = "API Key is required";
            statusColor = 0xFF5555;
            return;
        }

        // In edit mode with empty key, send special marker to keep existing key
        String sendKey = key.isEmpty() ? "__KEEP__" : key;

        LLMNetwork.CHANNEL.sendToServer(new C2SSetupProviderPacket(name, url, model, sendKey, format));
        statusMessage = "Saved! Provider '" + name + "' sent to server.";
        statusColor = 0x55FF55;
        keyInput.setValue("");
        editMode = false;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;
        var font = Minecraft.getInstance().font;
        graphics.fill(x, y, x + width, y + height, 0x80000000);

        int labelX = x + 4;
        int row = y + 9;

        graphics.drawString(font, "Name:", labelX, row, 0xFFFFFF, false);
        row += ROW_H;

        graphics.drawString(font, "Format:", labelX, row, 0xFFFFFF, false);
        graphics.drawString(font, "(openai/claude/gemini)", x + LABEL_W + 318, row, 0x666666, false);
        row += ROW_H;

        graphics.drawString(font, "URL:", labelX, row, 0xFFFFFF, false);
        row += ROW_H;

        graphics.drawString(font, "Model:", labelX, row, 0xFFFFFF, false);
        row += ROW_H;

        graphics.drawString(font, "API Key:", labelX, row, 0xFFFFFF, false);
        row += ROW_H + 4;

        // saveButton renders itself via Screen

        if (statusMessage != null) {
            graphics.drawString(font, statusMessage, labelX, row + 28, statusColor, false);
        }

        int helpY = y + height - 28;
        graphics.drawString(font, "This creates a provider entry in llmjs.secret (not distributed with modpacks)", labelX, helpY, 0x666666, false);
        graphics.drawString(font, "Modpack presets from providers.json can be configured with /llm setkey <name> <key>", labelX, helpY + 10, 0x666666, false);
    }
}
