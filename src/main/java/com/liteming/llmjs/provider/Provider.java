package com.liteming.llmjs.provider;

import com.liteming.llmjs.format.ApiFormat;
import com.liteming.llmjs.pipeline.LLMResponse;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Provider {
    String getName();
    String getType();
    @Nullable String getFormat();
    String getModel();
    CompletableFuture<LLMResponse> sendAsync(List<ApiFormat.Message> messages,
                                              @Nullable Double temperature,
                                              @Nullable Integer maxTokens,
                                              int timeoutSeconds);
    CompletableFuture<LLMResponse> testConnection(int timeoutSeconds);
    boolean isValid();
    /** Whether this provider has a valid API key configured. */
    boolean isConfigured();
    String getMaskedKey();
}
