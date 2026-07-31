// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide read-only handoff for the fully initialized LLM runtime.
 * Configuration ownership and writes remain in the host runtime module.
 *
 * @since 1.4.2
 */
public final class SharedLlmRuntime {
    private static final AtomicReference<LlmOrchestrator> CURRENT = new AtomicReference<>();

    private SharedLlmRuntime() {
    }

    public static Optional<LlmOrchestrator> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void install(LlmOrchestrator orchestrator) {
        CURRENT.set(Objects.requireNonNull(orchestrator, "orchestrator"));
    }

    public static boolean clear(LlmOrchestrator expectedInstance) {
        return expectedInstance != null && CURRENT.compareAndSet(expectedInstance, null);
    }
}
