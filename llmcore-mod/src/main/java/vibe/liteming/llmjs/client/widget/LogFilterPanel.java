package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import vibe.liteming.llmjs.client.ConsoleColorStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;

/** Log search, exact tag filters, and session/history view selector. */
@OnlyIn(Dist.CLIENT)
public class LogFilterPanel extends AbstractWidget {
    private static final int MENU_ROW_HEIGHT = 18;
    private final Font font;
    private final LogPanel logPanel;
    private final EditBox searchBox;
    private final Button routeButton;
    private final Button modelButton;
    private final Button sessionButton;
    private final Button historyButton;
    private final Set<String> serverRoutes = new LinkedHashSet<>();
    private final Set<String> serverModels = new LinkedHashSet<>();
    private final List<String> menuTags = new ArrayList<>();
    private ConsoleColorStore.Group openMenu;
    private String selectedRoute = "";
    private String selectedModel = "";
    private LogPanel.ViewMode viewMode = LogPanel.ViewMode.LIVE;
    private int menuScroll;

    public LogFilterPanel(int x, int y, int width, int height, Font font, LogPanel logPanel,
            String statusJson) {
        super(x, y, width, height, text("log.filters"));
        this.font = font;
        this.logPanel = logPanel;
        searchBox = new EditBox(font, 0, 0, 100, 20, text("log.search"));
        searchBox.setMaxLength(256);
        searchBox.setHint(text("log.search.hint"));
        searchBox.setResponder(value -> applyFilters());
        routeButton = Button.builder(Component.empty(), button -> toggleMenu(ConsoleColorStore.Group.ROUTE))
                .bounds(0, 0, 90, 20).build();
        modelButton = Button.builder(Component.empty(), button -> toggleMenu(ConsoleColorStore.Group.MODEL))
                .bounds(0, 0, 90, 20).build();
        sessionButton = Button.builder(text("log.live"), button -> setViewMode(LogPanel.ViewMode.LIVE))
                .bounds(0, 0, 58, 20).build();
        historyButton = Button.builder(text("log.history"), button -> setViewMode(LogPanel.ViewMode.HISTORY))
                .bounds(0, 0, 64, 20).build();
        updateStatus(statusJson);
        setBounds(x, y, width, height);
        updateButtonLabels();
    }

    public List<AbstractWidget> getWidgets() {
        return List.of(searchBox, routeButton, modelButton, sessionButton, historyButton);
    }

    public void setPanelVisible(boolean visible) {
        this.visible = visible;
        for (AbstractWidget widget : getWidgets()) widget.visible = visible;
        if (!visible) openMenu = null;
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        setWidth(Math.max(1, width));
        setHeight(Math.max(1, height));
        int searchWidth = Math.min(110, Math.max(72, width / 3));
        int dropdownWidth = Math.max(54, (width - searchWidth - 12) / 2);
        searchBox.setX(x);
        searchBox.setY(y);
        searchBox.setWidth(searchWidth);
        routeButton.setX(x + searchWidth + 4);
        routeButton.setY(y);
        routeButton.setWidth(dropdownWidth);
        modelButton.setX(routeButton.getX() + dropdownWidth + 4);
        modelButton.setY(y);
        modelButton.setWidth(Math.max(54, x + width - modelButton.getX()));
        historyButton.setX(x + width - 64);
        historyButton.setY(y + 22);
        sessionButton.setX(historyButton.getX() - 60);
        sessionButton.setY(y + 22);
        updateButtonLabels();
    }

