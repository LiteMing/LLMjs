package vibe.liteming.llmjs.pipeline;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class LLMResponse {
    private final boolean success;
    private final @Nullable String content;
    private final @Nullable String error;
    private final @Nullable String model;
    private final @Nullable String provider;
    private final int promptTokens;
    private final int completionTokens;
    private final long latencyMs;
    private final List<AttemptRecord> attempts;

    public record AttemptRecord(String provider, boolean success, @Nullable String error, long latencyMs) {}

    private LLMResponse(boolean success, @Nullable String content, @Nullable String error,
                         @Nullable String model, @Nullable String provider,
                         int promptTokens, int completionTokens, long latencyMs,
                         List<AttemptRecord> attempts) {
        this.success = success;
        this.content = content;
        this.error = error;
        this.model = model;
        this.provider = provider;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
        this.attempts = attempts;
    }

    public static LLMResponse success(String content, String model, String provider,
                                       int promptTokens, int completionTokens, long latencyMs) {
        return new LLMResponse(true, content, null, model, provider,
                promptTokens, completionTokens, latencyMs, new ArrayList<>());
    }

    public static LLMResponse error(String error) {
        return new LLMResponse(false, null, error, null, null, 0, 0, 0, new ArrayList<>());
    }

    public LLMResponse withAttempts(List<AttemptRecord> attempts) {
        return new LLMResponse(success, content, error, model, provider,
                promptTokens, completionTokens, latencyMs, attempts);
    }

    public boolean isSuccess() { return success; }
    public @Nullable String getContent() { return content; }
    public @Nullable String getError() { return error; }
    public @Nullable String getModel() { return model; }
    public @Nullable String getProvider() { return provider; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public long getLatencyMs() { return latencyMs; }
    public List<AttemptRecord> getAttempts() { return attempts; }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("success", success);
        if (success) {
            obj.addProperty("content", content);
            obj.addProperty("model", model);
            obj.addProperty("provider", provider);
            obj.addProperty("promptTokens", promptTokens);
            obj.addProperty("completionTokens", completionTokens);
            obj.addProperty("latencyMs", latencyMs);
        } else {
            obj.addProperty("error", error);
        }
        return obj;
    }
}
