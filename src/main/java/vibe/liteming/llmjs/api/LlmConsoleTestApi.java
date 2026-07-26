package vibe.liteming.llmjs.api;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import vibe.liteming.llmjs.client.ClientEventHandler;
import vibe.liteming.llmjs.test.ConsoleTestCodec;
import vibe.liteming.llmjs.test.ConsoleTestRequest;

/**
 * Stable client handoff for other mods. Callers may use reflection and only need
 * the versioned JSON schema, avoiding a hard compile dependency on llmjs.
 */
@OnlyIn(Dist.CLIENT)
public final class LlmConsoleTestApi {
    public static final int HANDOFF_SCHEMA_VERSION = ConsoleTestRequest.SCHEMA_VERSION;

    private LlmConsoleTestApi() {
    }

    public static int handoffSchemaVersion() {
        return HANDOFF_SCHEMA_VERSION;
    }

    /** Validate, open the Console Test tab, and retain the complete unsent draft. */
    public static boolean openJson(String handoffJson) {
        ConsoleTestCodec.parseRequest(handoffJson, false);
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) return false;
        minecraft.execute(
                () -> ClientEventHandler.openConsoleTest(handoffJson));
        return true;
    }
}
