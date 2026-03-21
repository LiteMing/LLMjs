package com.liteming.llmjs.client.screen;

import com.liteming.llmjs.client.ClientEventHandler;
import com.liteming.llmjs.client.widget.LogPanel;
import com.liteming.llmjs.client.widget.ProviderListPanel;
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
    private enum Tab { LOG, PROVIDERS, TEST }

    private Tab activeTab = Tab.LOG;
    private LogPanel logPanel;
    private ProviderListPanel providerPanel;
    private TestPanel testPanel;
    private final String initialStatusJson;

    public LLMConsoleScreen(String statusJson) {
        super(Component.literal("LLMjs Console"));
        this.initialStatusJson = statusJson;
    }

    @Override
    protected void init() {
        int tabY = 10;
        int tabW = 80;

        addRenderableWidget(Button.builder(Component.literal("Log"), b -> switchTab(Tab.LOG))
                .pos(width / 2 - 125, tabY).size(tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Providers"), b -> switchTab(Tab.PROVIDERS))
                .pos(width / 2 - 40, tabY).size(tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Test"), b -> switchTab(Tab.TEST))
                .pos(width / 2 + 45, tabY).size(tabW, 20).build());

        int panelY = 35;
        int panelH = height - 45;
        int panelW = width - 20;
        int panelX = 10;

        logPanel = new LogPanel(panelX, panelY, panelW, panelH);
        providerPanel = new ProviderListPanel(panelX, panelY, panelW, panelH, initialStatusJson);
        testPanel = new TestPanel(panelX, panelY, panelW, panelH, font);

        addRenderableWidget(logPanel);
        addRenderableWidget(providerPanel);
        addRenderableWidget(testPanel);

        switchTab(Tab.LOG);
    }

    private void switchTab(Tab tab) {
        activeTab = tab;
        logPanel.visible = (tab == Tab.LOG);
        providerPanel.visible = (tab == Tab.PROVIDERS);
        testPanel.visible = (tab == Tab.TEST);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 2, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
        if (providerPanel != null) providerPanel.updateStatus(statusJson);
    }

    public void onLogEntry(String logEntryJson) {
        if (logPanel != null) logPanel.addEntry(logEntryJson);
    }
}
