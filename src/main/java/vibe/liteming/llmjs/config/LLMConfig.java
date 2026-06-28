package vibe.liteming.llmjs.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class LLMConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_PROVIDER;
    public static final ForgeConfigSpec.IntValue TIMEOUT;
    public static final ForgeConfigSpec.IntValue RATE_LIMIT;
    public static final ForgeConfigSpec.IntValue MAX_PROMPT_LENGTH;
    public static final ForgeConfigSpec.IntValue MAX_IMAGE_BYTES;
    public static final ForgeConfigSpec.IntValue MAX_IMAGE_WIDTH;
    public static final ForgeConfigSpec.IntValue LOG_BUFFER_SIZE;
    public static final ForgeConfigSpec.IntValue REQUIRE_OP_LEVEL;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ALL_PLAYERS;

    static {
        BUILDER.push("general");
        DEFAULT_PROVIDER = BUILDER.comment("Default provider name (must match a key in providers.json)").define("default_provider", "openai");
        TIMEOUT = BUILDER.comment("Per-request timeout in seconds").defineInRange("timeout", 30, 1, 300);
        RATE_LIMIT = BUILDER.comment("Max requests per minute globally. 0 = unlimited").defineInRange("rate_limit", 30, 0, 1000);
        MAX_PROMPT_LENGTH = BUILDER.comment("Max characters per prompt (prevents abuse via network packets)").defineInRange("max_prompt_length", 10000, 100, 100000);
        MAX_IMAGE_BYTES = BUILDER.comment("Max compressed screenshot upload size in bytes").defineInRange("max_image_bytes", 786432, 32768, 2097152);
        MAX_IMAGE_WIDTH = BUILDER.comment("Max compressed screenshot width/height sent to LLM").defineInRange("max_image_width", 1024, 128, 2048);
        LOG_BUFFER_SIZE = BUILDER.comment("Number of log entries to keep in ring buffer").defineInRange("log_buffer_size", 200, 10, 10000);
        BUILDER.pop();

        BUILDER.push("permission");
        REQUIRE_OP_LEVEL = BUILDER.comment("Minimum OP level required to use LLM features").defineInRange("require_op_level", 2, 0, 4);
        ALLOW_ALL_PLAYERS = BUILDER.comment("Server-only: grant LLM request permission to ALL players.\nWARNING: may cause excessive LLM requests from unauthorized players.").define("allow_all_players", false);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
    }
}