    public void updateStatus(String statusJson) {
        serverRoutes.clear();
        serverModels.clear();
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            if (root.has("purposes") && root.get("purposes").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("purposes")) {
                    JsonObject purpose = element.getAsJsonObject();
                    addNonBlank(serverRoutes, purpose.has("id") ? purpose.get("id").getAsString() : "");
                }
            }
            if (root.has("providers") && root.get("providers").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("providers")) {
                    JsonObject provider = element.getAsJsonObject();
                    addNonBlank(serverRoutes, provider.has("name") ? provider.get("name").getAsString() : "");
                    if (provider.has("model")) addNonBlank(serverModels, provider.get("model").getAsString());
                    if (provider.has("models") && provider.get("models").isJsonArray()) {
                        for (JsonElement model : provider.getAsJsonArray("models")) {
                            addNonBlank(serverModels, model.getAsString());
                        }
                    }
                    if (provider.has("targets") && provider.get("targets").isJsonArray()) {
                        for (JsonElement targetElement : provider.getAsJsonArray("targets")) {
                            JsonObject target = targetElement.getAsJsonObject();
                            if (target.has("model")) addNonBlank(serverModels, target.get("model").getAsString());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (openMenu != null) rebuildMenuTags();
    }

    private static void addNonBlank(Set<String> target, String value) {
        if (value != null && !value.isBlank()) target.add(value.trim());
    }

    private void toggleMenu(ConsoleColorStore.Group group) {
        if (openMenu == group) {
            openMenu = null;
            return;
        }
        openMenu = group;
        menuScroll = 0;
        rebuildMenuTags();
    }

    private void rebuildMenuTags() {
        Set<String> tags = new LinkedHashSet<>(openMenu == ConsoleColorStore.Group.ROUTE
                ? serverRoutes : serverModels);
        tags.addAll(logPanel.colorTags(openMenu));
        menuTags.clear();
        tags.stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(menuTags::add);
        clampMenuScroll();
    }

    private void chooseTag(String tag) {
        if (openMenu == ConsoleColorStore.Group.ROUTE) selectedRoute = tag;
        else selectedModel = tag;
        openMenu = null;
        updateButtonLabels();
        applyFilters();
    }

    private void clearTag(ConsoleColorStore.Group group) {
        if (group == ConsoleColorStore.Group.ROUTE) selectedRoute = "";
        else selectedModel = "";
        updateButtonLabels();
        applyFilters();
    }

    private void setViewMode(LogPanel.ViewMode mode) {
        viewMode = mode;
        logPanel.setViewMode(mode);
        updateButtonLabels();
    }

    private void applyFilters() {
        logPanel.setSearchFilters(searchBox.getValue(), selectedRoute, selectedModel);
    }

    private void updateButtonLabels() {
        if (routeButton == null) return;
        routeButton.setMessage(dropdownLabel("log.purpose", selectedRoute, routeButton.getWidth()));
        modelButton.setMessage(dropdownLabel("log.model", selectedModel, modelButton.getWidth()));
        sessionButton.setMessage(text(viewMode == LogPanel.ViewMode.LIVE ? "log.live.selected" : "log.live"));
        historyButton.setMessage(text(viewMode == LogPanel.ViewMode.HISTORY
                ? "log.history.selected" : "log.history"));
    }

    private Component dropdownLabel(String key, String selected, int buttonWidth) {
        String value = selected.isEmpty() ? string("common.all") : selected;
        String label = string(key) + ": " + value + " ▾";
        return Component.literal(font.plainSubstrByWidth(label, Math.max(12, buttonWidth - 8)));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderChips(graphics, mouseX, mouseY);
        if (openMenu != null) renderMenu(graphics, mouseX, mouseY);
    }

    private void renderChips(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = getX();
        int maxRight = sessionButton.getX() - 4;
        if (!selectedRoute.isEmpty()) {
            int width = chipWidth(selectedRoute, maxRight - x);
            renderChip(graphics, mouseX, mouseY, x, width, selectedRoute, ConsoleColorStore.Group.ROUTE);
            x += width + 4;
        }
        if (!selectedModel.isEmpty() && x < maxRight) {
            int width = chipWidth(selectedModel, maxRight - x);
            renderChip(graphics, mouseX, mouseY, x, width, selectedModel, ConsoleColorStore.Group.MODEL);
        }
    }

    private int chipWidth(String tag, int available) {
        return Math.max(0, Math.min(available, font.width(tag) + 34));
    }

    private void renderChip(GuiGraphics graphics, int mouseX, int mouseY, int x, int width, String tag,
            ConsoleColorStore.Group group) {
        if (width < 20) return;
        int y = getY() + 24;
        int color = ConsoleColorStore.resolve(group, tag,
                group == ConsoleColorStore.Group.ROUTE ? 0xFF55AAFF : 0xFFAAAAAA);
        if (group == ConsoleColorStore.Group.ROUTE) {
            LogPanel.fillHorizontalGradient(graphics, x, y, x + width, y + 16,
                    withAlpha(color, 0xB0), color & 0x00FFFFFF);
        } else {
            LogPanel.fillHorizontalGradient(graphics, x, y, x + width, y + 16,
                    color & 0x00FFFFFF, withAlpha(color, 0xB0));
        }
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 16;
        if (hovered) graphics.fill(x, y, x + width, y + 16, 0x33444444);
        String label = font.plainSubstrByWidth(tag, Math.max(6, width - 18));
        graphics.drawString(font, label, x + 4, y + 4, 0xFFFFFFFF, true);
        graphics.drawString(font, "×", x + width - 10, y + 4, 0xFFFFCCCC, true);
    }

    private void renderMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = menuX();
        int y = menuY();
        int width = menuWidth();
        int rows = visibleMenuRows();
        graphics.fill(x - 1, y - 1, x + width + 1, y + rows * MENU_ROW_HEIGHT + 1, 0xFF777777);
        for (int row = 0; row < rows; row++) {
            int itemIndex = menuScroll + row;
            String tag = itemIndex == 0 ? "" : menuTags.get(itemIndex - 1);
            int rowY = y + row * MENU_ROW_HEIGHT;
            boolean hovered = mouseX >= x && mouseX < x + width
                    && mouseY >= rowY && mouseY < rowY + MENU_ROW_HEIGHT;
            graphics.fill(x, rowY, x + width, rowY + MENU_ROW_HEIGHT,
                    hovered ? 0xF0444444 : 0xF0181818);
            if (!tag.isEmpty()) {
                int color = ConsoleColorStore.resolve(openMenu, tag,
                        openMenu == ConsoleColorStore.Group.ROUTE ? 0xFF55AAFF : 0xFFAAAAAA);
                if (openMenu == ConsoleColorStore.Group.ROUTE) {
                    LogPanel.fillHorizontalGradient(graphics, x, rowY, x + width, rowY + MENU_ROW_HEIGHT,
                            withAlpha(color, 0x90), color & 0x00FFFFFF);
                } else {
                    LogPanel.fillHorizontalGradient(graphics, x, rowY, x + width, rowY + MENU_ROW_HEIGHT,
                            color & 0x00FFFFFF, withAlpha(color, 0x90));
                }
            }
            String label = tag.isEmpty() ? string("common.all") : tag;
            graphics.drawString(font, font.plainSubstrByWidth(label, Math.max(8, width - 10)),
                    x + 5, rowY + 5, 0xFFFFFFFF, true);
        }
    }

    private int menuX() {
        return openMenu == ConsoleColorStore.Group.ROUTE ? routeButton.getX() : modelButton.getX();
    }

    private int menuY() {
        return getY() + 44;
    }

    private int menuWidth() {
        return openMenu == ConsoleColorStore.Group.ROUTE ? routeButton.getWidth() : modelButton.getWidth();
    }

    private int visibleMenuRows() {
        int available = Math.max(1, (height - 46) / MENU_ROW_HEIGHT);
        int total = menuTags.size() + 1;
        return Math.max(1, Math.min(Math.min(10, available), total - menuScroll));
    }

    private void clampMenuScroll() {
        int capacity = Math.max(1, Math.min(10, (height - 46) / MENU_ROW_HEIGHT));
        menuScroll = Math.max(0, Math.min(menuScroll, Math.max(0, menuTags.size() + 1 - capacity)));
    }

    private boolean overTopButton(double mouseX, double mouseY) {
        return mouseY >= getY() && mouseY < getY() + 20
                && ((mouseX >= routeButton.getX() && mouseX < routeButton.getX() + routeButton.getWidth())
                || (mouseX >= modelButton.getX() && mouseX < modelButton.getX() + modelButton.getWidth()));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) return false;
        if (overTopButton(mouseX, mouseY)) return false;
        int chipX = getX();
        int maxRight = sessionButton.getX() - 4;
        if (!selectedRoute.isEmpty()) {
            int width = chipWidth(selectedRoute, maxRight - chipX);
            if (mouseX >= chipX && mouseX < chipX + width
                    && mouseY >= getY() + 22 && mouseY < getY() + 42) {
                clearTag(ConsoleColorStore.Group.ROUTE);
                return true;
            }
            chipX += width + 4;
        }
        if (!selectedModel.isEmpty() && chipX < maxRight) {
            int width = chipWidth(selectedModel, maxRight - chipX);
            if (mouseX >= chipX && mouseX < chipX + width
                    && mouseY >= getY() + 22 && mouseY < getY() + 42) {
                clearTag(ConsoleColorStore.Group.MODEL);
                return true;
            }
        }
        if (openMenu != null) {
            int x = menuX();
            int y = menuY();
            int width = menuWidth();
            int rows = visibleMenuRows();
            if (mouseX >= x && mouseX < x + width && mouseY >= y
                    && mouseY < y + rows * MENU_ROW_HEIGHT) {
                int row = (int) ((mouseY - y) / MENU_ROW_HEIGHT);
                int itemIndex = menuScroll + row;
                chooseTag(itemIndex == 0 ? "" : menuTags.get(itemIndex - 1));
                return true;
            }
            openMenu = null;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible || openMenu == null || mouseX < menuX() || mouseX >= menuX() + menuWidth()
                || mouseY < menuY()) return false;
        menuScroll -= (int) Math.signum(delta);
        clampMenuScroll();
        return true;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.min((color >>> 24) & 0xFF, alpha) << 24);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
