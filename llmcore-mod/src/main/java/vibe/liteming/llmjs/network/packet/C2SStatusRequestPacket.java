package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.log.LLMLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;

public class C2SStatusRequestPacket {
    private final boolean openConsole;

    public C2SStatusRequestPacket() {
        this(false);
    }

    public C2SStatusRequestPacket(boolean openConsole) {
        this.openConsole = openConsole;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(openConsole);
    }

    public static C2SStatusRequestPacket decode(FriendlyByteBuf buf) {
        return new C2SStatusRequestPacket(buf.readBoolean());
    }

    public static void handle(C2SStatusRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || (!PermissionCheck.canUse(player) && !PermissionCheck.canTest(player))) return;
            String statusJson = PermissionCheck.statusFor(player).toString();
            LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CStatusResponsePacket(statusJson, msg.openConsole));
            if (msg.openConsole && PermissionCheck.canUse(player)) {
                List<String> history = new ArrayList<>();
                for (LLMLogger.LogEntry entry : LLMLogger.INSTANCE.getRecentEntries(200)) {
                    history.add(entry.toJson().toString());
                }
                LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CLogHistoryPacket(history));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
