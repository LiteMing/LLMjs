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
    static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    record Usage(UUID playerId, long promptTokens, long completionTokens, long estimatedTokens,
            long updatedAtMs) {
        long totalTokens() {
            return saturatedAdd(saturatedAdd(promptTokens, completionTokens), estimatedTokens);
        }
    }

    private final Path file;
    private final Map<UUID, Usage> usageByPlayer = new LinkedHashMap<>();
    private boolean writable = true;

    PersonalBudgetLedger(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        load();
    }

    synchronized boolean isWritable() {
        return writable;
    }

    synchronized Usage usage(UUID playerId) {
        Usage usage = playerId == null ? null : usageByPlayer.get(playerId);
        return usage == null ? new Usage(playerId, 0L, 0L, 0L, 0L) : usage;
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
        Usage previous = usage(playerId);
        Usage next = new Usage(playerId,
                saturatedAdd(previous.promptTokens(), Math.max(0L, promptTokens)),
                saturatedAdd(previous.completionTokens(), Math.max(0L, completionTokens)),
                saturatedAdd(previous.estimatedTokens(), Math.max(0L, estimatedTokens)),
                Math.max(0L, nowMs));
        usageByPlayer.put(playerId, next);
        try {
            persist();
        } catch (RuntimeException failure) {
            if (previous.totalTokens() == 0L && previous.updatedAtMs() == 0L) usageByPlayer.remove(playerId);
            else usageByPlayer.put(playerId, previous);
            writable = false;
            throw failure;
        }
        return next;
    }

    synchronized boolean reset(UUID playerId) {
        requireWritable();
        Usage previous = usageByPlayer.remove(playerId);
        if (previous == null) return false;
        try {
            persist();
        } catch (RuntimeException failure) {
            usageByPlayer.put(previous.playerId(), previous);
            writable = false;
            throw failure;
        }
        return true;
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
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

    private void requireWritable() {
        if (!writable) throw new IllegalStateException("Personal token usage file is unreadable");
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
