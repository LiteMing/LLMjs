package vibe.liteming.llmcore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Global priority-routing config shared by all consumers of llm-core. Maps a
 * {@code purpose} (e.g. "MEMORY_SUMMARY") to an ordered provider chain (first
 * provider is preferred; subsequent entries are fallbacks). Also holds a single
 * default chain used when a purpose has no explicit mapping.
 *
 * <p>This is the authoritative, mod-agnostic table edited from the /llm console.
 * Mod-specific overrides (specific entities / entity types / custom names) remain
 * in each consumer's own config and are layered on top by the consumer.</p>
 *
 * @param purposeChains purpose id -> ordered provider chain (mutable on load; treated as snapshot)
 * @param defaultChain  provider chain used when a purpose has no explicit entry
 */
public record PriorityRoutingConfig(Map<String, List<String>> purposeChains, List<String> defaultChain) {

    public PriorityRoutingConfig {
        purposeChains = new LinkedHashMap<>(Objects.requireNonNullElse(purposeChains, Map.of()));
        defaultChain = new ArrayList<>(Objects.requireNonNullElse(defaultChain, List.of()));
        // freeze inner lists so callers can't mutate them in place
        for (var entry : purposeChains.entrySet()) {
            entry.setValue(List.copyOf(Objects.requireNonNullElse(entry.getValue(), List.of())));
        }
    }

    public static PriorityRoutingConfig empty() {
        return new PriorityRoutingConfig(Map.of(), List.of());
    }

    /**
     * Resolve the provider chain for a purpose. Falls back to {@link #defaultChain()},
     * then to the supplied {@code fallback} (typically the orchestrator's full provider
     * set when nothing else is configured).
     */
    public List<String> resolveChain(String purpose, List<String> fallback) {
        if (purpose != null) {
            List<String> chain = purposeChains.get(purpose.trim());
            if (chain != null && !chain.isEmpty()) return chain;
        }
        if (!defaultChain.isEmpty()) return Collections.unmodifiableList(defaultChain);
        return Objects.requireNonNullElse(fallback, List.of());
    }

    /**
     * Return a new config with one purpose's chain replaced. Returns a copy; this
     * instance stays immutable. Empty / blank chains remove the entry.
     */
    public PriorityRoutingConfig withPurpose(String purpose, List<String> chain) {
        Map<String, List<String>> next = new LinkedHashMap<>(purposeChains);
        if (chain == null || chain.isEmpty()) {
            next.remove(purpose == null ? "" : purpose.trim());
        } else {
            next.put(purpose.trim(), List.copyOf(chain));
        }
        return new PriorityRoutingConfig(next, defaultChain);
    }

    public PriorityRoutingConfig withDefault(List<String> chain) {
        List<String> next = chain == null || chain.isEmpty() ? List.of() : List.copyOf(chain);
        return new PriorityRoutingConfig(purposeChains, next);
    }
}
