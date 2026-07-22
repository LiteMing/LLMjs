package vibe.liteming.llmcore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class LlmMessage {
    private final String role;
    private final List<Part> parts;

    public LlmMessage(String role, String content) {
        this(role, List.of(new TextPart(content)));
    }

    public LlmMessage(String role, List<Part> parts) {
        this.role = role == null || role.isBlank() ? "user" : role.trim();
        this.parts = List.copyOf(parts == null ? List.of() : parts);
    }

    public String role() {
        return role;
    }

    public List<Part> parts() {
        return parts;
    }

    public String content() {
        return parts.stream().map(Part::asText).collect(Collectors.joining("\n"));
    }

    public boolean hasImage() {
        return parts.stream().anyMatch(part -> part instanceof ImagePart);
    }

    public sealed interface Part permits TextPart, ImagePart {
        String asText();
    }

    public record TextPart(String text) implements Part {
        public TextPart {
            text = Objects.requireNonNullElse(text, "");
        }

        @Override
        public String asText() {
            return text;
        }
    }

    public record ImagePart(String mimeType, String base64Data, String detail) implements Part {
        public ImagePart {
            mimeType = mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType;
            base64Data = Objects.requireNonNullElse(base64Data, "");
            detail = detail == null || detail.isBlank() ? "low" : detail;
        }

        @Override
        public String asText() {
            return "[image:" + mimeType + "]";
        }

        public String dataUrl() {
            return "data:" + mimeType + ";base64," + base64Data;
        }
    }
}
