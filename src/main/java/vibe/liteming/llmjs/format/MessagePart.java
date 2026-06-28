package vibe.liteming.llmjs.format;

import org.jetbrains.annotations.Nullable;

public interface MessagePart {
    String asText();

    record TextPart(String text) implements MessagePart {
        public TextPart {
            if (text == null) text = "";
        }

        @Override
        public String asText() {
            return text;
        }
    }

    record ImagePart(String mimeType, String base64Data, @Nullable String detail,
                     int width, int height, int byteSize) implements MessagePart {
        public ImagePart {
            if (mimeType == null || mimeType.isBlank()) mimeType = "image/jpeg";
            if (base64Data == null) base64Data = "";
            if (detail == null || detail.isBlank()) detail = "low";
        }

        @Override
        public String asText() {
            return "[image:" + mimeType + "," + byteSize + " bytes]";
        }

        public String dataUrl() {
            return "data:" + mimeType + ";base64," + base64Data;
        }
    }

    static TextPart text(String text) {
        return new TextPart(text);
    }

    static ImagePart image(String mimeType, String base64Data, @Nullable String detail,
                           int width, int height, int byteSize) {
        return new ImagePart(mimeType, base64Data, detail, width, height, byteSize);
    }
}
