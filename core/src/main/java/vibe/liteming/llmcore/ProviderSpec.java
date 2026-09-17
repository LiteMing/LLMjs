package vibe.liteming.llmcore;

import java.util.List;
import java.util.LinkedHashSet;

public record ProviderSpec(
        String name,
        String format,
        String url,
        String model,
        Double temperature,
        Integer maxTokens,
        Integer contextWindowTokens,
        List<Credential> credentials,
        List<String> models,
        RequestMode requestMode) {

    public static final int MAX_MODELS = 16;

    public enum RequestMode {
        ROTATION,
        PARALLEL;

        public static RequestMode parse(String value) {
            return "parallel".equalsIgnoreCase(clean(value)) ? PARALLEL : ROTATION;
        }

        public String configValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public ProviderSpec(String name, String format, String url, String model, Double temperature,
            Integer maxTokens, List<Credential> credentials) {
        this(name, format, url, model, temperature, maxTokens, null, credentials,
                parseModels(model), RequestMode.ROTATION);
    }

    public ProviderSpec(String name, String format, String url, String model, Double temperature,
            Integer maxTokens, Integer contextWindowTokens, List<Credential> credentials) {
        this(name, format, url, model, temperature, maxTokens, contextWindowTokens, credentials,
                parseModels(model), RequestMode.ROTATION);
    }

    public ProviderSpec {
        name = clean(name);
        format = clean(format).isEmpty() ? "openai" : clean(format).toLowerCase();
        url = clean(url);
        models = normalizeModels(models == null || models.isEmpty() ? parseModels(model) : models);
        model = models.isEmpty() ? clean(model) : models.get(0);
        contextWindowTokens = contextWindowTokens != null && contextWindowTokens > 0
                ? contextWindowTokens : null;
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
        requestMode = requestMode == null ? RequestMode.ROTATION : requestMode;
    }

    public boolean isValid() {
        return !name.isEmpty() && !url.isEmpty() && !models.isEmpty();
    }

    public ProviderSpec forModel(String selectedModel) {
        String selected = clean(selectedModel);
        return new ProviderSpec(name, format, url, selected, temperature, maxTokens,
                contextWindowTokens, credentials, List.of(selected), RequestMode.ROTATION);
    }

    public record Credential(String id, String key, int weight) {
        public Credential {
            id = clean(id);
            key = key == null ? "" : key.trim();
            weight = Math.max(1, weight);
        }

        public boolean isConfigured() {
            return !key.isEmpty();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static List<String> parseModels(String value) {
        if (value == null || value.isBlank()) return List.of();
        return normalizeModels(java.util.Arrays.asList(value.split(",")));
    }

    private static List<String> normalizeModels(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String cleaned = clean(value);
                if (!cleaned.isEmpty()) unique.add(cleaned);
                if (unique.size() >= MAX_MODELS) break;
            }
        }
        return List.copyOf(unique);
    }
}
