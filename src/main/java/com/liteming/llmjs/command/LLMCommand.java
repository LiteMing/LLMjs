package com.liteming.llmjs.command;

import com.liteming.llmjs.network.LLMNetwork;
import com.liteming.llmjs.network.PermissionCheck;
import com.liteming.llmjs.network.packet.S2CStatusResponsePacket;
import com.liteming.llmjs.provider.ProviderManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

public class LLMCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("llm")
                .then(Commands.literal("console")
                        .executes(ctx -> openConsole(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> showStatus(ctx.getSource())))
                .then(Commands.literal("test")
                        .then(Commands.argument("provider", StringArgumentType.string())
                                .executes(ctx -> testProvider(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "provider")))))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> reloadConfig(ctx.getSource())))
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
}
