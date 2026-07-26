package vibe.liteming.llmcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingConfigStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void readsLegacyArrayRoutesWithoutLosingOrder() {
        PriorityRoutingConfig config = RoutingConfigStore.parse("""
                {
                  "default": ["stable", "last"],
                  "purposes": {
                    "CHAT": ["fast", "stable"]
                  }
                }
                """);

        assertEquals(List.of("stable", "last"), config.defaultChain());
        assertEquals(List.of("fast", "stable"), config.purposeChains().get("CHAT"));
        assertTrue(config.resolveOptions("CHAT").isEmpty());
    }

    @Test
    void savesVersionedOptionsAtomicallyAndChangesFingerprint() {
        Map<String, List<String>> chains = new LinkedHashMap<>();
        chains.put("CHAT", List.of("primary", "fallback"));
        LlmRouteOptions options = new LlmRouteOptions(0.4, 2000, 120, 16000, 3000);
        PriorityRoutingConfig first = new PriorityRoutingConfig(chains, List.of("fallback"),
                Map.of("CHAT", options));
        Path file = tempDir.resolve("routing.json");

        assertTrue(RoutingConfigStore.save(file, first));
        PriorityRoutingConfig loaded = RoutingConfigStore.load(file);
        assertEquals(first, loaded);
        assertEquals(options, loaded.resolveOptions("CHAT"));

        PriorityRoutingConfig second = first.withPurposeOptions("CHAT",
                new LlmRouteOptions(0.5, 2000, 120, 16000, 3000));
        assertNotEquals(RoutingConfigStore.fingerprint(first), RoutingConfigStore.fingerprint(second));
    }

    @Test
    void rejectsInvalidOrAmbiguousValuesExplicitly() {
        IllegalArgumentException range = assertThrows(IllegalArgumentException.class,
                () -> RoutingConfigStore.parse("""
                        {"purposes":{"CHAT":{"providers":["p"],"temperature":4.0}}}
                        """));
        assertTrue(range.getMessage().contains("temperature"));

        IllegalArgumentException type = assertThrows(IllegalArgumentException.class,
                () -> RoutingConfigStore.parse("""
                        {"purposes":{"CHAT":{"providers":["p"],"timeoutSeconds":"60"}}}
                        """));
        assertTrue(type.getMessage().contains("timeoutSeconds"));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> RoutingConfigStore.parse("""
                        {"purposes":{"CHAT":["p","p"]}}
                        """));
        assertTrue(duplicate.getMessage().contains("duplicate"));
    }
}
