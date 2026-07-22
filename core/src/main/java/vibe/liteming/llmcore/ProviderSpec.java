package vibe.liteming.llmcore;

import java.util.List;

public record ProviderSpec(
        String name,
        String format,
        String url,
        String model,
        Double temperature,
        Integer maxTokens,
        List<Credential> credentials) {

    public ProviderSpec {
        name = clean(name);
        format = clean(format).isEmpty() ? "openai" : clean(format).toLowerCase();
        url = clean(url);
        model = clean(model);
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
    }

    public boolean isValid() {
        return !name.isEmpty() && !url.isEmpty() && !model.isEmpty();
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
}
