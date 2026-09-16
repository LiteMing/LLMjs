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
import vibe.liteming.llmcore.LlmCacheUsage;
import vibe.liteming.llmcore.LlmCostRate;
import vibe.liteming.llmcore.LlmOrchestrator;
import vibe.liteming.llmcore.LlmRequestLogger;
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
        assertEquals(List.of("qwen"), published.getRoutingConfig().purposeChains().get("CHAT"));
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
        metrics.record(now - 3_600_001L, 900L, true, "");
        metrics.record(now - 1000L, 100L, true, "");
        metrics.record(now, 300L, false, "boom");

        var json = metrics.toJson(now);
        assertEquals(2, json.get("requests1h").getAsInt());
        assertEquals(0.5D, json.get("successRate1h").getAsDouble());
        assertEquals(200L, json.get("averageLatency1hMs").getAsLong());
        assertFalse(json.get("cacheSupported").getAsBoolean());
        assertEquals("boom", json.get("lastError").getAsString());
    }

    @Test
    void cacheMetricsAggregateByPurposeAndDomainWithBoundedProtocolPayload() {
        ProviderManager.CacheUsageMetrics metrics = new ProviderManager.CacheUsageMetrics();
        long now = 10_000_000L;
        for (int index = 0; index < 40; index++) {
            String purpose = index == 0 ? "CHAT" : "PURPOSE_" + index;
            String domain = "domain-" + index;
            metrics.record(now, new LlmRequestLogger.AttemptEvent(purpose, "request-" + index,
                    "provider", "model", "credential", true, 10L, "", "stop",
                    "provider > fallback", "provider/model", domain,
                    index == 0 ? LlmCacheUsage.reported(75L, null, 25L, 100L)
                            : index == 1 ? LlmCacheUsage.unsupported("automatic-only adapter")
                            : LlmCacheUsage.unknown("usage missing")));
        }

        var snapshot = metrics.toJson(now, Map.of("provider", new LlmCostRate(1, 1, 0.25, null)));
        assertEquals(40, snapshot.get("samples").getAsInt());
        assertEquals(16, snapshot.getAsJsonArray("entries").size());
        assertEquals(24, snapshot.get("truncatedGroups").getAsInt());
        var chat = snapshot.getAsJsonArray("entries").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(value -> value.get("purpose").getAsString().equals("CHAT"))
                .findFirst().orElseThrow();
        assertEquals(1, chat.get("reportedRequests").getAsInt());
        assertEquals(75L, chat.get("cacheReadInputTokens").getAsLong());
        assertEquals(100L, chat.get("totalInputTokens").getAsLong());
        assertEquals(0.75D, chat.get("hitRatio").getAsDouble());
        assertEquals(44L, chat.get("cacheAwareInputCostUnits").getAsLong());
        String payload = snapshot.toString();
        assertTrue(payload.length() < 32_767);
        assertFalse(payload.contains("request-0"));
        assertFalse(payload.contains("credential"));
    }
}
