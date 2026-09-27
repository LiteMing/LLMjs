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
import java.util.Locale;
import java.util.Set;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;

/** Searchable client-side editor for automatic and manual route/model colors. */
@OnlyIn(Dist.CLIENT)
public class LogColorPanel extends AbstractWidget {
    private static final int ROW_HEIGHT = 20;
    private static final int[] PALETTE = {
            0xFFF5F5F5, 0xFFBDBDBD, 0xFF616161, 0xFF212121,
            0xFFEF5350, 0xFFFF7043, 0xFFFFCA28, 0xFF9CCC65,
            0xFF26A69A, 0xFF29B6F6, 0xFF5C6BC0, 0xFFAB47BC,
            0xFFEC407A, 0xFF8D6E63, 0xFF66BB6A, 0xFF42A5F5
    };

    private final Font font;
    private final LogPanel logPanel;
    private final Set<String> serverRoutes = new LinkedHashSet<>();
    private final Set<String> serverModels = new LinkedHashSet<>();
    private final Set<String> customRoutes = new LinkedHashSet<>();
    private final Set<String> customModels = new LinkedHashSet<>();
    private final List<String> filteredTags = new ArrayList<>();
    private final EditBox searchBox;
    private final EditBox colorBox;
    private final Button routeButton;
    private final Button modelButton;
    private final Button addTagButton;
    private final Button automaticButton;

    private ConsoleColorStore.Group group = ConsoleColorStore.Group.ROUTE;
    private String selectedTag = "";
    private int scrollOffset;
    private boolean loadingColor;
    private String validationMessage = "";
    private int previewRouteColor = 0xFF55AAFF;
    private int previewModelColor = 0xFFAAAAAA;

    public LogColorPanel(int x, int y, int width, int height, Font font, LogPanel logPanel,
            String statusJson) {
        super(x, y, width, height, text("colors.title"));
        this.font = font;
        this.logPanel = logPanel;
        routeButton = Button.builder(text("colors.routes"), button -> switchGroup(ConsoleColorStore.Group.ROUTE))
                .bounds(0, 0, 70, 20).build();
        modelButton = Button.builder(text("colors.models"), button -> switchGroup(ConsoleColorStore.Group.MODEL))
                .bounds(0, 0, 70, 20).build();
        searchBox = new EditBox(font, 0, 0, 100, 20, text("colors.search"));
        searchBox.setMaxLength(128);
        searchBox.setHint(text("colors.search.hint"));
        searchBox.setResponder(value -> refreshTags(false));
        addTagButton = Button.builder(text("colors.add"), button -> addSearchTag())
                .bounds(0, 0, 44, 20).build();
        colorBox = new EditBox(font, 0, 0, 130, 20, text("colors.value"));
        colorBox.setMaxLength(10);
        colorBox.setHint(Component.literal("FFFFFF / AARRGGBB"));
        colorBox.setResponder(this::onColorEdited);
        automaticButton = Button.builder(text("colors.automatic"), button -> useAutomaticColor())
                .bounds(0, 0, 90, 20).build();
        updateStatus(statusJson);
        refreshTags(true);
        setBounds(x, y, width, height);
    }

    public List<AbstractWidget> getWidgets() {
        return List.of(routeButton, modelButton, searchBox, addTagButton, colorBox, automaticButton);
    }

    public void setPanelVisible(boolean visible) {
        this.visible = visible;
        for (AbstractWidget widget : getWidgets()) widget.visible = visible;
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        setWidth(Math.max(1, width));
        setHeight(Math.max(1, height));
        int listWidth = listWidth();
        routeButton.setX(x + 8);
        routeButton.setY(y + 8);
        modelButton.setX(x + 80);
        modelButton.setY(y + 8);
        int addWidth = Math.min(48, Math.max(36, listWidth / 4));
        searchBox.setX(x + 8);
        searchBox.setY(y + 34);
        searchBox.setWidth(Math.max(40, listWidth - addWidth - 18));
        addTagButton.setX(x + listWidth - addWidth - 8);
        addTagButton.setY(y + 34);
        addTagButton.setWidth(addWidth);
        int editorX = editorX();
        int editorWidth = Math.max(1, x + width - editorX);
        colorBox.setX(editorX + 8);
        colorBox.setY(y + 70);
        colorBox.setWidth(Math.max(60, Math.min(150, editorWidth - 16)));
        automaticButton.setX(editorX + 8);
        automaticButton.setY(y + 94);
        automaticButton.setWidth(Math.max(60, Math.min(100, editorWidth - 16)));
        clampScroll();
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
        refreshTags(false);
    }

    public void refreshObservedTags() {
        refreshTags(false);
    }

    private static void addNonBlank(Set<String> target, String value) {
        if (value != null && !value.isBlank()) target.add(value.trim());
    }

    private void switchGroup(ConsoleColorStore.Group next) {
        if (group == next) return;
        group = next;
        scrollOffset = 0;
        selectedTag = "";
        refreshTags(true);
    }

