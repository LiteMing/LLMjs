package vibe.liteming.llmjs.client.widget;

import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SSetupProviderPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;
import static vibe.liteming.llmjs.client.ConsoleTexts.tooltip;

@OnlyIn(Dist.CLIENT)
public class SetupPanel {
    private int x, y, width, height;
    private int contentHeight;
    private boolean stackedLayout;
    private final ConsoleScrollBar pageScroll = new ConsoleScrollBar();
    private final EditBox nameInput;
    private final EditBox formatInput;
    private final EditBox urlInput;
    private final EditBox modelInput;
    private final EditBox keyInput;
    private final Button saveButton;
    private final Button clearKeyButton;
    private @Nullable String statusMessage;
    private int statusColor = 0xFFFFFF;
    private boolean visible = true;
    private boolean editMode = false;
    private @Nullable String currentMaskedKey;

    private static final int LABEL_W = 70;
    private static final int ROW_H = 26;
    private static final int COMPACT_ROW_H = 20;
    private static final int NORMAL_MIN_CONTENT_HEIGHT = 224;
    private static final int COMPACT_MIN_CONTENT_HEIGHT = 150;
    private static final int STACKED_MIN_CONTENT_HEIGHT = 320;

    public SetupPanel(int x, int y, int width, int height, Font font) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        int inputX = x + LABEL_W + 10;
        int inputW = Math.min(width - LABEL_W - 28, 360);
        int row = y + 10;

        nameInput = new EditBox(font, inputX, row, inputW, 18, text("setup.name"));
        nameInput.setMaxLength(64);
        nameInput.setValue("");
        row += ROW_H;

        formatInput = new EditBox(font, inputX, row, inputW, 18, text("setup.format"));
        formatInput.setMaxLength(32);
        formatInput.setValue("openai");
        row += ROW_H;

        urlInput = new EditBox(font, inputX, row, inputW, 18, text("setup.url"));
        urlInput.setMaxLength(512);
        urlInput.setValue("https://api.openai.com/v1/chat/completions");
        row += ROW_H;

        modelInput = new EditBox(font, inputX, row, inputW, 18, text("setup.model"));
        modelInput.setMaxLength(128);
        modelInput.setValue("");
        row += ROW_H;

        keyInput = new EditBox(font, inputX, row, inputW, 18, text("setup.api_key"));
        keyInput.setMaxLength(256);
        keyInput.setValue("");
        // Hide typed characters for security; leave empty to keep existing key
        keyInput.setFormatter((value, pos) -> net.minecraft.util.FormattedCharSequence.forward(
                "*".repeat(Math.max(0, value.length())), net.minecraft.network.chat.Style.EMPTY));
        row += ROW_H + 6;

