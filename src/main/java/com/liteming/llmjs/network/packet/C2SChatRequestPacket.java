package com.liteming.llmjs.network.packet;

import com.liteming.llmjs.config.LLMConfig;
import com.liteming.llmjs.format.ApiFormat;
import com.liteming.llmjs.network.LLMNetwork;
import com.liteming.llmjs.network.PermissionCheck;
import com.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class C2SChatRequestPacket {
    private final UUID requestId;
    private final String prompt;
    private final String provider;

    public C2SChatRequestPacket(UUID requestId, String prompt, String provider) {
        this.requestId = requestId;
        this.prompt = prompt;
        this.provider = provider;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeUtf(prompt, 32767);
        buf.writeUtf(provider, 256);
    }

    public static C2SChatRequestPacket decode(FriendlyByteBuf buf) {
        return new C2SChatRequestPacket(buf.readUUID(), buf.readUtf(32767), buf.readUtf(256));
    }

    public static void handle(C2SChatRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!PermissionCheck.canUse(player)) {
                LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CChatResponsePacket(msg.requestId, false, "No permission", null));
                return;
            }
            if (!PermissionCheck.isPromptValid(msg.prompt)) {
                LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CChatResponsePacket(msg.requestId, false, "Prompt too long", null));
                return;
            }
            List<ApiFormat.Message> messages = List.of(new ApiFormat.Message("user", msg.prompt));
            int timeout = LLMConfig.TIMEOUT.get();
            ProviderManager.INSTANCE.sendWithFallback(messages, List.of(msg.provider), null, null, timeout)
                    .thenAccept(response -> {
                        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                                new S2CChatResponsePacket(msg.requestId, response.isSuccess(),
                                        response.isSuccess() ? null : response.getError(),
                                        response.getContent()));
                    });
        });
        ctx.get().setPacketHandled(true);
    }
}
