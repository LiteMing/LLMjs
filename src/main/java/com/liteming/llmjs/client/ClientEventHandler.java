package com.liteming.llmjs.client;

import com.liteming.llmjs.LLMjs;
import com.liteming.llmjs.client.screen.LLMConsoleScreen;
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

    public static void clearActiveConsole() {
        activeConsole = null;
    }
}
