package vibe.liteming.llmjs.json;

import com.google.gson.JsonParser;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.provider.ProviderManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class JsonMode {

    public static void chatJson(String prompt, @Nullable String provider,
                                 @Nullable Double temperature, @Nullable Integer maxTokens,
                                 Consumer<Object> callback) {
        String jsonPrompt = prompt + "\n\nIMPORTANT: Respond ONLY with valid JSON. No markdown, no code blocks, no explanation.";
        List<ApiFormat.Message> messages = new ArrayList<>();
        messages.add(new ApiFormat.Message("system", "You are a JSON generator. Always respond with valid JSON only."));
        messages.add(new ApiFormat.Message("user", jsonPrompt));

        String providerName = provider != null ? provider : LLMConfig.DEFAULT_PROVIDER.get();
        int timeout = LLMConfig.TIMEOUT.get();

        ProviderManager.INSTANCE.sendWithFallback(messages, List.of(providerName), temperature, maxTokens, timeout)
                .thenAccept(response -> {
                    if (!response.isSuccess()) { callback.accept(null); return; }
                    String content = cleanJsonResponse(response.getContent());
                    try {
                        callback.accept(JsonParser.parseString(content));
                    } catch (Exception e) {
                        retryJsonParse(prompt, providerName, temperature, maxTokens, callback);
                    }
                });
    }

    private static void retryJsonParse(String prompt, String provider,
                                        @Nullable Double temperature, @Nullable Integer maxTokens,
                                        Consumer<Object> callback) {
        List<ApiFormat.Message> messages = new ArrayList<>();
        messages.add(new ApiFormat.Message("system", "Respond with valid JSON only. No markdown formatting."));
        messages.add(new ApiFormat.Message("user", prompt + "\n\nYou MUST respond with ONLY valid JSON. No other text."));

        int timeout = LLMConfig.TIMEOUT.get();
        ProviderManager.INSTANCE.sendWithFallback(messages, List.of(provider), temperature, maxTokens, timeout)
                .thenAccept(response -> {
                    if (!response.isSuccess()) { callback.accept(null); return; }
                    try { callback.accept(JsonParser.parseString(cleanJsonResponse(response.getContent()))); }
                    catch (Exception e) { callback.accept(null); }
                });
    }

    static String cleanJsonResponse(String content) {
        content = content.trim();
        if (content.startsWith("```")) {
            content = content.replaceAll("^```(?:json)?\\n?", "").replaceAll("\\n?```$", "");
        }
        return content.trim();
    }
}
