package vibe.liteming.llmcore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Optional hooks for mods that want raw HTTP request/response visibility
 * without depending on a specific host (LLMjs, CreatureChat, etc.).
 */
public final class LlmRequestLogger {
    public record Event(
            String source,
            String purpose,
            String requestId,
            String provider,
            String model,
            boolean success,
            long latencyMs,
            int promptTokens,
            int completionTokens,
            String summary,
            String requestBody,
            String responseBody,
            String error,
            String finishReason,
            int contentLength,
            String responsePreview) {
        public Event(String source, String purpose, String requestId, String provider, String model,
                boolean success, long latencyMs, int promptTokens, int completionTokens, String summary,
                String requestBody, String responseBody, String error) {
            this(source, purpose, requestId, provider, model, success, latencyMs, promptTokens,
                    completionTokens, summary, requestBody, responseBody, error, "", 0, "");
        }
    }

    private static final List<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();

    private LlmRequestLogger() {
    }

    public static void addListener(Consumer<Event> listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<Event> listener) {
        LISTENERS.remove(listener);
    }

    public static void publish(Event event) {
        if (event == null || LISTENERS.isEmpty()) return;
        for (Consumer<Event> listener : LISTENERS) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
            }
        }
    }

    public static void publish(String source, LlmRequest request, LlmResponse response) {
        if (request == null || response == null) return;
        String summary = "";
        if (request.messages() != null && !request.messages().isEmpty()) {
            summary = request.messages().get(request.messages().size() - 1).content();
        }
        publish(new Event(
                source == null ? "" : source,
                request.context() == null ? "" : request.context().purpose(),
                request.context() == null ? "" : request.context().requestId(),
                response.provider(),
                response.model(),
                response.success(),
                response.latencyMs(),
                response.promptTokens(),
                response.completionTokens(),
                summary,
                response.requestBody(),
                response.responseBody(),
                response.error(),
                response.finishReason(),
                response.content() == null ? 0 : response.content().length(),
                preview(response.content())));
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= 240 ? clean : clean.substring(0, 237) + "...";
    }
}
