// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedLlmRuntimeTest {
    @AfterEach
    void clearRuntime() {
        SharedLlmRuntime.current().ifPresent(SharedLlmRuntime::clear);
    }

    @Test
    void currentIsEmptyUntilInstalled() {
        SharedLlmRuntime.current().ifPresent(SharedLlmRuntime::clear);

        assertTrue(SharedLlmRuntime.current().isEmpty());
    }

    @Test
    void installPublishesTheSameInstance() {
        LlmOrchestrator runtime = new LlmOrchestrator(Map.of());

        SharedLlmRuntime.install(runtime);

        assertSame(runtime, SharedLlmRuntime.current().orElseThrow());
    }

    @Test
    void staleClearCannotRemoveNewerRuntime() {
        LlmOrchestrator oldRuntime = new LlmOrchestrator(Map.of());
        LlmOrchestrator newRuntime = new LlmOrchestrator(Map.of());
        SharedLlmRuntime.install(oldRuntime);
        SharedLlmRuntime.install(newRuntime);

        assertFalse(SharedLlmRuntime.clear(oldRuntime));
        assertSame(newRuntime, SharedLlmRuntime.current().orElseThrow());
        assertTrue(SharedLlmRuntime.clear(newRuntime));
        assertTrue(SharedLlmRuntime.current().isEmpty());
    }
}
