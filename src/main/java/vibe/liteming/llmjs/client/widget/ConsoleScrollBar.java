package vibe.liteming.llmjs.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Small page/list scrollbar shared by the Console panels. Offsets are pixels,
 * so the thumb remains proportional when GUI scale or window size changes.
 */
@OnlyIn(Dist.CLIENT)
final class ConsoleScrollBar {
    private static final int TRACK_WIDTH = 5;
    private static final int MIN_THUMB_HEIGHT = 16;

    private int x;
    private int top;
    private int bottom;
    private int viewportHeight;
    private int contentHeight;
    private int offset;
    private boolean dragging;
    private int dragGrabY;

    void setTrack(int x, int top, int bottom) {
        this.x = x;
        this.top = top;
        this.bottom = Math.max(top + 1, bottom);
    }

    void update(int contentHeight, int viewportHeight) {
        this.contentHeight = Math.max(0, contentHeight);
        this.viewportHeight = Math.max(1, viewportHeight);
        setOffset(offset);
    }

    int offset() {
        return offset;
    }

    int maxOffset() {
        return maxOffset(contentHeight, viewportHeight);
    }

    boolean isScrollable() {
        return maxOffset() > 0;
    }

    void setOffset(int value) {
        offset = clamp(value, 0, maxOffset());
    }

    boolean scroll(double delta, int pixelStep) {
        if (!isScrollable() || delta == 0.0D) return false;
        int before = offset;
        setOffset(offset - (int) Math.signum(delta) * Math.max(1, pixelStep));
        return offset != before;
    }

    void ensureVisible(int contentTop, int contentBottom) {
        if (contentTop < offset) {
            setOffset(contentTop);
        } else if (contentBottom > offset + viewportHeight) {
            setOffset(contentBottom - viewportHeight);
        }
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isScrollable() || !isMouseOver(mouseX, mouseY)) return false;
        int thumbY = thumbY();
        int thumbHeight = thumbHeight();
        if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
            dragGrabY = (int) mouseY - thumbY;
        } else {
            dragGrabY = thumbHeight / 2;
            moveThumbTo((int) mouseY - dragGrabY);
        }
        dragging = true;
        return true;
    }

    boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!dragging || button != 0) return false;
        moveThumbTo((int) mouseY - dragGrabY);
        return true;
    }

    boolean mouseReleased(int button) {
        if (button != 0 || !dragging) return false;
        dragging = false;
        return true;
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isScrollable()) return;
        boolean hovered = isMouseOver(mouseX, mouseY) || dragging;
        graphics.fill(x, top, x + TRACK_WIDTH, bottom, hovered ? 0x77404040 : 0x55202020);
        int thumbY = thumbY();
        graphics.fill(x, thumbY, x + TRACK_WIDTH, thumbY + thumbHeight(),
                hovered ? 0xFFE4E8EE : 0xFFB7BDC7);
    }

    private boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= x - 1 && mouseX < x + TRACK_WIDTH + 1
                && mouseY >= top && mouseY < bottom;
    }

    private void moveThumbTo(int targetY) {
        int travel = trackHeight() - thumbHeight();
        if (travel <= 0) {
            setOffset(0);
            return;
        }
        int relative = clamp(targetY - top, 0, travel);
        setOffset((int) Math.round((double) relative * maxOffset() / travel));
    }

    private int thumbY() {
        int travel = trackHeight() - thumbHeight();
        if (travel <= 0 || maxOffset() <= 0) return top;
        return top + (int) Math.round((double) travel * offset / maxOffset());
    }

    private int thumbHeight() {
        return thumbHeight(trackHeight(), viewportHeight, contentHeight);
    }

    private int trackHeight() {
        return Math.max(1, bottom - top);
    }

    static int maxOffset(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - Math.max(1, viewportHeight));
    }

    static int thumbHeight(int trackHeight, int viewportHeight, int contentHeight) {
        int track = Math.max(1, trackHeight);
        if (contentHeight <= viewportHeight || contentHeight <= 0) return track;
        int proportional = (int) ((long) track * Math.max(1, viewportHeight) / contentHeight);
        return clamp(proportional, Math.min(MIN_THUMB_HEIGHT, track), track);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
