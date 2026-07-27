package vibe.liteming.llmjs.session;

import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.pipeline.LLMResponse;
import vibe.liteming.llmjs.provider.ProviderManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

public class ChatSession {
    private final String providerName;
    private @Nullable String systemPrompt;
    private double temperature = 0.7;
    private int maxTokens = 1000;
    private final List<ApiFormat.Message> history = Collections.synchronizedList(new ArrayList<>());
    private int maxHistory = 20;
    private @Nullable UUID delegatedBillingPlayer;

    public ChatSession(String providerName) {
        this.providerName = providerName;
    }

    public ChatSession system(String prompt) { this.systemPrompt = prompt; return this; }
    public ChatSession temperature(double t) { this.temperature = t; return this; }
    public ChatSession maxTokens(int t) { this.maxTokens = t; return this; }
    public ChatSession maxHistory(int n) { this.maxHistory = n; return this; }
    public ChatSession billTo(ServerPlayer player) {
        if (player == null) throw new IllegalArgumentException("billTo requires a player");
        delegatedBillingPlayer = player.getUUID();
        return this;
    }

    public void chat(String message, Consumer<LLMResponse> callback) {
        List<ApiFormat.Message> messages = new ArrayList<>();
        if (systemPrompt != null) messages.add(new ApiFormat.Message("system", systemPrompt));
        synchronized (history) {
            messages.addAll(history);
        }
        messages.add(new ApiFormat.Message("user", message));

        int timeout = LLMConfig.TIMEOUT.get();
        var billing = delegatedBillingPlayer == null
                ? ProviderManager.INSTANCE.createScriptBilling(
                        messages, List.of(providerName), temperature, maxTokens, timeout, 1)
                : ProviderManager.INSTANCE.createPlayerBilling(delegatedBillingPlayer,
                        messages, List.of(providerName), temperature, maxTokens, timeout, 1);
        ProviderManager.INSTANCE.sendWithFallback(
                messages, List.of(providerName), temperature, maxTokens, timeout, billing)
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        synchronized (history) {
                            history.add(new ApiFormat.Message("user", message));
                            history.add(new ApiFormat.Message("assistant", response.getContent()));
                            while (history.size() > maxHistory * 2) {
                                history.remove(0);
                                history.remove(0);
                            }
                        }
                    }
                    callback.accept(response);
                });
    }

    public void clear() { history.clear(); }
    public int getHistorySize() { return history.size(); }
}
