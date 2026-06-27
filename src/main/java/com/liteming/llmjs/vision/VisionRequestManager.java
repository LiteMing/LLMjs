package com.liteming.llmjs.vision;

import com.liteming.llmjs.config.LLMConfig;
import com.liteming.llmjs.format.ApiFormat;
import com.liteming.llmjs.format.MessagePart;
import com.liteming.llmjs.network.LLMNetwork;
import com.liteming.llmjs.network.packet.S2CScreenshotRequestPacket;
import com.liteming.llmjs.pipeline.LLMResponse;
import com.liteming.llmjs.provider.Provider;
import com.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class VisionRequestManager {
    public static final String DEFAULT_PROMPT = """
            Look at the current Minecraft client screenshot, including any open UI.
            Answer with only the most useful short observation for the player.
            If there is a clear danger, missing requirement, selected item, button, dialog, or error, mention it.
            Keep the answer under 60 characters.
            """;

    private static final long REQUEST_TTL_MS = 30_000L;
    private static final ConcurrentMap<UUID, PendingVisionRequest> PENDING = new ConcurrentHashMap<>();

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

    public record RequestOptions(@Nullable String provider, @Nullable String systemPrompt,
                                 @Nullable Double temperature, @Nullable Integer maxTokens,
                                 CaptureOptions capture, @Nullable String expected,
                                 @Nullable String harnessPrompt, @Nullable String harnessProvider,
                                 boolean showHarnessFailures) {
    }

    private record PendingVisionRequest(UUID playerId, String prompt, RequestOptions options, long createdAt) {
    }

    public static UUID requestActionbar(ServerPlayer player, String prompt, RequestOptions options) {
        UUID requestId = UUID.randomUUID();
        String effectivePrompt = prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt;
        PENDING.put(requestId, new PendingVisionRequest(player.getUUID(), effectivePrompt, options, System.currentTimeMillis()));
        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CScreenshotRequestPacket(requestId, options.capture()));
        return requestId;
    }

    public static void handleScreenshot(ServerPlayer player, UUID requestId, @Nullable String error,
                                        String mimeType, byte[] imageBytes, int width, int height) {
        PendingVisionRequest pending = PENDING.remove(requestId);
        if (pending == null) {
            player.displayClientMessage(Component.literal("LLM screenshot request expired"), true);
            return;
        }
        if (!pending.playerId().equals(player.getUUID())) {
            player.displayClientMessage(Component.literal("LLM screenshot request owner mismatch"), true);
            return;
        }
        if (System.currentTimeMillis() - pending.createdAt() > REQUEST_TTL_MS) {
            player.displayClientMessage(Component.literal("LLM screenshot request timed out"), true);
            return;
        }
        if (error != null && !error.isBlank()) {
            player.displayClientMessage(Component.literal("Screenshot failed: " + error), true);
            return;
        }
        int maxBytes = pending.options().capture().maxBytes();
        if (imageBytes == null || imageBytes.length == 0 || imageBytes.length > maxBytes) {
            player.displayClientMessage(Component.literal("Screenshot too large for LLM"), true);
            return;
        }

        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        MessagePart.ImagePart image = MessagePart.image(mimeType, base64, pending.options().capture().detail(),
                width, height, imageBytes.length);
        sendImageActionbar(player, pending.prompt(), pending.options(), image);
    }

    public static void sendImageActionbar(ServerPlayer player, String prompt, RequestOptions options,
                                          MessagePart.ImagePart image) {
        List<ApiFormat.Message> messages = new ArrayList<>();
        if (options.systemPrompt() != null && !options.systemPrompt().isBlank()) {
            messages.add(new ApiFormat.Message("system", options.systemPrompt()));
        }
        messages.add(ApiFormat.Message.userWithImage(prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt, image));

        PendingVisionRequest synthetic = new PendingVisionRequest(player.getUUID(),
                prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt, options, System.currentTimeMillis());
        List<String> chain = resolveProviderChain(options.provider());
        int timeout = LLMConfig.TIMEOUT.get();
        ProviderManager.INSTANCE.sendWithFallback(messages, chain, options.temperature(),
                        options.maxTokens(), timeout)
                .thenAccept(response -> {
                    if (!response.isSuccess()) {
                        runOnServer(player, () -> player.displayClientMessage(
                                Component.literal("Vision failed: " + response.getError()), true));
                        return;
                    }
                    maybeHarnessThenDisplay(player, synthetic, response);
                });
    }

    private static void maybeHarnessThenDisplay(ServerPlayer player, PendingVisionRequest pending, LLMResponse response) {
        String expected = pending.options().expected();
        if (expected == null || expected.isBlank()) {
            displayActionbar(player, response.getContent());
            return;
        }

        String harnessPrompt = pending.options().harnessPrompt();
        if (harnessPrompt == null || harnessPrompt.isBlank()) {
            harnessPrompt = """
                    You are a strict response harness. Decide whether the candidate answer satisfies the expected condition.
                    Reply with exactly PASS or FAIL, with no explanation.
                    """;
        }

        String judgeInput = "Original screenshot task:\n" + pending.prompt()
                + "\n\nExpected condition:\n" + expected
                + "\n\nCandidate answer:\n" + response.getContent();

        List<ApiFormat.Message> judgeMessages = List.of(
                new ApiFormat.Message("system", harnessPrompt),
                new ApiFormat.Message("user", judgeInput)
        );
        List<String> chain = resolveProviderChain(
                pending.options().harnessProvider() != null ? pending.options().harnessProvider() : pending.options().provider());

        ProviderManager.INSTANCE.sendWithFallback(judgeMessages, chain, 0.0, 8, LLMConfig.TIMEOUT.get())
                .thenAccept(judge -> {
                    boolean pass = judge.isSuccess() && judge.getContent() != null
                            && judge.getContent().trim().toUpperCase(Locale.ROOT).startsWith("PASS");
                    if (pass) {
                        displayActionbar(player, response.getContent());
                    } else if (pending.options().showHarnessFailures()) {
                        displayActionbar(player, "Vision answer hidden: harness failed");
                    }
                });
    }

    private static List<String> resolveProviderChain(@Nullable String providerName) {
        if (providerName != null && !providerName.isBlank()) {
            return List.of(providerName);
        }
        Provider defaultProvider = ProviderManager.INSTANCE.getDefaultProvider();
        return defaultProvider != null ? List.of(defaultProvider.getName()) : List.of();
    }

    private static void displayActionbar(ServerPlayer player, @Nullable String text) {
        if (text == null || text.isBlank()) return;
        runOnServer(player, () -> player.displayClientMessage(Component.literal(text), true));
    }

    private static void runOnServer(ServerPlayer player, Runnable task) {
        if (player.server != null) {
            player.server.execute(task);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
