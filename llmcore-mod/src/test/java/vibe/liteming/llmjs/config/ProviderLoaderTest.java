package vibe.liteming.llmjs.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void selectsCanonicalPathForNewInstallations() {
        assertEquals(tempDir.resolve("llmcore.secret"), ProviderLoader.resolveSecretFile(tempDir));
    }

    @Test
    void keepsUsingLegacyPathWhenItIsTheOnlyExistingSecret() throws Exception {
        Path legacy = Files.writeString(tempDir.resolve("llmjs.secret"), "{}");

        assertEquals(legacy, ProviderLoader.resolveSecretFile(tempDir));
    }

    @Test
    void canonicalPathWinsWhenBothFilesExist() throws Exception {
        Files.writeString(tempDir.resolve("llmjs.secret"), "{}");
        Path canonical = Files.writeString(tempDir.resolve("llmcore.secret"), "{}");

        assertEquals(canonical, ProviderLoader.resolveSecretFile(tempDir));
    }
}
