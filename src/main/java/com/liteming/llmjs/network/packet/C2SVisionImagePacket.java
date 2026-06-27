package com.liteming.llmjs.network.packet;

import com.liteming.llmjs.vision.VisionRequestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

public class C2SVisionImagePacket {
    private static final int MAX_PACKET_IMAGE_BYTES = 2 * 1024 * 1024;

    private final UUID requestId;
    private final @Nullable String error;
    private final String mimeType;
    private final byte[] imageBytes;
    private final int width;
    private final int height;

    public C2SVisionImagePacket(UUID requestId, @Nullable String error, String mimeType,
                                byte[] imageBytes, int width, int height) {
        this.requestId = requestId;
        this.error = error;
        this.mimeType = mimeType != null ? mimeType : "image/jpeg";
        this.imageBytes = imageBytes != null ? imageBytes : new byte[0];
        this.width = width;
        this.height = height;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeUtf(error != null ? error : "", 512);
        buf.writeUtf(mimeType, 64);
        buf.writeInt(width);
        buf.writeInt(height);
        buf.writeByteArray(imageBytes);
    }

    public static C2SVisionImagePacket decode(FriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        String error = buf.readUtf(512);
        String mimeType = buf.readUtf(64);
        int width = buf.readInt();
        int height = buf.readInt();
        byte[] imageBytes = buf.readByteArray(MAX_PACKET_IMAGE_BYTES);
        return new C2SVisionImagePacket(requestId, error.isBlank() ? null : error, mimeType, imageBytes, width, height);
    }

    public static void handle(C2SVisionImagePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            VisionRequestManager.handleScreenshot(player, msg.requestId, msg.error,
                    msg.mimeType, msg.imageBytes, msg.width, msg.height);
        });
        ctx.get().setPacketHandled(true);
    }
}
