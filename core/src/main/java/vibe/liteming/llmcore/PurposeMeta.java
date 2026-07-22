package vibe.liteming.llmcore;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Neutral metadata about an LLM "purpose" (a category of work, e.g. CHAT, MEMORY_SUMMARY).
 * Consumers (mods) register their own purposes at startup so the /llm console can list
 * them in a routing/priority UI without the core library knowing any business semantics.
 *
 * @param id           stable machine identifier (upper snake-case, e.g. "MEMORY_SUMMARY")
 * @param displayName  human label shown in the console
 * @param description  short tooltip / help text
 * @param modId        owner mod id used to attribute the entry
 * @param builtIn      true if shipped by llm-core itself (CHAT etc.)
 */
public record PurposeMeta(String id, String displayName, String description, String modId, boolean builtIn) {
    public PurposeMeta {
        Objects.requireNonNull(id, "id");
        id = id.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("purpose id must not be blank");
        displayName = Objects.requireNonNullElse(displayName, id).trim();
        description = Objects.requireNonNullElse(description, "").trim();
        modId = Objects.requireNonNullElse(modId, "").trim();
    }
}
