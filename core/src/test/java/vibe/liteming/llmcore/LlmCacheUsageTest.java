// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmCacheUsageTest {
    @Test
    void parsesOpenAiAndCompatibleFieldsWithoutInventingMissingDimensions() {
        LlmCacheUsage openAi = parse("openai", """
                {"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":75}}
                """);
        assertEquals(LlmCacheUsage.Status.REPORTED, openAi.status());
        assertEquals(75L, openAi.cacheReadInputTokens());
        assertNull(openAi.cacheWriteInputTokens());
        assertEquals(25L, openAi.uncachedInputTokens());
        assertEquals(100L, openAi.totalInputTokens());
        assertEquals(0.75D, openAi.hitRatio());

        LlmCacheUsage partial = parse("openai", """
                {"prompt_tokens":100,"prompt_cache_hit_tokens":60}
                """);
        assertEquals(60L, partial.cacheReadInputTokens());
        assertNull(partial.uncachedInputTokens());
        assertEquals(100L, partial.totalInputTokens());
    }

    @Test
    void parsesAnthropicAndGeminiWithoutConvertingAbsentFieldsToZero() {
        LlmCacheUsage anthropic = parse("anthropic", """
                {"input_tokens":20,"cache_read_input_tokens":70,"cache_creation_input_tokens":10}
                """);
        assertEquals(LlmCacheUsage.reported(70L, 10L, 20L, 100L), anthropic);

        LlmCacheUsage partial = parse("claude", """
                {"input_tokens":20,"cache_read_input_tokens":70}
                """);
        assertEquals(70L, partial.cacheReadInputTokens());
        assertNull(partial.cacheWriteInputTokens());
        assertEquals(20L, partial.uncachedInputTokens());
        assertNull(partial.totalInputTokens());

        LlmCacheUsage gemini = parse("gemini", """
                {"promptTokenCount":120,"cachedContentTokenCount":80}
                """);
        assertEquals(LlmCacheUsage.reported(80L, null, 40L, 120L), gemini);
    }

    @Test
    void malformedMissingAndUnsupportedUsageRemainExplicit() {
        for (String json : new String[] {
                "{\"prompt_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":-1}}",
                "{\"prompt_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":\"bad\"}}"}) {
            LlmCacheUsage usage = parse("openai", json);
            assertEquals(LlmCacheUsage.Status.UNKNOWN, usage.status());
            assertTrue(usage.reason().startsWith("invalid cache usage:"));
        }
        assertEquals(LlmCacheUsage.Status.UNKNOWN, parse("openai", "{\"prompt_tokens\":10}").status());
        assertEquals(LlmCacheUsage.Status.UNKNOWN, LlmOrchestrator.parseCacheUsage("gemini", null).status());
        assertEquals(LlmCacheUsage.Status.UNSUPPORTED, parse("custom", "{}").status());
        assertThrows(IllegalArgumentException.class,
                () -> LlmCacheUsage.reported(-1L, null, null, null));
    }

    private static LlmCacheUsage parse(String format, String json) {
        JsonObject usage = JsonParser.parseString(json).getAsJsonObject();
        return LlmOrchestrator.parseCacheUsage(format, usage);
    }
}
