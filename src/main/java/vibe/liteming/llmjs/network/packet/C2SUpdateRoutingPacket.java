package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.RoutingConfigStore;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            if (player == null || !player.hasPermissions(2)) return;
            PriorityRoutingConfig parsed = parseLoose(msg.routingJson);
            ProviderManager.INSTANCE.updateRouting(parsed);
            // Broadcast refreshed status to every player who can see the console, so
            // all open Routing tabs reflect the new table.
            String statusJson = ProviderManager.INSTANCE.getStatusJson().toString();
            for (var p : player.getServer().getPlayerList().getPlayers()) {
                if (vibe.liteming.llmjs.network.PermissionCheck.canUse(p)) {
                    LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                            new S2CStatusResponsePacket(statusJson, false));
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Tolerant parse used on the wire (clients may send a partial JSON with only
     * the fields they care about). Falls back to an empty config on any error.
     */
    private static PriorityRoutingConfig parseLoose(String json) {
        if (json == null || json.isBlank()) return PriorityRoutingConfig.empty();
        try {
            var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            List<String> defaultChain = new ArrayList<>();
            if (root.has("default") && root.get("default").isJsonArray()) {
                for (var el : root.getAsJsonArray("default")) {
                    if (el.isJsonPrimitive()) defaultChain.add(el.getAsString());
                }
            }
            Map<String, List<String>> purposes = new LinkedHashMap<>();
            if (root.has("purposes") && root.get("purposes").isJsonObject()) {
                var obj = root.getAsJsonObject("purposes");
                for (var entry : obj.entrySet()) {
                    if (entry.getValue().isJsonArray()) {
                        List<String> chain = new ArrayList<>();
                        for (var el : entry.getValue().getAsJsonArray()) {
                            if (el.isJsonPrimitive()) chain.add(el.getAsString());
                        }
                        if (!chain.isEmpty()) purposes.put(entry.getKey(), chain);
                    }
                }
            }
            return new PriorityRoutingConfig(purposes, defaultChain);
        } catch (Exception e) {
            return PriorityRoutingConfig.empty();
        }
    }

    /** Convenience builder used by the client UI to serialize its edited table. */
    public static String toJson(List<String> defaultChain, Map<String, List<String>> purposes) {
        return RoutingConfigStore.toJsonString(new PriorityRoutingConfig(purposes, defaultChain));
    }
}