        saveButton = tooltip(Button.builder(text("setup.save"), b -> save())
                .pos(inputX, row).size(100, 20).build(), "setup.save.tip");
        clearKeyButton = tooltip(Button.builder(text("setup.clear_key"), b -> {
            keyInput.setValue("");
            statusMessage = string("setup.status.key_field_cleared");
            statusColor = 0xAAAAAA;
        }).pos(inputX + 108, row).size(120, 20).build(), "setup.clear_key.tip");
        tooltip(nameInput, "setup.name.tip");
        tooltip(formatInput, "setup.format.tip");
        tooltip(urlInput, "setup.url.tip");
        tooltip(modelInput, "setup.model.tip");
        tooltip(keyInput, "setup.api_key.tip");
        setBounds(x, y, width, height);
    }

    public List<net.minecraft.client.gui.components.AbstractWidget> getWidgets() {
        return List.of(nameInput, formatInput, urlInput, modelInput, keyInput, saveButton, clearKeyButton);
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.stackedLayout = this.width < 250;
        int minimum = stackedLayout ? STACKED_MIN_CONTENT_HEIGHT
                : compactLayout() ? COMPACT_MIN_CONTENT_HEIGHT : NORMAL_MIN_CONTENT_HEIGHT;
        this.contentHeight = Math.max(minimum, this.height);
        pageScroll.setTrack(this.x + this.width - 6, this.y + 2, this.y + this.height - 2);
        pageScroll.update(contentHeight, this.height);
        layoutWidgets();
    }

    private boolean compactLayout() {
        return !stackedLayout && height < 200;
    }

    private int rowH() {
        return compactLayout() ? COMPACT_ROW_H : ROW_H;
    }

    private int contentY(int relativeY) {
        return y + relativeY - pageScroll.offset();
    }

    private int fieldRelativeY(int index) {
        if (stackedLayout) return 18 + index * 34;
        return (compactLayout() ? 4 : 10) + index * rowH();
    }

    private int labelRelativeY(int index) {
        if (stackedLayout) return 8 + index * 34;
        return (compactLayout() ? 2 : 14) + index * rowH();
    }

    private int buttonRelativeY() {
        if (stackedLayout) return 184;
        if (compactLayout()) return fieldRelativeY(4) + 18 + 6;
        return 146;
    }

    private int statusRelativeY() {
        if (stackedLayout) return 238;
        if (compactLayout()) return buttonRelativeY() + 24;
        return 176;
    }

    private void layoutWidgets() {
        int inputX = stackedLayout ? x + 8 : x + LABEL_W + 10;
        int inputW = stackedLayout ? Math.max(40, width - 20)
                : Math.max(60, Math.min(width - LABEL_W - 28, 360));
        List<EditBox> inputs = List.of(nameInput, formatInput, urlInput, modelInput, keyInput);
        for (int index = 0; index < inputs.size(); index++) {
            place(inputs.get(index), inputX, contentY(fieldRelativeY(index)), inputW, 18);
        }
        place(saveButton, inputX, contentY(buttonRelativeY()), Math.min(100, inputW), 20);
        if (stackedLayout) {
            place(clearKeyButton, inputX, contentY(buttonRelativeY() + 24), Math.min(120, inputW), 20);
        } else {
            place(clearKeyButton, inputX + 108, contentY(buttonRelativeY()),
                    Math.max(40, Math.min(120, x + width - 8 - (inputX + 108))), 20);
        }
        for (var widget : getWidgets()) {
            boolean overlaps = widget.getY() < y + height && widget.getY() + widget.getHeight() > y;
            widget.visible = visible && overlaps;
            if (!widget.visible && widget.isFocused()) widget.setFocused(false);
        }
    }

    private static void place(net.minecraft.client.gui.components.AbstractWidget widget,
                              int x, int y, int width, int height) {
        widget.setX(x);
        widget.setY(y);
        widget.setWidth(Math.max(1, width));
        widget.setHeight(Math.max(1, height));
    }

    public void setVisible(boolean v) {
        this.visible = v;
        layoutWidgets();
    }

    public boolean isVisible() { return visible; }

    public void prefill(String name, String format, String url, String model, @Nullable String maskedKey) {
        editMode = true;
        currentMaskedKey = maskedKey;
        nameInput.setValue(name == null ? "" : name);
        if (format != null && !format.isEmpty() && !"-".equals(format)) formatInput.setValue(format);
        urlInput.setValue(url == null ? "" : url);
        modelInput.setValue(model == null ? "" : model);
        keyInput.setValue("");
        if (maskedKey != null && !maskedKey.isBlank() && !maskedKey.equals("***")) {
            statusMessage = string("setup.status.editing_masked", name, maskedKey);
            statusColor = 0x55FF55;
        } else if (maskedKey != null && !maskedKey.isBlank()) {
            statusMessage = string("setup.status.editing_hidden", name);
            statusColor = 0x55FF55;
        } else {
            statusMessage = string("setup.status.editing_no_key", name);
            statusColor = 0xFFFF55;
            editMode = false;
        }
    }

    private void save() {
        String name = nameInput.getValue().strip();
        String format = formatInput.getValue().strip();
        String url = urlInput.getValue().strip();
        String model = modelInput.getValue().strip();
        String key = keyInput.getValue().strip();

        if (name.isEmpty()) {
            statusMessage = string("setup.validation.name_required");
            statusColor = 0xFF5555;
            return;
        }
        if (url.isEmpty()) {
            statusMessage = string("setup.validation.url_required");
            statusColor = 0xFF5555;
            return;
        }
        if (key.isEmpty() && !editMode) {
            statusMessage = string("setup.validation.key_required");
            statusColor = 0xFF5555;
            return;
        }

        String sendKey = key.isEmpty() ? "__KEEP__" : key;
        LLMNetwork.CHANNEL.sendToServer(new C2SSetupProviderPacket(name, url, model, sendKey, format));
        statusMessage = string(key.isEmpty() ? "setup.status.saved_unchanged" : "setup.status.saved", name);
        statusColor = 0x55FF55;
        keyInput.setValue("");
        editMode = true;
        if (!key.isEmpty()) currentMaskedKey = "****";
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;
        var font = Minecraft.getInstance().font;
        pageScroll.update(contentHeight, height);
        layoutWidgets();
        graphics.fill(x, y, x + width, y + height, 0x90000000);
        graphics.enableScissor(x, y, x + width, y + height);

        int labelX = x + 8;
        String[] labelKeys = {"setup.name", "setup.format", "setup.url", "setup.model", "setup.api_key"};
        for (int index = 0; index < labelKeys.length; index++) {
            graphics.drawString(font, text(labelKeys[index]), labelX, contentY(labelRelativeY(index)), 0xFFFFFF, false);
        }
        int inputRight = formatInput.getX() + formatInput.getWidth();
        int hintX = inputRight + 8;
        int formatY = contentY(labelRelativeY(1));
        if (!stackedLayout && hintX + font.width("openai / claude / gemini") < x + width - 8) {
            graphics.drawString(font, "openai / claude / gemini", hintX, formatY, 0x666666, false);
        }
        if (currentMaskedKey != null && !currentMaskedKey.isBlank()) {
            Component keyText = text("setup.key_on_file", currentMaskedKey);
            int keyY = contentY(labelRelativeY(4));
            if (!stackedLayout && hintX + font.width(keyText) < x + width - 8) {
                graphics.drawString(font, keyText, hintX, keyY, 0x55AA55, false);
            }
        }

        if (statusMessage != null) {
            graphics.drawString(font, font.plainSubstrByWidth(statusMessage, Math.max(8, width - 18)),
                    labelX, contentY(statusRelativeY()), statusColor, false);
        }

        int helpY = contentY(contentHeight - 36);
        graphics.drawString(font, text("setup.secret_help"), labelX, helpY, 0x666666, false);
        graphics.drawString(font, text("setup.creaturechat_help"),
                labelX, helpY + 12, 0x666666, false);
        graphics.disableScissor();
        pageScroll.render(graphics, mouseX, mouseY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible || mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) return false;
        int step = stackedLayout ? 24 : rowH();
        if (!pageScroll.scroll(delta, step)) return false;
        layoutWidgets();
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return visible && pageScroll.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!pageScroll.mouseDragged(mouseX, mouseY, button)) return false;
        layoutWidgets();
        return true;
    }

    public boolean mouseReleased(int button) {
        return pageScroll.mouseReleased(button);
    }
}
