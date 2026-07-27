package vibe.liteming.llmjs.command;

import vibe.liteming.llmjs.config.ProviderLoader;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.network.packet.S2CLogHistoryPacket;
import vibe.liteming.llmjs.network.packet.S2CStatusResponsePacket;
import vibe.liteming.llmjs.provider.ProviderManager;
import vibe.liteming.llmjs.security.PersonalBudgetService;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LLMCommand {
    private static final long OFFLINE_CONFIRMATION_WINDOW_MS = 30_000L;
    private static final WhitelistConfirmationGuard WHITELIST_CONFIRMATIONS =
            new WhitelistConfirmationGuard(OFFLINE_CONFIRMATION_WINDOW_MS);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("llm")
                .then(Commands.literal("console")
                        .executes(ctx -> openConsole(ctx.getSource())))
                .then(Commands.literal("status")
                        .requires(PermissionCheck::canAdminister)
                        .executes(ctx -> showStatus(ctx.getSource())))
                .then(Commands.literal("test")
                        .requires(PermissionCheck::canAdminister)
                        .then(Commands.argument("provider", StringArgumentType.string())
                                .suggests(LLMCommand::suggestProvidersWithStar)
                                .executes(ctx -> testProvider(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "provider")))))
                .then(Commands.literal("reload")
                        .requires(PermissionCheck::canAdminister)
                        .executes(ctx -> reloadConfig(ctx.getSource())))
                .then(Commands.literal("setkey")
                        .requires(PermissionCheck::canAdminister)
                        .then(Commands.argument("provider", StringArgumentType.string())
                                .suggests(LLMCommand::suggestProviders)
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(ctx -> setKey(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "provider"),
                                                StringArgumentType.getString(ctx, "key"))))))
                .then(Commands.literal("whitelist")
                        .requires(PermissionCheck::canManageAdministrators)
                        .executes(ctx -> showWhitelistUsage(ctx.getSource()))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> setWhitelist(
                                                ctx.getSource(),
                                                GameProfileArgument.getGameProfiles(ctx, "player"),
                                                BoolArgumentType.getBool(ctx, "enabled"))))))
                .then(Commands.literal("budget")
                        .executes(ctx -> showOwnBudget(ctx.getSource()))
                        .then(Commands.literal("list")
                                .requires(PermissionCheck::canManageAdministrators)
                                .executes(ctx -> listBudgets(ctx.getSource())))
                        .then(Commands.literal("limit")
                                .requires(PermissionCheck::canManageAdministrators)
                                .then(Commands.argument("tokens", LongArgumentType.longArg(0L))
                                        .executes(ctx -> setBudgetLimit(ctx.getSource(),
                                                LongArgumentType.getLong(ctx, "tokens")))))
                        .then(Commands.literal("reset")
                                .requires(PermissionCheck::canManageAdministrators)
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(ctx -> resetBudgets(ctx.getSource(),
                                                GameProfileArgument.getGameProfiles(ctx, "player"))))))
        );
    }

    private static int openConsole(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Console can only be opened by players"));
            return 0;
        }
        if (!PermissionCheck.canUse(player)) {
            source.sendFailure(Component.literal("No permission to use LLM features"));
            return 0;
        }
        String statusJson = PermissionCheck.statusFor(player).toString();
        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CStatusResponsePacket(statusJson, true));
        // Dump full recent buffer so logs received while console was closed still appear
        List<String> history = new ArrayList<>();
        for (LLMLogger.LogEntry entry : LLMLogger.INSTANCE.getRecentEntries(200)) {
            history.add(entry.toJson().toString());
        }
        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CLogHistoryPacket(history));
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[LLM Core] Provider status:"), false);
        for (String name : ProviderManager.INSTANCE.getProviderNames()) {
            var provider = ProviderManager.INSTANCE.getProvider(name);
            var cached = ProviderManager.INSTANCE.getCachedStatus(name);
            String statusStr = cached != null
                    ? (cached.connected() ? "OK (" + cached.latencyMs() + "ms)" : "ERROR")
                    : "untested";
            String type = provider != null ? provider.getType() : "?";
            source.sendSuccess(() -> Component.literal("  " + name + " [" + type + "] - " + statusStr), false);
        }
        return 1;
    }

    private static int testProvider(CommandSourceStack source, String providerName) {
        if ("*".equals(providerName)) {
            source.sendSuccess(() -> Component.literal("[LLM Core] Testing all providers..."), false);
            for (String name : ProviderManager.INSTANCE.getProviderNames()) {
                testSingle(source, name);
            }
        } else {
            testSingle(source, providerName);
        }
        return 1;
    }

    private static void testSingle(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        ProviderManager.INSTANCE.testProvider(name, player == null ? null : player.getUUID()).thenAccept(status -> {
            if (status.connected()) {
                source.sendSuccess(() -> Component.literal("[LLM Core] " + name + ": OK (" + status.latencyMs() + "ms)"), false);
            } else {
                source.sendFailure(Component.literal("[LLM Core] " + name + ": FAILED - " + status.lastError()));
            }
        });
    }

    private static int reloadConfig(CommandSourceStack source) {
        ProviderManager.INSTANCE.reload();
        source.sendSuccess(() -> Component.literal("[LLM Core] Configuration reloaded"), false);
        return 1;
    }

    private static int setKey(CommandSourceStack source, String providerName, String apiKey) {
        if (ProviderLoader.setKey(providerName, apiKey)) {
            ProviderManager.INSTANCE.reload();
            source.sendSuccess(() -> Component.literal("[LLM Core] Key set for '" + providerName + "', config reloaded"), false);
            return 1;
        } else {
            source.sendFailure(Component.literal("[LLM Core] Failed to write key. Check server logs."));
            return 0;
        }
    }

    private static int showWhitelistUsage(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("command.llm.whitelist.usage"), false);
        source.sendSuccess(() -> Component.translatable("command.llm.whitelist.warning"), false);
        return 1;
    }

    private static int setWhitelist(CommandSourceStack source, Collection<GameProfile> profiles, boolean enabled) {
        for (GameProfile profile : profiles) {
            if (profile.getId() == null) {
                source.sendFailure(Component.translatable(
                        "command.llm.whitelist.missing_uuid", profile.getName()));
                return 0;
            }
        }

        if (source.getServer().isDedicatedServer() && !source.getServer().usesAuthentication()) {
            source.sendFailure(Component.translatable("command.llm.whitelist.offline_warning"));
            String actor = source.getPlayer() == null
                    ? "server-console"
                    : "player:" + source.getPlayer().getUUID();
            String operation = whitelistOperation(profiles, enabled);
            if (!WHITELIST_CONFIRMATIONS.confirmOrArm(actor, operation, System.currentTimeMillis())) {
                source.sendSuccess(() -> Component.translatable(
                        "command.llm.whitelist.offline_confirm", OFFLINE_CONFIRMATION_WINDOW_MS / 1000), false);
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("command.llm.whitelist.offline_confirmed"), false);
        }

        int processed = 0;
        for (GameProfile profile : profiles) {
            boolean changed = LLMConfig.setAdministrator(profile.getId(), enabled);
            String key = changed
                    ? (enabled ? "command.llm.whitelist.granted" : "command.llm.whitelist.revoked")
                    : (enabled ? "command.llm.whitelist.already_granted" : "command.llm.whitelist.already_revoked");
            source.sendSuccess(() -> Component.translatable(
                    key, profile.getName(), profile.getId().toString()), false);

            ServerPlayer online = source.getServer().getPlayerList().getPlayer(profile.getId());
            if (online != null) {
                LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> online),
                        new S2CStatusResponsePacket(PermissionCheck.statusFor(online).toString(), false));
            }
            processed++;
        }
        return processed;
    }

    private static String whitelistOperation(Collection<GameProfile> profiles, boolean enabled) {
        String targets = profiles.stream()
                .sorted(Comparator.comparing(profile -> profile.getId().toString()))
                .map(profile -> profile.getId().toString())
                .collect(Collectors.joining(","));
        return enabled + ":" + targets;
    }

    private static int showOwnBudget(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return listBudgets(source);
        sendBudgetStatus(source, player.getGameProfile(),
                PersonalBudgetService.INSTANCE.status(player.getUUID()));
        return 1;
    }

    private static int listBudgets(CommandSourceStack source) {
        if (!PersonalBudgetService.INSTANCE.storageAvailable()) {
            source.sendFailure(Component.translatable("command.llm.budget.storage_unavailable"));
            return 0;
        }
        List<PersonalBudgetService.Status> entries = PersonalBudgetService.INSTANCE.list();
        source.sendSuccess(() -> Component.translatable("command.llm.budget.list_header", entries.size()), false);
        for (PersonalBudgetService.Status status : entries) {
            GameProfile profile = source.getServer().getProfileCache()
                    .get(status.playerId()).orElse(new GameProfile(status.playerId(), status.playerId().toString()));
            sendBudgetStatus(source, profile, status);
        }
        return entries.size();
    }

    private static int setBudgetLimit(CommandSourceStack source, long tokens) {
        LLMConfig.setPersonalBudgetLimit(tokens);
        String key = tokens == 0L ? "command.llm.budget.limit_unlimited" : "command.llm.budget.limit_set";
        source.sendSuccess(() -> Component.translatable(key, tokens), true);
        return 1;
    }

    private static int resetBudgets(CommandSourceStack source, Collection<GameProfile> profiles) {
        int changed = 0;
        for (GameProfile profile : profiles) {
            if (profile.getId() == null) continue;
            if (!PersonalBudgetService.INSTANCE.storageAvailable()) {
                source.sendFailure(Component.translatable("command.llm.budget.storage_unavailable"));
                return changed;
            }
            boolean reset = PersonalBudgetService.INSTANCE.reset(profile.getId());
            String key = reset ? "command.llm.budget.reset" : "command.llm.budget.reset_empty";
            source.sendSuccess(() -> Component.translatable(
                    key, profile.getName(), profile.getId().toString()), true);
            if (reset) changed++;
        }
        return changed;
    }

    private static void sendBudgetStatus(CommandSourceStack source, GameProfile profile,
            PersonalBudgetService.Status status) {
        Component limit = status.limitTokens() <= 0L
                ? Component.translatable("command.llm.budget.unlimited")
                : Component.literal(Long.toString(status.limitTokens()));
        Component state = !status.storageAvailable()
                ? Component.translatable("command.llm.budget.state_storage_unavailable")
                : status.exhausted()
                        ? Component.translatable("command.llm.budget.state_exhausted")
                        : Component.translatable("command.llm.budget.state_available");
        source.sendSuccess(() -> Component.translatable("command.llm.budget.status",
                profile.getName(), status.playerId().toString(), status.totalTokens(), limit,
                status.promptTokens(), status.completionTokens(), status.estimatedTokens(),
                status.reservedTokens(), state), false);
    }

    private static CompletableFuture<Suggestions> suggestProviders(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(ProviderManager.INSTANCE.getProviderNames(), builder);
    }

    private static CompletableFuture<Suggestions> suggestProvidersWithStar(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>(ProviderManager.INSTANCE.getProviderNames());
        names.add(0, "*");
        return SharedSuggestionProvider.suggest(names, builder);
    }
}
