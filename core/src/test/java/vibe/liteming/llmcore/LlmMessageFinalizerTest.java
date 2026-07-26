package vibe.liteming.llmcore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmMessageFinalizerTest {
    private static final LlmMessageFinalizer.TokenEstimator CONTENT_LENGTH = message -> message.content().length();

    @Test
    void keepsRequiredAndSelectsOptionalByPriorityWithoutReorderingMessages() {
        LlmMessageDraft draft = new LlmMessageDraft(List.of(
                entry("required", "AAAA", true, 0),
                entry("low", "BBBB", false, 10),
                entry("high", "CCCC", false, 100)));

        LlmMessageFinalization result = LlmMessageFinalizer.finalize(draft, 8, CONTENT_LENGTH);

        assertEquals(List.of("AAAA", "CCCC"), result.messages().stream().map(LlmMessage::content).toList());
        assertEquals(List.of(true, false, true),
                result.decisions().stream().map(LlmMessageFinalization.Decision::included).toList());
        assertEquals("budget_excluded", result.decisions().get(1).reason());
        assertTrue(result.withinBudget());
    }

    @Test
    void reportsRequiredOverBudgetInsteadOfSilentlyDroppingIt() {
        LlmMessageDraft draft = new LlmMessageDraft(List.of(entry("focus", "required", true, 1000)));

        LlmMessageFinalization result = LlmMessageFinalizer.finalize(draft, 2, CONTENT_LENGTH);

        assertEquals(1, result.messages().size());
        assertEquals("required_over_budget", result.decisions().get(0).reason());
        assertFalse(result.withinBudget());
    }

    private static LlmMessageDraft.Entry entry(String id, String content, boolean required, int priority) {
        return new LlmMessageDraft.Entry(id, "test", new LlmMessage("user", content), required, priority);
    }
}
