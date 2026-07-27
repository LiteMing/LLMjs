package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.provider.ProviderManager;
import vibe.liteming.llmjs.test.ConsoleTestCodec;
import vibe.liteming.llmjs.test.ConsoleTestRequest;
import vibe.liteming.llmjs.security.ConsoleTestGrantService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

public class C2SChatRequestPacket {
    private final UUID requestId;
    private final String requestJson;

    public C2SChatRequestPacket(UUID requestId, String requestJson) {
        this.requestId = requestId;
        this.requestJson = requestJson == null ? "" : requestJson;
    }

    /** Backward-compatible simple Test constructor; execution still uses the purpose pipeline. */
    public C2SChatRequestPacket(UUID requestId, String prompt, String provider) {
        this(requestId, ConsoleTestCodec.toJson(ConsoleTestRequest.simple(requestId, prompt, provider)));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeUtf(requestJson, ConsoleTestCodec.MAX_REQUEST_JSON_CHARS);
    }

    public static C2SChatRequestPacket decode(FriendlyByteBuf buf) {
        return new C2SChatRequestPacket(buf.readUUID(),
                buf.readUtf(ConsoleTestCodec.MAX_REQUEST_JSON_CHARS));
    }

    public static void handle(C2SChatRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ConsoleTestRequest request;
            if (PermissionCheck.canAdminister(player)) {
                try {
                    request = ConsoleTestCodec.parseRequest(msg.requestJson, true);
                    if (!msg.requestId.equals(request.requestUuid())) {
                        throw new IllegalArgumentException("packet/request requestId mismatch");
                    }
                } catch (IllegalArgumentException e) {
                    LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new S2CChatResponsePacket(msg.requestId,
                                    ConsoleTestCodec.error(msg.requestId.toString(), e.getMessage())));
                    return;
                }
            } else {
                request = ConsoleTestGrantService.INSTANCE
                        .authorizedRequest(player.getUUID(), msg.requestId).orElse(null);
                if (request == null) {
                    LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new S2CChatResponsePacket(msg.requestId, false, "No Test permission", null));
                    return;
                }
            }
            ProviderManager.INSTANCE.executeConsoleTest(request).handle((resultJson, throwable) -> {
                        String payload = throwable == null ? resultJson : ConsoleTestCodec.error(
                                msg.requestId.toString(), "Test execution failed: " + rootMessage(throwable));
                        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                                new S2CChatResponsePacket(msg.requestId, payload));
                        return null;
                    });
        });
        ctx.get().setPacketHandled(true);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
