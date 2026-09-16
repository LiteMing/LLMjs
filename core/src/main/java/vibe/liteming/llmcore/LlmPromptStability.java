// SPDX-FileCopyrightText: 2026 LiteMing
// SPDX-License-Identifier: MIT
package vibe.liteming.llmcore;

/** Business stability of one prompt entry, ordered from broadest reuse to per-turn data. */
public enum LlmPromptStability {
    GLOBAL_STATIC,
    PRESET_STATIC,
    NPC_STABLE,
    SESSION_STABLE,
    TURN_DYNAMIC;

    public boolean stableAcrossTurns() {
        return this != TURN_DYNAMIC;
    }
}
