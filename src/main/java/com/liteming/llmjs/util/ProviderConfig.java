package com.liteming.llmjs.util;

import com.liteming.llmjs.config.LLMConfig;

public class ProviderConfig {
    public final String name;
    public final String url;
    public final String key;
    public final String model;
    
    public ProviderConfig(String name, String url, String key, String model) {
        this.name = name;
        this.url = url;
        this.key = key;
        this.model = model;
    }
    
    public boolean isValid() {
        return url != null && !url.isEmpty() && 
               key != null && !key.isEmpty() && !key.equals("your-api-key-here") &&
               !key.equals("your-openai-key") && !key.equals("your-claude-key") &&
               !key.equals("your-custom1-key") && !key.equals("your-custom2-key") &&
               model != null && !model.isEmpty();
    }
    
    public static ProviderConfig getDefault() {
        return new ProviderConfig(
            "default",
            LLMConfig.DEFAULT_API_URL.get(),
            LLMConfig.DEFAULT_API_KEY.get(),
            LLMConfig.DEFAULT_MODEL.get()
        );
    }
    
    public static ProviderConfig getOpenAI() {
        return new ProviderConfig(
            "openai",
            LLMConfig.OPENAI_URL.get(),
            LLMConfig.OPENAI_KEY.get(),
            LLMConfig.OPENAI_MODEL.get()
        );
    }
    
    public static ProviderConfig getClaude() {
        return new ProviderConfig(
            "claude",
            LLMConfig.CLAUDE_URL.get(),
            LLMConfig.CLAUDE_KEY.get(),
            LLMConfig.CLAUDE_MODEL.get()
        );
    }
    
    public static ProviderConfig getLocal() {
        return new ProviderConfig(
            "local",
            LLMConfig.LOCAL_URL.get(),
            LLMConfig.LOCAL_KEY.get(),
            LLMConfig.LOCAL_MODEL.get()
        );
    }
    
    public static ProviderConfig getCustom1() {
        return new ProviderConfig(
            LLMConfig.CUSTOM1_NAME.get(),
            LLMConfig.CUSTOM1_URL.get(),
            LLMConfig.CUSTOM1_KEY.get(),
            LLMConfig.CUSTOM1_MODEL.get()
        );
    }
    
    public static ProviderConfig getCustom2() {
        return new ProviderConfig(
            LLMConfig.CUSTOM2_NAME.get(),
            LLMConfig.CUSTOM2_URL.get(),
            LLMConfig.CUSTOM2_KEY.get(),
            LLMConfig.CUSTOM2_MODEL.get()
        );
    }
    
    public static ProviderConfig getByName(String name) {
        switch (name.toLowerCase()) {
            case "default":
                return getDefault();
            case "openai":
                return getOpenAI();
            case "claude":
                return getClaude();
            case "local":
                return getLocal();
            case "custom1":
                return getCustom1();
            case "custom2":
                return getCustom2();
            default:
                return getDefault();
        }
    }
}