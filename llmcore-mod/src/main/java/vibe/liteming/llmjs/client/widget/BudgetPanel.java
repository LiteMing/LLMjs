package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

import static vibe.liteming.llmjs.client.ConsoleTexts.string;
import static vibe.liteming.llmjs.client.ConsoleTexts.text;

/** Read-only projection of the world budget ledger and runtime principal totals. */
@OnlyIn(Dist.CLIENT)
public final class BudgetPanel extends AbstractWidget {
    private record PlayerEntry(String name, String id, long total, long reserved, long limit,
            long requests, long average, boolean inherited, boolean unlimited,
            boolean disabled, boolean exhausted, boolean storageAvailable) {
    }

    private record PrincipalEntry(String kind, long total, long estimated, long requests,
            double estimatedRatio) {
    }

    private record DisplayLine(String text, int color) {
    }

    private static final int LINE_HEIGHT = 12;
    private final ConsoleScrollBar scroll = new ConsoleScrollBar();
    private final List<PlayerEntry> players = new ArrayList<>();
    private final List<PrincipalEntry> principals = new ArrayList<>();
    private PlayerEntry own;
    private long defaultLimit = -1L;
    private boolean canViewAll;
    private boolean canManage;
    private boolean confirmationRequired;

    public BudgetPanel(int x, int y, int width, int height, String statusJson) {
        super(x, y, width, height, text("tab.budget"));
        updateStatus(statusJson);
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        setWidth(Math.max(1, width));
        setHeight(Math.max(1, height));
        updateScrollRange(buildLines().size());
    }

