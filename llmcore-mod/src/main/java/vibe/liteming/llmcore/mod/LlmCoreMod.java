package vibe.liteming.llmcore.mod;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.llmcore.PurposeMeta;
import vibe.liteming.llmcore.PurposeRegistry;
import vibe.liteming.llmcore.LlmConsoleTestBridge;
import vibe.liteming.llmcore.LlmRequestAccounting;
import vibe.liteming.llmjs.command.LLMCommand;
import vibe.liteming.llmjs.config.GlobalConfig;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.network.packet.S2CLogPacket;
import vibe.liteming.llmjs.provider.ProviderManager;
import vibe.liteming.llmjs.security.ConsoleTestGrantService;
import vibe.liteming.llmjs.security.PersonalBudgetService;

/**
 * Forge entry point for the standalone llmcore mod.
 *
 * llm-core is a plain Java library (the :core subproject) holding the shared
 * LLM orchestration types used by both llmjs and CreatureChat:
 * LlmOrchestrator, PurposeRegistry, PriorityRoutingConfig, etc. This class
 * exists purely so Forge loads the llmcore jar as a mod and contributes its
 * classes to the runtime classpath exactly once, eliminating the
 * split-package collisions that arise when each consumer mod embeds a
 * private shadow-relocated copy.
 *
 * The Forge wrapper also owns the host-neutral runtime and administrator
 * Console. Consumer adapters such as llmjs only bridge their platform API to
 * these services; they no longer own provider configuration or the Console.
 */
@Mod("llmcore")
public final class LlmCoreMod {
    public static final String MODID = "llmcore";
    public static final Logger LOGGER = LoggerFactory.getLogger("llmcore");

    public LlmCoreMod() {
        LLMConfig.register();
        LLMNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
        registerBuiltInPurposes();
        LOGGER.info("llm-core runtime and Console initialized");
    }

    private static void registerBuiltInPurposes() {
        PurposeRegistry.registerBuiltIn(new PurposeMeta("CHAT", "Chat",
                "Default conversational request", MODID, true));
        PurposeRegistry.registerBuiltIn(new PurposeMeta("DEBUG_TEST",
                "Debug / Test", "Connection / smoke-test requests", MODID, true));
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        var server = event.getServer();
        // Game root comes from the Forge FMLPaths API only — MinecraftServer's
        // directory accessor differs between 1.20.1 and 1.20.2+ mappings and
        // threw NoSuchMethodError on the dev runtime (LLMCORE-PATH-COMPAT-1).
        var gameRoot = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .toAbsolutePath().normalize();
        // serverconfig lives at the game-instance root (Forge convention for
        // singleplayer: <gamedir>/serverconfig, NOT inside the world save).
        var serverConfigDir = gameRoot.resolve("serverconfig");
        GlobalConfig.init(gameRoot);
        ProviderManager.INSTANCE.init(serverConfigDir, gameRoot);
        // The personal budget is PER-WORLD data: it must live under the world
        // save (server.getWorldPath(LevelResource.ROOT)), not the game root —
        // world A and world B keep independent budgets. With the reobf
        // production artifact this 1.20.1 call remaps correctly.
        var worldRoot = server.getWorldPath(LevelResource.ROOT)
                .toAbsolutePath().normalize();
        var budgetFile = worldRoot.resolve("llmcore/personal-budget.json");
        migrateGameInstanceBudgetIfNeeded(gameRoot, budgetFile);
        PersonalBudgetService.INSTANCE.open(budgetFile);
        LlmRequestAccounting.install(PersonalBudgetService.INSTANCE);
        if (!LlmRequestAccounting.isInstalled()) {
            LOGGER.error("LLM billing policy failed to install; PLAYER requests will be denied");
        }
        if (!PersonalBudgetService.INSTANCE.storageAvailable()) {
            LOGGER.error("Personal budget ledger is unavailable; PLAYER requests will be denied");
        }
        if (!LLMConfig.PERSONAL_BUDGET_DEFAULT_CONFIRMED.get()) {
            LOGGER.warn("Personal budget default is unlimited and has not been acknowledged; "
                    + "open /llm console or run /llm budget confirm-default");
        }
        ConsoleTestGrantService.INSTANCE.clear();
        LlmConsoleTestBridge.install(ConsoleTestGrantService.INSTANCE::issue);
        LLMLogger.INSTANCE.resize(LLMConfig.LOG_BUFFER_SIZE.get());
        LLMLogger.INSTANCE.installCoreHook();
        LLMLogger.INSTANCE.addListener(entry -> {
            S2CLogPacket packet = new S2CLogPacket(entry.toJson().toString());
            server.getPlayerList().getPlayers().forEach(player -> {
                if (PermissionCheck.canUse(player)) {
                    LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
                }
            });
        });
        LOGGER.info("llm-core providers loaded: {}", ProviderManager.INSTANCE.getProviderNames());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ProviderManager.INSTANCE.close();
        LlmRequestAccounting.clear();
        PersonalBudgetService.INSTANCE.close();
        LlmConsoleTestBridge.clear();
        ConsoleTestGrantService.INSTANCE.clear();
    }

    /**
     * One-time migration (LLMCORE-PATH-COMPAT-1): a single hotfix build
     * briefly stored the personal budget at the game-instance root instead
     * of the world save. When the world file is missing but the legacy
     * game-instance file exists, it is MOVED (not copied) into the world —
     * never a silent reset, never a second copy.
     */
    private static void migrateGameInstanceBudgetIfNeeded(java.nio.file.Path gameRoot,
            java.nio.file.Path worldBudgetFile) {
        try {
            if (java.nio.file.Files.exists(worldBudgetFile)) {
                return;
            }
            var legacy = gameRoot.resolve("llmcore/personal-budget.json");
            if (!java.nio.file.Files.exists(legacy)) {
                return;
            }
            java.nio.file.Files.createDirectories(worldBudgetFile.getParent());
            java.nio.file.Files.move(legacy, worldBudgetFile,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("Migrated personal budget from game instance ({}) to world save ({})",
                    legacy, worldBudgetFile);
        } catch (Exception failure) {
            LOGGER.error("Personal budget migration failed; falling back to game-instance file: {}",
                    failure.getMessage());
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LLMCommand.register(event.getDispatcher());
    }
}
