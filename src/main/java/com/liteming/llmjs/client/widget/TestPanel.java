package com.liteming.llmjs.client.widget;

import com.liteming.llmjs.config.GlobalConfig;
import com.liteming.llmjs.network.LLMNetwork;
import com.liteming.llmjs.network.packet.C2SChatRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class TestPanel {
    private final int x, y, width, height;
    private final EditBox providerInput;
    private final EditBox promptInput;
    private final Button sendButton;
    private final Button prevTemplateBtn;
    private final Button nextTemplateBtn;
    private final Button saveTemplateBtn;
    private @Nullable String responseText;
    private boolean waiting = false;
    private boolean visible = true;

    // Provider tab-complete
    private List<String> providerNames = new ArrayList<>();
    private int providerCycleIndex = -1;
    private String providerCyclePrefix = "";

    // Prompt templates
    private final List<PromptTemplate> templates = new ArrayList<>();
    private int currentTemplateIndex = -1;

    public record PromptTemplate(String name, String prompt) {}

    public TestPanel(int x, int y, int width, int height, Font font) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        providerInput = new EditBox(font, x + 80, y + 4, 150, 18, Component.literal("Provider"));
        providerInput.setValue("");
        providerInput.setMaxLength(64);

        promptInput = new EditBox(font, x + 80, y + 28, width - 170, 18, Component.literal("Prompt"));
        promptInput.setMaxLength(1000);
        promptInput.setValue("Hello, this is a test.");

        sendButton = Button.builder(Component.literal("Send"), b -> sendTest())
                .pos(x + width - 80, y + 28).size(70, 18).build();

        // Template navigation buttons
        int templateY = y + 52;
        prevTemplateBtn = Button.builder(Component.literal("<"), b -> cycleTemplate(-1))
                .pos(x + 80, templateY).size(20, 16).build();
        nextTemplateBtn = Button.builder(Component.literal(">"), b -> cycleTemplate(1))
                .pos(x + 102, templateY).size(20, 16).build();
        saveTemplateBtn = Button.builder(Component.literal("Save"), b -> saveCurrentAsTemplate())
                .pos(x + 126, templateY).size(40, 16).build();

        // Load templates from config/llmjs/templates.json
        loadTemplatesFromConfig();
    }

    private void loadTemplatesFromConfig() {
        templates.clear();
        List<GlobalConfig.Template> loaded = GlobalConfig.loadTemplates();
        for (GlobalConfig.Template t : loaded) {
            templates.add(new PromptTemplate(t.name(), t.prompt()));
        }
        // Fallback if file was empty/missing
        if (templates.isEmpty()) {
            templates.add(new PromptTemplate("connection", "Say 'ok' to confirm connection."));
            templates.add(new PromptTemplate("simple", "Hello, this is a test."));
        }
    }

    public List<net.minecraft.client.gui.components.AbstractWidget> getWidgets() {
        return List.of(providerInput, promptInput, sendButton, prevTemplateBtn, nextTemplateBtn, saveTemplateBtn);
    }

    public void setVisible(boolean v) {
        this.visible = v;
        providerInput.visible = v;
        promptInput.visible = v;
        sendButton.visible = v;
        prevTemplateBtn.visible = v;
        nextTemplateBtn.visible = v;
        saveTemplateBtn.visible = v;
    }

    public boolean isVisible() { return visible; }

    /** Update the available provider names for tab-complete. */
    public void updateProviderNames(List<String> names) {
        this.providerNames = new ArrayList<>(names);
    }

    /**
     * Handle Tab key for provider name cycling.
     * Called from the Screen's keyPressed.
     */
    public boolean handleTabComplete(int keyCode) {
        // Tab key = 258
        if (keyCode != 258) return false;
        if (!providerInput.isFocused()) return false;
        if (providerNames.isEmpty()) return false;

        String current = providerInput.getValue();

        // Start a new cycle if prefix changed
        if (!current.equals(providerCyclePrefix) && (providerCycleIndex == -1 || !isMatchingCycle(current))) {
            providerCyclePrefix = current;
            providerCycleIndex = -1;
        }

        // Find next matching provider
        List<String> matches = new ArrayList<>();
        for (String name : providerNames) {
            if (providerCyclePrefix.isEmpty() || name.toLowerCase().startsWith(providerCyclePrefix.toLowerCase())) {
                matches.add(name);
            }
        }
        if (matches.isEmpty()) return false;

        providerCycleIndex = (providerCycleIndex + 1) % matches.size();
        providerInput.setValue(matches.get(providerCycleIndex));
        return true;
    }

    private boolean isMatchingCycle(String current) {
        // Check if current value is one of the cycle results
        for (String name : providerNames) {
            if (name.equals(current)) return true;
        }
        return false;
    }

    // === Template management ===

    private void cycleTemplate(int direction) {
        if (templates.isEmpty()) return;
        if (currentTemplateIndex == -1) {
            currentTemplateIndex = direction > 0 ? 0 : templates.size() - 1;
        } else {
            currentTemplateIndex = (currentTemplateIndex + direction + templates.size()) % templates.size();
        }
        PromptTemplate t = templates.get(currentTemplateIndex);
        promptInput.setValue(t.prompt);
    }

    private void saveCurrentAsTemplate() {
        String prompt = promptInput.getValue().strip();
        if (prompt.isEmpty()) return;
        String name = prompt.length() > 20 ? prompt.substring(0, 20) + "..." : prompt;
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).prompt.equals(prompt)) {
                currentTemplateIndex = i;
                return;
            }
        }
        templates.add(new PromptTemplate(name, prompt));
        currentTemplateIndex = templates.size() - 1;
        persistTemplates();
    }

    private void persistTemplates() {
        List<GlobalConfig.Template> toSave = new ArrayList<>();
        for (PromptTemplate t : templates) {
            toSave.add(new GlobalConfig.Template(t.name, t.prompt));
        }
        GlobalConfig.saveTemplates(toSave);
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

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;
        var font = Minecraft.getInstance().font;
        graphics.fill(x, y, x + width, y + height, 0x80000000);

        graphics.drawString(font, "Provider:", x + 4, y + 9, 0xFFFFFF, false);
        graphics.drawString(font, "Prompt:", x + 4, y + 33, 0xFFFFFF, false);

        // Template indicator
        int templateY = y + 54;
        graphics.drawString(font, "Template:", x + 4, templateY, 0xAAAAAA, false);
        if (currentTemplateIndex >= 0 && currentTemplateIndex < templates.size()) {
            String tName = templates.get(currentTemplateIndex).name;
            graphics.drawString(font, "(" + (currentTemplateIndex + 1) + "/" + templates.size() + ") " + tName,
                    x + 172, templateY, 0x888888, false);
        } else {
            graphics.drawString(font, "(" + templates.size() + " saved)", x + 172, templateY, 0x666666, false);
        }

        // Tab hint for provider
        if (providerInput.isFocused()) {
            graphics.drawString(font, "[Tab to cycle]", x + 236, y + 9, 0x556688, false);
        }

        int respY = y + 74;
        graphics.drawString(font, "Response:", x + 4, respY, 0xAAAAAA, false);
        if (responseText != null) {
            var lines = font.split(Component.literal(responseText), width - 12);
            int lineY = respY + 12;
            for (var line : lines) {
                if (lineY > y + height - 12) break;
                graphics.drawString(font, line, x + 4, lineY, 0xFFFFFF, false);
                lineY += 10;
            }
        }
    }
}
