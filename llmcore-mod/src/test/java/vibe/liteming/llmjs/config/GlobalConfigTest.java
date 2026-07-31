// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmjs.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void migratesOnlyOwnedGlobalFilesToLlmcoreDirectory() throws Exception {
        Path legacy = Files.createDirectories(tempDir.resolve("config/llmjs"));
        Files.writeString(legacy.resolve("providers.json"), "{\"provider\":{}}");
        Files.writeString(legacy.resolve("routing.json"), "{\"schemaVersion\":2}");
        Files.writeString(legacy.resolve("llmjs-only.json"), "{}");

        GlobalConfig.init(tempDir);

        Path canonical = tempDir.resolve("config/llmcore");
        assertEquals(canonical, GlobalConfig.getGlobalDir());
        assertTrue(Files.exists(canonical.resolve("providers.json")));
        assertTrue(Files.exists(canonical.resolve("routing.json")));
        assertFalse(Files.exists(legacy.resolve("providers.json")));
        assertTrue(Files.exists(legacy.resolve("llmjs-only.json")));
    }

    @Test
    void migratesServerProviderFilesToLlmcoreDirectory() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("serverconfig"));
        Path legacy = Files.createDirectories(root.resolve("llmjs"));
        Files.writeString(legacy.resolve("providers.json"), "{}");
        Files.writeString(legacy.resolve("providers_raw.json"), "{}");

        Path canonical = GlobalConfig.resolveServerDirectory(root);

        assertEquals(root.resolve("llmcore"), canonical);
        assertTrue(Files.exists(canonical.resolve("providers.json")));
        assertTrue(Files.exists(canonical.resolve("providers_raw.json")));
    }
}
