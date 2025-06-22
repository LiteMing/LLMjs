package com.liteming.llmjs.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class LLMConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> API_URL;
    public static final ForgeConfigSpec.ConfigValue<String> API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL;
    public static final ForgeConfigSpec.IntValue TIMEOUT;
    public static final ForgeConfigSpec.IntValue MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue TEMPERATURE;

    static {
        BUILDER.push("LLM Settings");

        API_URL = BUILDER
                .comment("API endpoint URL (OpenAI compatible)")
                .define("api_url", "https://api.openai.com/v1/chat/completions");

        API_KEY = BUILDER
                .comment("API key for authentication")
                .define("api_key", "your-api-key-here");

        MODEL = BUILDER
                .comment("Model name to use")
                .define("model", "gpt-3.5-turbo");

        TIMEOUT = BUILDER
                .comment("Request timeout in seconds")
                .defineInRange("timeout", 30, 1, 300);

        MAX_TOKENS = BUILDER
                .comment("Maximum tokens in response")
                .defineInRange("max_tokens", 1000, 1, 4096);

        TEMPERATURE = BUILDER
                .comment("Temperature for response randomness (0.0-2.0)")
                .defineInRange("temperature", 0.7, 0.0, 2.0);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
    }
}