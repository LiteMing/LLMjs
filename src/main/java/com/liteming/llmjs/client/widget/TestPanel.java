package com.liteming.llmjs.client.widget;

import com.liteming.llmjs.network.LLMNetwork;
import com.liteming.llmjs.network.packet.C2SChatRequestPacket;
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

import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class TestPanel extends AbstractWidget {
    private final EditBox providerInput;
    private final EditBox promptInput;
    private final Button sendButton;
    private @Nullable String responseText;
    private boolean waiting = false;

    public TestPanel(int x, int y, int width, int height, Font font) {
        super(x, y, width, height, Component.literal("Test"));

        providerInput = new EditBox(font, x + 80, y + 4, 150, 18, Component.literal("Provider"));
        providerInput.setValue("openai");
        providerInput.setMaxLength(64);

        promptInput = new EditBox(font, x + 80, y + 28, width - 170, 18, Component.literal("Prompt"));
        promptInput.setMaxLength(1000);
        promptInput.setValue("Hello, this is a test.");

        sendButton = Button.builder(Component.literal("Send"), b -> sendTest())
                .pos(x + width - 80, y + 28).size(70, 18).build();
    }

    private void sendTest() {
        if (waiting) return;
        waiting = true;
        responseText = "Sending...";
        UUID requestId = UUID.randomUUID();
        LLMNetwork.CHANNEL.sendToServer(
                new C2SChatRequestPacket(requestId, promptInput.getValue(), providerInput.getValue()));
    }

    public void onResponse(boolean success, @Nullable String content, @Nullable String error) {
        waiting = false;
        responseText = success ? content : ("ERROR: " + error);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x80000000);

        graphics.drawString(font, "Provider:", getX() + 4, getY() + 9, 0xFFFFFF, false);
        graphics.drawString(font, "Prompt:", getX() + 4, getY() + 33, 0xFFFFFF, false);

        providerInput.render(graphics, mouseX, mouseY, partialTick);
        promptInput.render(graphics, mouseX, mouseY, partialTick);
        sendButton.render(graphics, mouseX, mouseY, partialTick);

        int respY = getY() + 55;
        graphics.drawString(font, "Response:", getX() + 4, respY, 0xAAAAAA, false);
        if (responseText != null) {
            var lines = font.split(Component.literal(responseText), width - 12);
            int lineY = respY + 12;
            for (var line : lines) {
                if (lineY > getY() + height - 12) break;
                graphics.drawString(font, line, getX() + 4, lineY, 0xFFFFFF, false);
                lineY += 10;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;
        boolean handled = false;
        handled |= providerInput.mouseClicked(mouseX, mouseY, button);
        handled |= promptInput.mouseClicked(mouseX, mouseY, button);
        handled |= sendButton.mouseClicked(mouseX, mouseY, button);
        return handled || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (providerInput.isFocused()) return providerInput.keyPressed(keyCode, scanCode, modifiers);
        if (promptInput.isFocused()) return promptInput.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (providerInput.isFocused()) return providerInput.charTyped(c, modifiers);
        if (promptInput.isFocused()) return promptInput.charTyped(c, modifiers);
        return super.charTyped(c, modifiers);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
