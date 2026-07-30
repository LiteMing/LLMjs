package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.RoutingConfigStore;
import vibe.liteming.llmcore.CapabilityPolicyStore;
import vibe.liteming.llmcore.LlmCapabilityPolicy;
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
 * Client -> Server: replace the global priority-routing table and, when supplied,
 * the separately versioned capability-policy snapshot. Both payloads are validated
 * before either is applied, then persisted to their respective files and pushed
 * into the running {@link vibe.liteming.llmcore.LlmOrchestrator}.
 *
 * <p>Wire format for protocol 7: routing JSON, a capability-policy-present flag,
 * then capability-policy JSON when present. The one-argument constructor leaves
 * the current capability policy unchanged.</p>
 */
public class C2SUpdateRoutingPacket {
    private final String routingJson;
    private final String capabilityPolicyJson;

    public C2SUpdateRoutingPacket(String routingJson) {
        this(routingJson, null);
    }

    public C2SUpdateRoutingPacket(String routingJson, String capabilityPolicyJson) {
        this.routingJson = routingJson == null ? "{}" : routingJson;
        this.capabilityPolicyJson = capabilityPolicyJson;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(routingJson, 32767);
        buf.writeBoolean(capabilityPolicyJson != null);
        if (capabilityPolicyJson != null) buf.writeUtf(capabilityPolicyJson, 32767);
    }

    public static C2SUpdateRoutingPacket decode(FriendlyByteBuf buf) {
        String routingJson = buf.readUtf(32767);
        String capabilityPolicyJson = buf.readBoolean() ? buf.readUtf(32767) : null;
        return new C2SUpdateRoutingPacket(routingJson, capabilityPolicyJson);
    }

    public static void handle(C2SUpdateRoutingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PermissionCheck.canAdminister(player)) return;
            PriorityRoutingConfig parsed;
            LlmCapabilityPolicy parsedPolicy = null;
            try {
                parsed = RoutingConfigStore.parse(msg.routingJson);
                if (msg.capabilityPolicyJson != null) {
                    parsedPolicy = CapabilityPolicyStore.parse(msg.capabilityPolicyJson);
                }
            } catch (IllegalArgumentException e) {
                player.sendSystemMessage(Component.literal("Console policy update rejected: " + e.getMessage()));
                return;
            }
            boolean persisted = parsedPolicy == null
                    ? ProviderManager.INSTANCE.updateRouting(parsed)
                    : ProviderManager.INSTANCE.updateRoutingAndCapabilities(parsed, parsedPolicy);
            if (!persisted) {
                player.sendSystemMessage(Component.literal(
                        "Console policy applied for this session but could not be fully persisted"));
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
