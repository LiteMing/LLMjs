package com.liteming.llmjs.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

public class LLMConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    // 默认配置
    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_API_URL;
    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_MODEL;
    public static final ForgeConfigSpec.IntValue DEFAULT_TIMEOUT;
    public static final ForgeConfigSpec.IntValue DEFAULT_MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue DEFAULT_TEMPERATURE;
    
    // 多个API提供商配置
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PROVIDERS;
    
    // OpenAI配置
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_URL;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_MODEL;
    
    // Claude配置
    public static final ForgeConfigSpec.ConfigValue<String> CLAUDE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> CLAUDE_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> CLAUDE_MODEL;
    
    // 本地模型配置
    public static final ForgeConfigSpec.ConfigValue<String> LOCAL_URL;
    public static final ForgeConfigSpec.ConfigValue<String> LOCAL_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> LOCAL_MODEL;
    
    // 自定义提供商1
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM1_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM1_URL;
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM1_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM1_MODEL;
    
    // 自定义提供商2
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM2_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM2_URL;
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM2_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM2_MODEL;
    
    static {
        BUILDER.push("Default Settings");
        
        DEFAULT_API_URL = BUILDER
                .comment("Default API endpoint URL")
                .define("default_api_url", "https://api.openai.com/v1/chat/completions");
                
        DEFAULT_API_KEY = BUILDER
                .comment("Default API key")
                .define("default_api_key", "your-api-key-here");
                
        DEFAULT_MODEL = BUILDER
                .comment("Default model name")
                .define("default_model", "gpt-3.5-turbo");
                
        DEFAULT_TIMEOUT = BUILDER
                .comment("Default request timeout in seconds")
                .defineInRange("default_timeout", 30, 1, 300);
                
        DEFAULT_MAX_TOKENS = BUILDER
                .comment("Default maximum tokens in response")
                .defineInRange("default_max_tokens", 1000, 1, 4096);
                
        DEFAULT_TEMPERATURE = BUILDER
                .comment("Default temperature for response randomness (0.0-2.0)")
                .defineInRange("default_temperature", 0.7, 0.0, 2.0);
        
        PROVIDERS = BUILDER
                .comment("Enabled providers (openai, claude, local, custom1, custom2)")
                .defineList("enabled_providers", List.of("openai"), obj -> obj instanceof String);
        
        BUILDER.pop();
        
        // OpenAI配置
        BUILDER.push("OpenAI");
        
        OPENAI_URL = BUILDER
                .comment("OpenAI API endpoint")
                .define("openai_url", "https://api.openai.com/v1/chat/completions");
                
        OPENAI_KEY = BUILDER
                .comment("OpenAI API key")
                .define("openai_key", "your-openai-key");
                
        OPENAI_MODEL = BUILDER
                .comment("OpenAI model name")
                .define("openai_model", "gpt-3.5-turbo");
        
        BUILDER.pop();
        
        // Claude配置
        BUILDER.push("Claude");
        
        CLAUDE_URL = BUILDER
                .comment("Claude API endpoint")
                .define("claude_url", "https://api.anthropic.com/v1/messages");
                
        CLAUDE_KEY = BUILDER
                .comment("Claude API key")
                .define("claude_key", "your-claude-key");
                
        CLAUDE_MODEL = BUILDER
                .comment("Claude model name")
                .define("claude_model", "claude-3-haiku-20240307");
        
        BUILDER.pop();
        
        // 本地模型配置
        BUILDER.push("Local");
        
        LOCAL_URL = BUILDER
                .comment("Local model API endpoint")
                .define("local_url", "http://localhost:11434/v1/chat/completions");
                
        LOCAL_KEY = BUILDER
                .comment("Local model API key (if required)")
                .define("local_key", "not-required");
                
        LOCAL_MODEL = BUILDER
                .comment("Local model name")
                .define("local_model", "llama2");
        
        BUILDER.pop();
        
        // 自定义提供商1
        BUILDER.push("Custom Provider 1");
        
        CUSTOM1_NAME = BUILDER
                .comment("Custom provider 1 name")
                .define("custom1_name", "custom1");
                
        CUSTOM1_URL = BUILDER
                .comment("Custom provider 1 API endpoint")
                .define("custom1_url", "https://api.example.com/v1/chat/completions");
                
        CUSTOM1_KEY = BUILDER
                .comment("Custom provider 1 API key")
                .define("custom1_key", "your-custom1-key");
                
        CUSTOM1_MODEL = BUILDER
                .comment("Custom provider 1 model name")
                .define("custom1_model", "custom-model");
        
        BUILDER.pop();
        
        // 自定义提供商2
        BUILDER.push("Custom Provider 2");
        
        CUSTOM2_NAME = BUILDER
                .comment("Custom provider 2 name")
                .define("custom2_name", "custom2");
                
        CUSTOM2_URL = BUILDER
                .comment("Custom provider 2 API endpoint")
                .define("custom2_url", "https://api.example2.com/v1/chat/completions");
                
        CUSTOM2_KEY = BUILDER
                .comment("Custom provider 2 API key")
                .define("custom2_key", "your-custom2-key");
                
        CUSTOM2_MODEL = BUILDER
                .comment("Custom provider 2 model name")
                .define("custom2_model", "custom-model-2");
        
        BUILDER.pop();
        
        SPEC = BUILDER.build();
    }
    
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
    }
}