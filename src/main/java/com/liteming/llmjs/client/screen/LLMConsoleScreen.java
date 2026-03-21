package com.liteming.llmjs.client.screen;

import com.liteming.llmjs.client.ClientEventHandler;
import com.liteming.llmjs.client.widget.LogPanel;
import com.liteming.llmjs.client.widget.ProviderListPanel;
import com.liteming.llmjs.client.widget.SetupPanel;
import com.liteming.llmjs.client.widget.TestPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class LLMConsoleScreen extends Screen {
    private enum Tab { LOG, PROVIDERS, TEST, SETUP }

    private Tab activeTab = Tab.LOG;
    private LogPanel logPanel;
    private ProviderListPanel providerPanel;
    private TestPanel testPanel;
    private SetupPanel setupPanel;
    private final String initialStatusJson;

    public LLMConsoleScreen(String statusJson) {
        super(Component.literal("LLMjs Console"));
        this.initialStatusJson = statusJson;
    }

    @Override
    protected void init() {
        int tabY = 10;
        int tabW = 70;
        int startX = width / 2 - (tabW * 4 + 15) / 2;

        addRenderableWidget(Button.builder(Component.literal("Log"), b -> switchTab(Tab.LOG))
                .pos(startX, tabY).size(tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Providers"), b -> switchTab(Tab.PROVIDERS))
                .pos(startX + tabW + 5, tabY).size(tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Test"), b -> switchTab(Tab.TEST))
                .pos(startX + (tabW + 5) * 2, tabY).size(tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Setup"), b -> switchTab(Tab.SETUP))
                .pos(startX + (tabW + 5) * 3, tabY).size(tabW, 20).build());

        int panelY = 35;
        int panelH = height - 45;
        int panelW = width - 20;
        int panelX = 10;

        // LogPanel and ProviderListPanel are AbstractWidgets (no EditBox, just render+scroll)
        logPanel = new LogPanel(panelX, panelY, panelW, panelH);
        providerPanel = new ProviderListPanel(panelX, panelY, panelW, panelH, initialStatusJson);
        addRenderableWidget(logPanel);
        addRenderableWidget(providerPanel);

        // TestPanel and SetupPanel are plain objects - register their child widgets directly
        testPanel = new TestPanel(panelX, panelY, panelW, panelH, font);
        setupPanel = new SetupPanel(panelX, panelY, panelW, panelH, font);
        for (var w : testPanel.getWidgets()) addRenderableWidget(w);
        for (var w : setupPanel.getWidgets()) addRenderableWidget(w);

        // Sync initial provider names for tab-complete
        testPanel.updateProviderNames(providerPanel.getProviderNames());

        switchTab(Tab.LOG);
    }

    private void switchTab(Tab tab) {
        activeTab = tab;
        logPanel.visible = (tab == Tab.LOG);
        providerPanel.visible = (tab == Tab.PROVIDERS);
        testPanel.setVisible(tab == Tab.TEST);
        setupPanel.setVisible(tab == Tab.SETUP);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 2, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render panel backgrounds and labels (non-widget parts)
        testPanel.render(graphics, mouseX, mouseY, partialTick);
        setupPanel.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Forward Tab to TestPanel for provider cycling
        if (keyCode == 258 && activeTab == Tab.TEST && testPanel != null) {
            if (testPanel.handleTabComplete(keyCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        super.removed();
        ClientEventHandler.clearActiveConsole();
    }

    public void onChatResponse(UUID requestId, boolean success, @Nullable String content, @Nullable String error) {
        if (testPanel != null) testPanel.onResponse(success, content, error);
    }

    public void onStatusUpdate(String statusJson) {
        if (providerPanel != null) {
            providerPanel.updateStatus(statusJson);
            // Sync provider names to TestPanel for tab-complete
            if (testPanel != null) {
                testPanel.updateProviderNames(providerPanel.getProviderNames());
            }
        }
    }

    public void onLogEntry(String logEntryJson) {
        if (logPanel != null) logPanel.addEntry(logEntryJson);
    }

    public void openSetupFor(String name, String format, String url, String model, @Nullable String maskedKey) {
        switchTab(Tab.SETUP);
        if (setupPanel != null) setupPanel.prefill(name, format, url, model, maskedKey);
    }
}
