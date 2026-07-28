package vibe.liteming.llmjs.log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import vibe.liteming.llmcore.LlmRequestLogger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

public class LLMLogger {
    public static final LLMLogger INSTANCE = new LLMLogger();
    private static final int MAX_BODY_CHARS = 12000;

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
            @Nullable String errorMessage,
            String purpose,
            String requestId,
            String source,
            String requestBody,
            String responseBody,
            String finishReason,
            int contentLength,
            String responsePreview,
            String responderEntityId,
            String responderName,
            String triggerSource,
            String addressee,
            String audience,
            String inputKind,
            String billingPrincipal,
            String billingPrincipalId,
            String causalRootRequestId
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
            if (errorMessage != null) obj.addProperty("error", errorMessage);
            if (purpose != null && !purpose.isBlank()) obj.addProperty("purpose", purpose);
            if (requestId != null && !requestId.isBlank()) obj.addProperty("requestId", requestId);
            if (source != null && !source.isBlank()) obj.addProperty("source", source);
            if (finishReason != null && !finishReason.isBlank()) obj.addProperty("finishReason", finishReason);
            obj.addProperty("contentLength", contentLength);
            if (responsePreview != null && !responsePreview.isBlank()) obj.addProperty("responsePreview", responsePreview);
            if (responderEntityId != null && !responderEntityId.isBlank()) obj.addProperty("responderEntityId", responderEntityId);
            if (responderName != null && !responderName.isBlank()) obj.addProperty("responderName", responderName);
            if (triggerSource != null && !triggerSource.isBlank()) obj.addProperty("triggerSource", triggerSource);
            if (addressee != null && !addressee.isBlank()) obj.addProperty("addressee", addressee);
            if (audience != null && !audience.isBlank()) obj.addProperty("audience", audience);
            if (inputKind != null && !inputKind.isBlank()) obj.addProperty("inputKind", inputKind);
            if (billingPrincipal != null && !billingPrincipal.isBlank()) obj.addProperty("billingPrincipal", billingPrincipal);
            if (billingPrincipalId != null && !billingPrincipalId.isBlank()) obj.addProperty("billingPrincipalId", billingPrincipalId);
            if (causalRootRequestId != null && !causalRootRequestId.isBlank()) obj.addProperty("causalRootRequestId", causalRootRequestId);
            if (requestBody != null && !requestBody.isBlank()) obj.addProperty("requestBody", requestBody);
            if (responseBody != null && !responseBody.isBlank()) obj.addProperty("responseBody", responseBody);
            return obj;
        }
    }

    private LogEntry[] buffer;
    private int head = 0;
    private int size = 0;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Consumer<LogEntry>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean coreHookInstalled;

    private LLMLogger() {
        this.buffer = new LogEntry[200];
    }

    public void installCoreHook() {
        if (coreHookInstalled) return;
        coreHookInstalled = true;
        LlmRequestLogger.addListener(this::fromCoreEvent);
    }

    private void fromCoreEvent(LlmRequestLogger.Event event) {
        Level level = event.success() ? Level.INFO : Level.ERROR;
        String status = event.success() ? "success" : "error";
        String summary = event.summary() == null ? "" : event.summary();
        if (event.purpose() != null && !event.purpose().isBlank()) {
            summary = "[" + event.purpose() + "] " + summary;
        }
        log(level, event.provider(), summary, status, event.latencyMs(), event.promptTokens(),
                event.completionTokens(), event.error(), event.purpose(), event.requestId(),
                event.source(), event.requestBody(), event.responseBody(), event.finishReason(),
                event.contentLength(), event.responsePreview(), event.responderEntityId(), event.responderName(),
                event.triggerSource(), event.addressee(), event.audience(), event.inputKind(), event.billingPrincipal(),
                event.billingPrincipalId(), event.causalRootRequestId());
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

    /** Removes every buffered entry belonging to one request identity. */
    public boolean removeByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) return false;
        lock.writeLock().lock();
        try {
            LogEntry[] replacement = new LogEntry[buffer.length];
            int retained = 0;
            boolean removed = false;
            for (int i = 0; i < size; i++) {
                int index = (head - size + i + buffer.length) % buffer.length;
                LogEntry entry = buffer[index];
                if (entry != null && requestId.equals(entry.requestId())) {
                    removed = true;
                } else if (entry != null) {
                    replacement[retained++] = entry;
                }
            }
            if (removed) {
                buffer = replacement;
                size = retained;
                head = retained % buffer.length;
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Clears only the server's in-memory log ring. */
    public void clear() {
        lock.writeLock().lock();
        try {
            Arrays.fill(buffer, null);
            head = 0;
            size = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void log(Level level, String provider, String prompt, String status,
                    long latencyMs, int promptTokens, int completionTokens,
                    @Nullable String errorMessage) {
        log(level, provider, prompt, status, latencyMs, promptTokens, completionTokens, errorMessage,
                "", "", "llmjs", "", "");
    }

    public void log(Level level, String provider, String prompt, String status,
                    long latencyMs, int promptTokens, int completionTokens,
                    @Nullable String errorMessage, String purpose, String requestId, String source,
                    String requestBody, String responseBody) {
        log(level, provider, prompt, status, latencyMs, promptTokens, completionTokens, errorMessage, purpose,
                requestId, source, requestBody, responseBody, "", 0, "");
    }

    public void log(Level level, String provider, String prompt, String status,
                    long latencyMs, int promptTokens, int completionTokens,
                    @Nullable String errorMessage, String purpose, String requestId, String source,
                    String requestBody, String responseBody, String finishReason, int contentLength,
                    String responsePreview) {
        log(level, provider, prompt, status, latencyMs, promptTokens, completionTokens, errorMessage, purpose,
                requestId, source, requestBody, responseBody, finishReason, contentLength, responsePreview,
                "", "", "", "", purpose);
    }

    public void log(Level level, String provider, String prompt, String status,
                    long latencyMs, int promptTokens, int completionTokens,
                    @Nullable String errorMessage, String purpose, String requestId, String source,
                    String requestBody, String responseBody, String finishReason, int contentLength,
                    String responsePreview, String responderEntityId, String responderName,
                    String triggerSource, String audience, String inputKind) {
        log(level, provider, prompt, status, latencyMs, promptTokens, completionTokens, errorMessage,
                purpose, requestId, source, requestBody, responseBody, finishReason, contentLength,
                responsePreview, responderEntityId, responderName, triggerSource, audience, inputKind,
                "", "", "");
    }

    public void log(Level level, String provider, String prompt, String status,
                    long latencyMs, int promptTokens, int completionTokens,
                    @Nullable String errorMessage, String purpose, String requestId, String source,
                    String requestBody, String responseBody, String finishReason, int contentLength,
                    String responsePreview, String responderEntityId, String responderName,
                    String triggerSource, String audience, String inputKind, String billingPrincipal,
                    String billingPrincipalId, String causalRootRequestId) {
        log(level, provider, prompt, status, latencyMs, promptTokens, completionTokens,
                errorMessage, purpose, requestId, source, requestBody, responseBody, finishReason,
                contentLength, responsePreview, responderEntityId, responderName, triggerSource,
                "", audience, inputKind, billingPrincipal, billingPrincipalId, causalRootRequestId);
    }

    public void log(Level level, String provider, String prompt, String status,
                    long latencyMs, int promptTokens, int completionTokens,
                    @Nullable String errorMessage, String purpose, String requestId, String source,
                    String requestBody, String responseBody, String finishReason, int contentLength,
                    String responsePreview, String responderEntityId, String responderName,
                    String triggerSource, String addressee, String audience, String inputKind,
                    String billingPrincipal, String billingPrincipalId, String causalRootRequestId) {
        String summary = prompt == null ? "" : prompt;
        if (summary.length() > 100) summary = summary.substring(0, 100) + "...";
        LogEntry entry = new LogEntry(Instant.now(), level, provider == null ? "" : provider, summary,
                status == null ? "" : status, latencyMs, promptTokens, completionTokens, errorMessage,
                purpose == null ? "" : purpose, requestId == null ? "" : requestId,
                source == null ? "" : source, trimBody(requestBody), trimBody(responseBody),
                finishReason == null ? "" : finishReason, contentLength,
                responsePreview == null ? "" : responsePreview,
                responderEntityId == null ? "" : responderEntityId,
                responderName == null ? "" : responderName,
                triggerSource == null ? "" : triggerSource,
                addressee == null ? "" : addressee,
                audience == null ? "" : audience,
                inputKind == null ? "" : inputKind,
                billingPrincipal == null ? "" : billingPrincipal,
                billingPrincipalId == null ? "" : billingPrincipalId,
                causalRootRequestId == null ? "" : causalRootRequestId);

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

    /**
     * Entry point for other mods (CreatureChat) that embed their own llm-core copy
     * and cannot share LlmRequestLogger listeners.
     */
    public void logExternal(String source, String purpose, String requestId, String provider,
            boolean success, long latencyMs, int promptTokens, int completionTokens,
            String summary, String requestBody, String responseBody, @Nullable String error) {
        Level level = success ? Level.INFO : Level.ERROR;
        String status = success ? "success" : "error";
        String text = summary == null ? "" : summary;
        if (purpose != null && !purpose.isBlank()) {
            text = "[" + purpose + "] " + text;
        }
        log(level, provider, text, status, latencyMs, promptTokens, completionTokens, error,
                purpose, requestId, source == null || source.isBlank() ? "external" : source,
                requestBody, responseBody);
    }

    /**
     * Returns up to {@code count} most recent entries in chronological order
     * (oldest first, newest last). Sorted by {@link LogEntry#timestamp()} so
     * console history stays time-ordered even if concurrent completions race
     * the ring-buffer write path.
     */
    public LogEntry[] getRecentEntries(int count) {
        lock.readLock().lock();
        try {
            int n = Math.min(count, size);
            LogEntry[] result = new LogEntry[n];
            for (int i = 0; i < n; i++) {
                int idx = (head - n + i + buffer.length) % buffer.length;
                result[i] = buffer[idx];
            }
            Arrays.sort(result, TIMESTAMP_ASC);
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
                    filtered.add(buffer[idx]);
                }
            }
            filtered.sort(TIMESTAMP_ASC);
            return filtered.toArray(new LogEntry[0]);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static final Comparator<LogEntry> TIMESTAMP_ASC = Comparator
            .comparing(LogEntry::timestamp, Comparator.nullsLast(Comparator.naturalOrder()));

    public JsonArray toJsonArray(int count) {
        JsonArray arr = new JsonArray();
        for (LogEntry entry : getRecentEntries(count)) {
            arr.add(entry.toJson());
        }
        return arr;
    }

    public void addListener(Consumer<LogEntry> listener) { listeners.add(listener); }
    public void removeListener(Consumer<LogEntry> listener) { listeners.remove(listener); }

    private static String trimBody(String body) {
        if (body == null || body.isBlank()) return "";
        if (body.length() <= MAX_BODY_CHARS) return body;
        return body.substring(0, MAX_BODY_CHARS) + "\n...[truncated]";
    }
}
