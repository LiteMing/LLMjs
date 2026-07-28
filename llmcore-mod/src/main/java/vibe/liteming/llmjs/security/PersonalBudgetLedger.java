package vibe.liteming.llmjs.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Atomic per-world token usage store. Any malformed content fails closed. */
final class PersonalBudgetLedger {
    static final int SCHEMA_VERSION = 2;
    private static final long USAGE_FLUSH_INTERVAL_MS = 5_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    record Usage(UUID playerId, long promptTokens, long completionTokens, long estimatedTokens,
            long requestCount, Long limitTokens, long updatedAtMs) {
        long totalTokens() {
            return saturatedAdd(saturatedAdd(promptTokens, completionTokens), estimatedTokens);
        }

        boolean hasUsage() {
            return totalTokens() > 0L;
        }
    }

    private final Path file;
    private final Map<UUID, Usage> usageByPlayer = new LinkedHashMap<>();
    private boolean writable = true;
    private boolean dirty;
    private long lastPersistAtMs;

    PersonalBudgetLedger(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        load();
    }

    synchronized boolean isWritable() {
        return writable;
    }

    synchronized Usage usage(UUID playerId) {
        Usage usage = playerId == null ? null : usageByPlayer.get(playerId);
        return usage == null ? new Usage(playerId, 0L, 0L, 0L, 0L, null, 0L) : usage;
    }

    synchronized List<Usage> list() {
        return usageByPlayer.values().stream()
                .sorted(Comparator.comparing(value -> value.playerId().toString()))
                .toList();
    }

    synchronized Usage record(UUID playerId, long promptTokens, long completionTokens,
            long estimatedTokens, long nowMs) {
        requireWritable();
        Objects.requireNonNull(playerId, "playerId");
        Usage stored = usageByPlayer.get(playerId);
        Usage previous = stored == null ? usage(playerId) : stored;
        Usage next = new Usage(playerId,
                saturatedAdd(previous.promptTokens(), Math.max(0L, promptTokens)),
                saturatedAdd(previous.completionTokens(), Math.max(0L, completionTokens)),
                saturatedAdd(previous.estimatedTokens(), Math.max(0L, estimatedTokens)),
                saturatedAdd(previous.requestCount(), 1L),
                previous.limitTokens(),
                Math.max(0L, nowMs));
        usageByPlayer.put(playerId, next);
        try {
            dirty = true;
            flushIfDue(nowMs);
        } catch (RuntimeException failure) {
            if (stored == null) usageByPlayer.remove(playerId);
            else usageByPlayer.put(playerId, stored);
            writable = false;
            throw failure;
        }
        return next;
    }

    synchronized void flush() {
        requireWritable();
        if (!dirty) return;
        try {
            persist();
            dirty = false;
            lastPersistAtMs = System.currentTimeMillis();
        } catch (RuntimeException failure) {
            writable = false;
            throw failure;
        }
    }

    synchronized boolean reset(UUID playerId) {
        requireWritable();
        Usage previous = usageByPlayer.get(playerId);
        if (previous == null) return false;
        boolean changed = previous.hasUsage();
        if (!changed) return false;
        if (previous.limitTokens() == null) {
            usageByPlayer.remove(playerId);
        } else {
            usageByPlayer.put(playerId, new Usage(playerId, 0L, 0L, 0L, 0L,
                    previous.limitTokens(), previous.updatedAtMs()));
        }
        try {
            persist();
            dirty = false;
            lastPersistAtMs = System.currentTimeMillis();
        } catch (RuntimeException failure) {
            usageByPlayer.put(previous.playerId(), previous);
            writable = false;
            throw failure;
        }
        return true;
    }

