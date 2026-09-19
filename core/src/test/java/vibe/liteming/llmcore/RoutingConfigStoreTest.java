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
    void savesCompositeRoutesWithoutFlatteningThemWhenOnlyParametersChange() {
        PriorityRoutingConfig config = RoutingConfigStore.parse("""
                {"schemaVersion":3,"purposes":{"VISION":{"route":"A*3 > (B | C) > A","deadlineSeconds":90}}}
                """);
        PriorityRoutingConfig edited = config.withPurposeOptions("VISION", new LlmRouteOptions(0.5, null, null, null, null));
        PriorityRoutingConfig roundTrip = RoutingConfigStore.parse(RoutingConfigStore.toJsonString(edited));
        assertEquals(edited, roundTrip);
        assertEquals("A*3 > (B | C) > A", roundTrip.purposeRoutes().get("VISION").expression());
        assertEquals(List.of("A", "B", "C", "A"), roundTrip.resolveChain("VISION", List.of()));
        assertEquals(90, roundTrip.purposeRoutes().get("VISION").deadlineSeconds());
        assertNotEquals(RoutingConfigStore.fingerprint(config), RoutingConfigStore.fingerprint(edited));
        assertThrows(IllegalArgumentException.class, () -> LlmRoute.parse("A*3 > (B | B)"));
        assertThrows(IllegalArgumentException.class, () -> LlmRoute.parse("A*11"));
        assertThrows(IllegalArgumentException.class, () -> LlmRoute.parse("A >"));
        assertEquals("quoted > provider", LlmRoute.parse("\"quoted > provider\"*0").providers().get(0));
    }

    @Test
    void targetIdsKeepMachineEncodingWhileDisplayNamesRemainReadable() {
        LlmTarget target = LlmTarget.of("\u4E2D\u6587\u63D0\u4F9B\u5546", "\u4E2D\u6587\u6A21\u578B");

        assertTrue(target.id().contains("%E4%B8%AD"));
        assertEquals("\u4E2D\u6587\u63D0\u4F9B\u5546", LlmTarget.parse(target.id()).provider());
        assertEquals("\u4E2D\u6587\u6A21\u578B", LlmTarget.parse(target.id()).model());
        assertEquals("\u4E2D\u6587\u63D0\u4F9B\u5546/\u4E2D\u6587\u6A21\u578B",
                LlmTarget.parse(target.id()).displayName());
    }

    @Test
    void deadlinesCanOverrideIndependentlyOfInheritedProviders() {
        PriorityRoutingConfig config = RoutingConfigStore.parse("""
                {"schemaVersion":3,"default":{"route":"","deadlineSeconds":30},"purposes":{
                  "CHAT":{"route":"","deadlineSeconds":90},
                  "VISION":{"route":"A*1"},
                  "SUMMARY":{"temperature":0.2},
                  "RESET":{"deadlineSeconds":120}
                }}
                """);
        assertEquals(config, RoutingConfigStore.parse(RoutingConfigStore.toJsonString(config)));
        assertEquals(30, config.resolveRoute("OTHER", List.of("B")).deadlineSeconds());
        assertEquals(List.of("B"), config.resolveRoute("CHAT", List.of("B")).providers());
        assertEquals(90, config.resolveRoute("CHAT", List.of("B")).deadlineSeconds());
        assertEquals(30, config.resolveRoute("VISION", List.of("B")).deadlineSeconds());
        assertEquals("A*1", config.resolveRoute("VISION", List.of("B")).expression());
        assertEquals(30, config.resolveRoute("SUMMARY", List.of("B")).deadlineSeconds());
        assertEquals(120, config.resolveRoute("RESET", List.of("B")).deadlineSeconds());
        assertEquals(30, config.withPurposeRoute("CHAT", LlmRoute.empty())
                .resolveRoute("CHAT", List.of("B")).deadlineSeconds());
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
