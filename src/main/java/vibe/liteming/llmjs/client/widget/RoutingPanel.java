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

    private static final int ROW_HEIGHT = 18;
    private static final int HEADER_Y_OFFSET = 6;

    private final Font font;
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

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x90000000);
        int y = getY() + HEADER_Y_OFFSET;
        graphics.drawString(font, text("routing.purpose"), getX() + 8, y, 0xAAAAAA, false);
        graphics.drawString(font, text("routing.provider_chain"), getX() + 150, y, 0xAAAAAA, false);
        if (!routingFingerprint.isEmpty() && width > 520) {
            graphics.drawString(font, "cfg " + routingFingerprint.substring(0, Math.min(8, routingFingerprint.length())),
                    getX() + width - 150, y, 0x777777, false);
        }
        graphics.drawString(font, text(dirty ? "routing.unsaved" : "routing.saved"),
                getX() + width - 70, y, dirty ? 0xFFAA00 : 0x55FF55, false);
        y += ROW_HEIGHT;
        graphics.fill(getX() + 4, y - 2, getX() + width - 4, y - 1, 0xFF555555);

        int headerH = getY() + HEADER_Y_OFFSET + ROW_HEIGHT;
        int hoveredRow = -1;
        if (mouseX >= getX() && mouseX < getX() + width && mouseY > headerH) {
            int idx = (int) ((mouseY - headerH) / ROW_HEIGHT);
            int totalRows = 1 + purposes.size();
            if (idx >= 0 && idx < totalRows) hoveredRow = idx;
        }

        // Default row + per-purpose rows
        for (int row = 0; row < 1 + purposes.size(); row++) {
            int rowLimit = editingRow >= 0 ? editorTop() - 4 : getY() + height - 30;
            if (y + ROW_HEIGHT > rowLimit) break;
            int rowY = y;
            boolean isEditing = (row == editingRow);
            if (row == hoveredRow || isEditing) {
                graphics.fill(getX() + 4, rowY - 1, getX() + width - 4, rowY + ROW_HEIGHT - 2, isEditing ? 0x4055AAFF : 0x28FFFFFF);
            }
            graphics.drawString(font, font.plainSubstrByWidth(getRowLabel(row), 140), getX() + 8, rowY + 2, 0xFFFFFF, false);
            List<String> chain = getRowChain(row);
            String chainText = chain == null || chain.isEmpty()
                    ? string("routing.chain_all") : String.join("  >  ", chain);
            int chainWidth = width >= 650 ? Math.max(120, width / 3) : width - 320;
            graphics.drawString(font, font.plainSubstrByWidth(chainText, chainWidth), getX() + 150, rowY + 2, 0xCCCCCC, false);
            if (row > 0 && width >= 650) {
                String summary = optionSummary(getRowOptions(row));
                graphics.drawString(font, font.plainSubstrByWidth(summary, width - chainWidth - 330),
                        getX() + 158 + chainWidth, rowY + 2, 0x999999, false);
            }
            graphics.drawCenteredString(font, text(isEditing ? "routing.done" : "routing.edit"),
                    getX() + width - 56, rowY + 3, 0xFFAAAA);
            y += ROW_HEIGHT;
        }

        // Footer: action buttons (text labels; clicked via mouseClicked)
        int footerY = getY() + height - 22;
        graphics.drawString(font, text("routing.save"), getX() + 8, footerY, dirty ? 0x55FF55 : 0x666666, false);
        graphics.drawString(font, text("routing.reset"), getX() + 60, footerY, 0xAAAAFF, false);
        graphics.drawString(font, text("routing.refresh"), getX() + 110, footerY, 0xAAAAFF, false);
        if (providerNames.isEmpty()) {
            graphics.drawString(font, text("routing.no_providers"), getX() + 200, footerY, 0xFF5555, false);
        }

        // Inline editor row when editing
        if (editingRow >= 0) {
            renderInlineEditor(graphics, mouseX, mouseY, partialTick);
        }
        renderRoutingTooltip(graphics, mouseX, mouseY, hoveredRow, headerH, footerY);
    }

    private void renderRoutingTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                      int hoveredRow, int headerH, int footerY) {
        if (mouseX < getX() || mouseX >= getX() + width
                || mouseY < getY() || mouseY >= getY() + height) return;
        if (editingRow > 0) {
            String[] parameterTips = {"parameter.temperature.tip", "parameter.max_output.tip",
                    "parameter.timeout.tip", "parameter.input_budget.tip", "parameter.output_reserve.tip"};
            List<EditBox> inputs = parameterInputs();
            for (int index = 0; index < inputs.size(); index++) {
                if (inputs.get(index).isMouseOver(mouseX, mouseY)) {
                    graphics.renderTooltip(font, text(parameterTips[index]), mouseX, mouseY);
                    return;
                }
            }
            int editorY = editorTop();
            if (mouseY >= editorY + 12 && mouseY < editorY + 28) {
                graphics.renderTooltip(font, text(mouseX < getX() + width / 2
                        ? "routing.active.tip" : "routing.available.tip"), mouseX, mouseY);
                return;
            }
            if (mouseY >= editorY + 28 && mouseY < editorY + 44) {
                graphics.renderTooltip(font, text("routing.effective.tip"), mouseX, mouseY);
                return;
            }
        }
        if (mouseY >= getY() && mouseY < headerH) {
            if (mouseX < getX() + 145) {
                graphics.renderTooltip(font, text("routing.purpose.tip"), mouseX, mouseY);
            } else if (mouseX < getX() + width - 160) {
                graphics.renderTooltip(font, text("routing.provider_chain.tip"), mouseX, mouseY);
            } else {
                graphics.renderTooltip(font, text("routing.state.tip"), mouseX, mouseY);
            }
            return;
        }
        if (hoveredRow >= 0 && mouseY >= headerH && mouseY < footerY) {
            if (hoveredRow == 0) {
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
        int ey = editorTop();
        graphics.fill(getX() + 4, ey - 2, getX() + width - 4, getY() + height - 26, 0xE0222222);
        graphics.drawString(font, text("routing.editing", getRowLabel(editingRow)),
                getX() + 8, ey, 0xFFFFFF, false);
        // Active chain column (click to remove, click arrows to reorder)
        int cx = getX() + 8;
        int cy = ey + 14;
        graphics.drawString(font, text("routing.active"), cx, cy, 0xAAAAFF, false);
        int slotX = cx + 40;
        for (int i = 0; i < chain.size(); i++) {
            graphics.fill(slotX, cy - 1, slotX + 90, cy + 11, 0xFF333355);
            String label = (i == 0 ? "[1] " : "[fb] ") + font.plainSubstrByWidth(chain.get(i), 70);
            graphics.drawString(font, label, slotX + 2, cy + 1, 0xFFFFFF, false);
            slotX += 96;
            if (slotX + 96 > getX() + width / 2) break;
        }
        // Available providers column (click to append)
        int ax = getX() + width / 2 + 8;
        int ay = cy;
        graphics.drawString(font, text("routing.available"), ax, ay, 0xAAAAFF, false);
        int colX = ax + 50;
        for (String name : providerNames) {
            boolean inChain = chain.contains(name);
            int color = inChain ? 0x666666 : 0x55FF55;
            graphics.fill(colX, ay - 1, colX + 110, ay + 11, inChain ? 0xFF222222 : 0xFF223333);
            graphics.drawString(font, font.plainSubstrByWidth((inChain ? "[x] " : "[+] ") + name, 100), colX + 2, ay + 1, color, false);
            colX += 116;
            if (colX + 116 > getX() + width - 8) break;
        }

        if (editingRow > 0) {
            String purpose = purposes.get(editingRow - 1).id();
            EffectiveValues effective = effectiveValues.get(purpose);
            String effectiveText = effective == null ? string("routing.effective_unavailable")
                    : string("routing.effective", effective.provider, effective.temperature,
                            effective.maxOutput, effective.timeout, effective.inputBudget, effective.outputReserve);
            graphics.drawString(font, font.plainSubstrByWidth(effectiveText, width - 20),
                    getX() + 8, ey + 32, 0x88CCFF, false);
            updateParameterGeometry(ey);
            String[] labels = {"T", "Out", "Sec", "In", "Reserve"};
            List<EditBox> inputs = parameterInputs();
            for (int index = 0; index < inputs.size(); index++) {
                EditBox input = inputs.get(index);
                graphics.drawString(font, labels[index], input.getX(), ey + 47, 0xAAAAAA, false);
                input.render(graphics, mouseX, mouseY, partialTick);
            }
            String help = parameterError.isEmpty() ? string("routing.parameter_help") : parameterError;
            graphics.drawString(font, font.plainSubstrByWidth(help, width - 20), getX() + 8, ey + 80,
                    parameterError.isEmpty() ? 0x777777 : 0xFF5555, false);
        }
    }

    private int editorTop() {
        return getY() + height - (editingRow > 0 ? 118 : 60);
    }

    private void updateParameterGeometry(int editorY) {
        int cellWidth = Math.max(54, (width - 16) / 5);
        int inputWidth = Math.max(36, cellWidth - 6);
        List<EditBox> inputs = parameterInputs();
        for (int index = 0; index < inputs.size(); index++) {
            EditBox input = inputs.get(index);
            input.setX(getX() + 8 + index * cellWidth);
            input.setY(editorY + 56);
            input.setWidth(inputWidth);
        }
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
        int footerY = getY() + height - 22;
        // Footer actions
        if (mouseY >= footerY && mouseY < footerY + 12) {
            if (inLabel(mouseX, 8, 44)) { save(); return true; }
            if (inLabel(mouseX, 60, 96)) { LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket()); return true; }
            if (inLabel(mouseX, 110, 168)) { LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket()); return true; }
        }
        if (editingRow > 0) {
            updateParameterGeometry(editorTop());
            boolean handled = false;
            for (EditBox input : parameterInputs()) {
                boolean clicked = input.mouseClicked(mouseX, mouseY, button);
                input.setFocused(clicked);
                handled |= clicked;
            }
            if (handled) return true;
        }
        // Inline editor hit-testing
        if (editingRow >= 0 && mouseY >= editorTop() && mouseY < getY() + height - 24) {
            return handleEditorClick(mouseX, mouseY);
        }
        // Row click -> open/close editor (the Edit button area or anywhere in the row)
        int headerH = getY() + HEADER_Y_OFFSET + ROW_HEIGHT;
        if (mouseY > headerH) {
            int row = (int) ((mouseY - headerH) / ROW_HEIGHT);
            if (row >= 0 && row < 1 + purposes.size()) {
                int editBtnX = getX() + width - 70;
                int rowY = headerH + row * ROW_HEIGHT;
                if (mouseX >= editBtnX && mouseX <= editBtnX + 40 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2) {
                    openEditor(row);
                    return true;
                }
                // Anywhere else on the row toggles editor too (UX nicety)
                openEditor(row);
                return true;
            }
        }
        return true;
    }

    private boolean handleEditorClick(double mouseX, double mouseY) {
        List<String> chain = getRowChain(editingRow);
        if (chain == null) chain = new ArrayList<>();
        int ey = editorTop();
        int cy = ey + 14;
        // Active chain slots: click removes from chain
        int slotX = getX() + 48;
        for (int i = 0; i < chain.size(); i++) {
            if (mouseX >= slotX && mouseX < slotX + 90 && mouseY >= cy - 1 && mouseY < cy + 11) {
                List<String> next = new ArrayList<>(chain);
                next.remove(i);
                setRowChain(editingRow, next);
                return true;
            }
            slotX += 96;
            if (slotX + 96 > getX() + width / 2) break;
        }
        // Available providers: click appends
        int ax = getX() + width / 2 + 8;
        int colX = ax + 50;
        int ay = cy;
        for (String name : providerNames) {
            if (!chain.contains(name) && mouseX >= colX && mouseX < colX + 110 && mouseY >= ay - 1 && mouseY < ay + 11) {
                List<String> next = new ArrayList<>(chain);
                next.add(name);
                setRowChain(editingRow, next);
                return true;
            }
            colX += 116;
            if (colX + 116 > getX() + width - 8) break;
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
