package com.liteming.llmjs.network;

import com.liteming.llmjs.config.LLMConfig;
import net.minecraft.server.level.ServerPlayer;

public class PermissionCheck {
    public static boolean canUse(ServerPlayer player) {
        if (LLMConfig.ALLOW_ALL_PLAYERS.get()) return true;
        return player.hasPermissions(LLMConfig.REQUIRE_OP_LEVEL.get());
    }

    public static boolean isPromptValid(String prompt) {
        return prompt != null && prompt.length() <= LLMConfig.MAX_PROMPT_LENGTH.get();
    }
}
