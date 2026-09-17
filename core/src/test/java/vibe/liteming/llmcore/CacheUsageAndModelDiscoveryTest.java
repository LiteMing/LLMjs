// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CacheUsageAndModelDiscoveryTest {
    @Test
    void normalizesProviderReportedCacheUsageWithoutGuessingMissingFields() {
        var openAi = LlmOrchestrator.parseCacheUsage("openai", JsonParser.parseString("""
                {"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":60}}
                """).getAsJsonObject(), 100);
        var deepSeek = LlmOrchestrator.parseCacheUsage("openai", JsonParser.parseString("""
                {"prompt_tokens":100,"prompt_cache_hit_tokens":70,"prompt_cache_miss_tokens":30}
                """).getAsJsonObject(), 100);
        var anthropic = LlmOrchestrator.parseCacheUsage("claude", JsonParser.parseString("""
                {"input_tokens":20,"cache_read_input_tokens":70,"cache_creation_input_tokens":10}
                """).getAsJsonObject(), 20);
        var gemini = LlmOrchestrator.parseCacheUsage("gemini", JsonParser.parseString("""
                {"promptTokenCount":100,"cachedContentTokenCount":40}
                """).getAsJsonObject(), 100);
        var unknown = LlmOrchestrator.parseCacheUsage("openai",
                JsonParser.parseString("{\"prompt_tokens\":100}").getAsJsonObject(), 100);

        assertEquals(0.6D, openAi.hitRatio());
        assertEquals(70L, deepSeek.cacheReadInputTokens());
        assertEquals(10L, anthropic.cacheWriteInputTokens());
        assertEquals(60L, gemini.uncachedInputTokens());
        assertEquals(LlmCacheUsage.Status.UNKNOWN, unknown.status());
        assertNull(unknown.hitRatio());
    }

    @Test
    void cacheCostRequiresConfiguredReadAndWriteRates() {
        LlmCacheUsage usage = LlmCacheUsage.reported(60L, 10L, 30L, 100L);
        assertNull(new LlmCostRate(1, 2).weightedTokens(usage, 100, 5));
        assertEquals(56L, new LlmCostRate(1, 2, 0.1, 1.0).weightedTokens(usage, 100, 5));
    }

    @Test
    void derivesAndParsesCommonModelCatalogEndpoints() {
        assertEquals("https://api.deepseek.com/models", ProviderModelDiscovery.modelUri("openai",
                "https://api.deepseek.com/chat/completions").toString());
        assertEquals("https://api.anthropic.com/v1/models", ProviderModelDiscovery.modelUri("claude",
                "https://api.anthropic.com/v1/messages").toString());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models", ProviderModelDiscovery.modelUri("gemini",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5:generateContent").toString());
        var result = ProviderModelDiscovery.parse("gemini", 200,
                "{\"models\":[{\"name\":\"models/gemini-2.5-flash\"}]}".getBytes(StandardCharsets.UTF_8));
        assertTrue(result.success());
        assertEquals(java.util.List.of("gemini-2.5-flash"), result.models());
    }
}
