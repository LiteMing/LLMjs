// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Explicit provider/model capabilities. No capability is inferred from a model or vendor name.
 *
 * @since 1.4.1
 */
public final class ProviderCapabilities {
    public static final String INPUT_TEXT = "text";
    public static final String OUTPUT_TEXT = "text";

    private final Set<String> inputModalities;
    private final Set<String> outputModalities;
    private final HostedWebSearch webSearch;

    public ProviderCapabilities(Set<String> inputModalities, Set<String> outputModalities,
            HostedWebSearch webSearch) {
        this.inputModalities = normalize(inputModalities, Set.of(INPUT_TEXT));
        this.outputModalities = normalize(outputModalities, Set.of(OUTPUT_TEXT));
        this.webSearch = webSearch == null ? HostedWebSearch.disabled() : webSearch;
    }

    public static ProviderCapabilities textOnly() {
        return new ProviderCapabilities(Set.of(INPUT_TEXT), Set.of(OUTPUT_TEXT), HostedWebSearch.disabled());
    }

    public Set<String> inputModalities() {
        return inputModalities;
    }

    public Set<String> outputModalities() {
        return outputModalities;
    }

    public HostedWebSearch webSearch() {
        return webSearch;
    }

    private static Set<String> normalize(Set<String> values, Set<String> fallback) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(normalized.isEmpty() ? fallback : normalized);
    }

    public record HostedWebSearch(boolean enabled, String adapterId) {
        public HostedWebSearch {
            adapterId = adapterId == null ? "" : adapterId.trim().toLowerCase(Locale.ROOT);
            enabled = enabled && !adapterId.isEmpty();
            if (!enabled) adapterId = "";
        }

        public static HostedWebSearch disabled() {
            return new HostedWebSearch(false, "");
        }

        public static HostedWebSearch using(String adapterId) {
            return new HostedWebSearch(true, adapterId);
        }
    }
}
