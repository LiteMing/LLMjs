package vibe.liteming.llmjs;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(LLMjs.MODID)
public class LLMjs {
    public static final String MODID = "llmjs";
    public static final Logger LOGGER = LogManager.getLogger();

    public LLMjs() {
        LOGGER.info("LLMjs KubeJS adapter initialized");
    }
}
