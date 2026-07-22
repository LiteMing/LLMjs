package vibe.liteming.llmcore;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class LlmOrchestrator {
    private static final long RATE_LIMIT_COOLDOWN_MS = 60_000L;
    private static final long TRANSIENT_FAILURE_COOLDOWN_MS = 10_000L;
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final Map<String, ProviderRuntime> providers = new ConcurrentHashMap<>();
    private volatile PriorityRoutingConfig routingConfig = PriorityRoutingConfig.empty();

    public LlmOrchestrator(Map<String, ProviderSpec> providerSpecs) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        replaceProviders(providerSpecs);
    }

    public void replaceProviders(Map<String, ProviderSpec> providerSpecs) {
        Map<String, ProviderRuntime> replacement = new LinkedHashMap<>();
        if (providerSpecs != null) {
            providerSpecs.forEach((name, spec) -> replacement.put(name, new ProviderRuntime(spec)));
        }
        providers.clear();
        providers.putAll(replacement);
    }

    /**
     * Install the global priority-routing table. May be {@code null} to clear.
     * Callers (typically the /llm console persistence layer) update this at runtime;
     * {@link #send(LlmRequest)} and {@link #sendStreaming(LlmRequest, Consumer)}
     * consult it when the request carries no explicit providerChain.
     */
    public void setRoutingConfig(PriorityRoutingConfig config) {
        this.routingConfig = config == null ? PriorityRoutingConfig.empty() : config;
    }

    public PriorityRoutingConfig getRoutingConfig() {
        return routingConfig;
    }

    /**
     * Resolve the effective provider chain for a request: explicit caller-supplied
     * chain wins; otherwise the global {@link PriorityRoutingConfig} for the request's
     * purpose (with fallback to its default chain); otherwise every known provider.
     */
    private List<String> resolveChain(LlmRequest request) {
        List<String> requested = request.providerChain();
        if (requested != null && !requested.isEmpty()) return new ArrayList<>(requested);
        List<String> all = new ArrayList<>(providers.keySet());
        return new ArrayList<>(routingConfig.resolveChain(
                request.context() == null ? null : request.context().purpose(), all));
    }

    public Set<String> getProviderNames() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    public ProviderSpec getProviderSpec(String name) {
        ProviderRuntime runtime = providers.get(name);
        return runtime == null ? null : runtime.spec;
    }

    public CompletableFuture<LlmResponse> send(LlmRequest request) {
        List<String> chain = resolveChain(request);
        return attemptProvider(request, chain, 0, new ArrayList<>())
                .thenApply(response -> {
                    LlmRequestLogger.publish("llm-core", request, response);
                    return response;
                });
    }

    public CompletableFuture<LlmResponse> sendStreaming(LlmRequest request, Consumer<String> onDelta) {
        List<String> chain = resolveChain(request);
        return attemptStreaming(request, chain, 0, new ArrayList<>(), onDelta == null ? delta -> { } : onDelta)
                .thenApply(response -> {
                    LlmRequestLogger.publish("llm-core", request, response);
                    return response;
                });
    }

    private CompletableFuture<LlmResponse> attemptStreaming(LlmRequest request, List<String> chain, int index,
            List<LlmResponse.Attempt> attempts, Consumer<String> onDelta) {
        if (index >= chain.size()) return CompletableFuture.completedFuture(LlmResponse.failure("All providers failed", attempts));
        String providerName = chain.get(index);
        ProviderRuntime provider = providers.get(providerName);
        if (provider == null) {
            attempts.add(new LlmResponse.Attempt(providerName, "", false, "Provider not found", 0L));
            return attemptStreaming(request, chain, index + 1, attempts, onDelta);
        }
        if (!"openai".equals(provider.spec.format())) {
            LlmRequest singleProvider = new LlmRequest(request.messages(), List.of(providerName), request.temperature(),
                    request.maxTokens(), request.timeoutSeconds(), request.context());
            return send(singleProvider).thenCompose(response -> {
                attempts.addAll(response.attempts());
                if (response.success()) {
                    onDelta.accept(response.content());
                    return CompletableFuture.completedFuture(new LlmResponse(true, response.content(), "",
                            response.provider(), response.model(), response.credentialId(), response.promptTokens(),
                            response.completionTokens(), response.latencyMs(), attempts));
                }
                return attemptStreaming(request, chain, index + 1, attempts, onDelta);
            });
        }
        CredentialRuntime credential = provider.selectCredential(System.currentTimeMillis(), null);
        if (credential == null) {
            attempts.add(new LlmResponse.Attempt(providerName, "", false, "No healthy credential", 0L));
            return attemptStreaming(request, chain, index + 1, attempts, onDelta);
        }
        credential.inflight.incrementAndGet();
        long startedAt = System.currentTimeMillis();
        return sendSingleStreaming(request, provider.spec, credential.spec, onDelta).handle((result, throwable) -> {
            credential.inflight.decrementAndGet();
            return throwable == null ? result : StreamResult.failure(rootMessage(throwable), 0,
                    System.currentTimeMillis() - startedAt, false);
        }).thenCompose(result -> {
            attempts.add(new LlmResponse.Attempt(providerName, credential.spec.id(), result.success, result.error,
                    result.latencyMs));
            if (result.success) {
                credential.consecutiveFailures.set(0);
                return CompletableFuture.completedFuture(new LlmResponse(true, result.content, "", providerName,
                        provider.spec.model(), credential.spec.id(), 0, 0, result.latencyMs, attempts,
                        result.requestBody, result.responseBody));
            }
            applyFailure(credential, result.httpStatus);
            if (result.emittedContent) {
                return CompletableFuture.completedFuture(new LlmResponse(false, "",
                        "Streaming provider failed after emitting content: " + result.error, providerName,
                        provider.spec.model(), credential.spec.id(), 0, 0, result.latencyMs, attempts,
                        result.requestBody, result.responseBody));
            }
            if (isCredentialRetryable(result.httpStatus)
                    && provider.selectCredential(System.currentTimeMillis(), credential) != null) {
                return attemptStreaming(request, chain, index, attempts, onDelta);
            }
            return attemptStreaming(request, chain, index + 1, attempts, onDelta);
        });
    }

    public CompletableFuture<LlmResponse> testProvider(String providerName, int timeoutSeconds) {
        LlmRequest request = new LlmRequest(
                List.of(new LlmMessage("user", "Reply with OK.")),
                List.of(providerName),
                0.0,
                8,
                timeoutSeconds,
                new LlmRequestContext("", "DEBUG_TEST", "", "", "", "", providerName, false));
        return send(request);
    }

    private CompletableFuture<LlmResponse> attemptProvider(LlmRequest request, List<String> chain, int index,
            List<LlmResponse.Attempt> attempts) {
        if (index >= chain.size()) {
            return CompletableFuture.completedFuture(LlmResponse.failure("All providers failed", attempts));
        }
        String providerName = chain.get(index);
        ProviderRuntime provider = providers.get(providerName);
        if (provider == null) {
            attempts.add(new LlmResponse.Attempt(providerName, "", false, "Provider not found", 0L));
            return attemptProvider(request, chain, index + 1, attempts);
        }
        CredentialRuntime credential = provider.selectCredential(System.currentTimeMillis(), null);
        if (credential == null) {
            attempts.add(new LlmResponse.Attempt(providerName, "", false, "No healthy credential", 0L));
            return attemptProvider(request, chain, index + 1, attempts);
        }

        long startedAt = System.currentTimeMillis();
        credential.inflight.incrementAndGet();
        return sendSingle(request, provider.spec, credential.spec)
                .handle((result, throwable) -> {
                    credential.inflight.decrementAndGet();
                    long latency = System.currentTimeMillis() - startedAt;
                    if (throwable != null) {
                        credential.cooldownUntil = System.currentTimeMillis() + TRANSIENT_FAILURE_COOLDOWN_MS;
                        return SingleResult.failure("Request failed: " + rootMessage(throwable), 0, latency);
                    }
                    return result;
                })
                .thenCompose(result -> {
                    attempts.add(new LlmResponse.Attempt(providerName, credential.spec.id(), result.success,
                            result.error, result.latencyMs));
                    if (result.success) {
                        credential.consecutiveFailures.set(0);
                        return CompletableFuture.completedFuture(new LlmResponse(
                                true,
                                result.content,
                                "",
                                providerName,
                                provider.spec.model(),
                                credential.spec.id(),
                                result.promptTokens,
                                result.completionTokens,
                                result.latencyMs,
                                attempts,
                                result.requestBody,
                                result.responseBody));
                    }
                    applyFailure(credential, result.httpStatus);
                    if (!isCredentialRetryable(result.httpStatus)) {
                        return attemptProvider(request, chain, index + 1, attempts);
                    }
                    CredentialRuntime nextCredential = provider.selectCredential(System.currentTimeMillis(), credential);
                    if (nextCredential != null) {
                        return attemptProviderWithCredential(request, chain, index, attempts, provider, nextCredential);
                    }
                    return attemptProvider(request, chain, index + 1, attempts);
                });
    }

    private CompletableFuture<LlmResponse> attemptProviderWithCredential(LlmRequest request, List<String> chain,
            int index, List<LlmResponse.Attempt> attempts, ProviderRuntime provider, CredentialRuntime credential) {
        long startedAt = System.currentTimeMillis();
        credential.inflight.incrementAndGet();
        return sendSingle(request, provider.spec, credential.spec)
                .handle((result, throwable) -> {
                    credential.inflight.decrementAndGet();
                    long latency = System.currentTimeMillis() - startedAt;
                    return throwable == null ? result
                            : SingleResult.failure("Request failed: " + rootMessage(throwable), 0, latency);
                })
                .thenCompose(result -> {
                    attempts.add(new LlmResponse.Attempt(provider.spec.name(), credential.spec.id(), result.success,
                            result.error, result.latencyMs));
                    if (result.success) {
                        credential.consecutiveFailures.set(0);
                        return CompletableFuture.completedFuture(new LlmResponse(true, result.content, "",
                                provider.spec.name(), provider.spec.model(), credential.spec.id(), result.promptTokens,
                                result.completionTokens, result.latencyMs, attempts, result.requestBody,
                                result.responseBody));
                    }
                    applyFailure(credential, result.httpStatus);
                    if (!isCredentialRetryable(result.httpStatus)) {
                        return attemptProvider(request, chain, index + 1, attempts);
                    }
                    CredentialRuntime next = provider.selectCredential(System.currentTimeMillis(), credential);
                    return next != null
                            ? attemptProviderWithCredential(request, chain, index, attempts, provider, next)
                            : attemptProvider(request, chain, index + 1, attempts);
                });
    }

    private CompletableFuture<SingleResult> sendSingle(LlmRequest request, ProviderSpec provider,
            ProviderSpec.Credential credential) {
        String format = provider.format();
        String url = resolveUrl(provider, credential.key());
        JsonObject body = buildBody(format, provider, request);
        String requestBody = GSON.toJson(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(request.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        applyHeaders(builder, format, credential.key());
        long startedAt = System.currentTimeMillis();
        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(format, response.statusCode(), response.body(),
                        System.currentTimeMillis() - startedAt, requestBody));
    }

    private CompletableFuture<StreamResult> sendSingleStreaming(LlmRequest request, ProviderSpec provider,
            ProviderSpec.Credential credential, Consumer<String> onDelta) {
        JsonObject body = buildOpenAiBody(provider.model(), request.messages(),
                request.temperature() != null ? request.temperature() : provider.temperature(),
                request.maxTokens() != null ? request.maxTokens() : provider.maxTokens(), false);
        body.addProperty("stream", true);
        String requestBody = GSON.toJson(body);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(provider.url()))
                .timeout(Duration.ofSeconds(request.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + credential.key())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        long startedAt = System.currentTimeMillis();
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream()).thenApplyAsync(response -> {
            long latency;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try {
                    String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    latency = System.currentTimeMillis() - startedAt;
                    return StreamResult.failure(extractError(error), response.statusCode(), latency, false,
                            requestBody, error);
                } catch (Exception e) {
                    return StreamResult.failure(rootMessage(e), response.statusCode(),
                            System.currentTimeMillis() - startedAt, false, requestBody, "");
                }
            }
            StringBuilder accumulated = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            int chunkCount = 0;
            boolean emitted = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    chunkCount++;
                    JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                    JsonArray choices = chunk.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                    if (delta == null) continue;
                    // Some providers (e.g. LongCat) stream chain-of-thought separately.
                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                        String thought = delta.get("reasoning_content").getAsString();
                        if (!thought.isEmpty()) reasoning.append(thought);
                    }
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String text = delta.get("content").getAsString();
                        if (!text.isEmpty()) {
                            accumulated.append(text);
                            emitted = true;
                            onDelta.accept(text);
                        }
                    }
                }
                latency = System.currentTimeMillis() - startedAt;
                // Log a reconstructed chat-completion style body, not raw SSE chunks.
                String responseBody = buildStreamLogBody(provider.model(), accumulated.toString(),
                        reasoning.toString(), chunkCount);
                return accumulated.isEmpty()
                        ? StreamResult.failure("LLM stream produced no content", 0, latency, emitted, requestBody, responseBody)
                        : StreamResult.success(accumulated.toString(), latency, requestBody, responseBody);
            } catch (Exception e) {
                String responseBody = buildStreamLogBody(provider.model(), accumulated.toString(),
                        reasoning.toString(), chunkCount);
                return StreamResult.failure(rootMessage(e), 0, System.currentTimeMillis() - startedAt, emitted,
                        requestBody, responseBody);
            }
        });
    }

    /** Human-readable body for console logs (avoids dumping every SSE line). */
    private static String buildStreamLogBody(String model, String content, String reasoning, int chunkCount) {
        JsonObject body = new JsonObject();
        body.addProperty("object", "chat.completion");
        body.addProperty("stream", true);
        body.addProperty("model", model == null ? "" : model);
        body.addProperty("chunk_count", chunkCount);
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content == null ? "" : content);
        if (reasoning != null && !reasoning.isBlank()) {
            message.addProperty("reasoning_content", reasoning);
        }
        choice.add("message", message);
        choice.addProperty("finish_reason", "stop");
        choices.add(choice);
        body.add("choices", choices);
        return GSON.toJson(body);
    }

    private static JsonObject buildBody(String format, ProviderSpec provider, LlmRequest request) {
        Double temperature = request.temperature() != null ? request.temperature() : provider.temperature();
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : provider.maxTokens();
        return switch (format) {
            case "claude", "anthropic" -> buildClaudeBody(provider.model(), request.messages(), temperature, maxTokens);
            case "gemini" -> buildGeminiBody(request.messages(), temperature, maxTokens);
            default -> buildOpenAiBody(provider.model(), request.messages(), temperature, maxTokens,
                    request.context().structured());
        };
    }

    private static JsonObject buildOpenAiBody(String model, List<LlmMessage> messages, Double temperature,
            Integer maxTokens, boolean structured) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray array = new JsonArray();
        for (LlmMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            if (message.hasImage()) {
                JsonArray parts = new JsonArray();
                for (LlmMessage.Part part : message.parts()) {
                    JsonObject contentPart = new JsonObject();
                    if (part instanceof LlmMessage.ImagePart image) {
                        contentPart.addProperty("type", "image_url");
                        JsonObject imageUrl = new JsonObject();
                        imageUrl.addProperty("url", image.dataUrl());
                        imageUrl.addProperty("detail", image.detail());
                        contentPart.add("image_url", imageUrl);
                    } else {
                        contentPart.addProperty("type", "text");
                        contentPart.addProperty("text", part.asText());
                    }
                    parts.add(contentPart);
                }
                item.add("content", parts);
            } else {
                item.addProperty("content", message.content());
            }
            array.add(item);
        }
        body.add("messages", array);
        if (temperature != null) body.addProperty("temperature", temperature);
        if (maxTokens != null) body.addProperty("max_tokens", maxTokens);
        if (structured) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        }
        return body;
    }

    private static JsonObject buildClaudeBody(String model, List<LlmMessage> messages, Double temperature,
            Integer maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens == null ? 512 : maxTokens);
        if (temperature != null) body.addProperty("temperature", temperature);
        StringBuilder system = new StringBuilder();
        JsonArray array = new JsonArray();
        for (LlmMessage message : messages) {
            if ("system".equals(message.role())) {
                if (!system.isEmpty()) system.append('\n');
                system.append(message.content());
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("role", "assistant".equals(message.role()) ? "assistant" : "user");
            JsonArray content = new JsonArray();
            for (LlmMessage.Part part : message.parts()) {
                JsonObject contentPart = new JsonObject();
                if (part instanceof LlmMessage.ImagePart image) {
                    contentPart.addProperty("type", "image");
                    JsonObject source = new JsonObject();
                    source.addProperty("type", "base64");
                    source.addProperty("media_type", image.mimeType());
                    source.addProperty("data", image.base64Data());
                    contentPart.add("source", source);
                } else {
                    contentPart.addProperty("type", "text");
                    contentPart.addProperty("text", part.asText());
                }
                content.add(contentPart);
            }
            item.add("content", content);
            array.add(item);
        }
        if (!system.isEmpty()) body.addProperty("system", system.toString());
        body.add("messages", array);
        return body;
    }

    private static JsonObject buildGeminiBody(List<LlmMessage> messages, Double temperature, Integer maxTokens) {
        JsonObject body = new JsonObject();
        JsonArray contents = new JsonArray();
        for (LlmMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", "assistant".equals(message.role()) ? "model" : "user");
            JsonArray parts = new JsonArray();
            for (LlmMessage.Part messagePart : message.parts()) {
                JsonObject part = new JsonObject();
                if (messagePart instanceof LlmMessage.ImagePart image) {
                    JsonObject inlineData = new JsonObject();
                    inlineData.addProperty("mime_type", image.mimeType());
                    inlineData.addProperty("data", image.base64Data());
                    part.add("inline_data", inlineData);
                } else {
                    part.addProperty("text", ("system".equals(message.role()) ? "[System]\n" : "")
                            + messagePart.asText());
                }
                parts.add(part);
            }
            item.add("parts", parts);
            contents.add(item);
        }
        body.add("contents", contents);
        JsonObject generation = new JsonObject();
        if (temperature != null) generation.addProperty("temperature", temperature);
        if (maxTokens != null) generation.addProperty("maxOutputTokens", maxTokens);
        body.add("generationConfig", generation);
        return body;
    }

    private static SingleResult parseResponse(String format, int status, String body, long latencyMs,
            String requestBody) {
        if (status < 200 || status >= 300) {
            return SingleResult.failure(extractError(body), status, latencyMs, requestBody, body);
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String content;
            int promptTokens = 0;
            int completionTokens = 0;
            if ("gemini".equals(format)) {
                content = root.getAsJsonArray("candidates").get(0).getAsJsonObject()
                        .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject()
                        .get("text").getAsString();
                if (root.has("usageMetadata")) {
                    JsonObject usage = root.getAsJsonObject("usageMetadata");
                    promptTokens = getInt(usage, "promptTokenCount");
                    completionTokens = getInt(usage, "candidatesTokenCount");
                }
            } else if ("claude".equals(format) || "anthropic".equals(format)) {
                content = root.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                if (root.has("usage")) {
                    JsonObject usage = root.getAsJsonObject("usage");
                    promptTokens = getInt(usage, "input_tokens");
                    completionTokens = getInt(usage, "output_tokens");
                }
            } else {
                content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
                if (root.has("usage")) {
                    JsonObject usage = root.getAsJsonObject("usage");
                    promptTokens = getInt(usage, "prompt_tokens");
                    completionTokens = getInt(usage, "completion_tokens");
                }
            }
            return SingleResult.success(content, promptTokens, completionTokens, latencyMs, requestBody, body);
        } catch (Exception e) {
            return SingleResult.failure("Invalid provider response: " + rootMessage(e), -1, latencyMs, requestBody, body);
        }
    }

    private static void applyHeaders(HttpRequest.Builder builder, String format, String key) {
        if ("claude".equals(format) || "anthropic".equals(format)) {
            builder.header("x-api-key", key).header("anthropic-version", "2023-06-01");
        } else if (!"gemini".equals(format)) {
            builder.header("Authorization", "Bearer " + key);
        }
    }

    private static String resolveUrl(ProviderSpec provider, String key) {
        if (!"gemini".equals(provider.format())) {
            return provider.url();
        }
        String base = provider.url().replace("{model}", provider.model());
        if (base.contains("{key}")) return base.replace("{key}", key);
        return base + (base.contains("?") ? "&" : "?") + "key=" + key;
    }

    private static void applyFailure(CredentialRuntime credential, int status) {
        int failures = credential.consecutiveFailures.incrementAndGet();
        long now = System.currentTimeMillis();
        if (status == 401 || status == 403) {
            credential.disabled = true;
        } else if (status == 429) {
            credential.cooldownUntil = now + RATE_LIMIT_COOLDOWN_MS;
        } else if (status >= 500 || status == 0 || failures >= 3) {
            credential.cooldownUntil = now + TRANSIENT_FAILURE_COOLDOWN_MS;
        } else {
            credential.cooldownUntil = now + 1_000L;
        }
    }

    private static boolean isCredentialRetryable(int status) {
        return status == 0 || status == 401 || status == 403 || status == 429 || status >= 500;
    }

    private static String extractError(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                if (object.has("error")) {
                    JsonElement error = object.get("error");
                    if (error.isJsonPrimitive()) return error.getAsString();
                    if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                        return error.getAsJsonObject().get("message").getAsString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return body == null || body.isBlank() ? "Provider request failed" : body.substring(0, Math.min(300, body.length()));
    }

    private static int getInt(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : 0;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class ProviderRuntime {
        private final ProviderSpec spec;
        private final List<CredentialRuntime> credentials;
        private final AtomicInteger cursor = new AtomicInteger();

        private ProviderRuntime(ProviderSpec spec) {
            this.spec = spec;
            this.credentials = spec.credentials().stream()
                    .filter(ProviderSpec.Credential::isConfigured)
                    .map(CredentialRuntime::new)
                    .toList();
        }

        private CredentialRuntime selectCredential(long nowMs, CredentialRuntime excluded) {
            if (credentials.isEmpty()) return null;
            int start = Math.floorMod(cursor.getAndIncrement(), credentials.size());
            CredentialRuntime best = null;
            for (int offset = 0; offset < credentials.size(); offset++) {
                CredentialRuntime candidate = credentials.get((start + offset) % credentials.size());
                if (candidate == excluded || candidate.disabled || candidate.cooldownUntil > nowMs) continue;
                if (best == null || candidate.inflight.get() < best.inflight.get()) best = candidate;
            }
            return best;
        }
    }

    private static final class CredentialRuntime {
        private final ProviderSpec.Credential spec;
        private final AtomicInteger inflight = new AtomicInteger();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile long cooldownUntil;
        private volatile boolean disabled;

        private CredentialRuntime(ProviderSpec.Credential spec) {
            this.spec = spec;
        }
    }

    private record SingleResult(boolean success, String content, String error, int httpStatus,
            int promptTokens, int completionTokens, long latencyMs, String requestBody, String responseBody) {
        private static SingleResult success(String content, int promptTokens, int completionTokens, long latencyMs,
                String requestBody, String responseBody) {
            return new SingleResult(true, content, "", 200, promptTokens, completionTokens, latencyMs,
                    requestBody, responseBody);
        }

        private static SingleResult failure(String error, int httpStatus, long latencyMs) {
            return failure(error, httpStatus, latencyMs, "", "");
        }

        private static SingleResult failure(String error, int httpStatus, long latencyMs, String requestBody,
                String responseBody) {
            return new SingleResult(false, "", error, httpStatus, 0, 0, latencyMs, requestBody, responseBody);
        }
    }

    private record StreamResult(boolean success, String content, String error, int httpStatus, long latencyMs,
            boolean emittedContent, String requestBody, String responseBody) {
        private static StreamResult success(String content, long latencyMs, String requestBody, String responseBody) {
            return new StreamResult(true, content, "", 200, latencyMs, true, requestBody, responseBody);
        }

        private static StreamResult failure(String error, int status, long latencyMs, boolean emitted) {
            return failure(error, status, latencyMs, emitted, "", "");
        }

        private static StreamResult failure(String error, int status, long latencyMs, boolean emitted,
                String requestBody, String responseBody) {
            return new StreamResult(false, "", error, status, latencyMs, emitted, requestBody, responseBody);
        }
    }
}
