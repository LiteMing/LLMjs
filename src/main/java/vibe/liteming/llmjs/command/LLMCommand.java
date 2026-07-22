package vibe.liteming.llmjs.command;

import vibe.liteming.llmjs.config.ProviderLoader;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.network.packet.S2CLogHistoryPacket;
import vibe.liteming.llmjs.network.packet.S2CStatusResponsePacket;
import vibe.liteming.llmjs.provider.ProviderManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LLMCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("llm")
                .then(Commands.literal("console")
                        .executes(ctx -> openConsole(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> showStatus(ctx.getSource())))
                .then(Commands.literal("test")
                        .then(Commands.argument("provider", StringArgumentType.string())
                                .suggests(LLMCommand::suggestProvidersWithStar)
                                .executes(ctx -> testProvider(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "provider")))))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> reloadConfig(ctx.getSource())))
                .then(Commands.literal("setkey")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("provider", StringArgumentType.string())
                                .suggests(LLMCommand::suggestProviders)
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(ctx -> setKey(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "provider"),
                                                StringArgumentType.getString(ctx, "key"))))))
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
        String statusJson = ProviderManager.INSTANCE.getStatusJson().toString();
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
        source.sendSuccess(() -> Component.literal("[LLMjs] Provider status:"), false);
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
            source.sendSuccess(() -> Component.literal("[LLMjs] Testing all providers..."), false);
            for (String name : ProviderManager.INSTANCE.getProviderNames()) {
                testSingle(source, name);
            }
        } else {
            testSingle(source, providerName);
        }
        return 1;
    }

    private static void testSingle(CommandSourceStack source, String name) {
        ProviderManager.INSTANCE.testProvider(name).thenAccept(status -> {
            if (status.connected()) {
                source.sendSuccess(() -> Component.literal("[LLMjs] " + name + ": OK (" + status.latencyMs() + "ms)"), false);
            } else {
                source.sendFailure(Component.literal("[LLMjs] " + name + ": FAILED - " + status.lastError()));
            }
        });
    }

    private static int reloadConfig(CommandSourceStack source) {
        ProviderManager.INSTANCE.reload();
        source.sendSuccess(() -> Component.literal("[LLMjs] Configuration reloaded"), false);
        return 1;
    }

    private static int setKey(CommandSourceStack source, String providerName, String apiKey) {
        if (ProviderLoader.setKey(providerName, apiKey)) {
            ProviderManager.INSTANCE.reload();
            source.sendSuccess(() -> Component.literal("[LLMjs] Key set for '" + providerName + "', config reloaded"), false);
            return 1;
        } else {
            source.sendFailure(Component.literal("[LLMjs] Failed to write key. Check server logs."));
            return 0;
        }
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
