package vibe.liteming.llmcore;

import java.util.List;

/** Final messages plus a stable decision for every draft entry. */
public record LlmMessageFinalization(
        List<LlmMessage> messages,
        List<Decision> decisions,
        int hardBudgetTokens,
        int estimatedInputTokens,
        boolean withinBudget) {

    public LlmMessageFinalization {
        messages = messages == null ? List.of() : List.copyOf(messages);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public record Decision(
            int index,
            String entryId,
            String provenance,
            boolean included,
            String reason,
            int estimatedTokens,
            boolean required,
            int priority) {
        public Decision {
            entryId = entryId == null ? "" : entryId;
            provenance = provenance == null ? "" : provenance;
            reason = reason == null ? "" : reason;
        }
    }
}
