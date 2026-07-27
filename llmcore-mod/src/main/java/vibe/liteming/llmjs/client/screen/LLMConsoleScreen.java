package vibe.liteming.llmjs.client.screen;

import com.google.gson.JsonObject;
import vibe.liteming.llmjs.client.ClientEventHandler;
import vibe.liteming.llmjs.client.widget.LogPanel;
import vibe.liteming.llmjs.client.widget.ProviderListPanel;
import vibe.liteming.llmjs.client.widget.RoutingPanel;
import vibe.liteming.llmjs.client.widget.SetupPanel;
import vibe.liteming.llmjs.client.widget.TestPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;
import static vibe.liteming.llmjs.client.ConsoleTexts.tooltip;

@OnlyIn(Dist.CLIENT)
public class LLMConsoleScreen extends Screen {
    private enum Tab { LOG, PROVIDERS, ROUTING, TEST, SETUP }

    private Tab activeTab = Tab.LOG;
    private LogPanel logPanel;
    private ProviderListPanel providerPanel;
    private RoutingPanel routingPanel;
    private TestPanel testPanel;
    private SetupPanel setupPanel;
    private final String initialStatusJson;
    private final @Nullable String initialTestHandoff;
    private @Nullable Screen returnScreen;
    private boolean takeoverTipShown;
    private Button logTab;
    private Button providersTab;
    private Button routingTab;
    private Button testTab;
    private Button setupTab;

    public LLMConsoleScreen(String statusJson) {
        this(statusJson, null, null);
    }

    public LLMConsoleScreen(String statusJson, @Nullable String initialTestHandoff) {
        this(statusJson, initialTestHandoff, null);
    }

    public LLMConsoleScreen(String statusJson, @Nullable String initialTestHandoff,
            @Nullable Screen returnScreen) {
        super(text("title"));
        this.initialStatusJson = statusJson;
        this.initialTestHandoff = initialTestHandoff;
        this.returnScreen = returnScreen;
    }

    @Override
    protected void init() {
        int tabY = 22;
        int gap = width < 300 ? 2 : 4;
        int tabW = Math.max(36, Math.min(64, (width - 20 - gap * 4) / 5));
        int totalW = tabW * 5 + gap * 4;
        int startX = Math.max(4, (width - totalW) / 2);

        logTab = tooltip(Button.builder(text("tab.log"), b -> switchTab(Tab.LOG))
                .pos(startX, tabY).size(tabW, 20).build(), "tab.log.tip");
        providersTab = tooltip(Button.builder(text("tab.providers"), b -> switchTab(Tab.PROVIDERS))
                .pos(startX + (tabW + gap), tabY).size(tabW, 20).build(), "tab.providers.tip");
        routingTab = tooltip(Button.builder(text("tab.routing"), b -> switchTab(Tab.ROUTING))
                .pos(startX + (tabW + gap) * 2, tabY).size(tabW, 20).build(), "tab.routing.tip");
        testTab = tooltip(Button.builder(text("tab.test"), b -> switchTab(Tab.TEST))
                .pos(startX + (tabW + gap) * 3, tabY).size(tabW, 20).build(), "tab.test.tip");
        setupTab = tooltip(Button.builder(text("tab.setup"), b -> switchTab(Tab.SETUP))
                .pos(startX + (tabW + gap) * 4, tabY).size(tabW, 20).build(), "tab.setup.tip");
        addRenderableWidget(logTab);
        addRenderableWidget(providersTab);
        addRenderableWidget(routingTab);
        addRenderableWidget(testTab);
        addRenderableWidget(setupTab);

        int panelY = 48;
        int margin = width < 260 ? 4 : 10;
        int panelH = Math.max(1, height - 58);
        int panelW = Math.max(1, width - margin * 2);
        int panelX = margin;

        logPanel = new LogPanel(panelX, panelY, panelW, panelH);
        providerPanel = new ProviderListPanel(panelX, panelY, panelW, panelH, initialStatusJson);
        routingPanel = new RoutingPanel(panelX, panelY, panelW, panelH, font, initialStatusJson);
        addRenderableWidget(logPanel);
        addRenderableWidget(providerPanel);
        addRenderableWidget(routingPanel);

        testPanel = new TestPanel(panelX, panelY, panelW, panelH, font);
        setupPanel = new SetupPanel(panelX, panelY, panelW, panelH, font);
        for (var w : testPanel.getWidgets()) addRenderableWidget(w);
        for (var w : setupPanel.getWidgets()) addRenderableWidget(w);

        testPanel.updateStatus(initialStatusJson);
        maybeShowTakeoverTip();
        if (initialTestHandoff != null && !initialTestHandoff.isBlank()) {
            testPanel.loadHandoff(initialTestHandoff);
            switchTab(Tab.TEST);
        } else {
            switchTab(Tab.LOG);
        }
    }

