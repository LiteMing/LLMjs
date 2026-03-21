package com.liteming.llmjs.provider;

import com.liteming.llmjs.format.ApiFormat;
import com.liteming.llmjs.http.HttpService;
import com.liteming.llmjs.pipeline.LLMResponse;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SimpleProvider implements Provider {
    private final String name;
    private final String formatName;
    private final String url;
    private final String key;
    private final String model;
    private final @Nullable Double defaultTemperature;
    private final @Nullable Integer defaultMaxTokens;
    private final ApiFormat format;

    public SimpleProvider(String name, String formatName, String url, String key, String model,
                          @Nullable Double defaultTemperature, @Nullable Integer defaultMaxTokens) {
        this.name = name;
        this.formatName = formatName;
        this.url = url;
        this.key = key;
        this.model = model;
        this.defaultTemperature = defaultTemperature;
        this.defaultMaxTokens = defaultMaxTokens;
        this.format = ApiFormat.byName(formatName);
    }

    @Override public String getName() { return name; }
    @Override public String getType() { return "simple"; }
    @Override public @Nullable String getFormat() { return formatName; }
    @Override public String getModel() { return model; }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(List<ApiFormat.Message> messages,
                                                      @Nullable Double temperature,
                                                      @Nullable Integer maxTokens,
                                                      int timeoutSeconds) {
        Double temp = temperature != null ? temperature : defaultTemperature;
        Integer tokens = maxTokens != null ? maxTokens : defaultMaxTokens;
        String requestUrl = format.buildUrl(url, model, key);
        Map<String, String> headers = format.buildHeaders(key);
        String body = format.buildBody(model, messages, temp, tokens);
        return HttpService.INSTANCE.postAsync(requestUrl, body, headers, timeoutSeconds)
                .thenApply(result -> {
                    if (!result.isSuccess()) {
                        return LLMResponse.error("HTTP " + result.statusCode() + ": " + result.body());
                    }
                    return format.parseResponse(result.body(), name, result.latencyMs());
                });
    }

    @Override
    public CompletableFuture<LLMResponse> testConnection(int timeoutSeconds) {
        List<ApiFormat.Message> testMessages = List.of(
                new ApiFormat.Message("user", "Say 'ok' to confirm connection.")
        );
        return sendAsync(testMessages, 0.1, 10, timeoutSeconds);
    }

    @Override
    public boolean isValid() {
        return url != null && !url.isEmpty() && model != null && !model.isEmpty();
    }

    @Override
    public boolean isConfigured() {
        return isValid() && key != null && !key.isEmpty();
    }

    @Override
    public String getMaskedKey() {
        if (key == null || key.length() < 4) return "***";
        return key.substring(0, 4) + "***";
    }
}
