package com.liteming.llmjs.log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

public class LLMLogger {
    public static final LLMLogger INSTANCE = new LLMLogger();

    public enum Level { INFO, WARN, ERROR }

    public record LogEntry(
            Instant timestamp,
            Level level,
            String provider,
            String requestSummary,
            String status,
            long latencyMs,
            int promptTokens,
            int completionTokens,
            @Nullable String errorMessage
    ) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("timestamp", timestamp.toString());
            obj.addProperty("level", level.name());
            obj.addProperty("provider", provider);
            obj.addProperty("requestSummary", requestSummary);
            obj.addProperty("status", status);
            obj.addProperty("latencyMs", latencyMs);
            obj.addProperty("promptTokens", promptTokens);
            obj.addProperty("completionTokens", completionTokens);
            if (errorMessage != null) {
                obj.addProperty("error", errorMessage);
            }
            return obj;
        }
    }

    private LogEntry[] buffer;
    private int head = 0;
    private int size = 0;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Consumer<LogEntry>> listeners = new ArrayList<>();

    private LLMLogger() {
        this.buffer = new LogEntry[200];
    }

    public void resize(int capacity) {
        lock.writeLock().lock();
        try {
            LogEntry[] old = getRecentEntries(Math.min(size, capacity));
            buffer = new LogEntry[capacity];
            for (int i = 0; i < old.length; i++) {
                buffer[i] = old[i];
            }
            head = old.length % capacity;
            size = old.length;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void log(Level level, String provider, String prompt, String status,
                    long latencyMs, int promptTokens, int completionTokens,
                    @Nullable String errorMessage) {
        String summary = prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;
        LogEntry entry = new LogEntry(Instant.now(), level, provider, summary,
                status, latencyMs, promptTokens, completionTokens, errorMessage);

        lock.writeLock().lock();
        try {
            buffer[head] = entry;
            head = (head + 1) % buffer.length;
            if (size < buffer.length) size++;
        } finally {
            lock.writeLock().unlock();
        }

        for (Consumer<LogEntry> listener : listeners) {
            try { listener.accept(entry); } catch (Exception ignored) {}
        }
    }

    public void logInfo(String provider, String prompt, long latencyMs,
                        int promptTokens, int completionTokens) {
        log(Level.INFO, provider, prompt, "success", latencyMs, promptTokens, completionTokens, null);
    }

    public void logError(String provider, String prompt, long latencyMs, String error) {
        log(Level.ERROR, provider, prompt, "error", latencyMs, 0, 0, error);
    }

    public LogEntry[] getRecentEntries(int count) {
        lock.readLock().lock();
        try {
            int n = Math.min(count, size);
            LogEntry[] result = new LogEntry[n];
            for (int i = 0; i < n; i++) {
                int idx = (head - n + i + buffer.length) % buffer.length;
                result[i] = buffer[idx];
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public LogEntry[] getRecentEntries() {
        return getRecentEntries(size);
    }

    public LogEntry[] getRecentByLevel(Level level, int count) {
        lock.readLock().lock();
        try {
            List<LogEntry> filtered = new ArrayList<>();
            for (int i = size - 1; i >= 0 && filtered.size() < count; i--) {
                int idx = (head - 1 - (size - 1 - i) + buffer.length) % buffer.length;
                if (buffer[idx].level() == level) {
                    filtered.add(0, buffer[idx]);
                }
            }
            return filtered.toArray(new LogEntry[0]);
        } finally {
            lock.readLock().unlock();
        }
    }

    public JsonArray toJsonArray(int count) {
        JsonArray arr = new JsonArray();
        for (LogEntry entry : getRecentEntries(count)) {
            arr.add(entry.toJson());
        }
        return arr;
    }

    public void addListener(Consumer<LogEntry> listener) { listeners.add(listener); }
    public void removeListener(Consumer<LogEntry> listener) { listeners.remove(listener); }
}
