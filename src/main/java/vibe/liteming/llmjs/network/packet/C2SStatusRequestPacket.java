package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class C2SStatusRequestPacket {
    public C2SStatusRequestPacket() {}

    public void encode(FriendlyByteBuf buf) {}

    public static C2SStatusRequestPacket decode(FriendlyByteBuf buf) {
        return new C2SStatusRequestPacket();
    }

    public static void handle(C2SStatusRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PermissionCheck.canUse(player)) return;
            String statusJson = ProviderManager.INSTANCE.getStatusJson().toString();
            LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CStatusResponsePacket(statusJson, false));
        });
        ctx.get().setPacketHandled(true);
    }
}
