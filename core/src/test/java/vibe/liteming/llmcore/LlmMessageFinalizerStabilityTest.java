// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmMessageFinalizerStabilityTest {
    private static final LlmMessageFinalizer.TokenEstimator TEXT_LENGTH = message -> message.content().length();

    @Test
    void preservesEveryStabilityLevelThroughEntriesAndDecisions() {
        List<LlmMessageDraft.Entry> entries = Arrays.stream(LlmPromptStability.values())
                .map(level -> new LlmMessageDraft.Entry(level.name(), "test", new LlmMessage("system", "x"),
                        false, 1, level))
                .toList();

        LlmMessageFinalization result = LlmMessageFinalizer.finalize(
                new LlmMessageDraft(entries), 10, TEXT_LENGTH);

        assertEquals(List.of(LlmPromptStability.values()),
                result.entries().stream().map(LlmMessageFinalization.FinalEntry::stability).toList());
        assertEquals(List.of(LlmPromptStability.values()),
                result.decisions().stream().map(LlmMessageFinalization.Decision::stability).toList());
    }

    @Test
    void dynamicLengthCannotChangeEarlierStableSelectionOrPrefixHash() {
        LlmMessageFinalization shortDynamic = finalizeWithDynamic("dddd");
        LlmMessageFinalization longDynamic = finalizeWithDynamic("dddddddd");

        assertTrue(shortDynamic.decisions().get(0).included());
        assertTrue(longDynamic.decisions().get(0).included());
        assertTrue(shortDynamic.decisions().get(1).included());
        assertFalse(longDynamic.decisions().get(1).included());
        assertEquals(shortDynamic.wireDiagnostics().stablePrefixHash(),
                longDynamic.wireDiagnostics().stablePrefixHash());
        assertEquals(4, shortDynamic.wireDiagnostics().stablePrefixEstimatedTokens());
        assertEquals("dynamic", shortDynamic.wireDiagnostics().firstDynamicEntryId());
        assertEquals(List.of("stable", "dynamic"), shortDynamic.entries().stream()
                .map(LlmMessageFinalization.FinalEntry::entryId).toList());
        LlmMessageFinalization repeated = finalizeWithDynamic("dddd");
        assertEquals(shortDynamic.decisions(), repeated.decisions());
        assertEquals(shortDynamic.messages().stream().map(LlmMessage::content).toList(),
                repeated.messages().stream().map(LlmMessage::content).toList());
        assertEquals(shortDynamic.wireDiagnostics(), repeated.wireDiagnostics());
    }

    @Test
    void diagnosticsHashContentWithoutRetainingPromptText() {
        LlmMessageFinalization first = finalizeWithDynamic("player secret one", 100);
        LlmMessageFinalization second = finalizeWithDynamic("player secret two", 100);

        assertNotEquals(first.wireDiagnostics().finalMessageShapeHash(),
                second.wireDiagnostics().finalMessageShapeHash());
        assertEquals(first.wireDiagnostics().stablePrefixHash(), second.wireDiagnostics().stablePrefixHash());
        assertFalse(first.wireDiagnostics().toString().contains("player secret"));
        assertEquals(64, first.wireDiagnostics().finalMessageShapeHash().length());
        assertEquals(64, first.wireDiagnostics().stablePrefixHash().length());
    }

    private static LlmMessageFinalization finalizeWithDynamic(String dynamic) {
        return finalizeWithDynamic(dynamic, 8);
    }

    private static LlmMessageFinalization finalizeWithDynamic(String dynamic, int budget) {
        return LlmMessageFinalizer.finalize(new LlmMessageDraft(List.of(
                new LlmMessageDraft.Entry("stable", "test/stable", new LlmMessage("system", "ssss"),
                        false, 10, LlmPromptStability.NPC_STABLE),
                new LlmMessageDraft.Entry("dynamic", "test/dynamic", new LlmMessage("user", dynamic),
                        false, 100, LlmPromptStability.TURN_DYNAMIC))), budget, TEXT_LENGTH);
    }
}