    synchronized boolean setLimit(UUID playerId, Long limitTokens, long nowMs) {
        requireWritable();
        Objects.requireNonNull(playerId, "playerId");
        if (limitTokens != null && limitTokens < -1L) {
            throw new IllegalArgumentException("limitTokens must be null, -1, 0, or positive");
        }
        Usage previous = usageByPlayer.get(playerId);
        Usage current = previous == null ? usage(playerId) : previous;
        if (Objects.equals(current.limitTokens(), limitTokens)) return false;
        if (limitTokens == null && !current.hasUsage()) {
            usageByPlayer.remove(playerId);
        } else {
            usageByPlayer.put(playerId, new Usage(playerId, current.promptTokens(),
                    current.completionTokens(), current.estimatedTokens(), current.requestCount(), limitTokens,
                    Math.max(0L, nowMs)));
        }
        try {
            persist();
            dirty = false;
            lastPersistAtMs = Math.max(0L, nowMs);
        } catch (RuntimeException failure) {
            if (previous == null) usageByPlayer.remove(playerId);
            else usageByPlayer.put(playerId, previous);
            writable = false;
            throw failure;
        }
        return true;
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("schemaVersion")) {
                throw new IllegalArgumentException("missing schemaVersion");
            }
            int schemaVersion = root.get("schemaVersion").getAsInt();
            if (schemaVersion != 1 && schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported schemaVersion");
            }
            JsonArray entries = root.has("players") && root.get("players").isJsonArray()
                    ? root.getAsJsonArray("players") : new JsonArray();
            for (JsonElement element : entries) {
                JsonObject value = element.getAsJsonObject();
                UUID playerId = UUID.fromString(value.get("playerId").getAsString());
                Usage usage = new Usage(playerId,
                        nonNegative(value, "promptTokens"),
                        nonNegative(value, "completionTokens"),
                        nonNegative(value, "estimatedTokens"),
                        schemaVersion >= 2 && value.has("requestCount")
                                ? nonNegative(value, "requestCount") : 0L,
                        schemaVersion >= 2 ? optionalLimit(value) : null,
                        nonNegative(value, "updatedAtMs"));
                if (usageByPlayer.put(playerId, usage) != null) {
                    throw new IllegalArgumentException("duplicate playerId " + playerId);
                }
            }
        } catch (IOException | RuntimeException failure) {
            usageByPlayer.clear();
            writable = false;
        }
    }

    private static long nonNegative(JsonObject value, String key) {
        if (!value.has(key)) throw new IllegalArgumentException("missing " + key);
        long number = value.get(key).getAsLong();
        if (number < 0L) throw new IllegalArgumentException(key + " is negative");
        return number;
    }

    private static Long optionalLimit(JsonObject value) {
        if (!value.has("limitTokens")) return null;
        if (value.get("limitTokens").isJsonNull()) {
            throw new IllegalArgumentException("limitTokens must be omitted when unset");
        }
        long number = value.get("limitTokens").getAsLong();
        if (number < -1L) throw new IllegalArgumentException("limitTokens is below -1");
        return number;
    }

    private void persist() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray players = new JsonArray();
        for (Usage usage : list()) {
            JsonObject value = new JsonObject();
            value.addProperty("playerId", usage.playerId().toString());
            value.addProperty("promptTokens", usage.promptTokens());
            value.addProperty("completionTokens", usage.completionTokens());
            value.addProperty("estimatedTokens", usage.estimatedTokens());
            value.addProperty("requestCount", usage.requestCount());
            if (usage.limitTokens() != null) value.addProperty("limitTokens", usage.limitTokens());
            value.addProperty("updatedAtMs", usage.updatedAtMs());
            players.add(value);
        }
        root.add("players", players);

        Path parent = file.getParent();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to persist personal token usage", failure);
        }
    }

    private void flushIfDue(long nowMs) {
        long safeNow = Math.max(0L, nowMs);
        if (lastPersistAtMs > 0L && safeNow - lastPersistAtMs < USAGE_FLUSH_INTERVAL_MS) return;
        persist();
        dirty = false;
        lastPersistAtMs = safeNow;
    }

    private void requireWritable() {
        if (!writable) throw new IllegalStateException("Personal token usage file is unreadable");
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
