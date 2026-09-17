// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmjs.provider;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vibe.liteming.llmcore.CapabilityPolicyStore;
import vibe.liteming.llmcore.HostedWebSearchAdapterIds;
import vibe.liteming.llmcore.LlmCapabilityPolicy;
import vibe.liteming.llmcore.LlmOrchestrator;
import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.RoutingConfigStore;
import vibe.liteming.llmcore.SharedLlmRuntime;
import vibe.liteming.llmjs.config.GlobalConfig;
import vibe.liteming.llmjs.config.LLMConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProviderManagerRuntimeTest {
    @TempDir
    Path tempDir;

    private ProviderManager manager;

    @AfterEach
    void closeManager() {
        if (manager != null) manager.close();
        SharedLlmRuntime.current().ifPresent(SharedLlmRuntime::clear);
        LLMConfig.SPEC.setConfig(null);
    }

    @Test
    void publishesOnlyTheFullyConfiguredCandidateAndClearsItOnClose() throws Exception {
        GlobalConfig.init(tempDir);
        Files.writeString(GlobalConfig.getGlobalProvidersFile(), """
                {"qwen":{"format":"openai","url":"http://localhost/qwen","model":"qwen-plus",
                  "capabilities":{"webSearch":{"enabled":true,
                    "adapter":"dashscope_openai_chat_web_search"}}}}
                """);
        Files.writeString(tempDir.resolve("llmcore.secret"),
                "{\"providers\":{\"qwen\":\"sk-qwen\"}}");
        Path routingFile = GlobalConfig.getGlobalDir().resolve("routing.json");
        RoutingConfigStore.save(routingFile, new PriorityRoutingConfig(
                Map.of("CHAT", List.of("qwen")), List.of("qwen")));
        CapabilityPolicyStore.save(GlobalConfig.getGlobalDir().resolve("capability-policy.json"),
                LlmCapabilityPolicy.allowingWebSearch(List.of("CHAT")));
        CommentedConfig forgeConfig = CommentedConfig.inMemory();
        LLMConfig.SPEC.correct(forgeConfig);
        LLMConfig.SPEC.setConfig(forgeConfig);
        manager = new ProviderManager();

        manager.init(tempDir.resolve("serverconfig"), tempDir);

        LlmOrchestrator published = SharedLlmRuntime.current().orElseThrow();
        assertSame(published, SharedLlmRuntime.current().orElseThrow());
        assertEquals(List.of("qwen/qwen-plus"), published.getRoutingConfig().purposeChains().get("CHAT"));
        assertTrue(published.isWebSearchAllowed("CHAT"));
        assertTrue(published.isProviderWebSearchCapable("qwen"));
        assertEquals(HostedWebSearchAdapterIds.DASHSCOPE_OPENAI_CHAT,
                published.getProviderProfile("qwen").capabilities().webSearch().adapterId());

        Path serverProviders = tempDir.resolve("serverconfig/llmcore/providers.json");
        Files.writeString(serverProviders, """
                {"qwen":{"format":"openai","url":"http://localhost/server","model":"server-model",
                  "billing":{"inputMultiplier":2,"outputMultiplier":3}}}
                """);
        manager.reload();
        assertTrue(vibe.liteming.llmjs.config.ProviderLoader.updateWithoutKey("qwen",
                "http://localhost/server", "updated-model", "openai", new vibe.liteming.llmcore.LlmCostRate(4, 5)));
        manager.reload();
        published = SharedLlmRuntime.current().orElseThrow();
        assertEquals(new vibe.liteming.llmcore.LlmCostRate(4, 5), published.getProviderProfile("qwen").costRate());
        assertEquals("updated-model", published.getProviderSpec("qwen").model());
        assertTrue(Files.readString(GlobalConfig.getGlobalProvidersFile()).contains("qwen-plus"));
        Files.writeString(serverProviders, Files.readString(serverProviders).replace("4.0", "-4.0"));
        manager.reload();
        assertSame(published, SharedLlmRuntime.current().orElseThrow());
        assertEquals(new vibe.liteming.llmcore.LlmCostRate(4, 5), published.getProviderProfile("qwen").costRate());
        manager.close();

        assertTrue(SharedLlmRuntime.current().isEmpty());
        manager = null;
    }

    @Test
    void rollingMetricsExpireOldSamplesAndExposeRealAverage() {
        ProviderManager.RuntimeMetrics metrics = new ProviderManager.RuntimeMetrics();
        long now = 10_000_000L;
        metrics.record(now - 3_600_001L, 900L, true, "", vibe.liteming.llmcore.LlmCacheUsage.unknown("old"));
        metrics.record(now - 1000L, 100L, true, "", vibe.liteming.llmcore.LlmCacheUsage.reported(60L, 0L, 40L, 100L));
        metrics.record(now, 300L, false, "boom", vibe.liteming.llmcore.LlmCacheUsage.unknown("failed"));

        var json = metrics.toJson(now);
        assertEquals(2, json.get("requests1h").getAsInt());
        assertEquals(0.5D, json.get("successRate1h").getAsDouble());
        assertEquals(100L, json.get("averageLatency1hMs").getAsLong());
        assertEquals(0.6D, json.get("cacheHitRatio1h").getAsDouble());
        assertEquals(60L, json.get("cacheReadInputTokens1h").getAsLong());
        assertEquals("boom", json.get("lastError").getAsString());
    }
}
