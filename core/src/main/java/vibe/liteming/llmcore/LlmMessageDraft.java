package vibe.liteming.llmcore;

import java.util.List;

/** Ordered model-facing candidates before the shared hard-budget finalizer runs. */
public record LlmMessageDraft(List<Entry> entries) {
    public LlmMessageDraft {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static LlmMessageDraft required(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) return new LlmMessageDraft(List.of());
        return new LlmMessageDraft(java.util.stream.IntStream.range(0, messages.size())
                .mapToObj(index -> new Entry("message-" + index, "legacy", messages.get(index), true, 0))
                .toList());
    }

    public record Entry(
            String entryId,
            String provenance,
            LlmMessage message,
            boolean required,
            int priority) {
        public Entry {
            entryId = entryId == null ? "" : entryId;
            provenance = provenance == null ? "" : provenance;
            message = message == null ? new LlmMessage("user", "") : message;
        }
    }
}
