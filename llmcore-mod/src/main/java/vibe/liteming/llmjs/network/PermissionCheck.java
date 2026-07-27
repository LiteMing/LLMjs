package vibe.liteming.llmjs.network;

import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.provider.ProviderManager;
import vibe.liteming.llmjs.security.ConsoleTestGrantService;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class PermissionCheck {
    /** Viewer permission: permits opening the Console log and receiving log traffic. */
    public static boolean canUse(ServerPlayer player) {
        if (canAdminister(player)) return true;
        if (LLMConfig.ALLOW_ALL_PLAYERS.get()) return true;
        return player.hasPermissions(LLMConfig.REQUIRE_OP_LEVEL.get());
    }

    /** Administrator permission: permits provider, routing, and test operations. */
    public static boolean canAdminister(ServerPlayer player) {
        return canManageAdministrators(player)
                || isWhitelisted(player.getUUID(), LLMConfig.ADMIN_UUID_WHITELIST.get());
    }

    /** Delegated Test permission never grants provider, routing, setup, or log access. */
    public static boolean canTest(ServerPlayer player) {
        return player != null && (canAdminister(player)
                || ConsoleTestGrantService.INSTANCE.hasActiveGrant(player.getUUID()));
    }

    public static boolean canAdminister(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? source.hasPermission(4) : canAdminister(player);
    }

    /** Owner permission: permits changing the administrator whitelist itself. */
    public static boolean canManageAdministrators(ServerPlayer player) {
        if (player.hasPermissions(4)) return true;
        var server = player.getServer();
        return server != null && server.isSingleplayerOwner(player.getGameProfile());
    }

    public static boolean canManageAdministrators(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? source.hasPermission(4) : canManageAdministrators(player);
    }

    public static JsonObject statusFor(ServerPlayer player) {
        boolean canAdminister = canAdminister(player);
        boolean canView = canUse(player);
        var grant = ConsoleTestGrantService.INSTANCE.activeGrant(player.getUUID());
        boolean canTest = canAdminister || grant.isPresent();
        Supplier<JsonObject> restrictedStatus = () -> grant
                .map(value -> ProviderManager.INSTANCE.getTestStatusJson(value.request().purpose()))
                .orElseGet(JsonObject::new);
        return createStatusPayload(canView, canTest, canAdminister,
                ProviderManager.INSTANCE::getStatusJson, restrictedStatus);
    }

    static JsonObject createStatusPayload(boolean canView, boolean canTest, boolean canAdminister,
            Supplier<JsonObject> fullStatus, Supplier<JsonObject> restrictedTestStatus) {
        JsonObject result = canAdminister
                ? fullStatus.get()
                : canTest ? restrictedTestStatus.get() : new JsonObject();
        result.addProperty("canView", canView);
        result.addProperty("canTest", canTest);
        result.addProperty("canAdminister", canAdminister);
        return result;
    }

    static boolean isWhitelisted(UUID playerId, List<? extends String> configuredIds) {
        if (playerId == null || configuredIds == null) return false;
        String expected = playerId.toString();
        for (String configuredId : configuredIds) {
            if (configuredId != null && expected.equalsIgnoreCase(configuredId.strip())) return true;
        }
        return false;
    }

    public static boolean isPromptValid(String prompt) {
        return prompt != null && prompt.length() <= LLMConfig.MAX_PROMPT_LENGTH.get();
    }
}
