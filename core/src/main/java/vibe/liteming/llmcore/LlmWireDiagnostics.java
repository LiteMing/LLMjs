// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Hash-only prompt shape diagnostics. No prompt text or credential value is retained. */
public record LlmWireDiagnostics(
        String finalMessageShapeHash,
        String stablePrefixHash,
        int stablePrefixEstimatedTokens,
        String firstDynamicEntryId,
        String cacheDomainIdentity) {

    public LlmWireDiagnostics {
        finalMessageShapeHash = clean(finalMessageShapeHash);
        stablePrefixHash = clean(stablePrefixHash);
        stablePrefixEstimatedTokens = Math.max(0, stablePrefixEstimatedTokens);
        firstDynamicEntryId = clean(firstDynamicEntryId);
        cacheDomainIdentity = clean(cacheDomainIdentity);
    }

    static LlmWireDiagnostics fromEntries(List<LlmMessageFinalization.FinalEntry> entries,
            LlmMessageFinalizer.TokenEstimator estimator) {
        List<LlmMessageFinalization.FinalEntry> safe = entries == null ? List.of() : List.copyOf(entries);
        StringBuilder shape = new StringBuilder();
        StringBuilder stable = new StringBuilder();
        int stableTokens = 0;
        String firstDynamic = "";
        boolean prefix = true;
        for (LlmMessageFinalization.FinalEntry entry : safe) {
            appendEntry(shape, entry, true);
            if (entry.stability().stableAcrossTurns() && prefix) {
                appendEntry(stable, entry, true);
                stableTokens = saturatedAdd(stableTokens, Math.max(0, estimator.estimate(entry.message())));
            } else {
                prefix = false;
                if (firstDynamic.isEmpty() && entry.stability() == LlmPromptStability.TURN_DYNAMIC) {
                    firstDynamic = entry.entryId();
                }
            }
        }
        return new LlmWireDiagnostics(hash(shape.toString()), hash(stable.toString()), stableTokens,
                firstDynamic, "");
    }

    public LlmWireDiagnostics withCacheDomainIdentity(String identity) {
        return new LlmWireDiagnostics(finalMessageShapeHash, stablePrefixHash,
                stablePrefixEstimatedTokens, firstDynamicEntryId, identity);
    }

    public LlmWireDiagnostics withFinalMessageShapeHash(String hash) {
        return new LlmWireDiagnostics(hash, stablePrefixHash,
                stablePrefixEstimatedTokens, firstDynamicEntryId, cacheDomainIdentity);
    }

    static String hashMessages(List<LlmMessage> messages) {
        StringBuilder serialized = new StringBuilder();
        for (LlmMessage message : messages == null ? List.<LlmMessage>of() : messages) {
            append(serialized, message.role());
            append(serialized, Integer.toString(message.parts().size()));
            for (LlmMessage.Part part : message.parts()) {
                append(serialized, part instanceof LlmMessage.ImagePart ? "image" : "text");
                append(serialized, part.asText());
            }
        }
        return hash(serialized.toString());
    }

    private static void appendEntry(StringBuilder output, LlmMessageFinalization.FinalEntry entry,
            boolean includeText) {
        append(output, entry.entryId());
        append(output, entry.provenance());
        append(output, entry.stability().name());
        append(output, entry.message().role());
        append(output, Integer.toString(entry.message().parts().size()));
        for (LlmMessage.Part part : entry.message().parts()) {
            append(output, part instanceof LlmMessage.ImagePart ? "image" : "text");
            if (includeText) append(output, part.asText());
        }
    }

    private static void append(StringBuilder output, String value) {
        String safe = value == null ? "" : value;
        output.append(safe.length()).append(':').append(safe).append(';');
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static int saturatedAdd(int left, int right) {
        return right > 0 && left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
