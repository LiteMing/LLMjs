// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Administrator-owned authorization for optional LLM capabilities.
 *
 * <p>Web search is denied unless the request purpose is explicitly present. This
 * policy never enables a feature by itself: a future request must also ask for
 * web search and its selected provider must support it.</p>
 *
 * @since 1.4.1
 */
public final class LlmCapabilityPolicy {
    private static final LlmCapabilityPolicy EMPTY = new LlmCapabilityPolicy(Set.of());

    private final Set<String> webSearchPurposes;

    private LlmCapabilityPolicy(Collection<String> webSearchPurposes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String purpose : Objects.requireNonNullElse(webSearchPurposes, Set.<String>of())) {
            String id = normalizePurpose(purpose);
            if (id.isEmpty()) throw new IllegalArgumentException("purpose id must not be blank");
            normalized.add(id);
        }
        this.webSearchPurposes = Collections.unmodifiableSet(normalized);
    }

    public static LlmCapabilityPolicy empty() {
        return EMPTY;
    }

    public static LlmCapabilityPolicy allowingWebSearch(Collection<String> purposes) {
        if (purposes == null || purposes.isEmpty()) return EMPTY;
        return new LlmCapabilityPolicy(purposes);
    }

    public boolean allowsWebSearch(String purpose) {
        return webSearchPurposes.contains(normalizePurpose(purpose));
    }

    public Set<String> webSearchPurposes() {
        return webSearchPurposes;
    }

    public LlmCapabilityPolicy withWebSearchAllowed(String purpose, boolean allowed) {
        String id = normalizePurpose(purpose);
        if (id.isEmpty()) throw new IllegalArgumentException("purpose id must not be blank");
        LinkedHashSet<String> next = new LinkedHashSet<>(webSearchPurposes);
        if (allowed) next.add(id);
        else next.remove(id);
        return next.isEmpty() ? EMPTY : new LlmCapabilityPolicy(next);
    }

    private static String normalizePurpose(String purpose) {
        return purpose == null ? "" : purpose.trim();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof LlmCapabilityPolicy policy
                && webSearchPurposes.equals(policy.webSearchPurposes);
    }

    @Override
    public int hashCode() {
        return webSearchPurposes.hashCode();
    }

    @Override
    public String toString() {
        return "LlmCapabilityPolicy{webSearchPurposes=" + webSearchPurposes + '}';
    }
}
