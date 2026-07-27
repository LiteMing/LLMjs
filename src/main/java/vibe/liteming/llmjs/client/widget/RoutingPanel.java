package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vibe.liteming.llmcore.LlmRouteOptions;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SStatusRequestPacket;
import vibe.liteming.llmjs.network.packet.C2SUpdateRoutingPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;
import static vibe.liteming.llmjs.client.ConsoleTexts.tooltip;

/**
 * Routing tab widget for the /llm console. Renders one row per registered purpose
 * (from {@link vibe.liteming.llmcore.PurposeRegistry}) plus a "default" row. Each row
 * shows the current provider chain; clicking a row opens an inline provider and
 * inherited-parameter editor.
 *
 * <p>Each purpose has a fixed-size pool of available providers (right column); the
 * active chain is the left column in priority order. Blank parameter fields inherit.
 * Save broadcasts a C2SUpdateRoutingPacket; Reset reloads the server snapshot.</p>
 */
@OnlyIn(Dist.CLIENT)
public class RoutingPanel extends AbstractWidget {
    public record PurposeRow(String id, String displayName, String description, String modId, boolean builtIn) {}
    public record ProviderName(String name) {}
    private record EffectiveValues(String provider, String temperature, String maxOutput, String timeout,
            String inputBudget, String outputReserve) {}
    private record ProviderGrid(int startX, int slotWidth, int columnStep, int columns) {}
    private record ProviderEditorLayout(ProviderGrid activeGrid, int activeY,
                                        ProviderGrid availableGrid, int availableY, int bottomY) {}
    private record RowColumns(int purposeWidth, int chainX, int chainWidth, int editX) {}

    private static final int ROW_HEIGHT = 18;
    private static final int HEADER_Y_OFFSET = 6;
    private static final int PROVIDER_SLOT_HEIGHT = 12;
    private static final int PROVIDER_ROW_STEP = 14;
    private static final int PROVIDER_COLUMN_GAP = 6;
    private static final int ACTIVE_SLOT_WIDTH = 90;
    private static final int AVAILABLE_SLOT_WIDTH = 110;
    private static final int CONTENT_TOP = 6;
    private static final int FOOTER_GAP = 6;
    private static final int FOOTER_HEIGHT = 22;
    private static final int NARROW_PROVIDER_BREAKPOINT = 360;

    private final Font font;
    private final ConsoleScrollBar pageScroll = new ConsoleScrollBar();
    private List<PurposeRow> purposes = new ArrayList<>();
    private List<String> providerNames = new ArrayList<>();
    /** edited chains: key = purpose id (or "$default"), value = ordered provider names */
    private Map<String, List<String>> edited = new LinkedHashMap<>();
    private List<String> editedDefault = new ArrayList<>();
    private Map<String, LlmRouteOptions> editedOptions = new LinkedHashMap<>();
    private Map<String, EffectiveValues> effectiveValues = new LinkedHashMap<>();
    private final EditBox temperatureInput;
    private final EditBox maxOutputInput;
    private final EditBox timeoutInput;
    private final EditBox inputBudgetInput;
    private final EditBox outputReserveInput;
    private String routingFingerprint = "";
    private String parameterError = "";
    /** row index currently being edited (-1 = none). Header row 0 = default; subsequent = purposes. */
    private int editingRow = -1;
    private boolean dirty = false;

    public RoutingPanel(int x, int y, int width, int height, Font font, String statusJson) {
        super(x, y, width, height, text("tab.routing"));
        this.font = font;
        this.temperatureInput = parameterInput(font, "parameter.temperature", "parameter.temperature.tip");
        this.maxOutputInput = parameterInput(font, "parameter.max_output", "parameter.max_output.tip");
        this.timeoutInput = parameterInput(font, "parameter.timeout", "parameter.timeout.tip");
        this.inputBudgetInput = parameterInput(font, "parameter.input_budget", "parameter.input_budget.tip");
        this.outputReserveInput = parameterInput(font, "parameter.output_reserve", "parameter.output_reserve.tip");
        updateStatus(statusJson);
        updateScrollGeometry();
    }

    private static EditBox parameterInput(Font font, String labelKey, String tipKey) {
        EditBox input = new EditBox(font, 0, 0, 60, 16, text(labelKey));
        input.setMaxLength(12);
        input.setHint(text("common.inherit"));
        input.visible = false;
        return tooltip(input, tipKey);
    }

