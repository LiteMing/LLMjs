package vibe.liteming.llmjs;

import vibe.liteming.llmjs.command.LLMCommand;
import vibe.liteming.llmjs.config.GlobalConfig;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.S2CLogPacket;
import vibe.liteming.llmjs.provider.ProviderManager;
import vibe.liteming.llmcore.PurposeMeta;
import vibe.liteming.llmcore.PurposeRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

@Mod(LLMjs.MODID)
public class LLMjs {
    public static final String MODID = "llmjs";
    public static final Logger LOGGER = LogManager.getLogger();

    public LLMjs() {
        LLMConfig.register();
        LLMNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
        registerBuiltInPurposes();
        LOGGER.info("LLMjs v2.0 initialized");
    }

    /**
     * Register purposes that llm-core / llmjs define intrinsically. These cannot
     * be overwritten by mod-level registers, ensuring the routing UI always shows
     * the generic "CHAT" and "DEBUG_TEST" rows even with no consumer mods present.
     * Consumer mods register their own purposes at runtime via
     * {@link vibe.liteming.llmjs.api.LlmjsApi#registerPurpose}.
     */
    private static void registerBuiltInPurposes() {
        PurposeRegistry.registerBuiltIn(new PurposeMeta("CHAT", "Chat",
                "Default conversational request", MODID, true));
        PurposeRegistry.registerBuiltIn(new PurposeMeta("DEBUG_TEST",
                "Debug / Test", "Connection / smoke-test requests", MODID, true));
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        var server = event.getServer();
        var gameRoot = server.getServerDirectory().toPath();
        var serverConfigDir = gameRoot.resolve("serverconfig");
        GlobalConfig.init(gameRoot);
        ProviderManager.INSTANCE.init(serverConfigDir, gameRoot);
        LLMLogger.INSTANCE.resize(LLMConfig.LOG_BUFFER_SIZE.get());
        LLMLogger.INSTANCE.installCoreHook();
        // Wire logger to push log entries to connected clients
        LLMLogger.INSTANCE.addListener(entry -> {
            String json = entry.toJson().toString();
            S2CLogPacket packet = new S2CLogPacket(json);
            server.getPlayerList().getPlayers().forEach(player -> {
                if (vibe.liteming.llmjs.network.PermissionCheck.canUse(player)) {
                    LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
                }
            });
        });
        LOGGER.info("LLMjs providers loaded: {}", ProviderManager.INSTANCE.getProviderNames());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LLMCommand.register(event.getDispatcher());
    }
}
