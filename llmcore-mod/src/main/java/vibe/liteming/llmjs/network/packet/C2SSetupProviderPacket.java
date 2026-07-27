package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmjs.config.ProviderLoader;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * Client -> Server: create or update a provider in llmcore.secret.
 * Only OP level 2+ can use this.
 */
public class C2SSetupProviderPacket {
    private final String name;
    private final String url;
    private final String model;
    private final String key;
    private final String format;

    public C2SSetupProviderPacket(String name, String url, String model, String key, String format) {
        this.name = name;
        this.url = url;
        this.model = model;
        this.key = key;
        this.format = format;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name, 256);
        buf.writeUtf(url, 2048);
        buf.writeUtf(model, 256);
        buf.writeUtf(key, 512);
        buf.writeUtf(format, 64);
    }

    public static C2SSetupProviderPacket decode(FriendlyByteBuf buf) {
        return new C2SSetupProviderPacket(
                buf.readUtf(256), buf.readUtf(2048), buf.readUtf(256),
                buf.readUtf(512), buf.readUtf(64));
    }

    public static void handle(C2SSetupProviderPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!player.hasPermissions(2)) return;

            // __KEEP__ means don't change the key (edit mode)
            String effectiveKey = "__KEEP__".equals(msg.key) ? null : msg.key;
            boolean ok;
            if (effectiveKey == null) {
                // Update only non-key fields
                ok = ProviderLoader.updateWithoutKey(msg.name, msg.url, msg.model, msg.format);
            } else {
                ok = ProviderLoader.setup(msg.name, msg.url, msg.model, effectiveKey, msg.format);
            }
            if (ok) {
                ProviderManager.INSTANCE.reload();
                String statusJson = ProviderManager.INSTANCE.getStatusJson().toString();
                LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CStatusResponsePacket(statusJson, false));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
