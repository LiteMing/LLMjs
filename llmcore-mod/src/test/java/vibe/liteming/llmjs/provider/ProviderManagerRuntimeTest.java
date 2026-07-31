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

        manager.close();

        assertTrue(SharedLlmRuntime.current().isEmpty());
        manager = null;
    }
}
