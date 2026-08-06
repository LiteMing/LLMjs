package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import vibe.liteming.llmcore.LlmRouteOptions;
import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.RoutingConfigStore;
import vibe.liteming.llmjs.config.GlobalConfig;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SChatRequestPacket;
import vibe.liteming.llmjs.network.packet.C2SVisionProbePacket;
import vibe.liteming.llmjs.network.packet.C2SUpdateRoutingPacket;
import vibe.liteming.llmjs.test.ConsoleTestCodec;
import vibe.liteming.llmjs.test.ConsoleTestRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;
import static vibe.liteming.llmjs.client.ConsoleTexts.tooltip;

@OnlyIn(Dist.CLIENT)
public class TestPanel {
    private static final int PARAMETER_LABEL_Y = 76;
    private static final int PARAMETER_INPUT_Y = 85;
    private static final int TOOLBAR_Y = 106;
    private static final int STATUS_Y = 127;
    private static final int RESULT_TITLE_Y = 140;
    private static final int MIN_CONTENT_HEIGHT = 260;
    private static final int COMPACT_CHAIN_Y = 22;
    private static final int COMPACT_PROMPT_Y = 40;
    private static final int COMPACT_PARAM_INPUT_Y = 58;
    private static final int COMPACT_TOOLBAR_Y = 72;
    private static final int COMPACT_STATUS_Y = 88;
    private static final int COMPACT_RESULT_TITLE_Y = 100;
    private static final int COMPACT_MIN_CONTENT_HEIGHT = 130;
    private static final int JSON_EDITOR_Y = 42;
    private static final int JSON_EDITOR_HEIGHT = 180;
    private static final int JSON_CONTENT_HEIGHT = 380;

    private int x, y, width, height;
    private int contentHeight;
    private final Font font;
    private final ConsoleScrollBar pageScroll = new ConsoleScrollBar();
    private final EditBox purposeInput;
    private final EditBox providerInput;
    private final EditBox promptInput;
    private final EditBox temperatureInput;
    private final EditBox maxOutputInput;
    private final EditBox timeoutInput;
    private final EditBox inputBudgetInput;
    private final EditBox outputReserveInput;
    private final RequestJsonEditBox requestJsonInput;
    private final Button routingModeButton;
    private final Button sendButton;
    private final Button visionProbeButton;
    private final Button simpleButton;
    private final Button jsonModeButton;
    private final Button copyButton;
    private final Button saveRouteButton;
    private final Button prevTemplateBtn;
    private final Button nextTemplateBtn;
    private final Button saveTemplateBtn;
    private @Nullable String responseText;
    private @Nullable UUID activeRequestId;
    private @Nullable ConsoleTestRequest loadedHandoff;
    private boolean waiting;
    private boolean visible = true;
    private boolean restricted;
    private boolean jsonMode;
    private boolean jsonDraftValid;
    private String jsonValidationText = "";
    private int jsonValidationColor = 0xAAAAAA;
    private ConsoleTestRequest.RoutingMode routingMode = ConsoleTestRequest.RoutingMode.PURPOSE;
    private @Nullable String visionResultText;
    private int visionResultColor = 0xAAAAAA;
    private int responseScroll;
    private int responseLineCount;
    private List<ResultSegment> responseSegments = List.of();
    private int responseSelectionAnchor = -1;
    private int responseSelectionEnd = -1;
    private boolean draggingResponseSelection;
    private boolean responseSelectionActive;
    private long copyFlashUntilMs;
    private PriorityRoutingConfig routingSnapshot = PriorityRoutingConfig.empty();
    private Map<String, String> effectiveByPurpose = new LinkedHashMap<>();

    private List<String> providerNames = new ArrayList<>();
    private List<String> purposeNames = new ArrayList<>();
    private int providerCycleIndex = -1;
    private int purposeCycleIndex = -1;
    private final List<PromptTemplate> templates = new ArrayList<>();
    private int currentTemplateIndex = -1;

    public record PromptTemplate(String name, String prompt) {}
    private record ResultSegment(String text, int startOffset, int endOffset) {}

    private static final class RequestJsonEditBox extends MultiLineEditBox {
        private boolean readOnly;

        // MultiLineEditBox's underlying MultilineTextField is private and its
        // selection anchor is only collapsed on click when the "selecting" flag
        // happens to be false; setValue() leaves the anchor at the end of the
        // text, so a stale selecting=true made every plain click select from the
        // document end toward the click point and broke independent drag
        // selection. Locate the field by type so it also works after reobfuscation.
        private static final java.lang.reflect.Field TEXT_FIELD = locateTextField();

