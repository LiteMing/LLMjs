package vibe.liteming.llmjs.format;

import vibe.liteming.llmjs.pipeline.LLMResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public interface ApiFormat {

    String buildUrl(String baseUrl, String model, String key);
    Map<String, String> buildHeaders(String key);
    String buildBody(String model, List<Message> messages, Double temperature, Integer maxTokens);
    LLMResponse parseResponse(String responseBody, String providerName, long latencyMs);

    final class Message {
        private final String role;
        private final List<MessagePart> parts;

        public Message(String role, String content) {
            this(role, List.of(MessagePart.text(content)));
        }

        public Message(String role, List<MessagePart> parts) {
            this.role = role != null ? role : "user";
            this.parts = Collections.unmodifiableList(new ArrayList<>(parts != null ? parts : List.of()));
        }

        public static Message userWithImage(String text, MessagePart.ImagePart image) {
            return new Message("user", List.of(MessagePart.text(text), image));
        }

        public String role() {
            return role;
        }

        public String content() {
            return parts.stream()
                    .filter(part -> part instanceof MessagePart.TextPart)
                    .map(MessagePart::asText)
                    .collect(Collectors.joining("\n"));
        }

        public List<MessagePart> parts() {
            return parts;
        }

        public boolean hasImage() {
            return parts.stream().anyMatch(part -> part instanceof MessagePart.ImagePart);
        }
    }

    static ApiFormat byName(String name) {
        return switch (name.toLowerCase()) {
            case "openai" -> new OpenAiFormat();
            case "claude" -> new ClaudeFormat();
            case "gemini" -> new GeminiFormat();
            default -> throw new IllegalArgumentException("Unknown API format: " + name);
        };
    }
}
