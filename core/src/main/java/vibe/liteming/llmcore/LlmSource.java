package vibe.liteming.llmcore;

import java.util.Map;

/**
 * Provider-returned provenance. A source is not an assertion that its content is trustworthy.
 *
 * @since 1.4.1
 */
public record LlmSource(
        String uri,
        String title,
        String snippet,
        String provider,
        String sourceType,
        Integer startIndex,
        Integer endIndex,
        Map<String, String> metadata) {

    private static final int MAX_URI_CHARS = 4_096;
    private static final int MAX_TEXT_CHARS = 4_096;
    private static final int MAX_METADATA_ENTRIES = 16;

    public LlmSource {
        uri = bounded(uri, MAX_URI_CHARS);
        title = bounded(title, MAX_TEXT_CHARS);
        snippet = bounded(snippet, MAX_TEXT_CHARS);
        provider = bounded(provider, 256);
        sourceType = bounded(sourceType, 128);
        startIndex = validIndex(startIndex);
        endIndex = validIndex(endIndex);
        if (startIndex != null && endIndex != null && endIndex < startIndex) {
            endIndex = null;
        }
        if (metadata == null || metadata.isEmpty()) {
            metadata = Map.of();
        } else {
            java.util.LinkedHashMap<String, String> bounded = new java.util.LinkedHashMap<>();
            metadata.forEach((key, value) -> {
                if (bounded.size() < MAX_METADATA_ENTRIES) {
                    bounded.put(bounded(key, 128), bounded(value, 1_024));
                }
            });
            metadata = Map.copyOf(bounded);
        }
    }

    public LlmSource(String uri, String title, String snippet, String provider, String sourceType) {
        this(uri, title, snippet, provider, sourceType, null, null, Map.of());
    }

    private static Integer validIndex(Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    private static String bounded(String value, int maxChars) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars);
    }
}
