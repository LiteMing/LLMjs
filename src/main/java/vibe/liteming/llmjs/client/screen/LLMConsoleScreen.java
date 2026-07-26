package vibe.liteming.llmjs.client.screen;

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
    private boolean takeoverTipShown;
    private Button logTab;
    private Button providersTab;
    private Button routingTab;
    private Button testTab;
    private Button setupTab;

    public LLMConsoleScreen(String statusJson) {
        this(statusJson, null);
    }

    public LLMConsoleScreen(String statusJson, @Nullable String initialTestHandoff) {
        super(Component.literal("LLMjs Console"));
        this.initialStatusJson = statusJson;
        this.initialTestHandoff = initialTestHandoff;
    }

    @Override
    protected void init() {
        int tabY = 22;
        int tabW = 64;
        int gap = 4;
        int totalW = tabW * 5 + gap * 4;
        int startX = Math.max(10, (width - totalW) / 2);

        logTab = Button.builder(Component.literal("Log"), b -> switchTab(Tab.LOG))
                .pos(startX, tabY).size(tabW, 20).build();
        providersTab = Button.builder(Component.literal("Providers"), b -> switchTab(Tab.PROVIDERS))
                .pos(startX + (tabW + gap), tabY).size(tabW, 20).build();
        routingTab = Button.builder(Component.literal("Routing"), b -> switchTab(Tab.ROUTING))
                .pos(startX + (tabW + gap) * 2, tabY).size(tabW, 20).build();
        testTab = Button.builder(Component.literal("Test"), b -> switchTab(Tab.TEST))
                .pos(startX + (tabW + gap) * 3, tabY).size(tabW, 20).build();
        setupTab = Button.builder(Component.literal("Setup"), b -> switchTab(Tab.SETUP))
                .pos(startX + (tabW + gap) * 4, tabY).size(tabW, 20).build();
        addRenderableWidget(logTab);
        addRenderableWidget(providersTab);
        addRenderableWidget(routingTab);
        addRenderableWidget(testTab);
        addRenderableWidget(setupTab);

        int panelY = 48;
        int panelH = height - 58;
        int panelW = width - 20;
        int panelX = 10;

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
        String tip = "{\"level\":\"INFO\",\"provider\":\"system\",\"status\":\"info\",\"latencyMs\":0,"
                + "\"requestSummary\":\"CreatureChat linked: use /llm console for URL/key/model. "
                + "Legacy /creaturechat key|url|model and config GUI endpoints are superseded. "
                + "All CreatureChat LLM traffic appears in this Log tab (including while closed).\","
                + "\"purpose\":\"NOTICE\",\"source\":\"llmjs\","
                + "\"requestBody\":\"\",\"responseBody\":\"\"}";
        logPanel.addEntry(tip);
    }

    private void switchTab(Tab tab) {
        activeTab = tab;
        logPanel.visible = (tab == Tab.LOG);
        providerPanel.visible = (tab == Tab.PROVIDERS);
        routingPanel.visible = (tab == Tab.ROUTING);
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
        super.render(graphics, mouseX, mouseY, partialTick);
        testPanel.render(graphics, mouseX, mouseY, partialTick);
        setupPanel.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (activeTab == Tab.TEST && testPanel != null && testPanel.mouseScrolled(mouseX, mouseY, delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void removed() {
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