    @Override
    protected void repositionElements() {
        if (logTab == null || logPanel == null || testPanel == null || setupPanel == null) {
            super.repositionElements();
            return;
        }
        int tabY = 22;
        int gap = width < 300 ? 2 : 4;
        int tabW = Math.max(36, Math.min(64, (width - 20 - gap * 4) / 5));
        int totalW = tabW * 5 + gap * 4;
        int startX = Math.max(4, (width - totalW) / 2);
        List<Button> tabs = List.of(logTab, providersTab, routingTab, testTab, setupTab);
        for (int index = 0; index < tabs.size(); index++) {
            Button tab = tabs.get(index);
            tab.setX(startX + (tabW + gap) * index);
            tab.setY(tabY);
            tab.setWidth(tabW);
            tab.setHeight(20);
        }

        int panelY = 48;
        int margin = width < 260 ? 4 : 10;
        int panelH = Math.max(1, height - 58);
        int panelW = Math.max(1, width - margin * 2);
        int panelX = margin;
        logPanel.setBounds(panelX, panelY, panelW, panelH);
        providerPanel.setBounds(panelX, panelY, panelW, panelH);
        routingPanel.setBounds(panelX, panelY, panelW, panelH);
        testPanel.setBounds(panelX, panelY, panelW, panelH);
        setupPanel.setBounds(panelX, panelY, panelW, panelH);
        switchTab(activeTab);
    }

