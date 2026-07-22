package vibe.liteming.llmcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsMultipleCredentialSlotsWithoutExposingThemInProviderConfig() throws Exception {
        Path providers = tempDir.resolve("providers.json");
        Path secret = tempDir.resolve("llmjs.secret");
        Files.writeString(providers, """
                {"fast":{"format":"openai","url":"http://localhost/test","model":"test-model"}}
                """);
        Files.writeString(secret, """
                {"providers":{"fast":{"keys":[{"id":"primary","key":"sk-one"},{"id":"secondary","key":"sk-two"}]}}}
                """);

        Map<String, ProviderSpec> result = ProviderConfigLoader.load(null, providers, secret);

        assertEquals(2, result.get("fast").credentials().size());
        assertEquals("primary", result.get("fast").credentials().get(0).id());
        assertEquals("secondary", result.get("fast").credentials().get(1).id());
    }
}
