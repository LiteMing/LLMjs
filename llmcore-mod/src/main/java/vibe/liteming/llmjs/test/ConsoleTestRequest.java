package vibe.liteming.llmjs.test;

import vibe.liteming.llmcore.LlmMessage;
import vibe.liteming.llmcore.LlmMessageDraft;
import vibe.liteming.llmcore.LlmRouteOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Versioned, mod-neutral handoff consumed by the Console Test pipeline. */
public record ConsoleTestRequest(
        int schemaVersion,
        String requestId,
        RoutingMode routingMode,
        String purpose,
        String generationType,
        List<String> providerChain,
        List<MessageEntry> messages,
        LlmRouteOptions overrides,
        Map<String, String> metadata) {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_MESSAGES = 256;
    public static final int MAX_PARTS_PER_MESSAGE = 16;
    public static final int MAX_PROVIDER_CHAIN = 16;
    public static final int MAX_METADATA_ENTRIES = 64;
    public static final int MAX_TOTAL_CONTENT_CHARS = 160_000;

    public ConsoleTestRequest {
        requestId = clean(requestId);
        routingMode = routingMode == null ? RoutingMode.PURPOSE : routingMode;
        purpose = clean(purpose).isEmpty() ? "DEBUG_TEST" : clean(purpose);
        generationType = clean(generationType);
        providerChain = cleanList(providerChain);
        messages = messages == null ? List.of() : List.copyOf(messages);
        overrides = overrides == null ? LlmRouteOptions.empty() : overrides;
        metadata = cleanMap(metadata);
    }

    public enum RoutingMode {
        PURPOSE,
        EXPLICIT_CHAIN
    }

    public record MessageEntry(
            String entryId,
            String provenance,
            String role,
            List<Part> parts,
            boolean required,
            int priority) {
        public MessageEntry {
            entryId = clean(entryId);
            provenance = clean(provenance);
            role = clean(role).isEmpty() ? "user" : clean(role).toLowerCase(java.util.Locale.ROOT);
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        public static MessageEntry text(String entryId, String provenance, String role, String content,
                boolean required, int priority) {
            return new MessageEntry(entryId, provenance, role, List.of(Part.text(content)), required, priority);
        }

        LlmMessageDraft.Entry toDraftEntry() {
            List<LlmMessage.Part> coreParts = parts.stream().map(Part::toCorePart).toList();
            return new LlmMessageDraft.Entry(entryId, provenance, new LlmMessage(role, coreParts), required, priority);
        }
    }

    public record Part(String type, String text, String mimeType, String base64Data, String detail) {
        public Part {
            type = clean(type).isEmpty() ? "text" : clean(type).toLowerCase(java.util.Locale.ROOT);
            text = text == null ? "" : text;
            mimeType = clean(mimeType);
            base64Data = base64Data == null ? "" : base64Data;
            detail = clean(detail);
        }

        public static Part text(String text) {
            return new Part("text", text, "", "", "");
        }

        public static Part image(String mimeType, String base64Data, String detail) {
            return new Part("image", "", mimeType, base64Data, detail);
        }

        LlmMessage.Part toCorePart() {
            if ("image".equals(type)) return new LlmMessage.ImagePart(mimeType, base64Data, detail);
            return new LlmMessage.TextPart(text);
        }
    }

    public static ConsoleTestRequest simple(UUID requestId, String prompt, String provider) {
        String name = clean(provider);
        return new ConsoleTestRequest(SCHEMA_VERSION, requestId.toString(),
                name.isEmpty() ? RoutingMode.PURPOSE : RoutingMode.EXPLICIT_CHAIN,
                "DEBUG_TEST", "SIMPLE", name.isEmpty() ? List.of() : List.of(name),
                List.of(MessageEntry.text("simple.prompt", "console", "user", prompt, true, 1000)),
                LlmRouteOptions.empty(), Map.of("source", "console-simple"));
    }

    public UUID requestUuid() {
        return UUID.fromString(requestId);
    }

    public LlmMessageDraft toDraft() {
        return new LlmMessageDraft(messages.stream().map(MessageEntry::toDraftEntry).toList());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String cleaned = clean(value);
            result.add(cleaned);
        }
        return List.copyOf(result);
    }

    private static Map<String, String> cleanMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = clean(entry.getKey());
            if (!key.isEmpty()) result.put(key, entry.getValue() == null ? "" : entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
