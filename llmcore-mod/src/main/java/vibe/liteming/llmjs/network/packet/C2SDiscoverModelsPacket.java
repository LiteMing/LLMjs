// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import vibe.liteming.llmcore.ProviderModelDiscovery;
import vibe.liteming.llmcore.ProviderSpec;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.provider.ProviderManager;

import java.util.function.Supplier;

public record C2SDiscoverModelsPacket(String requestId, String providerName, String format, String url, String key) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(requestId, 64);
        buf.writeUtf(providerName, 256);
        buf.writeUtf(format, 64);
        buf.writeUtf(url, 2048);
        buf.writeUtf(key, 4096);
    }

    public static C2SDiscoverModelsPacket decode(FriendlyByteBuf buf) {
        return new C2SDiscoverModelsPacket(buf.readUtf(64), buf.readUtf(256), buf.readUtf(64),
                buf.readUtf(2048), buf.readUtf(4096));
    }

    public static void handle(C2SDiscoverModelsPacket message, Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        if (sender == null || !PermissionCheck.canAdminister(sender)) {
            context.get().setPacketHandled(true);
            return;
        }
        String effectiveKey = message.key();
        if ("__KEEP__".equals(effectiveKey)) {
            ProviderSpec spec = ProviderManager.INSTANCE.getProviderSpec(message.providerName());
            effectiveKey = spec == null ? "" : spec.credentials().stream()
                    .filter(ProviderSpec.Credential::isConfigured).findFirst()
                    .map(ProviderSpec.Credential::key).orElse("");
        } else {
            int comma = effectiveKey.indexOf(',');
            if (comma >= 0) effectiveKey = effectiveKey.substring(0, comma).trim();
        }
        String serverKey = effectiveKey;
        ProviderModelDiscovery.discover(message.format(), message.url(), serverKey, LLMConfig.TIMEOUT.get())
                .thenAccept(result -> {
                    if (sender.hasDisconnected()) return;
                    LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                            new S2CModelDiscoveryPacket(message.requestId(), result.models(), result.error()));
                });
        context.get().setPacketHandled(true);
    }
}
