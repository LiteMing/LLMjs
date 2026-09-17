// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmProviderWireTest {
    @Test
    void mergesOnlyConsecutiveLeadingTextSystemMessages() {
        List<LlmMessage> normalized = LlmProviderWire.mergeLeadingSystemMessages(List.of(
                new LlmMessage("system", "one"),
                new LlmMessage("system", "two"),
                new LlmMessage("user", "question"),
                new LlmMessage("system", "late")));

        assertEquals(3, normalized.size());
        assertEquals("one\n\ntwo", normalized.get(0).content());
        assertEquals("user", normalized.get(1).role());
        assertEquals("system", normalized.get(2).role());
        assertEquals("late", normalized.get(2).content());
    }

    @Test
    void mapsOnePreparedPromptToLegalProviderSpecificSystemBoundaries() {
        LlmRequest request = request();
        LlmRequest prepared = LlmProviderWire.prepareRequest(request);
        LlmResolvedParameters parameters = new LlmResolvedParameters("provider", 0.0, 64, 10,
                1_000, 64, null);

        JsonObject openAi = LlmOrchestrator.buildBody("openai", spec("openai"), prepared, parameters);
        assertEquals(3, openAi.getAsJsonArray("messages").size());
        assertEquals("one\n\ntwo", openAi.getAsJsonArray("messages").get(0).getAsJsonObject()
                .get("content").getAsString());
        assertEquals("system", openAi.getAsJsonArray("messages").get(2).getAsJsonObject()
                .get("role").getAsString());

        JsonObject anthropic = LlmOrchestrator.buildBody("anthropic", spec("anthropic"), prepared, parameters);
        assertEquals("one\n\ntwo", anthropic.get("system").getAsString());
        assertEquals(2, anthropic.getAsJsonArray("messages").size());
        assertEquals("[System]\nlate", anthropic.getAsJsonArray("messages").get(1).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());

        JsonObject gemini = LlmOrchestrator.buildBody("gemini", spec("gemini"), prepared, parameters);
        assertEquals("one\n\ntwo", gemini.getAsJsonObject("systemInstruction").getAsJsonArray("parts")
                .get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(2, gemini.getAsJsonArray("contents").size());
        assertEquals("[System]\nlate", gemini.getAsJsonArray("contents").get(1).getAsJsonObject()
                .getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());

        assertEquals(request.typedEntries(), prepared.typedEntries());
        assertNotEquals(request.wireDiagnostics().finalMessageShapeHash(),
                prepared.wireDiagnostics().finalMessageShapeHash());
    }

    private static LlmRequest request() {
        LlmMessageFinalization finalization = LlmMessageFinalizer.finalize(new LlmMessageDraft(List.of(
                entry("one", "system", "one", LlmPromptStability.GLOBAL_STATIC),
                entry("two", "system", "two", LlmPromptStability.PRESET_STATIC),
                entry("question", "user", "question", LlmPromptStability.TURN_DYNAMIC),
                entry("late", "system", "late", LlmPromptStability.TURN_DYNAMIC))),
                10_000, LlmMessageFinalizer.CONSERVATIVE_ESTIMATOR);
        return LlmRequest.routed(finalization, LlmRequestContext.chat());
    }

    private static LlmMessageDraft.Entry entry(String id, String role, String content,
            LlmPromptStability stability) {
        return new LlmMessageDraft.Entry(id, "test/" + id, new LlmMessage(role, content), true, 1, stability);
    }

    private static ProviderSpec spec(String format) {
        return new ProviderSpec("provider", format, "http://localhost", "model", 0.0, 64, List.of());
    }
}
