package vibe.liteming.llmcore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of {@link PurposeMeta} entries. Mods register their own
 * purposes at startup (e.g. on server starting); the /llm console reads the
 * aggregated list to render the routing/priority table. The registry is intentionally
 * string-keyed so non-Java consumers (KubeJS scripts, JSON config) can also reference
 * purpose ids without compile-time coupling to a particular enum.
 *
 * <p>Thread-safe. Last-write-wins for a given id, but built-in core entries registered
 * via {@link #registerBuiltIn(PurposeMeta)} cannot be overwritten by mod entries.</p>
 */
public final class PurposeRegistry {
    private static final Map<String, PurposeMeta> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> BUILT_IN_LOCKED = new ConcurrentHashMap<>();

    private PurposeRegistry() {
    }

    /**
     * Register (or replace) a purpose declared by a mod. Refuses to overwrite a
     * built-in core entry, in which case the call is a no-op and returns false.
     */
    public static boolean register(PurposeMeta meta) {
        if (meta == null || meta.id().isEmpty()) return false;
        if (BUILT_IN_LOCKED.getOrDefault(meta.id(), false)) return false;
        ENTRIES.put(meta.id(), meta);
        return true;
    }

    /**
     * Register a built-in core purpose (e.g. CHAT). Subsequent mod-level registers
     * for the same id are rejected.
     */
    public static void registerBuiltIn(PurposeMeta meta) {
        if (meta == null || meta.id().isEmpty()) return;
        ENTRIES.put(meta.id(), meta);
        BUILT_IN_LOCKED.put(meta.id(), true);
    }

    public static PurposeMeta get(String id) {
        if (id == null) return null;
        return ENTRIES.get(id.trim());
    }

    public static boolean isRegistered(String id) {
        return id != null && ENTRIES.containsKey(id.trim());
    }

    /**
     * Snapshot of all registered purposes ordered by registration (insertion) order.
     * Convenience for UI rendering.
     */
    public static List<PurposeMeta> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(ENTRIES.values()));
    }

    public static Collection<String> ids() {
        return Collections.unmodifiableSet(ENTRIES.keySet());
    }

    /** Reset registry (test only). */
    static void clearForTests() {
        ENTRIES.clear();
        BUILT_IN_LOCKED.clear();
    }
}
