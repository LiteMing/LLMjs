package com.liteming.llmjs;

import com.liteming.llmjs.command.LLMCommand;
import com.liteming.llmjs.config.LLMConfig;
import com.liteming.llmjs.log.LLMLogger;
import com.liteming.llmjs.network.LLMNetwork;
import com.liteming.llmjs.network.packet.S2CLogPacket;
import com.liteming.llmjs.provider.ProviderManager;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(LLMjs.MODID)
public class LLMjs {
    public static final String MODID = "llmjs";
    public static final Logger LOGGER = LogManager.getLogger();

    public LLMjs() {
        LLMConfig.register();
        LLMNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("LLMjs v2.0 initialized");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        var server = event.getServer();
        var configDir = server.getServerDirectory().toPath().resolve("serverconfig");
        ProviderManager.INSTANCE.init(configDir);
        LLMLogger.INSTANCE.resize(LLMConfig.LOG_BUFFER_SIZE.get());
        // Wire logger to push log entries to connected clients
        LLMLogger.INSTANCE.addListener(entry -> {
            String json = entry.toJson().toString();
            S2CLogPacket packet = new S2CLogPacket(json);
            server.getPlayerList().getPlayers().forEach(player -> {
                if (com.liteming.llmjs.network.PermissionCheck.canUse(player)) {
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
