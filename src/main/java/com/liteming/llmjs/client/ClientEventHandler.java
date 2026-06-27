package com.liteming.llmjs.client;

import com.liteming.llmjs.client.screen.LLMConsoleScreen;
import com.liteming.llmjs.network.LLMNetwork;
import com.liteming.llmjs.network.packet.C2SVisionImagePacket;
import com.liteming.llmjs.network.packet.S2CScreenshotRequestPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class ClientEventHandler {

    @Nullable
    private static LLMConsoleScreen activeConsole = null;

    public static void openConsole(String statusJson) {
        LLMConsoleScreen screen = new LLMConsoleScreen(statusJson);
        activeConsole = screen;
        net.minecraft.client.Minecraft.getInstance().setScreen(screen);
    }

    public static void handleChatResponse(UUID requestId, boolean success,
                                           @Nullable String content, @Nullable String error) {
        if (activeConsole != null) {
            activeConsole.onChatResponse(requestId, success, content, error);
        }
    }

    public static void updateStatus(String statusJson) {
        if (activeConsole != null) {
            activeConsole.onStatusUpdate(statusJson);
        }
    }

    public static void handleLogEntry(String logEntryJson) {
        if (activeConsole != null) {
            activeConsole.onLogEntry(logEntryJson);
        }
    }

    public static void captureScreenshotForLLM(S2CScreenshotRequestPacket request) {
        try {
            ScreenshotCapture.Result result = ScreenshotCapture.capture(request.getOptions());
            LLMNetwork.CHANNEL.sendToServer(new C2SVisionImagePacket(
                    request.getRequestId(),
                    null,
                    result.mimeType(),
                    result.bytes(),
                    result.width(),
                    result.height()
            ));
        } catch (Exception e) {
            LLMNetwork.CHANNEL.sendToServer(new C2SVisionImagePacket(
                    request.getRequestId(),
                    e.getMessage(),
                    request.getOptions().mimeType(),
                    new byte[0],
                    0,
                    0
            ));
        }
    }

    public static void clearActiveConsole() {
        activeConsole = null;
    }
}
