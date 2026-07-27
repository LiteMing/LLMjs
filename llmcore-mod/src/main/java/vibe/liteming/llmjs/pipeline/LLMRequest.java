package vibe.liteming.llmjs.pipeline;

import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.provider.Provider;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LLMRequest {
    private final String prompt;
    private @Nullable String systemPrompt;
    private @Nullable String providerName;
    private @Nullable Double temperature;
    private @Nullable Integer maxTokens;
    private @Nullable List<String> fallbackChain;
    private final List<PostProcessor> postProcessors = new ArrayList<>();
    private @Nullable Predicate<String> validator;
    private int retries = 0;
    private @Nullable Consumer<String> onInvalid;
    private int maxLength = 0;
    private @Nullable String truncatePattern;
    private boolean consumed = false;

    public LLMRequest(String prompt) {
        this.prompt = prompt;
    }

    public LLMRequest(String prompt, @Nullable String systemPrompt, @Nullable String provider,
                      @Nullable Double temperature, @Nullable Integer maxTokens,
                      @Nullable List<String> fallback) {
        this.prompt = prompt;
        this.systemPrompt = systemPrompt;
        this.providerName = provider;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.fallbackChain = fallback;
    }

    // Builder methods - each returns `this` for chaining
    public LLMRequest system(String s) { this.systemPrompt = s; return this; }
    public LLMRequest provider(String n) { this.providerName = n; return this; }
    public LLMRequest temperature(double t) { this.temperature = t; return this; }
    public LLMRequest maxTokens(int t) { this.maxTokens = t; return this; }

    public LLMRequest fallback(List<String> chain) {
        this.fallbackChain = new ArrayList<>(chain);
        return this;
    }

    public LLMRequest extract(String regex) {
        postProcessors.add(PostProcessor.extract(regex));
        return this;
    }

    public LLMRequest replace(String regex, String replacement) {
        postProcessors.add(PostProcessor.replace(regex, replacement));
        return this;
    }

    public LLMRequest pipe(String presetName) {
        postProcessors.add(input -> {
            RegexPreset preset = RegexPreset.get(presetName);
            return preset != null ? preset.apply(input) : input;
        });
        return this;
    }

    public LLMRequest validate(Predicate<String> v) { this.validator = v; return this; }
    public LLMRequest retries(int n) { this.retries = n; return this; }
    public LLMRequest onInvalid(Consumer<String> h) { this.onInvalid = h; return this; }
    public LLMRequest maxLength(int n) { this.maxLength = n; return this; }
    public LLMRequest truncateAt(String regex) { this.truncatePattern = regex; return this; }

    // Terminal operations
    public void tell(ServerPlayer player) {
        execute(content -> OutputTarget.tell(player, content));
    }

    public void actionbar(ServerPlayer player) {
        execute(content -> OutputTarget.actionbar(player, content));
    }

    public void tellraw(ServerPlayer player, String color, boolean bold) {
        execute(content -> OutputTarget.tellraw(player, content, color, bold));
    }

    public void broadcast(MinecraftServer server) {
        execute(content -> OutputTarget.broadcast(server, content));
    }

    public void broadcastActionbar(MinecraftServer server) {
        execute(content -> OutputTarget.broadcastActionbar(server, content));
    }

    public void callback(Consumer<LLMResponse> handler) {
        executeRaw(handler);
    }

    private void execute(Consumer<String> outputHandler) {
        executeRaw(response -> {
            if (response.isSuccess() && response.getContent() != null) {
                outputHandler.accept(response.getContent());
            }
        });
    }

    private void executeRaw(Consumer<LLMResponse> handler) {
        if (consumed) throw new IllegalStateException("LLMRequest already consumed");
        consumed = true;
        executeWithRetry(handler, 0);
    }

    private void executeWithRetry(Consumer<LLMResponse> handler, int attempt) {
        List<ApiFormat.Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new ApiFormat.Message("system", systemPrompt));
        }
        messages.add(new ApiFormat.Message("user", prompt));

        List<String> chain;
        if (fallbackChain != null && !fallbackChain.isEmpty()) {
            chain = fallbackChain;
        } else if (providerName != null) {
            chain = List.of(providerName);
        } else {
            Provider defaultProvider = ProviderManager.INSTANCE.getDefaultProvider();
            chain = defaultProvider != null ? List.of(defaultProvider.getName()) : List.of();
        }

        int timeout = LLMConfig.TIMEOUT.get();

        ProviderManager.INSTANCE.sendWithFallback(messages, chain, temperature, maxTokens, timeout)
                .thenAccept(response -> {
                    if (!response.isSuccess()) {
                        handler.accept(response);
                        return;
                    }

                    String content = response.getContent();
                    for (PostProcessor pp : postProcessors) {
                        content = pp.process(content);
                    }

                    if (maxLength > 0 && content.length() > maxLength) {
                        content = truncate(content);
                    }

                    if (validator != null && !validator.test(content)) {
                        if (attempt < retries) {
                            executeWithRetry(handler, attempt + 1);
                            return;
                        }
                        if (onInvalid != null) onInvalid.accept(content);
                        handler.accept(LLMResponse.error("Validation failed after " + (attempt + 1) + " attempts"));
                        return;
                    }

                    LLMResponse finalResponse = LLMResponse.success(
                            content, response.getModel(), response.getProvider(),
                            response.getPromptTokens(), response.getCompletionTokens(),
                            response.getLatencyMs()
                    ).withAttempts(response.getAttempts());

                    handler.accept(finalResponse);
                });
    }

    private String truncate(String content) {
        if (content.length() <= maxLength) return content;
        if (truncatePattern != null) {
            Pattern pattern = Pattern.compile(truncatePattern);
            Matcher matcher = pattern.matcher(content.substring(0, maxLength));
            int lastEnd = -1;
            while (matcher.find()) lastEnd = matcher.end();
            if (lastEnd > 0) return content.substring(0, lastEnd);
        }
        return content.substring(0, maxLength);
    }
}
