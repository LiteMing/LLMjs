package vibe.liteming.llmjs.provider;

import org.jetbrains.annotations.Nullable;
import vibe.liteming.llmcore.LlmMessage;
import vibe.liteming.llmcore.LlmOrchestrator;
import vibe.liteming.llmcore.LlmRequest;
import vibe.liteming.llmcore.LlmRequestContext;
import vibe.liteming.llmcore.ProviderSpec;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.format.MessagePart;
import vibe.liteming.llmjs.pipeline.LLMResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

final class CoreProviderAdapter implements Provider {
    private final ProviderSpec spec;
    private final LlmOrchestrator orchestrator;

    CoreProviderAdapter(ProviderSpec spec, LlmOrchestrator orchestrator) {
        this.spec = spec;
        this.orchestrator = orchestrator;
    }

    @Override
    public String getName() {
        return spec.name();
    }

    @Override
    public String getType() {
        return "simple";
    }

    @Override
    public @Nullable String getFormat() {
        return spec.format();
    }

    @Override
    public String getModel() {
        return spec.model();
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(List<ApiFormat.Message> messages,
            @Nullable Double temperature, @Nullable Integer maxTokens, int timeoutSeconds) {
        List<LlmMessage> coreMessages = messages.stream()
                .map(message -> new LlmMessage(message.role(), message.parts().stream().map(part -> {
                    if (part instanceof MessagePart.ImagePart image) {
                        return (LlmMessage.Part) new LlmMessage.ImagePart(image.mimeType(), image.base64Data(),
                                image.detail());
                    }
                    return (LlmMessage.Part) new LlmMessage.TextPart(part.asText());
                }).toList()))
                .toList();
        LlmRequest request = new LlmRequest(coreMessages, List.of(spec.name()), temperature, maxTokens,
                timeoutSeconds, LlmRequestContext.chat());
        return orchestrator.send(request).thenApply(CoreProviderAdapter::toLegacyResponse);
    }

    @Override
    public CompletableFuture<LLMResponse> testConnection(int timeoutSeconds) {
        return orchestrator.testProvider(spec.name(), timeoutSeconds).thenApply(CoreProviderAdapter::toLegacyResponse);
    }

    @Override
    public boolean isValid() {
        return spec.isValid();
    }

    @Override
    public boolean isConfigured() {
        return spec.credentials().stream().anyMatch(ProviderSpec.Credential::isConfigured);
    }

    @Override
    public String getMaskedKey() {
        return spec.credentials().stream().filter(ProviderSpec.Credential::isConfigured).findFirst()
                .map(credential -> mask(credential.key()))
                .orElse("");
    }

    @Override
    public String getUrl() {
        return spec.url() == null ? "" : spec.url();
    }

    static LLMResponse toLegacyResponse(vibe.liteming.llmcore.LlmResponse response) {
        if (!response.success()) {
            return LLMResponse.error(response.error()).withAttempts(response.attempts().stream()
                    .map(attempt -> new LLMResponse.AttemptRecord(attempt.provider(), attempt.success(),
                            attempt.error(), attempt.latencyMs()))
                    .toList());
        }
        return LLMResponse.success(response.content(), response.model(), response.provider(), response.promptTokens(),
                response.completionTokens(), response.latencyMs()).withAttempts(response.attempts().stream()
                .map(attempt -> new LLMResponse.AttemptRecord(attempt.provider(), attempt.success(), attempt.error(),
                        attempt.latencyMs()))
                .toList());
    }

    private static String mask(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
