package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import vibe.liteming.llmjs.config.ProviderLoader;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.provider.ProviderManager;

import java.util.function.Supplier;

/** Client -> Server: delete a provider from providers.json + llmcore.secret. */
public class C2SDeleteProviderPacket {
    private final String name;

    public C2SDeleteProviderPacket(String name) {
        this.name = name;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name, 256);
    }

    public static C2SDeleteProviderPacket decode(FriendlyByteBuf buf) {
        return new C2SDeleteProviderPacket(buf.readUtf(256));
    }

    public static void handle(C2SDeleteProviderPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !player.hasPermissions(2)) return;
            if (ProviderLoader.deleteProvider(msg.name)) {
                ProviderManager.INSTANCE.reload();
                String statusJson = ProviderManager.INSTANCE.getStatusJson().toString();
                LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CStatusResponsePacket(statusJson, false));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
