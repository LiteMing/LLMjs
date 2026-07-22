package vibe.liteming.llmcore.mod;

import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * The mod does no work on load. Consumer mods call into
 * vibe.liteming.llmcore.* types at their own initialization; this class is
 * just the @Mod-annotated anchor.
 */
@Mod("llmcore")
public final class LlmCoreMod {
    public static final Logger LOGGER = LoggerFactory.getLogger("llmcore");

    public LlmCoreMod() {
        LOGGER.info("llm-core loaded as shared Forge mod");
    }
}
