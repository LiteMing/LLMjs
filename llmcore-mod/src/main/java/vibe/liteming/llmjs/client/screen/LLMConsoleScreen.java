package vibe.liteming.llmjs.client.screen;

import com.google.gson.JsonParser;
import vibe.liteming.llmjs.client.ClientEventHandler;
import vibe.liteming.llmjs.client.widget.LogPanel;
import vibe.liteming.llmjs.client.widget.BudgetPanel;
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
    private enum Tab { LOG, BUDGET, PROVIDERS, ROUTING, TEST, SETUP }

    private Tab activeTab = Tab.LOG;
    private LogPanel logPanel;
    private BudgetPanel budgetPanel;
    private ProviderListPanel providerPanel;
    private RoutingPanel routingPanel;
    private TestPanel testPanel;
    private SetupPanel setupPanel;
    private final String initialStatusJson;
    private final @Nullable String initialTestHandoff;
    private @Nullable Screen returnScreen;
    private boolean canView;
    private boolean canTest;
    private boolean canAdminister;
    private boolean canManageBudgets;
    private boolean budgetDefaultConfirmationRequired;
    private Button logTab;
    private Button budgetTab;
    private Button providersTab;
    private Button routingTab;
    private Button testTab;
    private Button setupTab;
    private Button confirmBudgetButton;

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
        readPermissions(statusJson);
    }

    @Override
    protected void init() {
        int tabY = tabY();
        int gap = width < 340 ? 2 : 4;
        int tabW = Math.max(28, Math.min(64, (width - 20 - gap * 5) / 6));
        int totalW = tabW * 6 + gap * 5;
        int startX = Math.max(4, (width - totalW) / 2);

        logTab = tooltip(Button.builder(text("tab.log"), b -> switchTab(Tab.LOG))
                .pos(startX, tabY).size(tabW, 20).build(), "tab.log.tip");
        budgetTab = tooltip(Button.builder(text("tab.budget"), b -> switchTab(Tab.BUDGET))
                .pos(startX + (tabW + gap), tabY).size(tabW, 20).build(), "tab.budget.tip");
        providersTab = tooltip(Button.builder(text("tab.providers"), b -> switchTab(Tab.PROVIDERS))
                .pos(startX + (tabW + gap) * 2, tabY).size(tabW, 20).build(), "tab.providers.tip");
        routingTab = tooltip(Button.builder(text("tab.routing"), b -> switchTab(Tab.ROUTING))
                .pos(startX + (tabW + gap) * 3, tabY).size(tabW, 20).build(), "tab.routing.tip");
        testTab = tooltip(Button.builder(text("tab.test"), b -> switchTab(Tab.TEST))
                .pos(startX + (tabW + gap) * 4, tabY).size(tabW, 20).build(), "tab.test.tip");
        setupTab = tooltip(Button.builder(text("tab.setup"), b -> switchTab(Tab.SETUP))
                .pos(startX + (tabW + gap) * 5, tabY).size(tabW, 20).build(), "tab.setup.tip");
        addRenderableWidget(logTab);
        addRenderableWidget(budgetTab);
        addRenderableWidget(providersTab);
        addRenderableWidget(routingTab);
        addRenderableWidget(testTab);
        addRenderableWidget(setupTab);
        confirmBudgetButton = tooltip(Button.builder(text("budget.confirm"), button -> {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.connection.sendCommand("llm budget confirm-default");
            }
        }).pos(Math.max(4, width - 134), 19).size(128, 18).build(), "budget.confirm.tip");
        addRenderableWidget(confirmBudgetButton);

        int panelY = panelY();
        int margin = width < 260 ? 4 : 10;
        int panelH = Math.max(1, height - panelY - 10);
        int panelW = Math.max(1, width - margin * 2);
        int panelX = margin;

        logPanel = new LogPanel(panelX, panelY, panelW, panelH);
        budgetPanel = new BudgetPanel(panelX, panelY, panelW, panelH, initialStatusJson);
        providerPanel = new ProviderListPanel(panelX, panelY, panelW, panelH, initialStatusJson);
        routingPanel = new RoutingPanel(panelX, panelY, panelW, panelH, font, initialStatusJson);
        addRenderableWidget(logPanel);
        addRenderableWidget(budgetPanel);
        addRenderableWidget(providerPanel);
        addRenderableWidget(routingPanel);

        testPanel = new TestPanel(panelX, panelY, panelW, panelH, font);
        setupPanel = new SetupPanel(panelX, panelY, panelW, panelH, font);
        for (var w : testPanel.getWidgets()) addRenderableWidget(w);
        for (var w : setupPanel.getWidgets()) addRenderableWidget(w);

        testPanel.updateStatus(initialStatusJson);
        applyAccessState();
        if (canTest && initialTestHandoff != null && !initialTestHandoff.isBlank()) {
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
        int tabY = tabY();
        int gap = width < 340 ? 2 : 4;
        int tabW = Math.max(28, Math.min(64, (width - 20 - gap * 5) / 6));
        int totalW = tabW * 6 + gap * 5;
        int startX = Math.max(4, (width - totalW) / 2);
        List<Button> tabs = List.of(logTab, budgetTab, providersTab, routingTab, testTab, setupTab);
        for (int index = 0; index < tabs.size(); index++) {
            Button tab = tabs.get(index);
            tab.setX(startX + (tabW + gap) * index);
            tab.setY(tabY);
            tab.setWidth(tabW);
            tab.setHeight(20);
        }
        confirmBudgetButton.setX(Math.max(4, width - 134));
        confirmBudgetButton.setY(19);
        confirmBudgetButton.setWidth(Math.min(128, Math.max(52, width / 2 - 6)));
        confirmBudgetButton.setHeight(18);

        int panelY = panelY();
        int margin = width < 260 ? 4 : 10;
        int panelH = Math.max(1, height - panelY - 10);
        int panelW = Math.max(1, width - margin * 2);
        int panelX = margin;
        logPanel.setBounds(panelX, panelY, panelW, panelH);
        budgetPanel.setBounds(panelX, panelY, panelW, panelH);
        providerPanel.setBounds(panelX, panelY, panelW, panelH);
        routingPanel.setBounds(panelX, panelY, panelW, panelH);
        testPanel.setBounds(panelX, panelY, panelW, panelH);
        setupPanel.setBounds(panelX, panelY, panelW, panelH);
        switchTab(activeTab);
    }

    private void switchTab(Tab tab) {
        if ((tab == Tab.LOG && !canView)
                || (tab == Tab.BUDGET && !(canView || canTest))
                || (tab == Tab.TEST && !canTest)
                || ((tab == Tab.PROVIDERS || tab == Tab.ROUTING || tab == Tab.SETUP) && !canAdminister)) {
            tab = canTest ? Tab.TEST : Tab.LOG;
        }
        activeTab = tab;
        logPanel.visible = (tab == Tab.LOG);
        budgetPanel.visible = (tab == Tab.BUDGET);
        providerPanel.visible = (tab == Tab.PROVIDERS);
        routingPanel.setPanelVisible(tab == Tab.ROUTING);
        testPanel.setVisible(tab == Tab.TEST);
        setupPanel.setVisible(tab == Tab.SETUP);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        if (budgetDefaultConfirmationRequired) {
            graphics.fill(4, 18, width - 4, 39, 0xD0AA2200);
            int textWidth = Math.max(0, confirmBudgetButton.getX() - 12);
            String warning = font.plainSubstrByWidth(string("budget.confirm.warning"), textWidth);
            graphics.drawString(font, warning, 8, 24, 0xFFFF55, true);
        }
        // Active tab underline
        Button active = switch (activeTab) {
            case LOG -> logTab;
            case BUDGET -> budgetTab;
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
        if (!canTest || testPanel == null) return;
        testPanel.loadHandoff(handoffJson);
        switchTab(Tab.TEST);
    }

    public void onStatusUpdate(String statusJson) {
        readPermissions(statusJson);
        if (providerPanel != null) {
            providerPanel.updateStatus(statusJson);
            if (budgetPanel != null) {
                budgetPanel.updateStatus(statusJson);
            }
            if (testPanel != null) {
                testPanel.updateStatus(statusJson);
            }
        }
        if (routingPanel != null) {
            routingPanel.updateStatus(statusJson);
        }
        if (logTab != null) repositionElements();
        applyAccessState();
    }

    public void onLogEntry(String logEntryJson) {
        if (logPanel != null) logPanel.addEntry(logEntryJson);
    }

    public void onLogHistory(List<String> entries) {
        if (logPanel != null) {
            logPanel.setHistory(entries);
        }
    }

    public void openSetupFor(String name, String format, String url, String model, @Nullable String maskedKey) {
        if (!canAdminister) return;
        switchTab(Tab.SETUP);
        if (setupPanel != null) setupPanel.prefill(name, format, url, model, maskedKey);
    }

    public void onVisionProbeResult(String providerName, boolean supported, String error, long latencyMs) {
        if (testPanel != null) testPanel.onVisionProbeResult(providerName, supported, error, latencyMs);
    }

    private void applyAccessState() {
        if (providersTab == null) return;
        logTab.active = canView;
        budgetTab.active = canView || canTest;
        providersTab.active = canAdminister;
        routingTab.active = canAdminister;
        testTab.active = canTest;
        setupTab.active = canAdminister;
        confirmBudgetButton.visible = canManageBudgets && budgetDefaultConfirmationRequired;
        if (logPanel != null) logPanel.setCanManage(canAdminister);
        if (testPanel != null) testPanel.setRestricted(!canAdminister);
        if (!canAdminister && logPanel != null) switchTab(canTest ? Tab.TEST : Tab.LOG);
    }

    private void readPermissions(String statusJson) {
        try {
            var root = JsonParser.parseString(statusJson).getAsJsonObject();
            canAdminister = root.has("canAdminister") && root.get("canAdminister").getAsBoolean();
            canManageBudgets = root.has("canManageBudgets")
                    && root.get("canManageBudgets").getAsBoolean();
            budgetDefaultConfirmationRequired = root.has("budgetDefaultConfirmationRequired")
                    && root.get("budgetDefaultConfirmationRequired").getAsBoolean();
            canTest = canAdminister || (root.has("canTest") && root.get("canTest").getAsBoolean());
            canView = canAdminister || (root.has("canView") && root.get("canView").getAsBoolean());
        } catch (Exception ignored) {
            canView = false;
            canTest = false;
            canAdminister = false;
            canManageBudgets = false;
            budgetDefaultConfirmationRequired = false;
        }
    }

    private int tabY() {
        return budgetDefaultConfirmationRequired ? 42 : 22;
    }

    private int panelY() {
        return tabY() + 26;
    }
}
