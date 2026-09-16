// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: AGPL-3.0-or-later
package vibe.liteming.llmjs.network.packet;

import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import vibe.liteming.llmcore.LlmRoute;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.provider.ProviderManager;

import java.util.function.Supplier;

/** Player-owned route preference. The sender UUID is the only identity accepted. */
public final class C2SUpdatePersonalRoutingPacket {
    private static final int MAX_PURPOSE = 64;
    private static final int MAX_ROUTE = 8192;
    private final String purpose;
    private final String routeExpression;

    public C2SUpdatePersonalRoutingPacket(String purpose, String routeExpression) {
        this.purpose = purpose == null ? "" : purpose;
        this.routeExpression = routeExpression == null ? "" : routeExpression;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(purpose, MAX_PURPOSE);
        buf.writeUtf(routeExpression, MAX_ROUTE);
    }

    public static C2SUpdatePersonalRoutingPacket decode(FriendlyByteBuf buf) {
        return new C2SUpdatePersonalRoutingPacket(buf.readUtf(MAX_PURPOSE), buf.readUtf(MAX_ROUTE));
    }

    public static void handle(C2SUpdatePersonalRoutingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PermissionCheck.canConfigurePersonalRoute(player)) return;
            String purpose = msg.purpose.trim();
            try {
                if (purpose.isEmpty()) throw new IllegalArgumentException("purpose is required");
                LlmRoute route = LlmRoute.parse(msg.routeExpression.trim());
                boolean persisted = ProviderManager.INSTANCE.setPlayerRoute(player.getUUID(), purpose, route);
                send(player, persisted ? null : "route applied for this session but persistence failed");
            } catch (IllegalArgumentException error) {
                send(player, error.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void send(ServerPlayer player, String error) {
        JsonObject status = PermissionCheck.statusFor(player);
        if (error != null && !error.isBlank()) {
            status.addProperty("personalRoutingError", error);
            player.sendSystemMessage(Component.literal("Personal route rejected: " + error));
        } else status.addProperty("personalRoutingSaved", true);
        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CStatusResponsePacket(status.toString(), false));
    }
}
