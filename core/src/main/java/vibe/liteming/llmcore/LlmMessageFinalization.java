package vibe.liteming.llmcore;

import java.util.List;

/** Final messages plus a stable decision for every draft entry. */
public record LlmMessageFinalization(
        List<LlmMessage> messages,
        List<FinalEntry> entries,
        List<Decision> decisions,
        int hardBudgetTokens,
        int estimatedInputTokens,
        boolean withinBudget,
        LlmWireDiagnostics wireDiagnostics) {

    public LlmMessageFinalization(List<LlmMessage> messages, List<Decision> decisions,
            int hardBudgetTokens, int estimatedInputTokens, boolean withinBudget) {
        this(messages, legacyEntries(messages), decisions, hardBudgetTokens, estimatedInputTokens, withinBudget,
                null);
    }

    public LlmMessageFinalization {
        messages = messages == null ? List.of() : List.copyOf(messages);
        entries = entries == null ? List.of() : List.copyOf(entries);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        wireDiagnostics = wireDiagnostics == null
                ? LlmWireDiagnostics.fromEntries(entries, LlmMessageFinalizer.CONSERVATIVE_ESTIMATOR)
                : wireDiagnostics;
    }

    public record FinalEntry(int index, String entryId, String provenance, LlmMessage message,
            boolean required, int priority, LlmPromptStability stability) {
        public FinalEntry {
            entryId = entryId == null ? "" : entryId;
            provenance = provenance == null ? "" : provenance;
            message = message == null ? new LlmMessage("user", "") : message;
            stability = stability == null ? LlmPromptStability.TURN_DYNAMIC : stability;
        }
    }

    public record Decision(
            int index,
            String entryId,
            String provenance,
            boolean included,
            String reason,
            int estimatedTokens,
            boolean required,
            int priority,
            LlmPromptStability stability) {
        public Decision(int index, String entryId, String provenance, boolean included, String reason,
                int estimatedTokens, boolean required, int priority) {
            this(index, entryId, provenance, included, reason, estimatedTokens, required, priority,
                    LlmPromptStability.TURN_DYNAMIC);
        }

        public Decision {
            entryId = entryId == null ? "" : entryId;
            provenance = provenance == null ? "" : provenance;
            reason = reason == null ? "" : reason;
            stability = stability == null ? LlmPromptStability.TURN_DYNAMIC : stability;
        }
    }

    private static List<FinalEntry> legacyEntries(List<LlmMessage> messages) {
        if (messages == null) return List.of();
        return java.util.stream.IntStream.range(0, messages.size())
                .mapToObj(index -> new FinalEntry(index, "message-" + index, "legacy", messages.get(index),
                        true, 0, LlmPromptStability.TURN_DYNAMIC))
                .toList();
    }
}
