package vibe.liteming.llmjs.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void selectsCanonicalPathForNewInstallations() {
        assertEquals(tempDir.resolve("llmcore.secret"), ProviderLoader.resolveSecretFile(tempDir));
    }

    @Test
    void renamesLegacySecretWhenCanonicalIsAbsent() throws Exception {
        Path legacy = Files.writeString(tempDir.resolve("llmjs.secret"), "{\"legacy\":true}");

        Path resolved = ProviderLoader.resolveSecretFile(tempDir);

        assertEquals(tempDir.resolve("llmcore.secret"), resolved);
        assertFalse(Files.exists(legacy));
        assertEquals("{\"legacy\":true}", Files.readString(resolved));
    }

    @Test
    void canonicalPathWinsWhenBothFilesExist() throws Exception {
        Files.writeString(tempDir.resolve("llmjs.secret"), "{}");
        Path canonical = Files.writeString(tempDir.resolve("llmcore.secret"), "{}");

        assertEquals(canonical, ProviderLoader.resolveSecretFile(tempDir));
        assertTrue(Files.exists(tempDir.resolve("llmjs.secret")));
    }
}