    public void updateStatus(String statusJson) {
        List<PurposeRow> newPurposes = new ArrayList<>();
        List<String> newProviders = new ArrayList<>();
        Map<String, List<String>> serverChains = new LinkedHashMap<>();
        List<String> serverDefault = new ArrayList<>();
        Map<String, LlmRouteOptions> serverOptions = new LinkedHashMap<>();
        Map<String, EffectiveValues> serverEffective = new LinkedHashMap<>();
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            routingFingerprint = root.has("routingFingerprint")
                    ? root.get("routingFingerprint").getAsString() : "";
            if (root.has("providers") && root.get("providers").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("providers")) {
                    JsonObject p = el.getAsJsonObject();
                    newProviders.add(p.get("name").getAsString());
                }
            }
            if (root.has("purposes") && root.get("purposes").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("purposes")) {
                    JsonObject pm = el.getAsJsonObject();
                    String purposeId = pm.get("id").getAsString();
                    newPurposes.add(new PurposeRow(
                            purposeId,
                            pm.has("displayName") ? pm.get("displayName").getAsString() : pm.get("id").getAsString(),
                            pm.has("description") ? pm.get("description").getAsString() : "",
                            pm.has("modId") ? pm.get("modId").getAsString() : "",
                            pm.has("builtIn") && pm.get("builtIn").getAsBoolean()));
                    if (pm.has("effective") && pm.get("effective").isJsonObject()) {
                        serverEffective.put(purposeId, readEffective(pm.getAsJsonObject("effective")));
                    }
                }
            }
            if (root.has("routing") && root.get("routing").isJsonObject()) {
                JsonObject r = root.getAsJsonObject("routing");
                if (r.has("default")) {
                    serverDefault.addAll(readRouteChain(r.get("default")));
                }
                if (r.has("purposes") && r.get("purposes").isJsonObject()) {
                    JsonObject obj = r.getAsJsonObject("purposes");
                    for (var entry : obj.entrySet()) {
                        List<String> chain = readRouteChain(entry.getValue());
                        if (!chain.isEmpty()) serverChains.put(entry.getKey(), chain);
                        if (entry.getValue().isJsonObject()) {
                            LlmRouteOptions options = readOptions(entry.getValue().getAsJsonObject());
                            if (!options.isEmpty()) serverOptions.put(entry.getKey(), options);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        this.purposes = newPurposes;
        this.providerNames = newProviders;
        // Always reset local edits to the freshly-arrived server snapshot.
        this.edited = new LinkedHashMap<>(serverChains);
        this.editedDefault = new ArrayList<>(serverDefault);
        this.editedOptions = new LinkedHashMap<>(serverOptions);
        this.effectiveValues = new LinkedHashMap<>(serverEffective);
        this.editingRow = -1;
        this.dirty = false;
        this.parameterError = "";
        setParameterInputsVisible(false);
        updateScrollGeometry();
    }

    private static List<String> readRouteChain(JsonElement route) {
        JsonArray providers = null;
        if (route != null && route.isJsonArray()) providers = route.getAsJsonArray();
        else if (route != null && route.isJsonObject() && route.getAsJsonObject().has("providers")
                && route.getAsJsonObject().get("providers").isJsonArray()) {
            providers = route.getAsJsonObject().getAsJsonArray("providers");
        }
        if (providers == null) return List.of();
        List<String> chain = new ArrayList<>();
        for (JsonElement element : providers) chain.add(element.getAsString());
        return chain;
    }

    private static LlmRouteOptions readOptions(JsonObject route) {
        return new LlmRouteOptions(nullableDouble(route, "temperature"),
                nullableInt(route, "maxOutputTokens"), nullableInt(route, "timeoutSeconds"),
                nullableInt(route, "inputBudgetTokens"), nullableInt(route, "outputReserveTokens"));
    }

    private static EffectiveValues readEffective(JsonObject json) {
        return new EffectiveValues(textValue(json, "provider", string("common.none")),
                textValue(json, "temperature", string("common.unset")),
                textValue(json, "maxOutputTokens", string("common.unset")),
                textValue(json, "timeoutSeconds", string("common.unset")),
                json.has("inputBudgetUnbounded") && json.get("inputBudgetUnbounded").getAsBoolean()
                        ? string("common.unbounded")
                        : textValue(json, "inputBudgetTokens", string("common.unset")),
                textValue(json, "outputReserveTokens", string("common.unset")));
    }

    private static Double nullableDouble(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : null;
    }

    private static Integer nullableInt(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : null;
    }

    private static String textValue(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    public boolean isDirty() { return dirty; }

    private List<String> getRowChain(int row) {
        if (row == 0) return editedDefault;
        if (row >= 1 && row <= purposes.size()) return edited.get(purposes.get(row - 1).id());
        return null;
    }

    private void setRowChain(int row, List<String> chain) {
        if (row == 0) editedDefault = new ArrayList<>(chain);
        else if (row >= 1 && row <= purposes.size()) edited.put(purposes.get(row - 1).id(), new ArrayList<>(chain));
        dirty = true;
    }

    private LlmRouteOptions getRowOptions(int row) {
        if (row < 1 || row > purposes.size()) return LlmRouteOptions.empty();
        return editedOptions.getOrDefault(purposes.get(row - 1).id(), LlmRouteOptions.empty());
    }

    private void openEditor(int row) {
        if (!commitParameterFields()) return;
        editingRow = editingRow == row ? -1 : row;
        parameterError = "";
        boolean parameterRow = editingRow > 0;
        setParameterInputsVisible(parameterRow);
        if (parameterRow) loadParameterFields(getRowOptions(editingRow));
        updateScrollGeometry();
        ensureEditorVisible();
    }

    private void ensureEditorVisible() {
        if (editingRow < 0) return;
        int top = rowContentY(editingRow);
        int bottom = editorContentY() + editorHeight();
        if (bottom - top >= height) pageScroll.setOffset(top);
        else pageScroll.ensureVisible(top, bottom);
        updateScrollGeometry();
    }

    private void loadParameterFields(LlmRouteOptions options) {
        temperatureInput.setValue(number(options.temperature()));
        maxOutputInput.setValue(number(options.maxOutputTokens()));
        timeoutInput.setValue(number(options.timeoutSeconds()));
        inputBudgetInput.setValue(number(options.inputBudgetTokens()));
        outputReserveInput.setValue(number(options.outputReserveTokens()));
    }

    private static String number(Number value) {
        return value == null ? "" : value.toString();
    }

    private void setParameterInputsVisible(boolean visible) {
        for (EditBox input : parameterInputs()) {
            input.visible = visible;
            if (!visible) input.setFocused(false);
        }
    }

    private List<EditBox> parameterInputs() {
        return List.of(temperatureInput, maxOutputInput, timeoutInput, inputBudgetInput, outputReserveInput);
    }

    private boolean commitParameterFields() {
        if (editingRow <= 0 || editingRow > purposes.size()) return true;
        try {
            LlmRouteOptions options = new LlmRouteOptions(
                    parseDouble(temperatureInput.getValue(), string("parameter.temperature")),
                    parseInteger(maxOutputInput.getValue(), string("parameter.max_output")),
                    parseInteger(timeoutInput.getValue(), string("parameter.timeout")),
                    parseInteger(inputBudgetInput.getValue(), string("parameter.input_budget")),
                    parseInteger(outputReserveInput.getValue(), string("parameter.output_reserve")));
            String purpose = purposes.get(editingRow - 1).id();
            LlmRouteOptions previous = editedOptions.getOrDefault(purpose, LlmRouteOptions.empty());
            if (!options.equals(previous)) {
                if (options.isEmpty()) editedOptions.remove(purpose);
                else editedOptions.put(purpose, options);
                dirty = true;
            }
            parameterError = "";
            return true;
        } catch (IllegalArgumentException e) {
            parameterError = e.getMessage();
            return false;
        }
    }

    private static Double parseDouble(String text, String label) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(string("validation.number", label));
        }
    }

    private static Integer parseInteger(String text, String label) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(string("validation.integer", label));
        }
    }

    private String getRowLabel(int row) {
        if (row == 0) return string("routing.default");
        if (row >= 1 && row <= purposes.size()) return purposes.get(row - 1).displayName();
        return "?";
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        setWidth(Math.max(1, width));
        setHeight(Math.max(1, height));
        updateScrollGeometry();
    }

    public void setPanelVisible(boolean visible) {
        this.visible = visible;
        setParameterInputsVisible(visible && editingRow > 0);
        if (visible) updateScrollGeometry();
    }

    private void updateScrollGeometry() {
        pageScroll.setTrack(getX() + width - 7, getY() + 2, getY() + height - 2);
        pageScroll.update(contentHeight(), height);
        if (editingRow > 0) updateParameterGeometry(editorDetailsY());
    }

    private int screenY(int contentY) {
        return getY() + contentY - pageScroll.offset();
    }

    private int rowsStart() {
        return CONTENT_TOP + ROW_HEIGHT;
    }

    private int rowContentY(int row) {
        int result = rowsStart() + row * ROW_HEIGHT;
        if (editingRow >= 0 && row > editingRow) result += editorHeight();
        return result;
    }

    private int editorContentY() {
        if (editingRow < 0) return -1;
        return rowsStart() + (editingRow + 1) * ROW_HEIGHT;
    }

    private int editorScreenY() {
        return screenY(editorContentY());
    }

    private int footerContentY() {
        return rowsStart() + (1 + purposes.size()) * ROW_HEIGHT
                + (editingRow >= 0 ? editorHeight() : 0) + FOOTER_GAP;
    }

    private int contentHeight() {
        return footerContentY() + FOOTER_HEIGHT;
    }

    private int editorHeight() {
        if (editingRow < 0) return 0;
        ProviderEditorLayout layout = providerEditorLayout(0);
        return layout.bottomY() + (editingRow > 0 ? 58 : 8);
    }

    private int editorDetailsY() {
        return providerEditorLayout(editorScreenY()).bottomY() + 4;
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < getX() || mouseX >= getX() + width
                || mouseY < getY() || mouseY >= getY() + height) return -1;
        for (int row = 0; row < 1 + purposes.size(); row++) {
            int rowY = screenY(rowContentY(row));
            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) return row;
        }
        return -1;
    }

    private RowColumns rowColumns() {
        int editX = getX() + width - 66;
        int purposeWidth = Math.min(140, Math.max(54, (width - 90) / 2));
        int chainX = getX() + 8 + purposeWidth;
        int available = Math.max(12, editX - chainX - 4);
        int chainWidth = width >= 650 ? Math.min(available, Math.max(120, width / 3)) : available;
        return new RowColumns(purposeWidth, chainX, chainWidth, editX);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateScrollGeometry();
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x90000000);
        graphics.enableScissor(getX(), getY(), getX() + width, getY() + height);
        int headerY = screenY(HEADER_Y_OFFSET);
        RowColumns columns = rowColumns();
        graphics.drawString(font, ellipsize(string("routing.purpose"), columns.purposeWidth()),
                getX() + 8, headerY, 0xAAAAAA, false);
        graphics.drawString(font, ellipsize(string("routing.provider_chain"), columns.chainWidth()),
                columns.chainX(), headerY, 0xAAAAAA, false);
        if (!routingFingerprint.isEmpty() && width > 520) {
            graphics.drawString(font, "cfg " + routingFingerprint.substring(0, Math.min(8, routingFingerprint.length())),
                    getX() + width - 150, headerY, 0x777777, false);
        }
        graphics.drawString(font, text(dirty ? "routing.unsaved" : "routing.saved"),
                columns.editX(), headerY, dirty ? 0xFFAA00 : 0x55FF55, false);
        graphics.fill(getX() + 4, screenY(rowsStart()) - 2,
                getX() + width - 9, screenY(rowsStart()) - 1, 0xFF555555);

        int hoveredRow = rowAt(mouseX, mouseY);

        // Default row + per-purpose rows
        for (int row = 0; row < 1 + purposes.size(); row++) {
            int rowY = screenY(rowContentY(row));
            if (rowY + ROW_HEIGHT <= getY() || rowY >= getY() + height) continue;
            boolean isEditing = (row == editingRow);
            if (row == hoveredRow || isEditing) {
                graphics.fill(getX() + 4, rowY - 1, getX() + width - 9,
                        rowY + ROW_HEIGHT - 2, isEditing ? 0x4055AAFF : 0x28FFFFFF);
            }
            graphics.drawString(font, ellipsize(getRowLabel(row), columns.purposeWidth()),
                    getX() + 8, rowY + 2, 0xFFFFFF, false);
            List<String> chain = getRowChain(row);
            String chainText = chain == null || chain.isEmpty()
                    ? string("routing.chain_all") : chain.stream().map(this::providerLabel)
                    .collect(java.util.stream.Collectors.joining("  >  "));
            graphics.drawString(font, ellipsize(chainText, columns.chainWidth()),
                    columns.chainX(), rowY + 2, 0xCCCCCC, false);
            if (row > 0 && width >= 650) {
                String summary = optionSummary(getRowOptions(row));
                int summaryX = columns.chainX() + columns.chainWidth() + 8;
                graphics.drawString(font, ellipsize(summary, columns.editX() - summaryX - 4),
                        summaryX, rowY + 2, 0x999999, false);
            }
            graphics.drawCenteredString(font, text(isEditing ? "routing.done" : "routing.edit"),
                    columns.editX() + 27, rowY + 3, 0xFFAAAA);
        }

        if (editingRow >= 0) renderInlineEditor(graphics, mouseX, mouseY, partialTick);

        int footerY = screenY(footerContentY());
        graphics.drawString(font, text("routing.save"), getX() + 8, footerY, dirty ? 0x55FF55 : 0x666666, false);
        graphics.drawString(font, text("routing.reset"), getX() + 60, footerY, 0xAAAAFF, false);
        graphics.drawString(font, text("routing.refresh"), getX() + 110, footerY, 0xAAAAFF, false);
        if (providerNames.isEmpty()) {
            graphics.drawString(font, text("routing.no_providers"), getX() + 200, footerY, 0xFF5555, false);
        }

        graphics.disableScissor();
        pageScroll.render(graphics, mouseX, mouseY);
        renderRoutingTooltip(graphics, mouseX, mouseY, hoveredRow, headerY, footerY, columns);
    }

    private void renderRoutingTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                      int hoveredRow, int headerY, int footerY, RowColumns columns) {
        if (mouseX < getX() || mouseX >= getX() + width
                || mouseY < getY() || mouseY >= getY() + height) return;
        String hoveredProvider = providerAtEditorPosition(mouseX, mouseY);
        if (hoveredProvider != null) {
            graphics.renderTooltip(font, Component.literal(hoveredProvider), mouseX, mouseY);
            return;
        }
        if (editingRow > 0) {
            String[] parameterTips = {"parameter.temperature.tip", "parameter.max_output.tip",
                    "parameter.timeout.tip", "parameter.input_budget.tip", "parameter.output_reserve.tip"};
            List<EditBox> inputs = parameterInputs();
            for (int index = 0; index < inputs.size(); index++) {
                if (inputs.get(index).visible && inputs.get(index).isMouseOver(mouseX, mouseY)) {
                    graphics.renderTooltip(font, text(parameterTips[index]), mouseX, mouseY);
                    return;
                }
            }
            int detailsY = editorDetailsY();
            if (mouseY >= detailsY - 4 && mouseY < detailsY + 12) {
                graphics.renderTooltip(font, text("routing.effective.tip"), mouseX, mouseY);
                return;
            }
        }
        if (editingRow >= 0) {
            ProviderEditorLayout layout = providerEditorLayout(editorScreenY());
            if (mouseY >= editorScreenY() + 12 && mouseY < layout.bottomY()) {
                boolean activeArea = width >= NARROW_PROVIDER_BREAKPOINT
                        ? mouseX < getX() + width / 2
                        : mouseY < layout.availableY() - 2;
                graphics.renderTooltip(font, text(activeArea ? "routing.active.tip" : "routing.available.tip"),
                        mouseX, mouseY);
                return;
            }
            if (mouseY >= editorScreenY() && mouseY < editorScreenY() + editorHeight()) return;
        }
        if (mouseY >= headerY && mouseY < headerY + ROW_HEIGHT) {
            if (mouseX < columns.chainX()) {
                graphics.renderTooltip(font, text("routing.purpose.tip"), mouseX, mouseY);
            } else if (mouseX < columns.editX()) {
                graphics.renderTooltip(font, text("routing.provider_chain.tip"), mouseX, mouseY);
            } else {
                graphics.renderTooltip(font, text("routing.state.tip"), mouseX, mouseY);
            }
            return;
        }
        if (hoveredRow >= 0) {
            if (mouseX >= columns.chainX() && mouseX < columns.editX()) {
                graphics.renderTooltip(font, text("routing.chain.detail", chainDetails(getRowChain(hoveredRow))),
                        mouseX, mouseY);
            } else if (hoveredRow == 0) {
                graphics.renderTooltip(font, text("routing.default.tip"), mouseX, mouseY);
            } else if (hoveredRow <= purposes.size()) {
                PurposeRow purpose = purposes.get(hoveredRow - 1);
                graphics.renderTooltip(font, text("routing.row.tip", purpose.id(), purpose.modId(),
                        purpose.description().isBlank() ? string("common.none") : purpose.description()),
                        mouseX, mouseY);
            }
            return;
        }
        if (mouseY >= footerY && mouseY < footerY + 14) {
            if (inLabel(mouseX, 8, 44)) {
                graphics.renderTooltip(font, text("routing.save.tip"), mouseX, mouseY);
            } else if (inLabel(mouseX, 60, 96)) {
                graphics.renderTooltip(font, text("routing.reset.tip"), mouseX, mouseY);
            } else if (inLabel(mouseX, 110, 168)) {
                graphics.renderTooltip(font, text("routing.refresh.tip"), mouseX, mouseY);
            }
        }
    }

    private void renderInlineEditor(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        List<String> chain = getRowChain(editingRow);
        if (chain == null) chain = new ArrayList<>();
        int ey = editorScreenY();
        graphics.fill(getX() + 4, ey - 2, getX() + width - 9,
                ey + editorHeight() - 2, 0xE0222222);
        graphics.drawString(font, text("routing.editing", getRowLabel(editingRow)),
                getX() + 8, ey + 2, 0xFFFFFF, false);

        ProviderEditorLayout providerLayout = providerEditorLayout(ey);
        int activeLabelX = getX() + 8;
        int availableLabelX = width >= NARROW_PROVIDER_BREAKPOINT ? getX() + width / 2 + 8 : getX() + 8;
        graphics.drawString(font, text("routing.active"), activeLabelX, providerLayout.activeY(), 0xAAAAFF, false);
        graphics.drawString(font, text("routing.available"), availableLabelX,
                providerLayout.availableY(), 0xAAAAFF, false);

        ProviderGrid activeGrid = providerLayout.activeGrid();
        for (int i = 0; i < chain.size(); i++) {
            int slotX = activeGrid.startX() + (i % activeGrid.columns()) * activeGrid.columnStep();
            int slotY = providerLayout.activeY() + (i / activeGrid.columns()) * PROVIDER_ROW_STEP;
            graphics.fill(slotX, slotY - 1, slotX + activeGrid.slotWidth(),
                    slotY - 1 + PROVIDER_SLOT_HEIGHT, 0xFF333355);
            String label = (i == 0 ? "[1] " : "[fb] ") + providerLabel(chain.get(i));
            graphics.drawString(font, ellipsize(label, activeGrid.slotWidth() - 6),
                    slotX + 2, slotY + 1, 0xFFFFFF, false);
        }
        ProviderGrid availableGrid = providerLayout.availableGrid();
        for (int index = 0; index < providerNames.size(); index++) {
            String name = providerNames.get(index);
            int colX = availableGrid.startX() + (index % availableGrid.columns()) * availableGrid.columnStep();
            int slotY = providerLayout.availableY() + (index / availableGrid.columns()) * PROVIDER_ROW_STEP;
            boolean inChain = chain.contains(name);
            int color = inChain ? 0x666666 : 0x55FF55;
            graphics.fill(colX, slotY - 1, colX + availableGrid.slotWidth(),
                    slotY - 1 + PROVIDER_SLOT_HEIGHT, inChain ? 0xFF222222 : 0xFF223333);
            graphics.drawString(font, ellipsize((inChain ? "[x] " : "[+] ") + providerLabel(name),
                    availableGrid.slotWidth() - 6), colX + 2, slotY + 1, color, false);
        }

        if (editingRow > 0) {
            int detailsY = providerLayout.bottomY() + 4;
            String purpose = purposes.get(editingRow - 1).id();
            EffectiveValues effective = effectiveValues.get(purpose);
            String effectiveText = effective == null ? string("routing.effective_unavailable")
                    : string("routing.effective", effective.provider, effective.temperature,
                            effective.maxOutput, effective.timeout, effective.inputBudget, effective.outputReserve);
            graphics.drawString(font, ellipsize(effectiveText, width - 20),
                    getX() + 8, detailsY, 0x88CCFF, false);
            updateParameterGeometry(detailsY);
            String[] labels = {"T", "Out", "Sec", "In", "Reserve"};
            List<EditBox> inputs = parameterInputs();
            for (int index = 0; index < inputs.size(); index++) {
                EditBox input = inputs.get(index);
                graphics.drawString(font, labels[index], input.getX(), detailsY + 15, 0xAAAAAA, false);
                if (input.visible) input.render(graphics, mouseX, mouseY, partialTick);
            }
            String help = parameterError.isEmpty() ? string("routing.parameter_help") : parameterError;
            graphics.drawString(font, ellipsize(help, width - 20), getX() + 8, detailsY + 44,
                    parameterError.isEmpty() ? 0x777777 : 0xFF5555, false);
        }
    }

    private void updateParameterGeometry(int detailsY) {
        int cellWidth = Math.max(30, (width - 16) / 5);
        int inputWidth = Math.max(26, cellWidth - 4);
        List<EditBox> inputs = parameterInputs();
        for (int index = 0; index < inputs.size(); index++) {
            EditBox input = inputs.get(index);
            input.setX(getX() + 8 + index * cellWidth);
            input.setY(detailsY + 24);
            input.setWidth(inputWidth);
            input.visible = visible && editingRow > 0
                    && input.getY() >= getY() && input.getY() + input.getHeight() <= getY() + height;
            if (!input.visible && input.isFocused()) input.setFocused(false);
        }
    }

    private ProviderEditorLayout providerEditorLayout(int editorY) {
        List<String> chain = getRowChain(editingRow);
        int activeCount = chain == null ? 0 : chain.size();
        if (!usesStackedProviderLayout(width)) {
            int rowY = editorY + 16;
            ProviderGrid active = providerGrid(getX() + 48, getX() + width / 2 - 8, ACTIVE_SLOT_WIDTH);
            ProviderGrid available = providerGrid(getX() + width / 2 + 58,
                    getX() + width - 12, AVAILABLE_SLOT_WIDTH);
            int rows = Math.max(providerRows(activeCount, active.columns()),
                    providerRows(providerNames.size(), available.columns()));
            return new ProviderEditorLayout(active, rowY, available, rowY,
                    rowY + rows * PROVIDER_ROW_STEP);
        }

        ProviderGrid active = providerGrid(getX() + 58, getX() + width - 12, ACTIVE_SLOT_WIDTH);
        int activeY = editorY + 16;
        int availableY = activeY + providerRows(activeCount, active.columns()) * PROVIDER_ROW_STEP + 4;
        ProviderGrid available = providerGrid(getX() + 58, getX() + width - 12, AVAILABLE_SLOT_WIDTH);
        int bottom = availableY + providerRows(providerNames.size(), available.columns()) * PROVIDER_ROW_STEP;
        return new ProviderEditorLayout(active, activeY, available, availableY, bottom);
    }

    static boolean usesStackedProviderLayout(int panelWidth) {
        return panelWidth < NARROW_PROVIDER_BREAKPOINT;
    }

    private static ProviderGrid providerGrid(int startX, int endX, int preferredSlotWidth) {
        int usableWidth = Math.max(36, endX - startX);
        int slotWidth = Math.min(preferredSlotWidth, usableWidth);
        int columnStep = slotWidth + PROVIDER_COLUMN_GAP;
        int columns = providerColumnCount(usableWidth, preferredSlotWidth);
        return new ProviderGrid(startX, slotWidth, columnStep, columns);
    }

    static int providerColumnCount(int usableWidth, int preferredSlotWidth) {
        int width = Math.max(36, usableWidth);
        int slotWidth = Math.min(preferredSlotWidth, width);
        int columnStep = slotWidth + PROVIDER_COLUMN_GAP;
        return Math.max(1, 1 + Math.max(0, width - slotWidth) / columnStep);
    }

    static int providerRows(int itemCount, int columns) {
        return Math.max(1, (itemCount + columns - 1) / columns);
    }

    private String providerLabel(String providerName) {
        return providerName == null || providerName.isBlank() ? string("common.none") : providerName;
    }

    private String chainDetails(List<String> chain) {
        if (chain == null || chain.isEmpty()) return string("routing.chain_all");
        return chain.stream().map(this::providerLabel)
                .collect(java.util.stream.Collectors.joining("  >  "));
    }

    private String ellipsize(String value, int maxWidth) {
        if (value == null || maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        String suffix = "...";
        int prefixWidth = Math.max(0, maxWidth - font.width(suffix));
        return font.plainSubstrByWidth(value, prefixWidth) + suffix;
    }

    private String providerAtEditorPosition(double mouseX, double mouseY) {
        if (editingRow < 0) return null;
        List<String> chain = getRowChain(editingRow);
        if (chain == null) chain = List.of();
        ProviderEditorLayout layout = providerEditorLayout(editorScreenY());
        int activeIndex = providerIndexAt(mouseX, mouseY, layout.activeGrid(), layout.activeY(), chain.size());
        if (activeIndex >= 0) return chain.get(activeIndex);

        int availableIndex = providerIndexAt(mouseX, mouseY, layout.availableGrid(),
                layout.availableY(), providerNames.size());
        if (availableIndex >= 0) return providerNames.get(availableIndex);
        return null;
    }

    private static int providerIndexAt(double mouseX, double mouseY, ProviderGrid grid,
                                       int startY, int itemCount) {
        double relativeX = mouseX - grid.startX();
        double relativeY = mouseY - (startY - 1);
        if (relativeX < 0 || relativeY < 0) return -1;
        int column = (int) (relativeX / grid.columnStep());
        int row = (int) (relativeY / PROVIDER_ROW_STEP);
        if (column < 0 || column >= grid.columns()
                || relativeX - column * grid.columnStep() >= grid.slotWidth()
                || relativeY - row * PROVIDER_ROW_STEP >= PROVIDER_SLOT_HEIGHT) return -1;
        int index = row * grid.columns() + column;
        return index < itemCount ? index : -1;
    }

    private static String optionSummary(LlmRouteOptions options) {
        return "T:" + inherited(options.temperature()) + " out:" + inherited(options.maxOutputTokens())
                + " sec:" + inherited(options.timeoutSeconds()) + " in:" + inherited(options.inputBudgetTokens())
                + " res:" + inherited(options.outputReserveTokens());
    }

    private static String inherited(Number value) {
        return value == null ? string("common.inherit") : value.toString();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false;
        updateScrollGeometry();
        if (pageScroll.mouseClicked(mouseX, mouseY, button)) return true;
        int footerY = screenY(footerContentY());
        // Footer actions
        if (mouseY >= footerY && mouseY < footerY + 12) {
            if (inLabel(mouseX, 8, 44)) { save(); return true; }
            if (inLabel(mouseX, 60, 96)) { LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket()); return true; }
            if (inLabel(mouseX, 110, 168)) { LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket()); return true; }
        }
        if (editingRow > 0) {
            updateParameterGeometry(editorDetailsY());
            boolean handled = false;
            for (EditBox input : parameterInputs()) {
                boolean clicked = input.visible && input.mouseClicked(mouseX, mouseY, button);
                input.setFocused(clicked);
                handled |= clicked;
            }
            if (handled) return true;
        }
        // Inline editor hit-testing
        if (editingRow >= 0 && mouseY >= editorScreenY()
                && mouseY < editorScreenY() + editorHeight()) {
            return handleEditorClick(mouseX, mouseY);
        }
        // Row click -> open/close editor (the Edit button area or anywhere in the row)
        int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            openEditor(row);
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (pageScroll.mouseDragged(mouseX, mouseY, button)) {
            updateScrollGeometry();
            return true;
        }
        if (editingRow > 0) {
            for (EditBox input : parameterInputs()) {
                if (input.visible && input.isFocused()
                        && input.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pageScroll.mouseReleased(button)) return true;
        if (editingRow > 0) {
            boolean handled = false;
            for (EditBox input : parameterInputs()) {
                if (input.visible) handled |= input.mouseReleased(mouseX, mouseY, button);
            }
            if (handled) return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible || !isMouseOver(mouseX, mouseY)) return false;
        if (!pageScroll.scroll(delta, 24)) return false;
        updateScrollGeometry();
        return true;
    }

    private boolean handleEditorClick(double mouseX, double mouseY) {
        List<String> chain = getRowChain(editingRow);
        if (chain == null) chain = new ArrayList<>();
        ProviderEditorLayout layout = providerEditorLayout(editorScreenY());
        // Active chain slots: click removes from chain
        int activeIndex = providerIndexAt(mouseX, mouseY, layout.activeGrid(), layout.activeY(), chain.size());
        if (activeIndex >= 0) {
            List<String> next = new ArrayList<>(chain);
            next.remove(activeIndex);
            setRowChain(editingRow, next);
            updateScrollGeometry();
            return true;
        }
        // Available providers: click appends
        int availableIndex = providerIndexAt(mouseX, mouseY, layout.availableGrid(),
                layout.availableY(), providerNames.size());
        if (availableIndex >= 0) {
            String name = providerNames.get(availableIndex);
            if (!chain.contains(name)) {
                List<String> next = new ArrayList<>(chain);
                next.add(name);
                setRowChain(editingRow, next);
                updateScrollGeometry();
            }
            return true;
        }
        return true;
    }

    private boolean inLabel(double mouseX, int startXRel, int endXRel) {
        return mouseX >= getX() + startXRel && mouseX < getX() + endXRel;
    }

    private void save() {
        if (!commitParameterFields()) return;
        if (!dirty) return;
        String json = C2SUpdateRoutingPacket.toJson(editedDefault, edited, editedOptions);
        LLMNetwork.CHANNEL.sendToServer(new C2SUpdateRoutingPacket(json));
        dirty = false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editingRow > 0) {
            for (EditBox input : parameterInputs()) {
                if (input.isFocused() && input.keyPressed(keyCode, scanCode, modifiers)) return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (editingRow > 0) {
            for (EditBox input : parameterInputs()) {
                if (input.isFocused() && input.charTyped(codePoint, modifiers)) return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
