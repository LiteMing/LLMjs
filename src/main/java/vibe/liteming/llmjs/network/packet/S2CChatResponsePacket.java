package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import vibe.liteming.llmjs.test.ConsoleTestCodec;

import java.util.UUID;
import java.util.function.Supplier;

public class S2CChatResponsePacket {
    private final UUID requestId;
    private final String resultJson;

    public S2CChatResponsePacket(UUID requestId, String resultJson) {
        this.requestId = requestId;
        this.resultJson = resultJson == null ? ConsoleTestCodec.error(requestId.toString(), "Empty result") : resultJson;
    }

    /** Backward-compatible result constructor used by older server call sites. */
    public S2CChatResponsePacket(UUID requestId, boolean success, String error, String content) {
        this(requestId, legacyResult(requestId, success, error, content));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeUtf(resultJson, ConsoleTestCodec.MAX_RESULT_JSON_CHARS);
    }

    public static S2CChatResponsePacket decode(FriendlyByteBuf buf) {
        return new S2CChatResponsePacket(buf.readUUID(),
                buf.readUtf(ConsoleTestCodec.MAX_RESULT_JSON_CHARS));
    }

    public static void handle(S2CChatResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            vibe.liteming.llmjs.client.ClientEventHandler.handleChatResponse(msg.requestId, msg.resultJson);
        });
        ctx.get().setPacketHandled(true);
    }

    public UUID getRequestId() { return requestId; }
    public String getResultJson() { return resultJson; }

    private static String legacyResult(UUID requestId, boolean success, String error, String content) {
        if (!success) return ConsoleTestCodec.error(requestId.toString(), error);
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("requestId", requestId.toString());
        result.addProperty("success", true);
        result.addProperty("content", content == null ? "" : content);
        return result.toString();
    }
}