    private void maybeShowTakeoverTip() {
        if (takeoverTipShown || logPanel == null) return;
        boolean linked = false;
        try {
            linked = net.minecraftforge.fml.ModList.get().isLoaded("creaturechat");
            if (!linked && providerPanel != null) {
                for (String name : providerPanel.getProviderNames()) {
                    if (name != null && (name.startsWith("dialogue_") || name.startsWith("creaturechat_")
                            || "dialogue_primary".equals(name))) {
                        linked = true;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
        if (!linked) return;
        takeoverTipShown = true;
        JsonObject tip = new JsonObject();
        tip.addProperty("level", "INFO");
        tip.addProperty("provider", "system");
        tip.addProperty("status", "info");
        tip.addProperty("latencyMs", 0);
        tip.addProperty("requestSummary", string("notice.creaturechat_linked"));
        tip.addProperty("purpose", "NOTICE");
        tip.addProperty("source", "llmjs");
        tip.addProperty("requestBody", "");
        tip.addProperty("responseBody", "");
        logPanel.addEntry(tip.toString());
    }

    private void switchTab(Tab tab) {
        activeTab = tab;
        logPanel.visible = (tab == Tab.LOG);
        providerPanel.visible = (tab == Tab.PROVIDERS);
        routingPanel.setPanelVisible(tab == Tab.ROUTING);
        testPanel.setVisible(tab == Tab.TEST);
        setupPanel.setVisible(tab == Tab.SETUP);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        // Active tab underline
        Button active = switch (activeTab) {
            case LOG -> logTab;
            case PROVIDERS -> providersTab;
            case ROUTING -> routingTab;
            case TEST -> testTab;
            case SETUP -> setupTab;
        };
        if (active != null) {
            graphics.fill(active.getX(), active.getY() + active.getHeight() + 1,
                    active.getX() + active.getWidth(), active.getY() + active.getHeight() + 3, 0xFF55AAFF);
        }
        testPanel.render(graphics, mouseX, mouseY, partialTick);
        setupPanel.render(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null && returnScreen != null) {
            Screen parent = returnScreen;
            returnScreen = null;
            minecraft.setScreen(parent);
            return;
        }
        super.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 258 && activeTab == Tab.TEST && testPanel != null) {
            if (testPanel.handleTabComplete(keyCode)) return true;
        }
        // Ctrl+C / Cmd+C copies current selection in Log tab (priority: detail area, then list).
        if ((keyCode == 67) && (Screen.hasControlDown() || (Minecraft.ON_OSX && Screen.hasAltDown()))
                && activeTab == Tab.LOG && logPanel != null) {
            if (logPanel.handleCopyShortcut()) return true;
        }
        if ((keyCode == 67) && (Screen.hasControlDown() || (Minecraft.ON_OSX && Screen.hasAltDown()))
                && activeTab == Tab.TEST && testPanel != null) {
            if (testPanel.handleCopyShortcut()) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeTab == Tab.TEST && testPanel != null) {
            if (testPanel.mouseClicked(mouseX, mouseY, button)) {
                setFocused(null);
                return true;
            }
            testPanel.deactivateResponseSelection();
        }
        if (activeTab == Tab.SETUP && setupPanel != null
                && setupPanel.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (activeTab == Tab.TEST && testPanel != null && testPanel.mouseDragged(mouseX, mouseY, button)) {
            return true;
        }
        if (activeTab == Tab.SETUP && setupPanel != null
                && setupPanel.mouseDragged(mouseX, mouseY, button)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (activeTab == Tab.TEST && testPanel != null && testPanel.mouseReleased(button)) return true;
        if (activeTab == Tab.SETUP && setupPanel != null && setupPanel.mouseReleased(button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (activeTab == Tab.TEST && testPanel != null && testPanel.mouseScrolled(mouseX, mouseY, delta)) {
            return true;
        }
        if (activeTab == Tab.SETUP && setupPanel != null && setupPanel.mouseScrolled(mouseX, mouseY, delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void removed() {
        returnScreen = null;
        super.removed();
        ClientEventHandler.clearActiveConsole();
    }

    public void onChatResponse(UUID requestId, String resultJson) {
        if (testPanel != null) testPanel.onResponse(requestId, resultJson);
    }

    public void loadTestHandoff(String handoffJson) {
        if (testPanel == null) return;
        testPanel.loadHandoff(handoffJson);
        switchTab(Tab.TEST);
    }

    public void onStatusUpdate(String statusJson) {
        if (providerPanel != null) {
            providerPanel.updateStatus(statusJson);
            if (testPanel != null) {
                testPanel.updateStatus(statusJson);
            }
            maybeShowTakeoverTip();
        }
        if (routingPanel != null) {
            routingPanel.updateStatus(statusJson);
        }
    }

    public void onLogEntry(String logEntryJson) {
        if (logPanel != null) logPanel.addEntry(logEntryJson);
    }

    public void onLogHistory(List<String> entries) {
        if (logPanel != null) {
            // History replaces buffer; re-show tip after so it is not wiped by setHistory
            takeoverTipShown = false;
            logPanel.setHistory(entries);
            maybeShowTakeoverTip();
        }
    }

    public void openSetupFor(String name, String format, String url, String model, @Nullable String maskedKey) {
        switchTab(Tab.SETUP);
        if (setupPanel != null) setupPanel.prefill(name, format, url, model, maskedKey);
    }

    public void onVisionProbeResult(String providerName, boolean supported, String error, long latencyMs) {
        if (testPanel != null) testPanel.onVisionProbeResult(providerName, supported, error, latencyMs);
    }
}
