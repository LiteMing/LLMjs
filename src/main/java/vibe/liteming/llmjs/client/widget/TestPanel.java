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
    private final int x, y, width, height;
    private final Font font;
    private final EditBox purposeInput;
    private final EditBox providerInput;
    private final EditBox promptInput;
    private final EditBox temperatureInput;
    private final EditBox maxOutputInput;
    private final EditBox timeoutInput;
    private final EditBox inputBudgetInput;
    private final EditBox outputReserveInput;
    private final Button routingModeButton;
    private final Button sendButton;
    private final Button visionProbeButton;
    private final Button simpleButton;
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

        int parameterY = y + 85;
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
                .pos(x + 4, y + 106).size(20, 16).build(), "test.template.previous.tip");
        nextTemplateBtn = tooltip(Button.builder(Component.literal(">"), button -> cycleTemplate(1))
                .pos(x + 26, y + 106).size(20, 16).build(), "test.template.next.tip");
        saveTemplateBtn = tooltip(Button.builder(text("test.template.save"), button -> saveCurrentAsTemplate())
                .pos(x + 50, y + 106).size(62, 16).build(), "test.template.save.tip");
        copyButton = tooltip(Button.builder(text("test.copy_result"), button -> copyResult())
                .pos(x + 116, y + 106).size(90, 16).build(), "test.copy_result.tip");
        saveRouteButton = tooltip(Button.builder(text("test.save_route"), button -> saveToRouting())
                .pos(x + 210, y + 106).size(80, 16).build(), "test.save_route.tip");
        tooltip(purposeInput, "test.purpose.tip");
        tooltip(providerInput, "test.provider_chain.tip");
        tooltip(promptInput, "test.prompt.tip");
        loadTemplatesFromConfig();
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
                prevTemplateBtn, nextTemplateBtn, saveTemplateBtn, copyButton, saveRouteButton);
    }

    public void setVisible(boolean value) {
        visible = value;
        for (AbstractWidget widget : getWidgets()) widget.visible = value;
    }

    public boolean isVisible() {
        return visible;
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
            loadedHandoff = request;
            activeRequestId = request.requestUuid();
            waiting = false;
            routingMode = request.routingMode();
            updateRoutingModeButton();
            purposeInput.setValue(request.purpose());
            providerInput.setValue(String.join(",", request.providerChain()));
            setOverrides(request.overrides());
            promptInput.setValue(lastText(request));
            setResponseText(string("test.status.draft", request.requestId(), request.messages().size(),
                    request.generationType()));
        } catch (IllegalArgumentException e) {
            setResponseText(string("common.error", e.getMessage()));
        }
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
        loadedHandoff = null;
        activeRequestId = null;
        purposeInput.setValue("DEBUG_TEST");
        routingMode = ConsoleTestRequest.RoutingMode.PURPOSE;
        updateRoutingModeButton();
        setResponseText(null);
    }

    private void sendTest() {
        if (waiting) return;
        try {
            ConsoleTestRequest request = buildRequest();
            String requestJson = ConsoleTestCodec.toJson(request);
            activeRequestId = request.requestUuid();
            waiting = true;
            setResponseText(string("test.status.sending", request.purpose()));
            LLMNetwork.CHANNEL.sendToServer(new C2SChatRequestPacket(activeRequestId, requestJson));
        } catch (IllegalArgumentException e) {
            setResponseText(string("common.error", e.getMessage()));
        }
    }

    private ConsoleTestRequest buildRequest() {
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
        try {
            setResponseText(ConsoleTestCodec.pretty(resultJson));
        } catch (Exception e) {
            setResponseText(string("test.status.invalid_result", e.getMessage()));
        }
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
        if (!visible || mouseX < x || mouseX > x + width || mouseY < y + 140 || mouseY > y + height) return false;
        int visibleLines = Math.max(1, (height - 154) / 10);
        int maxScroll = Math.max(0, responseLineCount - visibleLines);
        responseScroll = Math.max(0, Math.min(maxScroll, responseScroll - (int) Math.signum(delta) * 3));
        return true;
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
        graphics.fill(x, y, x + width, y + height, 0x80000000);
        graphics.drawString(font, text("test.purpose"), x + 4, y + 9, 0xFFFFFF, false);
        graphics.drawString(font, text("test.chain"), x + 4, y + 33, 0xFFFFFF, false);
        graphics.drawString(font, text(loadedHandoff == null ? "test.prompt" : "test.focus"),
                x + 4, y + 57, 0xFFFFFF, false);
        int parameterY = y + 76;
        int parameterCell = Math.max(1, (width - 8) / 5);
        String[] labels = {"T", "Out", "Sec", "In", "Res"};
        for (int index = 0; index < labels.length; index++) {
            graphics.drawString(font, labels[index], x + 4 + parameterCell * index, parameterY, 0xAAAAAA, false);
        }
        String effective = effectiveByPurpose.getOrDefault(purposeInput.getValue().trim(),
                string("test.effective_unavailable"));
        String draftPrefix = loadedHandoff == null ? "" : string("test.message_count", loadedHandoff.messages().size());
        String status = visionResultText != null ? visionResultText : draftPrefix + effective;
        graphics.drawString(font, font.plainSubstrByWidth(status, width - 12),
                x + 4, y + 127, visionResultText == null ? 0x77AAFF : visionResultColor, false);

        int responseY = y + 140;
        graphics.drawString(font, text("test.result"), x + 4, responseY, 0xAAAAAA, false);
        if (responseText == null) {
            if (mouseX >= x && mouseX < x + width && mouseY >= responseY && mouseY < responseY + 12) {
                graphics.renderTooltip(font, text("test.result.tip"), mouseX, mouseY);
            }
            return;
        }
        responseLineCount = responseSegments.size();
        int lineY = responseY + 12;
        for (int index = responseScroll; index < responseSegments.size(); index++) {
            if (lineY > y + height - 10) break;
            ResultSegment segment = responseSegments.get(index);
            renderResultSelection(graphics, segment, x + 4, lineY);
            graphics.drawString(font, segment.text(), x + 4, lineY, 0xFFFFFF, false);
            lineY += 10;
        }
        if (System.currentTimeMillis() < copyFlashUntilMs) {
            graphics.drawString(font, text("common.copied"), x + width - 120, responseY, 0x55FF55, false);
        }
        if (mouseX >= x && mouseX < x + width && mouseY >= responseY && mouseY < responseY + 12) {
            graphics.renderTooltip(font, text("test.result.tip"), mouseX, mouseY);
        } else if (mouseX >= x && mouseX < x + width && mouseY >= y + 124 && mouseY < y + 139) {
            graphics.renderTooltip(font, text("test.effective.tip"), mouseX, mouseY);
        }
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
        int maxWidth = Math.max(8, width - 12);
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
                && mouseY >= y + 152 && mouseY < y + height;
    }

    private int resultCharOffsetAt(double mouseX, double mouseY, boolean clamp) {
        if (responseSegments.isEmpty()) return -1;
        int row = (int) ((mouseY - (y + 152)) / 10);
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
        if (!draggingResponseSelection || button != 0 || responseText == null) return false;
        int offset = resultCharOffsetAt(mouseX, mouseY, true);
        if (offset >= 0) responseSelectionEnd = offset;
        int visibleLines = Math.max(1, (height - 154) / 10);
        int maxScroll = Math.max(0, responseSegments.size() - visibleLines);
        if (mouseY < y + 158 && responseScroll > 0) responseScroll--;
        else if (mouseY > y + height - 8 && responseScroll < maxScroll) responseScroll++;
        return true;
    }

    public boolean mouseReleased(int button) {
        if (button != 0 || !draggingResponseSelection) return false;
        draggingResponseSelection = false;
        return true;
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