    private void refreshTags(boolean selectFirst) {
        Set<String> tags = new LinkedHashSet<>();
        tags.addAll(group == ConsoleColorStore.Group.ROUTE ? serverRoutes : serverModels);
        tags.addAll(group == ConsoleColorStore.Group.ROUTE ? customRoutes : customModels);
        tags.addAll(logPanel.colorTags(group));
        tags.addAll(ConsoleColorStore.configuredTags(group));
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        filteredTags.clear();
        tags.stream()
                .filter(tag -> query.isEmpty() || tag.toLowerCase(Locale.ROOT).contains(query))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(filteredTags::add);
        if (selectFirst || !filteredTags.contains(selectedTag)) {
            selectTag(filteredTags.isEmpty() ? "" : filteredTags.get(0));
        }
        clampScroll();
    }

    private void addSearchTag() {
        String tag = searchBox.getValue().trim();
        if (tag.isEmpty()) return;
        (group == ConsoleColorStore.Group.ROUTE ? customRoutes : customModels).add(tag);
        searchBox.setValue("");
        refreshTags(false);
        selectTag(tag);
    }

    private void selectTag(String tag) {
        selectedTag = tag == null ? "" : tag;
        validationMessage = "";
        loadingColor = true;
        if (selectedTag.isEmpty()) {
            colorBox.setValue("");
        } else {
            Integer manual = ConsoleColorStore.manualColor(group, selectedTag);
            int resolved = manual != null ? manual : ConsoleColorStore.automaticColor(selectedTag,
                    group == ConsoleColorStore.Group.ROUTE ? 0xFF55AAFF : 0xFFAAAAAA);
            colorBox.setValue(ConsoleColorStore.formatColor(resolved));
            validationMessage = string(manual == null ? "colors.mode.auto" : "colors.mode.manual");
            setPreview(group, resolved);
        }
        loadingColor = false;
    }

    private void onColorEdited(String value) {
        if (loadingColor || selectedTag.isEmpty()) return;
        Integer parsed = ConsoleColorStore.parseColor(value);
        if (parsed == null) {
            validationMessage = string("colors.invalid");
            return;
        }
        ConsoleColorStore.setColor(group, selectedTag, parsed);
        setPreview(group, parsed);
        validationMessage = string("colors.mode.manual");
    }

    private void useAutomaticColor() {
        if (selectedTag.isEmpty()) return;
        ConsoleColorStore.clearColor(group, selectedTag);
        selectTag(selectedTag);
    }

    private void setPaletteColor(int color) {
        if (selectedTag.isEmpty()) return;
        loadingColor = true;
        colorBox.setValue(ConsoleColorStore.formatColor(color));
        loadingColor = false;
        ConsoleColorStore.setColor(group, selectedTag, color);
        setPreview(group, color);
        validationMessage = string("colors.mode.manual");
    }

    private void setPreview(ConsoleColorStore.Group changedGroup, int color) {
        if (changedGroup == ConsoleColorStore.Group.ROUTE) previewRouteColor = color;
        else previewModelColor = color;
    }

    private int listWidth() {
        return Math.max(150, Math.min(270, width / 2));
    }

    private int editorX() {
        return getX() + listWidth() + 6;
    }

    private int listTop() {
        return getY() + 60;
    }

    private int visibleRows() {
        return Math.max(1, (height - 68) / ROW_HEIGHT);
    }

