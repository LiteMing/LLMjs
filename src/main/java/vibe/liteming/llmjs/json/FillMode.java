package vibe.liteming.llmjs.json;

import com.google.gson.*;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.provider.ProviderManager;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.UUID;
import vibe.liteming.llmcore.LlmBillingContext;

public class FillMode {

    public static void fill(JsonObject template, String description,
                            @Nullable String provider, @Nullable Double temperature,
                            @Nullable Integer maxTokens, Consumer<Object> callback) {
        fill(template, description, provider, temperature, maxTokens, callback, null);
    }

    public static void fill(JsonObject template, String description,
                            @Nullable String provider, @Nullable Double temperature,
                            @Nullable Integer maxTokens, Consumer<Object> callback,
                            @Nullable UUID billingPlayerId) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : template.entrySet()) {
            keys.add(entry.getKey());
        }

        String keysStr = String.join(", ", keys);
        String prompt = "Fill in values for these fields: " + keysStr + "\n"
                + "Context: " + description + "\n\n"
                + "Respond with ONLY the values separated by | in this exact order: " + keysStr + "\n"
                + "Do not include field names. Do not include any other text.\n"
                + "Example format: value1|value2|value3";

        List<ApiFormat.Message> messages = new ArrayList<>();
        messages.add(new ApiFormat.Message("system", "You fill in values for templates. Respond with ONLY pipe-separated values. No field names, no explanation."));
        messages.add(new ApiFormat.Message("user", prompt));

        String providerName = provider != null ? provider : LLMConfig.DEFAULT_PROVIDER.get();
        int timeout = LLMConfig.TIMEOUT.get();
        LlmBillingContext billing = billingPlayerId == null
                ? ProviderManager.INSTANCE.createScriptBilling(
                        messages, List.of(providerName), temperature, maxTokens, timeout, 1)
                : ProviderManager.INSTANCE.createPlayerBilling(billingPlayerId,
                        messages, List.of(providerName), temperature, maxTokens, timeout, 1);

        ProviderManager.INSTANCE.sendWithFallback(
                messages, List.of(providerName), temperature, maxTokens, timeout, billing)
                .thenAccept(response -> {
                    if (!response.isSuccess()) { callback.accept(null); return; }
                    String content = response.getContent().trim();
                    String[] values = content.split("\\|", -1);
                    if (values.length != keys.size()) { callback.accept(null); return; }
                    JsonObject result = new JsonObject();
                    for (int i = 0; i < keys.size(); i++) {
                        result.addProperty(keys.get(i), values[i].trim());
                    }
                    callback.accept(result);
                });
    }
}
