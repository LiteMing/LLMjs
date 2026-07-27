package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * Client -> Server: ask the server to send a tiny 1x1 PNG to a specific provider
 * and report whether that provider's model accepts image input. Console administrators only.
 * Reply is a {@link S2CVisionProbeResultPacket} addressed to the requesting player.
 */
public class C2SVisionProbePacket {
    private final String providerName;

    public C2SVisionProbePacket(String providerName) {
        this.providerName = providerName == null ? "" : providerName;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(providerName, 256);
    }

    public static C2SVisionProbePacket decode(FriendlyByteBuf buf) {
        return new C2SVisionProbePacket(buf.readUtf(256));
    }

    public static void handle(C2SVisionProbePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PermissionCheck.canAdminister(player)) return;
            ProviderManager.INSTANCE.testVision(msg.providerName).thenAccept(result -> {
                LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CVisionProbeResultPacket(msg.providerName,
                                result.supported(), result.error() == null ? "" : result.error(),
                                result.latencyMs()));
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
