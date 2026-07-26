package vibe.liteming.llmcore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic, provider-neutral hard-budget finalizer shared by production and Test. */
public final class LlmMessageFinalizer {
    @FunctionalInterface
    public interface TokenEstimator {
        int estimate(LlmMessage message);
    }

    /**
     * Deliberately conservative fallback used when a provider tokenizer is unavailable.
     * UTF-8 bytes avoid under-counting CJK text; image parts use a fixed low-detail reserve.
     */
    public static final TokenEstimator CONSERVATIVE_ESTIMATOR = message -> {
        int tokens = 4;
        for (LlmMessage.Part part : message.parts()) {
            if (part instanceof LlmMessage.ImagePart) {
                tokens += 256;
            } else {
                tokens += Math.max(1, part.asText().getBytes(StandardCharsets.UTF_8).length);
            }
        }
        return tokens;
    };

    private LlmMessageFinalizer() {
    }

    public static LlmMessageFinalization finalize(
            LlmMessageDraft draft, int hardBudgetTokens, TokenEstimator estimator) {
        LlmMessageDraft safeDraft = draft == null ? new LlmMessageDraft(List.of()) : draft;
        TokenEstimator safeEstimator = estimator == null ? CONSERVATIVE_ESTIMATOR : estimator;
        int budget = Math.max(0, hardBudgetTokens);
        List<IndexedEntry> indexed = new ArrayList<>();
        for (int index = 0; index < safeDraft.entries().size(); index++) {
            LlmMessageDraft.Entry entry = safeDraft.entries().get(index);
            indexed.add(new IndexedEntry(index, entry, Math.max(0, safeEstimator.estimate(entry.message()))));
        }

        boolean[] included = new boolean[indexed.size()];
        String[] reasons = new String[indexed.size()];
        long used = 0;
        for (IndexedEntry item : indexed) {
            if (!item.entry.required()) continue;
            included[item.index] = true;
            used += item.tokens;
            reasons[item.index] = used > budget ? "required_over_budget" : "required";
        }

        List<IndexedEntry> optional = indexed.stream()
                .filter(item -> !item.entry.required())
                .sorted(Comparator.comparingInt((IndexedEntry item) -> item.entry.priority()).reversed()
                        .thenComparingInt(item -> item.index))
                .toList();
        for (IndexedEntry item : optional) {
            if (used + item.tokens <= budget) {
                included[item.index] = true;
                reasons[item.index] = "priority_fit";
                used += item.tokens;
            } else {
                reasons[item.index] = "budget_excluded";
            }
        }

        List<LlmMessage> messages = new ArrayList<>();
        List<LlmMessageFinalization.Decision> decisions = new ArrayList<>();
        for (IndexedEntry item : indexed) {
            if (included[item.index]) messages.add(item.entry.message());
            decisions.add(new LlmMessageFinalization.Decision(item.index, item.entry.entryId(),
                    item.entry.provenance(), included[item.index], reasons[item.index], item.tokens,
                    item.entry.required(), item.entry.priority()));
        }
        int estimated = used > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) used;
        return new LlmMessageFinalization(messages, decisions, budget, estimated, used <= budget);
    }

    private record IndexedEntry(int index, LlmMessageDraft.Entry entry, int tokens) {
    }
}
