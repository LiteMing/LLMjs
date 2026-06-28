package vibe.liteming.llmjs.pipeline;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.ChatFormatting;

public class OutputTarget {

    public static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    public static void actionbar(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    public static void tellraw(ServerPlayer player, String message, String color, boolean bold) {
        Component component = Component.literal(message).withStyle(style -> {
            if (color != null) {
                ChatFormatting fmt = ChatFormatting.getByName(color);
                if (fmt != null) style = style.withColor(fmt);
            }
            if (bold) style = style.withBold(true);
            return style;
        });
        player.sendSystemMessage(component);
    }

    public static void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(Component.literal(message)));
    }

    public static void broadcastActionbar(MinecraftServer server, String message) {
        server.getPlayerList().getPlayers().forEach(p -> p.displayClientMessage(Component.literal(message), true));
    }
}
