package com.liteming.llmjs.client.widget;

import com.liteming.llmjs.network.LLMNetwork;
import com.liteming.llmjs.network.packet.C2SSetupProviderPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class SetupPanel extends AbstractWidget {
    private final EditBox nameInput;
    private final EditBox formatInput;
    private final EditBox urlInput;
    private final EditBox modelInput;
    private final EditBox keyInput;
    private final Button saveButton;
    private @Nullable String statusMessage;
    private int statusColor = 0xFFFFFF;

    private static final int LABEL_W = 60;
    private static final int ROW_H = 24;

    public SetupPanel(int x, int y, int width, int height, Font font) {
        super(x, y, width, height, Component.literal("Setup"));

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

    /**
     * Pre-fill the form for setting key on an existing provider.
     */
    public void prefill(String name, String format, String url, String model) {
        nameInput.setValue(name);
        if (format != null && !format.isEmpty() && !"-".equals(format)) formatInput.setValue(format);
        if (url != null && !url.isEmpty()) urlInput.setValue(url);
        if (model != null && !model.isEmpty()) modelInput.setValue(model);
        keyInput.setValue("");
        statusMessage = "Fill in your API key for '" + name + "'";
        statusColor = 0xFFFF55;
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
        if (key.isEmpty()) {
            statusMessage = "API Key is required";
            statusColor = 0xFF5555;
            return;
        }

        LLMNetwork.CHANNEL.sendToServer(new C2SSetupProviderPacket(name, url, model, key, format));
        statusMessage = "Saved! Provider '" + name + "' sent to server.";
        statusColor = 0x55FF55;
        keyInput.setValue("");
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x80000000);

        int labelX = getX() + 4;
        int row = getY() + 9;

        graphics.drawString(font, "Name:", labelX, row, 0xFFFFFF, false);
        nameInput.render(graphics, mouseX, mouseY, partialTick);
        row += ROW_H;

        graphics.drawString(font, "Format:", labelX, row, 0xFFFFFF, false);
        formatInput.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, "(openai/claude/gemini)", getX() + LABEL_W + 318, row, 0x666666, false);
        row += ROW_H;

        graphics.drawString(font, "URL:", labelX, row, 0xFFFFFF, false);
        urlInput.render(graphics, mouseX, mouseY, partialTick);
        row += ROW_H;

        graphics.drawString(font, "Model:", labelX, row, 0xFFFFFF, false);
        modelInput.render(graphics, mouseX, mouseY, partialTick);
        row += ROW_H;

        graphics.drawString(font, "API Key:", labelX, row, 0xFFFFFF, false);
        keyInput.render(graphics, mouseX, mouseY, partialTick);
        row += ROW_H + 4;

        saveButton.render(graphics, mouseX, mouseY, partialTick);

        if (statusMessage != null) {
            graphics.drawString(font, statusMessage, labelX, row + 28, statusColor, false);
        }

        // Help text at bottom
        int helpY = getY() + height - 28;
        graphics.drawString(font, "This creates a provider entry in llmjs.secret (not distributed with modpacks)", labelX, helpY, 0x666666, false);
        graphics.drawString(font, "Modpack presets from providers.json can be configured with /llm setkey <name> <key>", labelX, helpY + 10, 0x666666, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;
        boolean handled = false;
        handled |= nameInput.mouseClicked(mouseX, mouseY, button);
        handled |= formatInput.mouseClicked(mouseX, mouseY, button);
        handled |= urlInput.mouseClicked(mouseX, mouseY, button);
        handled |= modelInput.mouseClicked(mouseX, mouseY, button);
        handled |= keyInput.mouseClicked(mouseX, mouseY, button);
        handled |= saveButton.mouseClicked(mouseX, mouseY, button);
        return handled || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (nameInput.isFocused()) return nameInput.keyPressed(keyCode, scanCode, modifiers);
        if (formatInput.isFocused()) return formatInput.keyPressed(keyCode, scanCode, modifiers);
        if (urlInput.isFocused()) return urlInput.keyPressed(keyCode, scanCode, modifiers);
        if (modelInput.isFocused()) return modelInput.keyPressed(keyCode, scanCode, modifiers);
        if (keyInput.isFocused()) return keyInput.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (nameInput.isFocused()) return nameInput.charTyped(c, modifiers);
        if (formatInput.isFocused()) return formatInput.charTyped(c, modifiers);
        if (urlInput.isFocused()) return urlInput.charTyped(c, modifiers);
        if (modelInput.isFocused()) return modelInput.charTyped(c, modifiers);
        if (keyInput.isFocused()) return keyInput.charTyped(c, modifiers);
        return super.charTyped(c, modifiers);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
