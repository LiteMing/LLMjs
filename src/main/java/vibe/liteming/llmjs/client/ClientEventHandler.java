package vibe.liteming.llmjs.client;

import vibe.liteming.llmjs.client.screen.LLMConsoleScreen;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SVisionImagePacket;
import vibe.liteming.llmjs.network.packet.S2CScreenshotRequestPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class ClientEventHandler {

    private static final int MAX_CLIENT_BUFFER = 300;
    private static final Object BUFFER_LOCK = new Object();

    @Nullable
    private static LLMConsoleScreen activeConsole = null;

    /** Survives while console is closed; server history may replace on open. */
    private static final List<String> clientLogBuffer = new ArrayList<>();

    public static void openConsole(String statusJson) {
        LLMConsoleScreen screen = new LLMConsoleScreen(statusJson);
        activeConsole = screen;
        net.minecraft.client.Minecraft.getInstance().setScreen(screen);
        List<String> snapshot;
        synchronized (BUFFER_LOCK) {
            snapshot = new ArrayList<>(clientLogBuffer);
        }
        if (!snapshot.isEmpty()) {
            screen.onLogHistory(snapshot);
        }
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
        if (logEntryJson == null || logEntryJson.isBlank()) return;
        synchronized (BUFFER_LOCK) {
            clientLogBuffer.add(logEntryJson);
            trimBuffer();
        }
        if (activeConsole != null) {
            activeConsole.onLogEntry(logEntryJson);
        }
    }

    public static void handleLogHistory(List<String> entries) {
        if (entries == null) return;
        List<String> snapshot;
        synchronized (BUFFER_LOCK) {
            clientLogBuffer.clear();
            clientLogBuffer.addAll(entries);
            trimBuffer();
            snapshot = new ArrayList<>(clientLogBuffer);
        }
        if (activeConsole != null) {
            activeConsole.onLogHistory(snapshot);
        }
    }

    private static void trimBuffer() {
        while (clientLogBuffer.size() > MAX_CLIENT_BUFFER) {
            clientLogBuffer.remove(0);
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