    private void clampScroll() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, filteredTags.size() - visibleRows())));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int listRight = getX() + listWidth();
        int editorX = editorX();
        graphics.fill(getX(), getY(), listRight, getY() + height, 0x78000000);
        graphics.fill(editorX, getY(), getX() + width, getY() + height, 0x78000000);
        routeButton.setMessage(text(group == ConsoleColorStore.Group.ROUTE
                ? "colors.routes.selected" : "colors.routes"));
        modelButton.setMessage(text(group == ConsoleColorStore.Group.MODEL
                ? "colors.models.selected" : "colors.models"));

        int end = Math.min(filteredTags.size(), scrollOffset + visibleRows());
        for (int index = scrollOffset; index < end; index++) {
            String tag = filteredTags.get(index);
            int rowY = listTop() + (index - scrollOffset) * ROW_HEIGHT;
            boolean selected = tag.equals(selectedTag);
            boolean hovered = mouseX >= getX() + 4 && mouseX < listRight - 4
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2;
            int color = ConsoleColorStore.resolve(group, tag,
                    group == ConsoleColorStore.Group.ROUTE ? 0xFF55AAFF : 0xFFAAAAAA);
            if (group == ConsoleColorStore.Group.ROUTE) {
                LogPanel.fillHorizontalGradient(graphics, getX() + 4, rowY, listRight - 4, rowY + ROW_HEIGHT - 2,
                        withAlpha(color, selected ? 0xA0 : 0x78), color & 0x00FFFFFF);
            } else {
                LogPanel.fillHorizontalGradient(graphics, getX() + 4, rowY, listRight - 4, rowY + ROW_HEIGHT - 2,
                        color & 0x00FFFFFF, withAlpha(color, selected ? 0xA0 : 0x78));
            }
            if (selected || hovered) {
                int border = selected ? 0xFFDDDDDD : 0x88999999;
                graphics.fill(getX() + 4, rowY, listRight - 4, rowY + 1, border);
                graphics.fill(getX() + 4, rowY + ROW_HEIGHT - 3, listRight - 4, rowY + ROW_HEIGHT - 2, border);
            }
            Integer manual = ConsoleColorStore.manualColor(group, tag);
            String prefix = manual == null ? "A  " : "M  ";
            String label = font.plainSubstrByWidth(prefix + tag, Math.max(20, listWidth() - 20));
            graphics.drawString(font, label, getX() + 9, rowY + 5, 0xFFFFFFFF, true);
        }
        if (filteredTags.isEmpty()) {
            graphics.drawString(font, text("colors.empty"), getX() + 8, listTop() + 4, 0xFF888888, false);
        }

        int editorRight = getX() + width - 8;
        String selected = selectedTag.isEmpty() ? string("colors.none") : selectedTag;
        graphics.drawString(font, font.plainSubstrByWidth(selected, Math.max(20, editorRight - editorX - 16)),
                editorX + 8, getY() + 9, 0xFFFFFFFF, false);
        int previewTop = getY() + 26;
        int middle = editorX + (editorRight - editorX) / 2;
        LogPanel.fillHorizontalGradient(graphics, editorX + 8, previewTop, middle, previewTop + 24,
                withAlpha(previewRouteColor, 0xB0), previewRouteColor & 0x00FFFFFF);
        LogPanel.fillHorizontalGradient(graphics, middle, previewTop, editorRight, previewTop + 24,
                previewModelColor & 0x00FFFFFF, withAlpha(previewModelColor, 0xB0));
        graphics.fill(editorX + 8, previewTop, editorX + 11, previewTop + 24, previewRouteColor);
        graphics.fill(editorRight - 3, previewTop, editorRight, previewTop + 24, previewModelColor);
        graphics.drawCenteredString(font, text("colors.preview"),
                editorX + (editorRight - editorX) / 2, previewTop + 8, 0xFFFFFFFF);
        graphics.drawString(font, text("colors.value"), editorX + 8, getY() + 58, 0xFFAAAAAA, false);
        int messageColor = validationMessage.equals(string("colors.invalid")) ? 0xFFFF5555 : 0xFFAAAAAA;
        graphics.drawString(font, validationMessage, editorX + 8, getY() + 118, messageColor, false);
        graphics.drawString(font, text("colors.palette"), editorX + 8, getY() + 123, 0xFFAAAAAA, false);
        renderPalette(graphics, mouseX, mouseY);
    }

    private void renderPalette(GuiGraphics graphics, int mouseX, int mouseY) {
        int columns = paletteColumns();
        int startX = editorX() + 8;
        int startY = getY() + 135;
        for (int index = 0; index < visiblePaletteCount(); index++) {
            int x = startX + (index % columns) * 20;
            int y = startY + (index / columns) * 20;
            boolean hovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, hovered ? 0xFFFFFFFF : 0xFF555555);
            graphics.fill(x, y, x + 16, y + 16, PALETTE[index]);
            if (hovered) {
                graphics.renderTooltip(font, Component.literal(ConsoleColorStore.formatColor(PALETTE[index])),
                        mouseX, mouseY);
            }
        }
    }

    private int paletteColumns() {
        int editorWidth = Math.max(1, getX() + width - editorX() - 16);
        return Math.max(2, Math.min(8, editorWidth / 20));
    }

    private int visiblePaletteCount() {
        int availableRows = Math.max(0, (getY() + height - (getY() + 135)) / 20);
        return Math.min(PALETTE.length, paletteColumns() * availableRows);
    }

    private static int withAlpha(int color, int alpha) {
        int sourceAlpha = (color >>> 24) & 0xFF;
        return (color & 0x00FFFFFF) | (Math.min(sourceAlpha, alpha) << 24);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) return false;
        int listRight = getX() + listWidth();
        if (mouseX >= getX() + 4 && mouseX < listRight - 4 && mouseY >= listTop()) {
            int row = (int) ((mouseY - listTop()) / ROW_HEIGHT);
            int index = scrollOffset + row;
            if (index >= 0 && index < filteredTags.size()) {
                selectTag(filteredTags.get(index));
                return true;
            }
        }
        int columns = paletteColumns();
        int startX = editorX() + 8;
        int startY = getY() + 135;
        if (mouseX >= startX && mouseY >= startY) {
            int column = (int) ((mouseX - startX) / 20);
            int row = (int) ((mouseY - startY) / 20);
            int localX = (int) (mouseX - startX) % 20;
            int localY = (int) (mouseY - startY) % 20;
            int index = row * columns + column;
            if (column >= 0 && column < columns && localX < 16 && localY < 16
                    && index >= 0 && index < visiblePaletteCount()) {
                setPaletteColor(PALETTE[index]);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible || mouseX < getX() || mouseX >= getX() + listWidth()
                || mouseY < listTop() || mouseY >= getY() + height) return false;
        scrollOffset -= (int) Math.signum(delta);
        clampScroll();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
