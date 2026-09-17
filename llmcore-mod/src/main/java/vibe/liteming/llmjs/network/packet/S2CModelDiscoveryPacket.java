// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record S2CModelDiscoveryPacket(String requestId, List<String> models, String error) {
    public S2CModelDiscoveryPacket {
        models = models == null ? List.of() : List.copyOf(models);
        error = error == null ? "" : error;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(requestId, 64);
        buf.writeVarInt(models.size());
        for (String model : models) buf.writeUtf(model, 256);
        buf.writeUtf(error, 1024);
    }

    public static S2CModelDiscoveryPacket decode(FriendlyByteBuf buf) {
        String requestId = buf.readUtf(64);
        int count = buf.readVarInt();
        if (count < 0 || count > ProviderModelDiscoveryLimit.MAX) {
            throw new IllegalArgumentException("model result count exceeds " + ProviderModelDiscoveryLimit.MAX);
        }
        List<String> models = new ArrayList<>(count);
        for (int i = 0; i < count; i++) models.add(buf.readUtf(256));
        return new S2CModelDiscoveryPacket(requestId, models, buf.readUtf(1024));
    }

    public static void handle(S2CModelDiscoveryPacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> vibe.liteming.llmjs.client.ClientEventHandler.onModelDiscovery(
                message.requestId(), message.models(), message.error()));
        context.get().setPacketHandled(true);
    }

    private static final class ProviderModelDiscoveryLimit { private static final int MAX = 512; }
}
