package vibe.liteming.llmcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityPolicyStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void missingOrMalformedPolicyFailsClosedWithDiagnostics() throws Exception {
        Path file = tempDir.resolve("capability-policy.json");
        assertFalse(CapabilityPolicyStore.load(file).allowsWebSearch("SUPERVISOR_REVIEW"));

        Files.writeString(file, "{\"schemaVersion\":1,\"purposes\":[]}");
        List<String> diagnostics = new ArrayList<>();
        LlmCapabilityPolicy loaded = CapabilityPolicyStore.load(file, diagnostics::add);

        assertFalse(loaded.allowsWebSearch("SUPERVISOR_REVIEW"));
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("purposes"));
    }

    @Test
    void savesAndRestoresOnlyExplicitPurposeAuthorization() {
        Path file = tempDir.resolve("capability-policy.json");
        LlmCapabilityPolicy policy = LlmCapabilityPolicy.empty()
                .withWebSearchAllowed("SUPERVISOR_REVIEW", true);

        assertTrue(CapabilityPolicyStore.save(file, policy));
        LlmCapabilityPolicy loaded = CapabilityPolicyStore.load(file);

        assertEquals(policy, loaded);
        assertTrue(loaded.allowsWebSearch("SUPERVISOR_REVIEW"));
        assertFalse(loaded.allowsWebSearch("CHAT"));
        assertTrue(CapabilityPolicyStore.toJsonString(loaded).contains("\"schemaVersion\": 1"));
    }

    @Test
    void rejectsUnknownFieldsAndNonBooleanSwitches() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> CapabilityPolicyStore.parse("""
                        {"schemaVersion":1,"purposes":{},"webSearch":true}
                        """));
        assertTrue(unknown.getMessage().contains("unknown field"));

        IllegalArgumentException type = assertThrows(IllegalArgumentException.class,
                () -> CapabilityPolicyStore.parse("""
                        {"schemaVersion":1,"purposes":{"CHAT":{"webSearch":"yes"}}}
                        """));
        assertTrue(type.getMessage().contains("webSearch must be a boolean"));
    }

    @Test
    void disablingPurposeRemovesAuthorizationAndChangesFingerprint() {
        LlmCapabilityPolicy disabled = LlmCapabilityPolicy.empty();
        LlmCapabilityPolicy enabled = disabled.withWebSearchAllowed("CHAT", true);

        assertNotEquals(CapabilityPolicyStore.fingerprint(disabled),
                CapabilityPolicyStore.fingerprint(enabled));
        assertEquals(disabled, enabled.withWebSearchAllowed("CHAT", false));
    }

    @Test
    void orchestratorPolicyDoesNotAlterLegacyRequests() {
        LlmOrchestrator orchestrator = new LlmOrchestrator(java.util.Map.of());
        assertFalse(orchestrator.isWebSearchAllowed("CHAT"));

        orchestrator.setCapabilityPolicy(LlmCapabilityPolicy.empty()
                .withWebSearchAllowed("CHAT", true));

        assertTrue(orchestrator.isWebSearchAllowed("CHAT"));
        assertEquals(List.of(), orchestrator.resolveChain(
                LlmRequest.routed(List.of(), LlmRequestContext.chat())));
    }
}