    public void updateStatus(String statusJson) {
        players.clear();
        principals.clear();
        own = null;
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            canViewAll = bool(root, "canViewBudgets");
            canManage = bool(root, "canManageBudgets");
            confirmationRequired = bool(root, "budgetDefaultConfirmationRequired");
            defaultLimit = number(root, "personalBudgetDefault", -1L);
            if (root.has("personalBudget") && root.get("personalBudget").isJsonObject()) {
                own = player(root.getAsJsonObject("personalBudget"), "", "");
            }
            JsonArray playerArray = array(root, "personalBudgets");
            if (playerArray != null) {
                for (var element : playerArray) {
                    if (!element.isJsonObject()) continue;
                    JsonObject value = element.getAsJsonObject();
                    players.add(player(value, jsonString(value, "playerName"), jsonString(value, "playerId")));
                }
            }
            JsonArray principalArray = array(root, "principalUsage");
            if (principalArray != null) {
                for (var element : principalArray) {
                    if (!element.isJsonObject()) continue;
                    JsonObject value = element.getAsJsonObject();
                    principals.add(new PrincipalEntry(jsonString(value, "principalKind"),
                            number(value, "totalTokens", 0L), number(value, "estimatedTokens", 0L),
                            number(value, "requestCount", 0L), decimal(value, "estimatedRatio")));
                }
            }
        } catch (RuntimeException ignored) {
            canViewAll = false;
            canManage = false;
            confirmationRequired = false;
        }
        updateScrollRange(buildLines().size());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        List<DisplayLine> lines = buildLines();
        updateScrollRange(lines.size());
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x90000000);
        graphics.enableScissor(getX(), getY(), getX() + width, getY() + height);
        int first = Math.max(0, scroll.offset() / LINE_HEIGHT);
        for (int index = first; index < lines.size(); index++) {
            int drawY = getY() + 6 + index * LINE_HEIGHT - scroll.offset();
            if (drawY >= getY() + height) break;
            if (drawY + LINE_HEIGHT <= getY()) continue;
            DisplayLine line = lines.get(index);
            graphics.drawString(font, font.plainSubstrByWidth(line.text(), Math.max(8, width - 18)),
                    getX() + 8, drawY, line.color(), false);
        }
        graphics.disableScissor();
        scroll.render(graphics, mouseX, mouseY);
        if (isMouseOver(mouseX, mouseY)) {
            String tooltip = canManage ? "budget.admin.tip"
                    : canViewAll ? "budget.overview.tip" : "budget.self.tip";
            graphics.renderTooltip(font, text(tooltip),
                    mouseX, mouseY);
        }
    }

    private List<DisplayLine> buildLines() {
        List<DisplayLine> lines = new ArrayList<>();
        lines.add(new DisplayLine(string("budget.title"), 0xFFFFFF));
        lines.add(new DisplayLine(string("budget.default", limitText(defaultLimit)),
                confirmationRequired ? 0xFF5555 : 0xAAAAAA));
        if (confirmationRequired) {
            lines.add(new DisplayLine(string("budget.confirm.required"), 0xFFFF55));
        }
        if (canViewAll) {
            lines.add(new DisplayLine("", 0xFFFFFF));
            lines.add(new DisplayLine(string("budget.principals"), 0x55AAFF));
            if (principals.isEmpty()) {
                lines.add(new DisplayLine(string("budget.none"), 0x777777));
            } else {
                for (PrincipalEntry entry : principals) {
                    lines.add(new DisplayLine(string("budget.principal.row", entry.kind(), entry.total(),
                            entry.requests(), Math.round(entry.estimatedRatio() * 100.0D)), 0xDDDDDD));
                }
            }
            lines.add(new DisplayLine("", 0xFFFFFF));
            lines.add(new DisplayLine(string("budget.players", players.size()), 0x55AAFF));
            if (players.isEmpty()) {
                lines.add(new DisplayLine(string("budget.none"), 0x777777));
            } else {
                for (PlayerEntry entry : players) appendPlayer(lines, entry, true);
            }
        } else if (own != null) {
            lines.add(new DisplayLine("", 0xFFFFFF));
            lines.add(new DisplayLine(string("budget.self"), 0x55AAFF));
            appendPlayer(lines, own, false);
        }
        return lines;
    }

    private void appendPlayer(List<DisplayLine> lines, PlayerEntry entry, boolean includeIdentity) {
        if (includeIdentity) {
            String identity = entry.name().isBlank() ? entry.id() : entry.name() + "  " + entry.id();
            lines.add(new DisplayLine(identity, 0xFFFFFF));
        }
        String state = !entry.storageAvailable()
                ? string("budget.state.storage")
                : entry.disabled() ? string("budget.state.disabled")
                : entry.exhausted() ? string("budget.state.exhausted")
                : string("budget.state.available");
        int stateColor = !entry.storageAvailable() || entry.disabled() || entry.exhausted()
                ? 0xFF5555 : 0x55FF55;
        lines.add(new DisplayLine(string("budget.player.usage", entry.total(), entry.reserved(),
                limitText(entry.limit()), state), stateColor));
        lines.add(new DisplayLine(string("budget.player.detail", entry.requests(), entry.average(),
                entry.inherited() ? string("budget.source.default") : string("budget.source.override")),
                0x999999));
    }

    private void updateScrollRange(int lineCount) {
        scroll.setTrack(getX() + width - 7, getY() + 3, getY() + height - 3);
        scroll.update(Math.max(1, lineCount * LINE_HEIGHT + 12), Math.max(1, height));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return visible && isMouseOver(mouseX, mouseY) && scroll.scroll(delta, LINE_HEIGHT * 3);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return visible && isMouseOver(mouseX, mouseY) && scroll.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scroll.mouseDragged(mouseX, mouseY, button)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scroll.mouseReleased(button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private static PlayerEntry player(JsonObject value, String name, String id) {
        return new PlayerEntry(name, id, number(value, "totalTokens", 0L),
                number(value, "reservedTokens", 0L), number(value, "limitTokens", -1L),
                number(value, "requestCount", 0L), number(value, "averageTokens", 0L),
                bool(value, "limitInherited"), bool(value, "unlimited"), bool(value, "disabled"),
                bool(value, "exhausted"), bool(value, "storageAvailable"));
    }

    private static String limitText(long limit) {
        return limit == -1L ? string("budget.limit.unlimited")
                : limit == 0L ? string("budget.limit.disabled") : Long.toString(limit);
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null;
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).getAsBoolean();
    }

    private static long number(JsonObject object, String key, long fallback) {
        return object.has(key) ? object.get(key).getAsLong() : fallback;
    }

    private static double decimal(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsDouble() : 0.0D;
    }

    private static String jsonString(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
