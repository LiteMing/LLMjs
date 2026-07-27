package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.RoutingConfigStore;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client -> Server: replace the global priority-routing table with a new snapshot
 * (default chain + per-purpose chains). Persists to {@code routing.json}, pushes the
 * new config into the running {@link vibe.liteming.llmcore.LlmOrchestrator}, and
 * broadcasts the refreshed status (which carries the new routing payload) to all
 * operators with console access.
 *
 * <p>Wire format: a single JSON string (RoutingConfigStore.toJsonString). Empty
 * purposes map + empty default => "clear routing" (orchestrator falls back to all
 * providers in declaration order).</p>
 */
public class C2SUpdateRoutingPacket {
    private final String routingJson;

    public C2SUpdateRoutingPacket(String routingJson) {
        this.routingJson = routingJson == null ? "{}" : routingJson;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(routingJson, 32767);
    }

    public static C2SUpdateRoutingPacket decode(FriendlyByteBuf buf) {
        return new C2SUpdateRoutingPacket(buf.readUtf(32767));
    }

    public static void handle(C2SUpdateRoutingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PermissionCheck.canAdminister(player)) return;
            PriorityRoutingConfig parsed;
            try {
                parsed = RoutingConfigStore.parse(msg.routingJson);
            } catch (IllegalArgumentException e) {
                player.sendSystemMessage(Component.literal("Routing update rejected: " + e.getMessage()));
                return;
            }
            boolean persisted = ProviderManager.INSTANCE.updateRouting(parsed);
            if (!persisted) {
                player.sendSystemMessage(Component.literal(
                        "Routing applied for this session but could not be persisted"));
            }
            // Broadcast refreshed status to every player who can see the console, so
            // all open Routing tabs reflect the new table.
            for (var p : player.getServer().getPlayerList().getPlayers()) {
                if (PermissionCheck.canUse(p)) {
                    String statusJson = PermissionCheck.statusFor(p).toString();
                    LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                            new S2CStatusResponsePacket(statusJson, false));
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /** Convenience builder used by the client UI to serialize its edited table. */
    public static String toJson(List<String> defaultChain, Map<String, List<String>> purposes) {
        return RoutingConfigStore.toJsonString(new PriorityRoutingConfig(purposes, defaultChain));
    }

    public static String toJson(List<String> defaultChain, Map<String, List<String>> purposes,
            Map<String, vibe.liteming.llmcore.LlmRouteOptions> options) {
        return RoutingConfigStore.toJsonString(new PriorityRoutingConfig(purposes, defaultChain, options));
    }
}
