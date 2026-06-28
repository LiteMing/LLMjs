package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

public class S2CChatResponsePacket {
    private final UUID requestId;
    private final boolean success;
    private final @Nullable String error;
    private final @Nullable String content;

    public S2CChatResponsePacket(UUID requestId, boolean success,
                                  @Nullable String error, @Nullable String content) {
        this.requestId = requestId;
        this.success = success;
        this.error = error;
        this.content = content;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeBoolean(success);
        buf.writeUtf(error != null ? error : "", 32767);
        buf.writeUtf(content != null ? content : "", 32767);
    }

    public static S2CChatResponsePacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        boolean success = buf.readBoolean();
        String error = buf.readUtf(32767);
        String content = buf.readUtf(32767);
        return new S2CChatResponsePacket(id, success,
                error.isEmpty() ? null : error,
                content.isEmpty() ? null : content);
    }

    public static void handle(S2CChatResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            vibe.liteming.llmjs.client.ClientEventHandler.handleChatResponse(
                    msg.requestId, msg.success, msg.content, msg.error);
        });
        ctx.get().setPacketHandled(true);
    }

    public UUID getRequestId() { return requestId; }
    public boolean isSuccess() { return success; }
    public @Nullable String getContent() { return content; }
    public @Nullable String getError() { return error; }
}
