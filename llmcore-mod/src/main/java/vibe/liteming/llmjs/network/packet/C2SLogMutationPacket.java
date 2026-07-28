package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server-authoritative mutation of the in-memory Console log history. */
public final class C2SLogMutationPacket {
    public enum Action { CLEAR, REMOVE }

    private final Action action;
    private final String requestId;

    private C2SLogMutationPacket(Action action, String requestId) {
        this.action = action;
        this.requestId = requestId == null ? "" : requestId;
    }

    public static C2SLogMutationPacket clear() {
        return new C2SLogMutationPacket(Action.CLEAR, "");
    }

    public static C2SLogMutationPacket remove(String requestId) {
        return new C2SLogMutationPacket(Action.REMOVE, requestId);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(requestId, 128);
    }

    public static C2SLogMutationPacket decode(FriendlyByteBuf buf) {
        return new C2SLogMutationPacket(buf.readEnum(Action.class), buf.readUtf(128));
    }

    public static void handle(C2SLogMutationPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!PermissionCheck.canAdminister(player)) {
                player.sendSystemMessage(Component.translatable("gui.llmjs.log.mutation.denied"));
                if (PermissionCheck.canUse(player)) sendHistory(player);
                return;
            }

            if (msg.action == Action.CLEAR) {
                LLMLogger.INSTANCE.clear();
            } else if (!LLMLogger.INSTANCE.removeByRequestId(msg.requestId)) {
                player.sendSystemMessage(Component.translatable("gui.llmjs.log.mutation.not_found"));
            }
            sendHistory(player);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendHistory(ServerPlayer player) {
        List<String> history = new ArrayList<>();
        for (LLMLogger.LogEntry entry : LLMLogger.INSTANCE.getRecentEntries(200)) {
            history.add(entry.toJson().toString());
        }
        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CLogHistoryPacket(history));
    }
}
