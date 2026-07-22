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
    private final Button clearKeyButton;
    private @Nullable String statusMessage;
    private int statusColor = 0xFFFFFF;
    private boolean visible = true;
    private boolean editMode = false;
    private @Nullable String currentMaskedKey;

    private static final int LABEL_W = 70;
    private static final int ROW_H = 26;

    public SetupPanel(int x, int y, int width, int height, Font font) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        int inputX = x + LABEL_W + 10;
        int inputW = Math.min(width - LABEL_W - 28, 360);
        int row = y + 10;

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
        // Hide typed characters for security; leave empty to keep existing key
        keyInput.setFormatter((value, pos) -> net.minecraft.util.FormattedCharSequence.forward(
                "*".repeat(Math.max(0, value.length())), net.minecraft.network.chat.Style.EMPTY));
        row += ROW_H + 6;

        saveButton = Button.builder(Component.literal("Save"), b -> save())
                .pos(inputX, row).size(100, 20).build();
        clearKeyButton = Button.builder(Component.literal("Clear key field"), b -> {
            keyInput.setValue("");
            statusMessage = "Key field cleared (leave empty on Save to keep existing key)";
            statusColor = 0xAAAAAA;
        }).pos(inputX + 108, row).size(120, 20).build();
    }

    public List<net.minecraft.client.gui.components.AbstractWidget> getWidgets() {
        return List.of(nameInput, formatInput, urlInput, modelInput, keyInput, saveButton, clearKeyButton);
    }

    public void setVisible(boolean v) {
        this.visible = v;
        nameInput.visible = v;
        formatInput.visible = v;
        urlInput.visible = v;
        modelInput.visible = v;
        keyInput.visible = v;
        saveButton.visible = v;
        clearKeyButton.visible = v;
    }

    public boolean isVisible() { return visible; }

    public void prefill(String name, String format, String url, String model, @Nullable String maskedKey) {
        editMode = true;
        currentMaskedKey = maskedKey;
        nameInput.setValue(name == null ? "" : name);
        if (format != null && !format.isEmpty() && !"-".equals(format)) formatInput.setValue(format);
        urlInput.setValue(url == null ? "" : url);
        modelInput.setValue(model == null ? "" : model);
        keyInput.setValue("");
        if (maskedKey != null && !maskedKey.isBlank() && !maskedKey.equals("***")) {
            statusMessage = "Editing '" + name + "'. Key on file: " + maskedKey + " (leave blank to keep)";
            statusColor = 0x55FF55;
        } else if (maskedKey != null && !maskedKey.isBlank()) {
            statusMessage = "Editing '" + name + "'. Key is set (hidden). Leave blank to keep.";
            statusColor = 0x55FF55;
        } else {
            statusMessage = "Editing '" + name + "'. No key set yet - enter one to enable.";
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
            statusMessage = "API Key is required for new providers";
            statusColor = 0xFF5555;
            return;
        }

        String sendKey = key.isEmpty() ? "__KEEP__" : key;
        LLMNetwork.CHANNEL.sendToServer(new C2SSetupProviderPacket(name, url, model, sendKey, format));
        statusMessage = "Saved provider '" + name + "'" + (key.isEmpty() ? " (key unchanged)" : "");
        statusColor = 0x55FF55;
        keyInput.setValue("");
        editMode = true;
        if (!key.isEmpty()) currentMaskedKey = "****";
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;
        var font = Minecraft.getInstance().font;
        graphics.fill(x, y, x + width, y + height, 0x90000000);

        int labelX = x + 8;
        int row = y + 14;

        graphics.drawString(font, "Name", labelX, row, 0xFFFFFF, false);
        row += ROW_H;
        graphics.drawString(font, "Format", labelX, row, 0xFFFFFF, false);
        graphics.drawString(font, "openai / claude / gemini", x + LABEL_W + 380, row, 0x666666, false);
        row += ROW_H;
        graphics.drawString(font, "URL", labelX, row, 0xFFFFFF, false);
        row += ROW_H;
        graphics.drawString(font, "Model", labelX, row, 0xFFFFFF, false);
        row += ROW_H;
        graphics.drawString(font, "API Key", labelX, row, 0xFFFFFF, false);
        if (currentMaskedKey != null && !currentMaskedKey.isBlank()) {
            graphics.drawString(font, "on file: " + currentMaskedKey, x + LABEL_W + 380, row, 0x55AA55, false);
        }
        row += ROW_H + 8;

        if (statusMessage != null) {
            graphics.drawString(font, statusMessage, labelX, row + 24, statusColor, false);
        }

        int helpY = y + height - 36;
        graphics.drawString(font, "Keys go to llmjs.secret (not shipped with modpacks).", labelX, helpY, 0x666666, false);
        graphics.drawString(font, "CreatureChat dialogue_primary / legacy endpoints are managed here when shared providers are active.",
                labelX, helpY + 12, 0x666666, false);
    }
}
