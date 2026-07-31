package vibe.liteming.llmcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsMultipleCredentialSlotsWithoutExposingThemInProviderConfig() throws Exception {
        Path providers = tempDir.resolve("providers.json");
        Path secret = tempDir.resolve("llmcore.secret");
        Files.writeString(providers, """
                {"fast":{"format":"openai","url":"http://localhost/test","model":"test-model","context_window_tokens":32768}}
                """);
        Files.writeString(secret, """
                {"providers":{"fast":{"keys":[{"id":"primary","key":"sk-one"},{"id":"secondary","key":"sk-two"}]}}}
                """);

        Map<String, ProviderSpec> result = ProviderConfigLoader.load(null, providers, secret);

        assertEquals(2, result.get("fast").credentials().size());
        assertEquals("primary", result.get("fast").credentials().get(0).id());
        assertEquals("secondary", result.get("fast").credentials().get(1).id());
        assertEquals(32768, result.get("fast").contextWindowTokens());
    }

    @Test
    void capabilityProfilesAreTextOnlyUnlessExplicitlyDeclared() throws Exception {
        Path providers = tempDir.resolve("providers.json");
        Path secret = tempDir.resolve("llmcore.secret");
        Files.writeString(providers, """
                {
                  "deepseek":{"format":"openai","url":"http://localhost/deepseek","model":"deepseek-chat"},
                  "qwen":{"format":"openai","url":"http://localhost/qwen","model":"qwen-plus","capabilities":{
                    "input":["text","image"],"output":["text"],
                    "webSearch":{"enabled":true,"adapter":"dashscope_openai_chat_web_search"}
                  }}
                }
                """);
        Files.writeString(secret, """
                {"providers":{"deepseek":"sk-deepseek","qwen":"sk-qwen"}}
                """);

        Map<String, ProviderProfile> profiles = ProviderConfigLoader.loadProfiles(null, providers, secret);

        assertFalse(profiles.get("deepseek").capabilities().webSearch().enabled());
        assertEquals(Set.of("text"), profiles.get("deepseek").capabilities().inputModalities());
        assertTrue(profiles.get("qwen").capabilities().webSearch().enabled());
        assertEquals(HostedWebSearchAdapterIds.DASHSCOPE_OPENAI_CHAT,
                profiles.get("qwen").capabilities().webSearch().adapterId());
        assertEquals(Set.of("text", "image"), profiles.get("qwen").capabilities().inputModalities());
    }

    @Test
    void invalidCapabilityMetadataFailsClosedWithDiagnostics() throws Exception {
        Path providers = tempDir.resolve("providers.json");
        Path secret = tempDir.resolve("llmcore.secret");
        Files.writeString(providers, """
                {"mimo":{"format":"openai","url":"http://localhost/mimo","model":"mimo",
                  "capabilities":{"webSearch":{"enabled":true,"adapter":"vendor","unexpected":true}}}}
                """);
        Files.writeString(secret, "{\"providers\":{\"mimo\":\"sk-mimo\"}}");
        List<String> diagnostics = new ArrayList<>();

        ProviderProfile profile = ProviderConfigLoader.loadProfiles(
                null, providers, secret, diagnostics::add).get("mimo");

        assertFalse(profile.capabilities().webSearch().enabled());
        assertEquals(Set.of("text"), profile.capabilities().inputModalities());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("unexpected"));
    }

    @Test
    void serverDefinitionOverridesGlobalProfileAndSecretOnlyProviderCanDeclareCapabilities() throws Exception {
        Path global = tempDir.resolve("global.json");
        Path server = tempDir.resolve("server.json");
        Path secret = tempDir.resolve("llmcore.secret");
        Files.writeString(global, """
                {"qwen":{"format":"openai","url":"http://global/qwen","model":"qwen",
                  "capabilities":{"webSearch":{"enabled":true,"adapter":"openai_chat_web_search"}}}}
                """);
        Files.writeString(server, """
                {"qwen":{"format":"openai","url":"http://server/qwen","model":"qwen",
                  "capabilities":{"webSearch":{"enabled":true,"adapter":"dashscope_openai_chat_web_search"}}}}
                """);
        Files.writeString(secret, """
                {"providers":{
                  "qwen":"sk-qwen",
                  "hosted-deepseek":{"format":"openai","url":"http://third-party/deepseek","model":"deepseek-v3","key":"sk-hosted",
                    "capabilities":{"webSearch":{"enabled":true,"adapter":"vendor_search_v1"}}}
                }}
                """);

        Map<String, ProviderProfile> profiles = ProviderConfigLoader.loadProfiles(global, server, secret);

        assertEquals(HostedWebSearchAdapterIds.DASHSCOPE_OPENAI_CHAT,
                profiles.get("qwen").capabilities().webSearch().adapterId());
        assertEquals("vendor_search_v1",
                profiles.get("hosted-deepseek").capabilities().webSearch().adapterId());
        LlmOrchestrator orchestrator = new LlmOrchestrator(ProviderConfigLoader.load(global, server, secret));
        orchestrator.replaceProviderProfiles(profiles);
        assertFalse(orchestrator.isProviderWebSearchCapable("hosted-deepseek"));
    }
}