        private static java.lang.reflect.Field locateTextField() {
            for (java.lang.reflect.Field field : MultiLineEditBox.class.getDeclaredFields()) {
                if (MultilineTextField.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            }
            return null;
        }

        private RequestJsonEditBox(Font font, int x, int y, int width, int height,
                Component placeholder, Component narration) {
            super(font, x, y, width, height, placeholder, narration);
        }

        private void setReadOnly(boolean value) {
            readOnly = value;
        }

        // MultiLineEditBox hit-tests via withinContentAreaPoint() which ignores the
        // visible flag; an invisible editor was swallowing clicks over the whole
        // lower Setup panel. Gate all mouse handlers on visibility.
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!visible) return false;
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            // 1.20.1 bug: AbstractScrollWidget.mouseClicked returns true for every
            // in-content click without doing anything, so MultiLineEditBox's own
            // cursor-seek branch is unreachable and clicking never moves the caret.
            // Replicate the seek here.
            if (handled && button == 0 && withinContentAreaPoint(mouseX, mouseY)) {
                seekCursorToPoint(mouseX, mouseY);
            }
            return handled;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button,
                double dragX, double dragY) {
            return visible && super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            return visible && super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) {
            return !readOnly && super.charTyped(codePoint, modifiers);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!readOnly) return super.keyPressed(keyCode, scanCode, modifiers);
            boolean navigation = keyCode >= 262 && keyCode <= 269;
            return (navigation || Screen.isSelectAll(keyCode) || Screen.isCopy(keyCode))
                    && super.keyPressed(keyCode, scanCode, modifiers);
        }

        // Replicates MultiLineEditBox.seekCursorScreen: moves the caret (and with
        // selecting=false collapses the anchor, so a plain click never selects).
        private void seekCursorToPoint(double mouseX, double mouseY) {
            if (TEXT_FIELD == null) return;
            try {
                MultilineTextField textField = (MultilineTextField) TEXT_FIELD.get(this);
                textField.setSelecting(Screen.hasShiftDown());
                double localX = mouseX - (double) getX() - (double) innerPadding();
                double localY = mouseY - (double) getY() - (double) innerPadding() + scrollAmount();
                textField.seekCursorToPoint(localX, localY);
            } catch (Exception ignored) {
            }
        }
    }

    public TestPanel(int x, int y, int width, int height, Font font) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.font = font;

        int purposeWidth = Math.max(70, Math.min(180, width - 220));
        purposeInput = input(font, x + 62, y + 4, purposeWidth, "test.purpose", 96);
        purposeInput.setValue("DEBUG_TEST");
        routingModeButton = tooltip(Button.builder(text("test.mode.purpose"), button -> toggleRoutingMode())
                .pos(x + 66 + purposeWidth, y + 4).size(70, 18).build(), "test.mode.tip");
        visionProbeButton = tooltip(Button.builder(text("test.vision"), button -> sendVisionProbe())
                .pos(x + width - 72, y + 4).size(62, 18).build(), "test.vision.tip");
        providerInput = input(font, x + 62, y + 28, Math.max(80, width - 72), "test.provider_chain", 512);

        promptInput = input(font, x + 62, y + 52, Math.max(70, width - 208), "test.prompt", 16_384);
        promptInput.setValue(string("test.default_prompt"));
        sendButton = tooltip(Button.builder(text("test.send"), button -> sendTest())
                .pos(x + width - 140, y + 52).size(64, 18).build(), "test.send.tip");
        simpleButton = tooltip(Button.builder(text("test.simple"), button -> enterSimpleMode())
                .pos(x + width - 72, y + 52).size(62, 18).build(), "test.simple.tip");

        int parameterY = y + PARAMETER_INPUT_Y;
        int parameterCell = Math.max(1, (width - 8) / 5);
        temperatureInput = parameterInput(font, x + 4, parameterY, parameterCell - 4,
                "parameter.temperature", "parameter.temperature.tip");
        maxOutputInput = parameterInput(font, x + 4 + parameterCell, parameterY, parameterCell - 4,
                "parameter.max_output", "parameter.max_output.tip");
        timeoutInput = parameterInput(font, x + 4 + parameterCell * 2, parameterY, parameterCell - 4,
                "parameter.timeout", "parameter.timeout.tip");
        inputBudgetInput = parameterInput(font, x + 4 + parameterCell * 3, parameterY, parameterCell - 4,
                "parameter.input_budget", "parameter.input_budget.tip");
        outputReserveInput = parameterInput(font, x + 4 + parameterCell * 4, parameterY, parameterCell - 4,
                "parameter.output_reserve", "parameter.output_reserve.tip");

        prevTemplateBtn = tooltip(Button.builder(Component.literal("<"), button -> cycleTemplate(-1))
                .pos(x + 4, y + TOOLBAR_Y).size(20, 16).build(), "test.template.previous.tip");
        nextTemplateBtn = tooltip(Button.builder(Component.literal(">"), button -> cycleTemplate(1))
                .pos(x + 26, y + TOOLBAR_Y).size(20, 16).build(), "test.template.next.tip");
        saveTemplateBtn = tooltip(Button.builder(text("test.template.save"), button -> saveCurrentAsTemplate())
                .pos(x + 50, y + TOOLBAR_Y).size(62, 16).build(), "test.template.save.tip");
        copyButton = tooltip(Button.builder(text("test.copy_result"), button -> copyResult())
                .pos(x + 116, y + TOOLBAR_Y).size(72, 16).build(), "test.copy_result.tip");
        saveRouteButton = tooltip(Button.builder(text("test.save_route"), button -> saveToRouting())
                .pos(x + 192, y + TOOLBAR_Y).size(70, 16).build(), "test.save_route.tip");
        jsonModeButton = tooltip(Button.builder(text("test.json.open"), button -> toggleJsonMode())
                .pos(x + 266, y + TOOLBAR_Y).size(52, 16).build(), "test.json.mode.tip");
        requestJsonInput = new RequestJsonEditBox(font, x + 4, y + JSON_EDITOR_Y,
                Math.max(80, width - 12), JSON_EDITOR_HEIGHT,
                text("test.json.placeholder"), text("test.json.editor"));
        requestJsonInput.setCharacterLimit(ConsoleTestCodec.MAX_REQUEST_JSON_CHARS);
        requestJsonInput.setValueListener(this::validateJsonDraft);
        tooltip(requestJsonInput, "test.json.editor.tip");
        tooltip(purposeInput, "test.purpose.tip");
        tooltip(providerInput, "test.provider_chain.tip");
        tooltip(promptInput, "test.prompt.tip");
        loadTemplatesFromConfig();
        setBounds(x, y, width, height);
    }

    private static EditBox input(Font font, int x, int y, int width, String labelKey, int maxLength) {
        EditBox input = new EditBox(font, x, y, width, 18, text(labelKey));
        input.setMaxLength(maxLength);
        return input;
    }

    private static EditBox parameterInput(Font font, int x, int y, int width, String labelKey, String tipKey) {
        EditBox input = input(font, x, y, Math.max(34, width), labelKey, 12);
        input.setHint(text("common.inherit"));
        return tooltip(input, tipKey);
    }

    public List<AbstractWidget> getWidgets() {
        return List.of(purposeInput, routingModeButton, providerInput, visionProbeButton, promptInput, sendButton,
                simpleButton, temperatureInput, maxOutputInput, timeoutInput, inputBudgetInput, outputReserveInput,
                prevTemplateBtn, nextTemplateBtn, saveTemplateBtn, copyButton, saveRouteButton, jsonModeButton,
                requestJsonInput);
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.contentHeight = Math.max(jsonMode ? JSON_CONTENT_HEIGHT : formContentHeight(), this.height);
        pageScroll.setTrack(this.x + this.width - 6, this.y + 2, this.y + this.height - 2);
        pageScroll.update(contentHeight, this.height);
        layoutWidgets();
        responseSegments = buildResultSegments(responseText);
        responseLineCount = responseSegments.size();
        clampResponseScroll();
    }

    private boolean compactLayout() {
        return !jsonMode && height < 200;
    }

    private int chainY() {
        return compactLayout() ? COMPACT_CHAIN_Y : 28;
    }

    private int promptY() {
        return compactLayout() ? COMPACT_PROMPT_Y : 52;
    }

    private int parameterLabelY() {
        return compactLayout() ? COMPACT_PARAM_INPUT_Y - 9 : PARAMETER_LABEL_Y;
    }

    private int parameterInputY() {
        return compactLayout() ? COMPACT_PARAM_INPUT_Y : PARAMETER_INPUT_Y;
    }

    private int toolbarY() {
        return compactLayout() ? COMPACT_TOOLBAR_Y : TOOLBAR_Y;
    }

    private int statusY() {
        return compactLayout() ? COMPACT_STATUS_Y : STATUS_Y;
    }

    private int formContentHeight() {
        return compactLayout() ? COMPACT_MIN_CONTENT_HEIGHT : MIN_CONTENT_HEIGHT;
    }

    private int contentY(int relativeY) {
        return y + relativeY - pageScroll.offset();
    }

    private void layoutWidgets() {
        int purposeWidth = Math.max(70, Math.min(180, width - 220));
        place(purposeInput, x + 62, contentY(4), purposeWidth, 18);
        place(routingModeButton, x + 66 + purposeWidth, contentY(4), 70, 18);
        place(visionProbeButton, x + width - 72, contentY(4), 62, 18);
        place(providerInput, x + 62, contentY(chainY()), Math.max(80, width - 72), 18);
        place(promptInput, x + 62, contentY(promptY()), Math.max(70, width - 208), 18);
        place(sendButton, x + width - 140, contentY(promptY()), 64, 18);
        place(simpleButton, x + width - 72, contentY(promptY()), 62, 18);

        int parameterCell = Math.max(1, (width - 8) / 5);
        List<EditBox> parameters = List.of(temperatureInput, maxOutputInput, timeoutInput,
                inputBudgetInput, outputReserveInput);
        for (int index = 0; index < parameters.size(); index++) {
            place(parameters.get(index), x + 4 + parameterCell * index, contentY(parameterInputY()),
                    Math.max(34, parameterCell - 4), 18);
        }

        place(prevTemplateBtn, x + 4, contentY(toolbarY()), 20, 16);
        place(nextTemplateBtn, x + 26, contentY(toolbarY()), 20, 16);
        place(saveTemplateBtn, x + 50, contentY(toolbarY()), 62, 16);
        place(copyButton, x + 116, contentY(toolbarY()), 72, 16);
        place(saveRouteButton, x + 192, contentY(toolbarY()), 70, 16);
        place(jsonModeButton, jsonMode ? x + 4 : x + 266,
                contentY(jsonMode ? 4 : toolbarY()), jsonMode ? 62 : 52, jsonMode ? 18 : 16);
        place(sendButton, jsonMode ? x + width - 72 : x + width - 140,
                contentY(jsonMode ? 4 : promptY()), jsonMode ? 62 : 64, 18);
        place(requestJsonInput, x + 4, contentY(JSON_EDITOR_Y), Math.max(80, width - 12), jsonEditorHeight());

        for (AbstractWidget widget : getWidgets()) {
            boolean overlaps = widget.getY() < y + height && widget.getY() + widget.getHeight() > y;
            boolean modeVisible = jsonMode
                    ? widget == jsonModeButton || widget == sendButton || widget == requestJsonInput
                    : widget != requestJsonInput;
            widget.visible = visible && overlaps && modeVisible;
            if (!widget.visible && widget.isFocused()) widget.setFocused(false);
        }
    }

    private static void place(AbstractWidget widget, int x, int y, int width, int height) {
        widget.setX(x);
        widget.setY(y);
        widget.setWidth(Math.max(1, width));
        widget.setHeight(Math.max(1, height));
    }

    public void setVisible(boolean value) {
        visible = value;
        layoutWidgets();
    }

    public boolean isVisible() {
        return visible;
    }

    /** Restricted handoffs can only execute the immutable server-authorized draft. */
    public void setRestricted(boolean value) {
        restricted = value;
        updateRestrictedControls();
    }

    private void updateRestrictedControls() {
        purposeInput.setEditable(!restricted);
        providerInput.setEditable(!restricted);
        promptInput.setEditable(!restricted);
        temperatureInput.setEditable(!restricted);
        maxOutputInput.setEditable(!restricted);
        timeoutInput.setEditable(!restricted);
        inputBudgetInput.setEditable(!restricted);
        outputReserveInput.setEditable(!restricted);
        routingModeButton.active = !restricted;
        visionProbeButton.active = !restricted;
        simpleButton.active = !restricted;
        prevTemplateBtn.active = !restricted;
        nextTemplateBtn.active = !restricted;
        saveTemplateBtn.active = !restricted;
        saveRouteButton.active = !restricted;
        requestJsonInput.setReadOnly(restricted);
        requestJsonInput.active = true;
        jsonModeButton.active = true;
        updateSendButtonState();
    }

    private void updateSendButtonState() {
        boolean allowed = !waiting && (!restricted || loadedHandoff != null);
        if (jsonMode && !restricted) allowed = allowed && jsonDraftValid;
        sendButton.active = allowed;
    }

    public void updateStatus(String statusJson) {
        List<String> providers = new ArrayList<>();
        List<String> purposes = new ArrayList<>();
        Map<String, String> effective = new LinkedHashMap<>();
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            if (root.has("routing") && root.get("routing").isJsonObject()) {
                routingSnapshot = RoutingConfigStore.parse(root.getAsJsonObject("routing").toString());
            }
            if (root.has("providers") && root.get("providers").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("providers")) {
                    providers.add(element.getAsJsonObject().get("name").getAsString());
                }
            }
            if (root.has("purposes") && root.get("purposes").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("purposes")) {
                    JsonObject purpose = element.getAsJsonObject();
                    String id = purpose.get("id").getAsString();
                    purposes.add(id);
                    if (purpose.has("effective") && purpose.get("effective").isJsonObject()) {
                        effective.put(id, effectiveSummary(purpose.getAsJsonObject("effective")));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        updateProviderNames(providers);
        purposeNames = purposes;
        effectiveByPurpose = effective;
    }

    private static String effectiveSummary(JsonObject effective) {
        String provider = jsonText(effective, "provider", string("common.none"));
        String temperature = jsonText(effective, "temperature", string("common.unset"));
        String output = jsonText(effective, "maxOutputTokens", string("common.unset"));
        String timeout = jsonText(effective, "timeoutSeconds", string("common.unset"));
        String input = effective.has("inputBudgetUnbounded") && effective.get("inputBudgetUnbounded").getAsBoolean()
                ? string("common.unbounded")
                : jsonText(effective, "inputBudgetTokens", string("common.unset"));
        String reserve = jsonText(effective, "outputReserveTokens", string("common.unset"));
        return string("test.effective", provider, temperature, output, timeout, input, reserve);
    }

    private static String jsonText(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    public void updateProviderNames(List<String> names) {
        providerNames = names == null ? new ArrayList<>() : new ArrayList<>(names);
    }

    public boolean handleTabComplete(int keyCode) {
        if (restricted) return false;
        if (keyCode != 258) return false;
        if (providerInput.isFocused() && !providerNames.isEmpty()) {
            providerCycleIndex = cycleMatch(providerInput, providerNames, providerCycleIndex);
            return providerCycleIndex >= 0;
        }
        if (purposeInput.isFocused() && !purposeNames.isEmpty()) {
            purposeCycleIndex = cycleMatch(purposeInput, purposeNames, purposeCycleIndex);
            return purposeCycleIndex >= 0;
        }
        return false;
    }

    private static int cycleMatch(EditBox input, List<String> values, int currentIndex) {
        String current = input.getValue().trim();
        String prefix = values.contains(current) ? "" : current.toLowerCase(java.util.Locale.ROOT);
        List<String> matches = values.stream()
                .filter(value -> prefix.isEmpty() || value.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                .toList();
        if (matches.isEmpty()) return -1;
        int next = (currentIndex + 1) % matches.size();
        input.setValue(matches.get(next));
        return next;
    }

    public void loadHandoff(String handoffJson) {
        try {
            ConsoleTestRequest request = ConsoleTestCodec.parseRequest(handoffJson, false);
            applyRequestToForm(request);
            requestJsonInput.setValue(displayRequestJson(request));
            waiting = false;
            setResponseText(string("test.status.draft", request.requestId(), request.messages().size(),
                    request.generationType()));
            updateRestrictedControls();
        } catch (IllegalArgumentException e) {
            setResponseText(string("common.error", e.getMessage()));
        }
    }

    private void toggleJsonMode() {
        try {
            if (jsonMode) {
                ConsoleTestRequest request = restricted && loadedHandoff != null
                        ? loadedHandoff
                        : ConsoleTestCodec.parseRequest(requestJsonInput.getValue(), false);
                applyRequestToForm(request);
                jsonMode = false;
            } else {
                ConsoleTestRequest request = buildRequest();
                requestJsonInput.setValue(displayRequestJson(request));
                jsonMode = true;
            }
            contentHeight = Math.max(jsonMode ? JSON_CONTENT_HEIGHT : formContentHeight(), height);
            pageScroll.setOffset(0);
            pageScroll.update(contentHeight, height);
            jsonModeButton.setMessage(text(jsonMode ? "test.json.close" : "test.json.open"));
            updateRestrictedControls();
            layoutWidgets();
            clampResponseScroll();
        } catch (IllegalArgumentException e) {
            setResponseText(string("common.error", e.getMessage()));
            updateSendButtonState();
        }
    }

    private void applyRequestToForm(ConsoleTestRequest request) {
        loadedHandoff = request;
        activeRequestId = request.requestUuid();
        routingMode = request.routingMode();
        updateRoutingModeButton();
        purposeInput.setValue(request.purpose());
        providerInput.setValue(String.join(",", request.providerChain()));
        setOverrides(request.overrides());
        promptInput.setValue(lastText(request));
    }

    private static String displayRequestJson(ConsoleTestRequest request) {
        String compact = ConsoleTestCodec.toJson(request);
        String pretty = ConsoleTestCodec.pretty(compact);
        return pretty.length() <= ConsoleTestCodec.MAX_REQUEST_JSON_CHARS ? pretty : compact;
    }

    private void validateJsonDraft(String json) {
        try {
            ConsoleTestRequest request = ConsoleTestCodec.parseRequest(json, false);
            jsonDraftValid = true;
            jsonValidationText = string("test.json.valid", request.messages().size(), json.length());
            jsonValidationColor = 0x55FF55;
        } catch (IllegalArgumentException e) {
            jsonDraftValid = false;
            jsonValidationText = string("test.json.invalid", e.getMessage());
            jsonValidationColor = 0xFF5555;
        }
        updateSendButtonState();
    }

    private void toggleRoutingMode() {
        routingMode = routingMode == ConsoleTestRequest.RoutingMode.PURPOSE
                ? ConsoleTestRequest.RoutingMode.EXPLICIT_CHAIN : ConsoleTestRequest.RoutingMode.PURPOSE;
        updateRoutingModeButton();
    }

    private void updateRoutingModeButton() {
        routingModeButton.setMessage(text(routingMode == ConsoleTestRequest.RoutingMode.PURPOSE
                ? "test.mode.purpose" : "test.mode.explicit"));
    }

    private void enterSimpleMode() {
        if (restricted) return;
        loadedHandoff = null;
        activeRequestId = null;
        purposeInput.setValue("DEBUG_TEST");
        routingMode = ConsoleTestRequest.RoutingMode.PURPOSE;
        updateRoutingModeButton();
        setResponseText(null);
        updateSendButtonState();
    }

    private void sendTest() {
        if (waiting || (restricted && loadedHandoff == null)) return;
        try {
            ConsoleTestRequest request = jsonMode && !restricted
                    ? ConsoleTestCodec.parseRequest(requestJsonInput.getValue(), false)
                    : buildRequest();
            String requestJson = ConsoleTestCodec.toJson(request);
            if (jsonMode) requestJsonInput.setValue(displayRequestJson(request));
            activeRequestId = request.requestUuid();
            waiting = true;
            setResponseText(string("test.status.sending", request.purpose()));
            updateSendButtonState();
            LLMNetwork.CHANNEL.sendToServer(new C2SChatRequestPacket(activeRequestId, requestJson));
        } catch (IllegalArgumentException e) {
            setResponseText(string("common.error", e.getMessage()));
            updateSendButtonState();
        }
    }

    private ConsoleTestRequest buildRequest() {
        if (restricted && loadedHandoff != null) return loadedHandoff;
        UUID requestId = loadedHandoff == null ? UUID.randomUUID() : loadedHandoff.requestUuid();
        List<ConsoleTestRequest.MessageEntry> messages = loadedHandoff == null
                ? List.of(ConsoleTestRequest.MessageEntry.text("simple.prompt", "console", "user",
                        promptInput.getValue(), true, 1000))
                : replaceLastUserText(loadedHandoff.messages(), promptInput.getValue());
        Map<String, String> metadata = loadedHandoff == null
                ? new LinkedHashMap<>(Map.of("source", "console-simple"))
                : new LinkedHashMap<>(loadedHandoff.metadata());
        metadata.put("testUi", "llmjs-console");
        return new ConsoleTestRequest(ConsoleTestRequest.SCHEMA_VERSION, requestId.toString(), routingMode,
                purposeInput.getValue(), loadedHandoff == null ? "SIMPLE" : loadedHandoff.generationType(),
                parseProviderChain(providerInput.getValue()), messages, readOverrides(), metadata);
    }

    private static List<ConsoleTestRequest.MessageEntry> replaceLastUserText(
            List<ConsoleTestRequest.MessageEntry> messages, String text) {
        List<ConsoleTestRequest.MessageEntry> result = new ArrayList<>(messages);
        for (int index = result.size() - 1; index >= 0; index--) {
            ConsoleTestRequest.MessageEntry message = result.get(index);
            if (!"user".equals(message.role())) continue;
            List<ConsoleTestRequest.Part> parts = new ArrayList<>(message.parts());
            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                if ("text".equals(parts.get(partIndex).type())) {
                    parts.set(partIndex, ConsoleTestRequest.Part.text(text));
                    result.set(index, new ConsoleTestRequest.MessageEntry(message.entryId(), message.provenance(),
                            message.role(), parts, message.required(), message.priority()));
                    return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<String> parseProviderChain(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : value.split("[,>]")) {
            String provider = part.trim();
            if (!provider.isEmpty()) result.add(provider);
        }
        return List.copyOf(result);
    }

    private LlmRouteOptions readOverrides() {
        return new LlmRouteOptions(parseDouble(temperatureInput.getValue(), string("parameter.temperature")),
                parseInteger(maxOutputInput.getValue(), string("parameter.max_output")),
                parseInteger(timeoutInput.getValue(), string("parameter.timeout")),
                parseInteger(inputBudgetInput.getValue(), string("parameter.input_budget")),
                parseInteger(outputReserveInput.getValue(), string("parameter.output_reserve")));
    }

    private void setOverrides(LlmRouteOptions overrides) {
        temperatureInput.setValue(number(overrides.temperature()));
        maxOutputInput.setValue(number(overrides.maxOutputTokens()));
        timeoutInput.setValue(number(overrides.timeoutSeconds()));
        inputBudgetInput.setValue(number(overrides.inputBudgetTokens()));
        outputReserveInput.setValue(number(overrides.outputReserveTokens()));
    }

    private static String number(Number value) {
        return value == null ? "" : value.toString();
    }

    private static Double parseDouble(String text, String label) {
        if (text == null || text.isBlank()) return null;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(string("validation.number", label));
        }
    }

    private static Integer parseInteger(String text, String label) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(string("validation.integer", label));
        }
    }

    private static String lastText(ConsoleTestRequest request) {
        for (int messageIndex = request.messages().size() - 1; messageIndex >= 0; messageIndex--) {
            ConsoleTestRequest.MessageEntry message = request.messages().get(messageIndex);
            if (!"user".equals(message.role())) continue;
            for (ConsoleTestRequest.Part part : message.parts()) {
                if ("text".equals(part.type())) return part.text();
            }
        }
        return "";
    }

    public void onResponse(UUID requestId, String resultJson) {
        if (activeRequestId == null || !activeRequestId.equals(requestId)) return;
        waiting = false;
        updateSendButtonState();
        try {
            setResponseText(ConsoleTestCodec.pretty(resultJson));
        } catch (Exception e) {
            setResponseText(string("test.status.invalid_result", e.getMessage()));
        }
    }

    public void tick() {
        if (visible && jsonMode) requestJsonInput.tick();
    }

    private void sendVisionProbe() {
        List<String> chain = parseProviderChain(providerInput.getValue());
        if (chain.isEmpty()) {
            visionResultText = string("test.vision.provider_required");
            visionResultColor = 0xFF5555;
            return;
        }
        String provider = chain.get(0);
        visionResultText = string("test.vision.probing", provider);
        visionResultColor = 0xAAAAFF;
        LLMNetwork.CHANNEL.sendToServer(new C2SVisionProbePacket(provider));
    }

    public void onVisionProbeResult(String providerName, boolean supported, String error, long latencyMs) {
        List<String> chain = parseProviderChain(providerInput.getValue());
        if (chain.isEmpty() || !chain.get(0).equalsIgnoreCase(providerName)) return;
        if (supported) {
            visionResultText = string("test.vision.ok", latencyMs);
            visionResultColor = 0x55FF55;
        } else {
            visionResultText = string("test.vision.no",
                    error == null || error.isEmpty() ? string("test.vision.not_supported") : error);
            visionResultColor = 0xFF5555;
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible || mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) return false;
        if (jsonMode && requestJsonInput.visible && requestJsonInput.isMouseOver(mouseX, mouseY)) return false;
        if (isInResponseArea(mouseX, mouseY)) {
            int before = responseScroll;
            responseScroll = Math.max(0, Math.min(maxResponseScroll(),
                    responseScroll - (int) Math.signum(delta) * 3));
            if (responseScroll != before) return true;
        }
        if (pageScroll.scroll(delta, compactLayout() ? 20 : 24)) {
            layoutWidgets();
            return true;
        }
        return false;
    }

    private void copyResult() {
        if (responseText == null) return;
        int low = selectionLow();
        int high = selectionHigh();
        String copied = low >= 0 && high > low ? responseText.substring(low, high) : responseText;
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(copied);
            copyFlashUntilMs = System.currentTimeMillis() + 1_500L;
        } catch (Exception ignored) {
        }
    }

    private void saveToRouting() {
        try {
            String purpose = purposeInput.getValue().trim();
            if (purpose.isEmpty()) throw new IllegalArgumentException(string("validation.purpose_required"));
            PriorityRoutingConfig next = routingSnapshot.withPurposeOptions(purpose, readOverrides());
            List<String> chain = parseProviderChain(providerInput.getValue());
            if (routingMode == ConsoleTestRequest.RoutingMode.EXPLICIT_CHAIN && !chain.isEmpty()) {
                next = next.withPurpose(purpose, chain);
            }
            LLMNetwork.CHANNEL.sendToServer(new C2SUpdateRoutingPacket(RoutingConfigStore.toJsonString(next)));
            setResponseText(string("test.status.routing_saved", purpose));
        } catch (IllegalArgumentException e) {
            setResponseText(string("common.error", e.getMessage()));
        }
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;
        pageScroll.update(contentHeight, height);
        layoutWidgets();
        graphics.fill(x, y, x + width, y + height, 0x80000000);
        graphics.enableScissor(x, y, x + width, y + height);
        if (jsonMode) {
            graphics.drawString(font, text("test.json.editor"), x + 4, contentY(JSON_EDITOR_Y - 12),
                    0xFFFFFF, false);
            graphics.drawString(font, font.plainSubstrByWidth(jsonValidationText, Math.max(8, width - 18)),
                    x + 4, contentY(jsonStatusY()), jsonValidationColor, false);
        } else {
            graphics.drawString(font, text("test.purpose"), x + 4, contentY(9), 0xFFFFFF, false);
            graphics.drawString(font, text("test.chain"), x + 4, contentY(chainY() + 5), 0xFFFFFF, false);
            graphics.drawString(font, text(loadedHandoff == null ? "test.prompt" : "test.focus"),
                    x + 4, contentY(promptY() + 5), 0xFFFFFF, false);
            int parameterY = contentY(parameterLabelY());
            int parameterCell = Math.max(1, (width - 8) / 5);
            String[] labels = {"T", "Out", "Sec", "In", "Res"};
            for (int index = 0; index < labels.length; index++) {
                graphics.drawString(font, labels[index], x + 4 + parameterCell * index,
                        parameterY, 0xAAAAAA, false);
            }
            String effective = effectiveByPurpose.getOrDefault(purposeInput.getValue().trim(),
                    string("test.effective_unavailable"));
            String draftPrefix = loadedHandoff == null
                    ? "" : string("test.message_count", loadedHandoff.messages().size());
            String status = visionResultText != null ? visionResultText : draftPrefix + effective;
            graphics.drawString(font, font.plainSubstrByWidth(status, Math.max(8, width - 18)),
                    x + 4, contentY(statusY()), visionResultText == null ? 0x77AAFF : visionResultColor, false);
        }

        int responseY = contentY(resultTitleY());
        graphics.drawString(font, text("test.result"), x + 4, responseY, 0xAAAAAA, false);
        if (responseText == null) {
            graphics.disableScissor();
            pageScroll.render(graphics, mouseX, mouseY);
            if (mouseX >= x && mouseX < x + width && mouseY >= responseY && mouseY < responseY + 12) {
                graphics.renderTooltip(font, text("test.result.tip"), mouseX, mouseY);
            }
            return;
        }
        responseLineCount = responseSegments.size();
        int lineY = responseY + 12;
        for (int index = responseScroll; index < responseSegments.size(); index++) {
            if (lineY > contentY(contentHeight) - 10) break;
            ResultSegment segment = responseSegments.get(index);
            renderResultSelection(graphics, segment, x + 4, lineY);
            graphics.drawString(font, segment.text(), x + 4, lineY, 0xFFFFFF, false);
            lineY += 10;
        }
        if (System.currentTimeMillis() < copyFlashUntilMs) {
            graphics.drawString(font, text("common.copied"), x + width - 120, responseY, 0x55FF55, false);
        }
        graphics.disableScissor();
        pageScroll.render(graphics, mouseX, mouseY);
        if (mouseX >= x && mouseX < x + width && mouseY >= responseY && mouseY < responseY + 12) {
            graphics.renderTooltip(font, text("test.result.tip"), mouseX, mouseY);
        } else if (mouseX >= x && mouseX < x + width
                && !jsonMode && mouseY >= contentY(statusY()) - 3
                && mouseY < contentY(resultTitleY()) - 1) {
            graphics.renderTooltip(font, text("test.effective.tip"), mouseX, mouseY);
        }
    }

    private int resultTitleY() {
        return jsonMode ? jsonStatusY() + 14
                : compactLayout() ? COMPACT_RESULT_TITLE_Y : RESULT_TITLE_Y;
    }

    private int resultTextY() {
        return resultTitleY() + 12;
    }

    private int jsonEditorHeight() {
        return Math.max(72, Math.min(JSON_EDITOR_HEIGHT, height - JSON_EDITOR_Y - 8));
    }

    private int jsonStatusY() {
        return JSON_EDITOR_Y + jsonEditorHeight() + 18;
    }

    private void setResponseText(@Nullable String text) {
        responseText = text;
        responseScroll = 0;
        responseSelectionAnchor = -1;
        responseSelectionEnd = -1;
        responseSelectionActive = false;
        draggingResponseSelection = false;
        responseSegments = buildResultSegments(text);
        responseLineCount = responseSegments.size();
    }

    private List<ResultSegment> buildResultSegments(@Nullable String text) {
        if (text == null) return List.of();
        List<ResultSegment> segments = new ArrayList<>();
        String[] logicalLines = text.split("\\n", -1);
        int globalOffset = 0;
        int maxWidth = Math.max(8, width - 20);
        for (int lineIndex = 0; lineIndex < logicalLines.length; lineIndex++) {
            String line = logicalLines[lineIndex];
            if (line.isEmpty()) {
                segments.add(new ResultSegment("", globalOffset, globalOffset));
            } else {
                int localOffset = 0;
                while (localOffset < line.length()) {
                    String remaining = line.substring(localOffset);
                    String visible = font.plainSubstrByWidth(remaining, maxWidth);
                    int length = Math.max(1, visible.length());
                    int end = Math.min(line.length(), localOffset + length);
                    segments.add(new ResultSegment(line.substring(localOffset, end),
                            globalOffset + localOffset, globalOffset + end));
                    localOffset = end;
                }
            }
            globalOffset += line.length();
            if (lineIndex + 1 < logicalLines.length) globalOffset++;
        }
        return List.copyOf(segments);
    }

    private int selectionLow() {
        if (responseSelectionAnchor < 0 || responseSelectionEnd < 0) return -1;
        return Math.min(responseSelectionAnchor, responseSelectionEnd);
    }

    private int selectionHigh() {
        if (responseSelectionAnchor < 0 || responseSelectionEnd < 0) return -1;
        return Math.max(responseSelectionAnchor, responseSelectionEnd);
    }

    private void renderResultSelection(GuiGraphics graphics, ResultSegment segment, int textX, int drawY) {
        int low = selectionLow();
        int high = selectionHigh();
        if (low < 0 || high <= low) return;
        int overlapStart = Math.max(low, segment.startOffset());
        int overlapEnd = Math.min(high, segment.endOffset());
        if (overlapEnd <= overlapStart) return;
        int localStart = overlapStart - segment.startOffset();
        int localEnd = overlapEnd - segment.startOffset();
        int x1 = textX + font.width(segment.text().substring(0, localStart));
        int x2 = textX + font.width(segment.text().substring(0, localEnd));
        graphics.fill(x1, drawY - 1, x2, drawY + 9, 0x663388FF);
    }

    private boolean isInResponseArea(double mouseX, double mouseY) {
        return visible && responseText != null && mouseX >= x && mouseX <= x + width
                && mouseY >= Math.max(y, contentY(resultTextY()))
                && mouseY < Math.min(y + height, contentY(contentHeight));
    }

    private int resultCharOffsetAt(double mouseX, double mouseY, boolean clamp) {
        if (responseSegments.isEmpty()) return -1;
        int row = (int) ((mouseY - contentY(resultTextY())) / 10);
        int segmentIndex = responseScroll + row;
        if (clamp) segmentIndex = Math.max(0, Math.min(responseSegments.size() - 1, segmentIndex));
        if (segmentIndex < 0 || segmentIndex >= responseSegments.size()) return -1;
        ResultSegment segment = responseSegments.get(segmentIndex);
        double dx = mouseX - (x + 4);
        if (dx <= 0) return segment.startOffset();
        int local = 0;
        for (int index = 1; index <= segment.text().length(); index++) {
            if (font.width(segment.text().substring(0, index)) <= dx) local = index;
            else break;
        }
        return segment.startOffset() + local;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (visible && pageScroll.mouseClicked(mouseX, mouseY, button)) return true;
        if (!isInResponseArea(mouseX, mouseY)) return false;
        responseSelectionActive = true;
        if (button == 1) {
            copyResult();
            return true;
        }
        if (button != 0) return false;
        int offset = resultCharOffsetAt(mouseX, mouseY, false);
        if (offset < 0) return false;
        responseSelectionAnchor = offset;
        responseSelectionEnd = offset;
        draggingResponseSelection = true;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (pageScroll.mouseDragged(mouseX, mouseY, button)) {
            layoutWidgets();
            return true;
        }
        if (!draggingResponseSelection || button != 0 || responseText == null) return false;
        int offset = resultCharOffsetAt(mouseX, mouseY, true);
        if (offset >= 0) responseSelectionEnd = offset;
        int maxScroll = maxResponseScroll();
        if (mouseY < contentY(resultTextY()) + 6 && responseScroll > 0) responseScroll--;
        else if (mouseY > y + height - 8 && responseScroll < maxScroll) responseScroll++;
        return true;
    }

    public boolean mouseReleased(int button) {
        if (pageScroll.mouseReleased(button)) return true;
        if (button != 0 || !draggingResponseSelection) return false;
        draggingResponseSelection = false;
        return true;
    }

    private int maxResponseScroll() {
        int visibleLines = Math.max(1, (contentHeight - resultTextY() - 4) / 10);
        return Math.max(0, responseSegments.size() - visibleLines);
    }

    private void clampResponseScroll() {
        responseScroll = Math.max(0, Math.min(responseScroll, maxResponseScroll()));
    }

    public void deactivateResponseSelection() {
        responseSelectionActive = false;
        draggingResponseSelection = false;
    }

    public boolean handleCopyShortcut() {
        if (!visible || !responseSelectionActive || selectionHigh() <= selectionLow()) return false;
        copyResult();
        return true;
    }

    private void loadTemplatesFromConfig() {
        templates.clear();
        for (GlobalConfig.Template template : GlobalConfig.loadTemplates()) {
            templates.add(new PromptTemplate(template.name(), template.prompt()));
        }
        if (templates.isEmpty()) {
            templates.add(new PromptTemplate(string("test.template.connection"),
                    string("test.template.connection_prompt")));
            templates.add(new PromptTemplate(string("test.template.simple"), string("test.default_prompt")));
        }
    }

    private void cycleTemplate(int direction) {
        if (templates.isEmpty()) return;
        enterSimpleMode();
        currentTemplateIndex = currentTemplateIndex == -1
                ? (direction > 0 ? 0 : templates.size() - 1)
                : (currentTemplateIndex + direction + templates.size()) % templates.size();
        promptInput.setValue(templates.get(currentTemplateIndex).prompt());
    }

    private void saveCurrentAsTemplate() {
        String prompt = promptInput.getValue().strip();
        if (prompt.isEmpty()) return;
        for (int index = 0; index < templates.size(); index++) {
            if (templates.get(index).prompt().equals(prompt)) {
                currentTemplateIndex = index;
                return;
            }
        }
        String name = prompt.length() > 20 ? prompt.substring(0, 20) + "..." : prompt;
        templates.add(new PromptTemplate(name, prompt));
        currentTemplateIndex = templates.size() - 1;
        GlobalConfig.saveTemplates(templates.stream()
                .map(template -> new GlobalConfig.Template(template.name(), template.prompt())).toList());
    }
}
