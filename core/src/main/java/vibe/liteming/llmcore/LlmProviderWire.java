// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Shared final wire preparation used by every provider transport path. */
public final class LlmProviderWire {
    public record Prepared(List<LlmMessage> messages,
            List<LlmMessageFinalization.FinalEntry> sourceEntries,
            LlmWireDiagnostics diagnostics) {
        public Prepared {
            messages = messages == null ? List.of() : List.copyOf(messages);
            sourceEntries = sourceEntries == null ? List.of() : List.copyOf(sourceEntries);
        }
    }

    private LlmProviderWire() {
    }

    public static Prepared prepare(LlmRequest request) {
        LlmRequest safe = request == null
                ? LlmRequest.routed(List.of(), LlmRequestContext.chat()) : request;
        List<LlmMessage> normalized = mergeLeadingSystemMessages(safe.messages());
        LlmWireDiagnostics diagnostics = safe.wireDiagnostics()
                .withFinalMessageShapeHash(LlmWireDiagnostics.hashMessages(normalized));
        return new Prepared(normalized, safe.typedEntries(), diagnostics);
    }

    static LlmRequest prepareRequest(LlmRequest request) {
        Prepared prepared = prepare(request);
        return new LlmRequest(prepared.messages(), request.providerChain(), request.temperature(),
                request.maxTokens(), request.timeoutSeconds(), request.context(), request.overrides(),
                request.billingContext(), prepared.sourceEntries(), prepared.diagnostics());
    }

    public static List<LlmMessage> mergeLeadingSystemMessages(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        int leadingSystems = 0;
        while (leadingSystems < messages.size()) {
            LlmMessage message = messages.get(leadingSystems);
            if (message == null || !"system".equalsIgnoreCase(message.role()) || message.hasImage()) break;
            leadingSystems++;
        }
        if (leadingSystems <= 1) return List.copyOf(messages);
        String merged = messages.subList(0, leadingSystems).stream()
                .map(LlmMessage::content).collect(Collectors.joining("\n\n"));
        List<LlmMessage> normalized = new ArrayList<>(messages.size() - leadingSystems + 1);
        normalized.add(new LlmMessage("system", merged));
        normalized.addAll(messages.subList(leadingSystems, messages.size()));
        return List.copyOf(normalized);
    }
}
