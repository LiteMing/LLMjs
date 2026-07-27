package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmjs.vision.VisionRequestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class S2CScreenshotRequestPacket {
    private final UUID requestId;
    private final VisionRequestManager.CaptureOptions options;

    public S2CScreenshotRequestPacket(UUID requestId, VisionRequestManager.CaptureOptions options) {
        this.requestId = requestId;
        this.options = options;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeInt(options.maxWidth());
        buf.writeInt(options.maxBytes());
        buf.writeFloat(options.quality());
        buf.writeUtf(options.compression(), 32);
        buf.writeUtf(options.mimeType(), 64);
        buf.writeUtf(options.detail(), 32);
    }

    public static S2CScreenshotRequestPacket decode(FriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        VisionRequestManager.CaptureOptions options = new VisionRequestManager.CaptureOptions(
                buf.readInt(),
                buf.readInt(),
                buf.readFloat(),
                buf.readUtf(32),
                buf.readUtf(64),
                buf.readUtf(32)
        );
        return new S2CScreenshotRequestPacket(requestId, options);
    }

    public static void handle(S2CScreenshotRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> vibe.liteming.llmjs.client.ClientEventHandler.captureScreenshotForLLM(msg));
        ctx.get().setPacketHandled(true);
    }

    public UUID getRequestId() {
        return requestId;
    }

    public VisionRequestManager.CaptureOptions getOptions() {
        return options;
    }
}
