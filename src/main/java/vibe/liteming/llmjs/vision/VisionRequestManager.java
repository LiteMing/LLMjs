package vibe.liteming.llmjs.vision;

import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.S2CScreenshotRequestPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class VisionRequestManager {
    private static final long REQUEST_TTL_MS = 30_000L;
    private static final ConcurrentMap<UUID, PendingScreenshotRequest> PENDING = new ConcurrentHashMap<>();

    private VisionRequestManager() {
    }

    public record CaptureOptions(int maxWidth, int maxBytes, float quality, String compression,
                                 String mimeType, String detail) {
        public CaptureOptions {
            maxWidth = clamp(maxWidth, 128, LLMConfig.MAX_IMAGE_WIDTH.get());
            maxBytes = clamp(maxBytes, 32768, LLMConfig.MAX_IMAGE_BYTES.get());
            quality = Math.max(0.15f, Math.min(0.95f, quality));
            compression = normalize(compression, "auto").toLowerCase(Locale.ROOT);
            mimeType = normalize(mimeType, "image/jpeg");
            detail = normalize(detail, "low");
        }
    }

    private record PendingScreenshotRequest(UUID playerId, CaptureOptions options, long createdAt,
                                            Consumer<VisionImage> callback,
                                            @Nullable Consumer<String> errorCallback) {
    }

    public static UUID requestScreenshot(ServerPlayer player, CaptureOptions options,
                                         Consumer<VisionImage> callback,
                                         @Nullable Consumer<String> errorCallback) {
        UUID requestId = UUID.randomUUID();
        PENDING.put(requestId, new PendingScreenshotRequest(
                player.getUUID(), options, System.currentTimeMillis(), callback, errorCallback));
        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CScreenshotRequestPacket(requestId, options));
        return requestId;
    }

    public static void handleScreenshot(ServerPlayer player, UUID requestId, @Nullable String error,
                                        String mimeType, byte[] imageBytes, int width, int height) {
        PendingScreenshotRequest pending = PENDING.remove(requestId);
        if (pending == null) {
            return;
        }
        if (!pending.playerId().equals(player.getUUID())) {
            fail(pending, "Screenshot request owner mismatch");
            return;
        }
        if (System.currentTimeMillis() - pending.createdAt() > REQUEST_TTL_MS) {
            fail(pending, "Screenshot request timed out");
            return;
        }
        if (error != null && !error.isBlank()) {
            fail(pending, error);
            return;
        }
        if (imageBytes == null || imageBytes.length == 0 || imageBytes.length > pending.options().maxBytes()) {
            fail(pending, "Screenshot too large or empty");
            return;
        }

        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        VisionImage image = new VisionImage(mimeType, base64, pending.options().detail(),
                width, height, imageBytes.length, "screenshot", requestId.toString());
        try {
            pending.callback().accept(image);
        } catch (Exception e) {
            fail(pending, "Screenshot callback failed: " + e.getMessage());
        }
    }

    private static void fail(PendingScreenshotRequest pending, String message) {
        if (pending.errorCallback() != null) {
            try {
                pending.errorCallback().accept(message);
            } catch (Exception ignored) {
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
