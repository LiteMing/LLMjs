package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmjs.config.ProviderLoader;
import vibe.liteming.llmcore.LlmCostRate;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * Client -> Server: create or update a provider in llmcore.secret.
 * Only Console administrators can use this.
 */
public class C2SSetupProviderPacket {
    private final String requestId;
    private final String name;
    private final String url;
    private final String model;
    private final String key;
    private final String format;
    private final double inputMultiplier;
    private final double outputMultiplier;

    public C2SSetupProviderPacket(String name, String url, String model, String key, String format,
            double inputMultiplier, double outputMultiplier, String requestId) {
        this.requestId = requestId;
        this.name = name;
        this.url = url;
        this.model = model;
        this.key = key;
        this.format = format;
        this.inputMultiplier = inputMultiplier;
        this.outputMultiplier = outputMultiplier;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name, 256);
        buf.writeUtf(url, 2048);
        buf.writeUtf(model, 256);
        buf.writeUtf(key, 512);
        buf.writeUtf(format, 64);
        buf.writeDouble(inputMultiplier);
        buf.writeDouble(outputMultiplier);
        buf.writeUtf(requestId, 64);
    }

    public static C2SSetupProviderPacket decode(FriendlyByteBuf buf) {
        return new C2SSetupProviderPacket(
                buf.readUtf(256), buf.readUtf(2048), buf.readUtf(256),
                buf.readUtf(512), buf.readUtf(64), buf.readDouble(), buf.readDouble(), buf.readUtf(64));
    }

    public static void handle(C2SSetupProviderPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!PermissionCheck.canAdminister(player)) return;

            LlmCostRate rate;
            try {
                rate = new LlmCostRate(msg.inputMultiplier, msg.outputMultiplier);
            } catch (IllegalArgumentException invalid) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "gui.llmjs.setup.validation.cost_rate"));
                reply(player, msg, "save_failed");
                return;
            }
            var existing = ProviderManager.INSTANCE.getProvider(msg.name);
            if (existing != null && "raw".equals(existing.getType())) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "gui.llmjs.providers.raw.tip", rate.inputMultiplier(), rate.outputMultiplier()));
                reply(player, msg, "save_failed");
                return;
            }
            // __KEEP__ means don't change the key (edit mode)
            String effectiveKey = "__KEEP__".equals(msg.key) ? null : msg.key;
            boolean ok;
            if (effectiveKey == null) {
                // Update only non-key fields
                ok = ProviderLoader.updateWithoutKey(msg.name, msg.url, msg.model, msg.format, rate);
            } else {
                ok = ProviderLoader.setup(msg.name, msg.url, msg.model, effectiveKey, msg.format, rate);
            }
            reply(player, msg, !ok ? "save_failed"
                    : ProviderManager.INSTANCE.tryReload() ? "applied" : "reload_failed");
        });
        ctx.get().setPacketHandled(true);
    }
    private static void reply(ServerPlayer player, C2SSetupProviderPacket msg, String outcome) {
        var status = PermissionCheck.statusFor(player);
        var result = new com.google.gson.JsonObject();
        result.addProperty("requestId", msg.requestId);
        result.addProperty("name", msg.name);
        result.addProperty("outcome", outcome);
        status.add("providerSave", result);
        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CStatusResponsePacket(status.toString(), false));
    }
}
