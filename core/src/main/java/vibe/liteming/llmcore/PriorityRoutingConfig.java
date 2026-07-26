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
 * @param purposeOptions purpose id -> nullable/inherited generic request controls
 */
public record PriorityRoutingConfig(
        Map<String, List<String>> purposeChains,
        List<String> defaultChain,
        Map<String, LlmRouteOptions> purposeOptions) {

    public PriorityRoutingConfig(Map<String, List<String>> purposeChains, List<String> defaultChain) {
        this(purposeChains, defaultChain, Map.of());
    }

    public PriorityRoutingConfig {
        purposeChains = new LinkedHashMap<>(Objects.requireNonNullElse(purposeChains, Map.of()));
        defaultChain = new ArrayList<>(Objects.requireNonNullElse(defaultChain, List.of()));
        purposeOptions = new LinkedHashMap<>(Objects.requireNonNullElse(purposeOptions, Map.of()));
        // freeze inner lists so callers can't mutate them in place
        for (var entry : purposeChains.entrySet()) {
            entry.setValue(List.copyOf(Objects.requireNonNullElse(entry.getValue(), List.of())));
        }
        purposeChains = Collections.unmodifiableMap(purposeChains);
        defaultChain = List.copyOf(defaultChain);
        purposeOptions.replaceAll((purpose, options) -> options == null ? LlmRouteOptions.empty() : options);
        purposeOptions = Collections.unmodifiableMap(purposeOptions);
    }

    public static PriorityRoutingConfig empty() {
        return new PriorityRoutingConfig(Map.of(), List.of(), Map.of());
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
        String key = purpose == null ? "" : purpose.trim();
        if (key.isEmpty() && chain != null && !chain.isEmpty()) {
            throw new IllegalArgumentException("purpose is required");
        }
        Map<String, List<String>> next = new LinkedHashMap<>(purposeChains);
        if (chain == null || chain.isEmpty()) {
            next.remove(key);
        } else {
            next.put(key, List.copyOf(chain));
        }
        return new PriorityRoutingConfig(next, defaultChain, purposeOptions);
    }

    public PriorityRoutingConfig withDefault(List<String> chain) {
        List<String> next = chain == null || chain.isEmpty() ? List.of() : List.copyOf(chain);
        return new PriorityRoutingConfig(purposeChains, next, purposeOptions);
    }

    public LlmRouteOptions resolveOptions(String purpose) {
        if (purpose == null) return LlmRouteOptions.empty();
        return purposeOptions.getOrDefault(purpose.trim(), LlmRouteOptions.empty());
    }

    public PriorityRoutingConfig withPurposeOptions(String purpose, LlmRouteOptions options) {
        String key = purpose == null ? "" : purpose.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("purpose is required");
        Map<String, LlmRouteOptions> next = new LinkedHashMap<>(purposeOptions);
        if (options == null || options.isEmpty()) next.remove(key);
        else next.put(key, options);
        return new PriorityRoutingConfig(purposeChains, defaultChain, next);
    }
}
