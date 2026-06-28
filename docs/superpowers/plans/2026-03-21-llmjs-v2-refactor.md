# LLMjs v2.0 Complete Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete rewrite of LLMjs mod with dual-track provider system, async builder pipeline, multi-format API support, client-server architecture, and debug UI console.

**Architecture:** Server-side ProviderManager holds all config/keys and executes LLM HTTP requests via format adapters (OpenAI/Claude/Gemini) or raw templates. KubeJS scripts use a chainable `LLMRequest` builder with post-processing pipeline. Client UI console communicates via Forge SimpleChannel packets.

**Tech Stack:** Minecraft Forge 1.20.1, KubeJS 2001.6.x (Rhino JS engine), java.net.http.HttpClient, Gson, Forge Config (SERVER type), Forge SimpleChannel networking.

**Spec:** `docs/superpowers/specs/2026-03-21-llmjs-v2-refactor-design.md`

---

## File Map

### Delete (old v1.x code)
- `src/main/java/com/liteming/llmjs/util/LLMApiClient.java`
- `src/main/java/com/liteming/llmjs/util/LLMUtil.java`
- `src/main/java/com/liteming/llmjs/util/ProviderConfig.java`
- `run/config/llmjs/llm_config.json`

### Modify
- `build.gradle` — remove OkHttp, MixinGradle; clean up
- `gradle.properties` — fix mod_id casing, update version to 2.0.0
- `src/main/java/com/liteming/llmjs/LLMjs.java` — rewrite entry point
- `src/main/java/com/liteming/llmjs/config/LLMConfig.java` — rewrite as SERVER config
- `src/main/java/com/liteming/llmjs/kubejs/LLMjsPlugin.java` — rewrite bindings
- `src/main/resources/META-INF/mods.toml` — update version, description
- `src/main/resources/kubejs.plugins.txt` — no change needed

### Create (new files, grouped by package)

**`log/`**
- `LLMLogger.java` — ring buffer logger

**`http/`**
- `HttpService.java` — async HTTP client wrapper

**`format/`**
- `ApiFormat.java` — interface
- `OpenAiFormat.java`
- `ClaudeFormat.java`
- `GeminiFormat.java`

**`provider/`**
- `Provider.java` — interface
- `SimpleProvider.java`
- `RawProvider.java`
- `ProviderManager.java` — pool management + hot reload

**`config/`**
- `ProviderLoader.java` — JSON file loading

**`pipeline/`**
- `LLMResponse.java` — response data wrapper
- `PostProcessor.java` — post-processing interface + implementations
- `RegexPreset.java` — named regex presets
- `LLMRequest.java` — chainable builder
- `OutputTarget.java` — terminal output operations

**`session/`**
- `ChatSession.java` — multi-turn conversation

**`json/`**
- `JsonMode.java` — force JSON output
- `SchemaMode.java` — schema-constrained
- `FillMode.java` — fill template mode

**`kubejs/`**
- `LLMBinding.java` — main API facade exposed as `LLM`

**`network/`**
- `LLMNetwork.java` — SimpleChannel registration
- `PermissionCheck.java` — permission verification
- `packet/C2SChatRequestPacket.java`
- `packet/C2SStatusRequestPacket.java`
- `packet/S2CChatResponsePacket.java`
- `packet/S2CStatusResponsePacket.java`
- `packet/S2CLogPacket.java`

**`command/`**
- `LLMCommand.java` — `/llm` command tree

**`client/`**
- `ClientEventHandler.java` — client-side event registration
- `screen/LLMConsoleScreen.java` — console main screen
- `widget/LogPanel.java`
- `widget/ProviderListPanel.java`
- `widget/TestPanel.java`

---

## Task 1: Project Cleanup & Build Config

**Files:**
- Modify: `build.gradle`
- Modify: `gradle.properties`
- Modify: `src/main/resources/META-INF/mods.toml`
- Delete: `src/main/java/com/liteming/llmjs/util/LLMApiClient.java`
- Delete: `src/main/java/com/liteming/llmjs/util/LLMUtil.java`
- Delete: `src/main/java/com/liteming/llmjs/util/ProviderConfig.java`
- Delete: `run/config/llmjs/llm_config.json`

- [ ] **Step 1: Update `build.gradle`**

Remove OkHttp dependency, MixinGradle plugin + config, and mixin annotation processor. Also fix group ID. Keep all other deps.

```groovy
// REMOVE these lines:
//   classpath "org.spongepowered:mixingradle:${mixingradle_version}"
//   id 'org.spongepowered.mixin' version '0.7.+'
//   mixin { }
//   annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'
//   implementation 'com.squareup.okhttp3:okhttp:4.12.0'

// FIX group ID:
group = 'vibe.liteming.llmjs'  // was 'com.example.llmjs'
```

- [ ] **Step 2: Update `gradle.properties`**

```properties
mod_id=llmjs
mod_version=2.0.0
mod_group_id=vibe.liteming.llmjs
mod_license=MIT
```

Remove `mixingradle_version` line.

- [ ] **Step 3: Update `mods.toml`**

Change version to `2.0.0`. Update description to reflect v2 features.

- [ ] **Step 4: Delete old source files**

```bash
rm src/main/java/com/liteming/llmjs/util/LLMApiClient.java
rm src/main/java/com/liteming/llmjs/util/LLMUtil.java
rm src/main/java/com/liteming/llmjs/util/ProviderConfig.java
rm run/config/llmjs/llm_config.json
rmdir src/main/java/com/liteming/llmjs/util
```

- [ ] **Step 5: Stub out LLMjs.java to compile**

Temporarily simplify `LLMjs.java` to just the `@Mod` annotation and logger (remove `LLMConfig.register()` call since LLMConfig will be rewritten).

```java
package vibe.liteming.llmjs;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(LLMjs.MODID)
public class LLMjs {
    public static final String MODID = "llmjs";
    public static final Logger LOGGER = LogManager.getLogger();

    public LLMjs() {
        LOGGER.info("LLMjs v2.0 initializing...");
    }
}
```

Also stub `LLMjsPlugin.java` to empty:

```java
package vibe.liteming.llmjs.kubejs;

import dev.latvian.mods.kubejs.KubeJSPlugin;

public class LLMjsPlugin extends KubeJSPlugin {
}
```

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (with no source errors)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: clean up v1.x code and build config for v2.0 refactor"
```

---

## Task 2: Core Data Types & Logger

**Files:**
- Create: `src/main/java/com/liteming/llmjs/pipeline/LLMResponse.java`
- Create: `src/main/java/com/liteming/llmjs/log/LLMLogger.java`

- [ ] **Step 1: Create `LLMResponse.java`**

```java
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

    // Getters
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
```

- [ ] **Step 2: Create `LLMLogger.java`**

```java
package vibe.liteming.llmjs.log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

public class LLMLogger {
    public static final LLMLogger INSTANCE = new LLMLogger();

    public enum Level { INFO, WARN, ERROR }

    public record LogEntry(
            Instant timestamp,
            Level level,
            String provider,
            String requestSummary,
            String status,
            long latencyMs,
            int promptTokens,
            int completionTokens,
            @Nullable String errorMessage
    ) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("timestamp", timestamp.toString());
            obj.addProperty("level", level.name());
            obj.addProperty("provider", provider);
            obj.addProperty("requestSummary", requestSummary);
            obj.addProperty("status", status);
            obj.addProperty("latencyMs", latencyMs);
            obj.addProperty("promptTokens", promptTokens);
            obj.addProperty("completionTokens", completionTokens);
            if (errorMessage != null) {
                obj.addProperty("error", errorMessage);
            }
            return obj;
        }
    }

    private LogEntry[] buffer;
    private int head = 0;
    private int size = 0;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Consumer<LogEntry>> listeners = new ArrayList<>();

    private LLMLogger() {
        this.buffer = new LogEntry[200];
    }

    public void resize(int capacity) {
        lock.writeLock().lock();
        try {
            LogEntry[] old = getRecentEntries(Math.min(size, capacity));
            buffer = new LogEntry[capacity];
            for (int i = 0; i < old.length; i++) {
                buffer[i] = old[i];
            }
            head = old.length % capacity;
            size = old.length;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void log(Level level, String provider, String prompt, String status,
                    long latencyMs, int promptTokens, int completionTokens,
                    @Nullable String errorMessage) {
        String summary = prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;
        LogEntry entry = new LogEntry(Instant.now(), level, provider, summary,
                status, latencyMs, promptTokens, completionTokens, errorMessage);

        lock.writeLock().lock();
        try {
            buffer[head] = entry;
            head = (head + 1) % buffer.length;
            if (size < buffer.length) size++;
        } finally {
            lock.writeLock().unlock();
        }

        // Notify listeners (for console UI push)
        for (Consumer<LogEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (Exception ignored) {}
        }
    }

    public void logInfo(String provider, String prompt, long latencyMs,
                        int promptTokens, int completionTokens) {
        log(Level.INFO, provider, prompt, "success", latencyMs, promptTokens, completionTokens, null);
    }

    public void logError(String provider, String prompt, long latencyMs, String error) {
        log(Level.ERROR, provider, prompt, "error", latencyMs, 0, 0, error);
    }

    public LogEntry[] getRecentEntries(int count) {
        lock.readLock().lock();
        try {
            int n = Math.min(count, size);
            LogEntry[] result = new LogEntry[n];
            for (int i = 0; i < n; i++) {
                int idx = (head - n + i + buffer.length) % buffer.length;
                result[i] = buffer[idx];
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public LogEntry[] getRecentEntries() {
        return getRecentEntries(size);
    }

    public LogEntry[] getRecentByLevel(Level level, int count) {
        lock.readLock().lock();
        try {
            List<LogEntry> filtered = new ArrayList<>();
            for (int i = size - 1; i >= 0 && filtered.size() < count; i--) {
                int idx = (head - 1 - (size - 1 - i) + buffer.length) % buffer.length;
                if (buffer[idx].level() == level) {
                    filtered.add(0, buffer[idx]);
                }
            }
            return filtered.toArray(new LogEntry[0]);
        } finally {
            lock.readLock().unlock();
        }
    }

    public JsonArray toJsonArray(int count) {
        JsonArray arr = new JsonArray();
        for (LogEntry entry : getRecentEntries(count)) {
            arr.add(entry.toJson());
        }
        return arr;
    }

    public void addListener(Consumer<LogEntry> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<LogEntry> listener) {
        listeners.remove(listener);
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add LLMResponse data type and ring-buffer LLMLogger"
```

---

## Task 3: HTTP Service

**Files:**
- Create: `src/main/java/com/liteming/llmjs/http/HttpService.java`

- [ ] **Step 1: Create `HttpService.java`**

Fully async HTTP wrapper around `java.net.http.HttpClient`. Supports custom headers, timeouts, and returns `CompletableFuture`.

```java
package vibe.liteming.llmjs.http;

import vibe.liteming.llmjs.LLMjs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HttpService {
    public static final HttpService INSTANCE = new HttpService();

    private final HttpClient client;

    private HttpService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record HttpResult(int statusCode, String body, long latencyMs) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    public CompletableFuture<HttpResult> postAsync(String url, String jsonBody,
                                                     Map<String, String> headers,
                                                     int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        headers.forEach(builder::header);

        HttpRequest request = builder.build();
        long startTime = System.currentTimeMillis();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    long latency = System.currentTimeMillis() - startTime;
                    return new HttpResult(response.statusCode(), response.body(), latency);
                })
                .exceptionally(ex -> {
                    long latency = System.currentTimeMillis() - startTime;
                    LLMjs.LOGGER.error("HTTP request failed: {}", url, ex);
                    return new HttpResult(-1, "Request failed: " + ex.getMessage(), latency);
                });
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add async HttpService wrapper"
```

---

## Task 4: API Format Adapters

**Files:**
- Create: `src/main/java/com/liteming/llmjs/format/ApiFormat.java`
- Create: `src/main/java/com/liteming/llmjs/format/OpenAiFormat.java`
- Create: `src/main/java/com/liteming/llmjs/format/ClaudeFormat.java`
- Create: `src/main/java/com/liteming/llmjs/format/GeminiFormat.java`

- [ ] **Step 1: Create `ApiFormat.java` interface**

```java
package vibe.liteming.llmjs.format;

import vibe.liteming.llmjs.pipeline.LLMResponse;

import java.util.Map;

public interface ApiFormat {

    /**
     * Build the full URL for the request (some APIs modify the URL, e.g. Gemini appends model + key).
     */
    String buildUrl(String baseUrl, String model, String key);

    /**
     * Build HTTP headers for the request.
     */
    Map<String, String> buildHeaders(String key);

    /**
     * Build the JSON request body.
     * @param messages list of {role, content} pairs as json strings
     * @param systemPrompt extracted system prompt (may be null)
     */
    String buildBody(String model, java.util.List<Message> messages,
                     Double temperature, Integer maxTokens);

    /**
     * Parse the HTTP response body into an LLMResponse.
     */
    LLMResponse parseResponse(String responseBody, String providerName, long latencyMs);

    record Message(String role, String content) {}

    static ApiFormat byName(String name) {
        return switch (name.toLowerCase()) {
            case "openai" -> new OpenAiFormat();
            case "claude" -> new ClaudeFormat();
            case "gemini" -> new GeminiFormat();
            default -> throw new IllegalArgumentException("Unknown API format: " + name);
        };
    }
}
```

- [ ] **Step 2: Create `OpenAiFormat.java`**

```java
package vibe.liteming.llmjs.format;

import com.google.gson.*;
import vibe.liteming.llmjs.pipeline.LLMResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiFormat implements ApiFormat {

    @Override
    public String buildUrl(String baseUrl, String model, String key) {
        return baseUrl; // URL is used as-is
    }

    @Override
    public Map<String, String> buildHeaders(String key) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + key);
        return headers;
    }

    @Override
    public String buildBody(String model, List<Message> messages,
                            Double temperature, Integer maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray msgArray = new JsonArray();
        for (Message msg : messages) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.role());
            m.addProperty("content", msg.content());
            msgArray.add(m);
        }
        body.add("messages", msgArray);

        if (temperature != null) body.addProperty("temperature", temperature);
        if (maxTokens != null) body.addProperty("max_tokens", maxTokens);

        return body.toString();
    }

    @Override
    public LLMResponse parseResponse(String responseBody, String providerName, long latencyMs) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("error")) {
                String errMsg = json.getAsJsonObject("error").get("message").getAsString();
                return LLMResponse.error("API error: " + errMsg);
            }

            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return LLMResponse.error("No choices in response");
            }

            String content = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();

            String model = json.has("model") ? json.get("model").getAsString() : "unknown";

            int promptTokens = 0, completionTokens = 0;
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
                completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
            }

            return LLMResponse.success(content, model, providerName,
                    promptTokens, completionTokens, latencyMs);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse response: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Create `ClaudeFormat.java`**

```java
package vibe.liteming.llmjs.format;

import com.google.gson.*;
import vibe.liteming.llmjs.pipeline.LLMResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClaudeFormat implements ApiFormat {

    @Override
    public String buildUrl(String baseUrl, String model, String key) {
        return baseUrl;
    }

    @Override
    public Map<String, String> buildHeaders(String key) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", key);
        headers.put("anthropic-version", "2023-06-01");
        return headers;
    }

    @Override
    public String buildBody(String model, List<Message> messages,
                            Double temperature, Integer maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        // Extract system prompt from messages
        String systemPrompt = null;
        JsonArray msgArray = new JsonArray();
        for (Message msg : messages) {
            if ("system".equals(msg.role())) {
                systemPrompt = msg.content();
            } else {
                JsonObject m = new JsonObject();
                m.addProperty("role", msg.role());
                m.addProperty("content", msg.content());
                msgArray.add(m);
            }
        }
        body.add("messages", msgArray);

        if (systemPrompt != null) {
            body.addProperty("system", systemPrompt);
        }

        // Claude requires max_tokens
        body.addProperty("max_tokens", maxTokens != null ? maxTokens : 1000);
        if (temperature != null) body.addProperty("temperature", temperature);

        return body.toString();
    }

    @Override
    public LLMResponse parseResponse(String responseBody, String providerName, long latencyMs) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("error")) {
                JsonObject err = json.getAsJsonObject("error");
                String errMsg = err.has("message") ? err.get("message").getAsString() : err.toString();
                return LLMResponse.error("API error: " + errMsg);
            }

            // Claude response: content[0].text
            JsonArray content = json.getAsJsonArray("content");
            if (content == null || content.isEmpty()) {
                return LLMResponse.error("No content in response");
            }
            String text = content.get(0).getAsJsonObject().get("text").getAsString();
            String model = json.has("model") ? json.get("model").getAsString() : "unknown";

            int inputTokens = 0, outputTokens = 0;
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
                outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
            }

            return LLMResponse.success(text, model, providerName,
                    inputTokens, outputTokens, latencyMs);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse Claude response: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Create `GeminiFormat.java`**

```java
package vibe.liteming.llmjs.format;

import com.google.gson.*;
import vibe.liteming.llmjs.pipeline.LLMResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GeminiFormat implements ApiFormat {

    @Override
    public String buildUrl(String baseUrl, String model, String key) {
        // Gemini URL: {baseUrl}/{model}:generateContent?key={key}
        String url = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return url + model + ":generateContent?key=" + key;
    }

    @Override
    public Map<String, String> buildHeaders(String key) {
        // Gemini uses URL param for key, no auth header needed
        return new LinkedHashMap<>();
    }

    @Override
    public String buildBody(String model, List<Message> messages,
                            Double temperature, Integer maxTokens) {
        JsonObject body = new JsonObject();

        // Extract system prompt
        String systemPrompt = null;
        JsonArray contents = new JsonArray();
        for (Message msg : messages) {
            if ("system".equals(msg.role())) {
                systemPrompt = msg.content();
                continue;
            }
            JsonObject content = new JsonObject();
            // Gemini uses "model" instead of "assistant"
            content.addProperty("role", "assistant".equals(msg.role()) ? "model" : msg.role());
            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", msg.content());
            parts.add(textPart);
            content.add("parts", parts);
            contents.add(content);
        }
        body.add("contents", contents);

        if (systemPrompt != null) {
            JsonObject sysInstruction = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", systemPrompt);
            parts.add(textPart);
            sysInstruction.add("parts", parts);
            body.add("systemInstruction", sysInstruction);
        }

        JsonObject genConfig = new JsonObject();
        if (temperature != null) genConfig.addProperty("temperature", temperature);
        if (maxTokens != null) genConfig.addProperty("maxOutputTokens", maxTokens);
        if (genConfig.size() > 0) {
            body.add("generationConfig", genConfig);
        }

        return body.toString();
    }

    @Override
    public LLMResponse parseResponse(String responseBody, String providerName, long latencyMs) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("error")) {
                JsonObject err = json.getAsJsonObject("error");
                String errMsg = err.has("message") ? err.get("message").getAsString() : err.toString();
                return LLMResponse.error("API error: " + errMsg);
            }

            // Gemini: candidates[0].content.parts[0].text
            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return LLMResponse.error("No candidates in response");
            }

            String text = candidates.get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts").get(0).getAsJsonObject()
                    .get("text").getAsString();

            int promptTokens = 0, completionTokens = 0;
            if (json.has("usageMetadata")) {
                JsonObject usage = json.getAsJsonObject("usageMetadata");
                promptTokens = usage.has("promptTokenCount") ? usage.get("promptTokenCount").getAsInt() : 0;
                completionTokens = usage.has("candidatesTokenCount") ? usage.get("candidatesTokenCount").getAsInt() : 0;
            }

            return LLMResponse.success(text, "gemini", providerName,
                    promptTokens, completionTokens, latencyMs);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse Gemini response: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add ApiFormat interface with OpenAI, Claude, and Gemini adapters"
```

---

## Task 5: Provider System

**Files:**
- Create: `src/main/java/com/liteming/llmjs/provider/Provider.java`
- Create: `src/main/java/com/liteming/llmjs/provider/SimpleProvider.java`
- Create: `src/main/java/com/liteming/llmjs/provider/RawProvider.java`

- [ ] **Step 1: Create `Provider.java` interface**

```java
package vibe.liteming.llmjs.provider;

import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.pipeline.LLMResponse;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Provider {
    String getName();
    String getType(); // "simple" or "raw"
    @Nullable String getFormat(); // "openai", "claude", "gemini", or null for raw
    String getModel();

    CompletableFuture<LLMResponse> sendAsync(List<ApiFormat.Message> messages,
                                              @Nullable Double temperature,
                                              @Nullable Integer maxTokens,
                                              int timeoutSeconds);

    CompletableFuture<LLMResponse> testConnection(int timeoutSeconds);

    boolean isValid();

    /** Return masked key for display (first 4 chars + ***) */
    String getMaskedKey();
}
```

- [ ] **Step 2: Create `SimpleProvider.java`**

```java
package vibe.liteming.llmjs.provider;

import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.http.HttpService;
import vibe.liteming.llmjs.pipeline.LLMResponse;
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
        return url != null && !url.isEmpty()
                && key != null && !key.isEmpty()
                && model != null && !model.isEmpty();
    }

    @Override
    public String getMaskedKey() {
        if (key == null || key.length() < 4) return "***";
        return key.substring(0, 4) + "***";
    }
}
```

- [ ] **Step 3: Create `RawProvider.java`**

```java
package vibe.liteming.llmjs.provider;

import com.google.gson.*;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.http.HttpService;
import vibe.liteming.llmjs.pipeline.LLMResponse;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RawProvider implements Provider {
    private final String name;
    private final String url;
    private final String method;
    private final Map<String, String> headerTemplates;
    private final JsonObject bodyTemplate;
    private final String responsePath;
    private final String key;
    private final String model;

    public RawProvider(String name, String url, String method,
                       Map<String, String> headerTemplates, JsonObject bodyTemplate,
                       String responsePath, String key, String model) {
        this.name = name;
        this.url = url;
        this.method = method;
        this.headerTemplates = headerTemplates;
        this.bodyTemplate = bodyTemplate;
        this.responsePath = responsePath;
        this.key = key;
        this.model = model;
    }

    @Override public String getName() { return name; }
    @Override public String getType() { return "raw"; }
    @Override public @Nullable String getFormat() { return null; }
    @Override public String getModel() { return model; }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(List<ApiFormat.Message> messages,
                                                      @Nullable Double temperature,
                                                      @Nullable Integer maxTokens,
                                                      int timeoutSeconds) {
        // Build variable map for substitution
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("key", key);
        vars.put("model", model);
        vars.put("temperature", temperature != null ? temperature.toString() : "0.7");
        vars.put("max_tokens", maxTokens != null ? maxTokens.toString() : "1000");

        // Build messages JSON
        JsonArray msgArray = new JsonArray();
        String systemPrompt = "";
        for (ApiFormat.Message msg : messages) {
            if ("system".equals(msg.role())) {
                systemPrompt = msg.content();
            }
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.role());
            m.addProperty("content", msg.content());
            msgArray.add(m);
        }
        vars.put("messages", msgArray.toString());
        vars.put("system", systemPrompt);

        // Substitute in headers
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headerTemplates.entrySet()) {
            headers.put(entry.getKey(), substitute(entry.getValue(), vars));
        }

        // Substitute in body template
        String body = substituteJson(bodyTemplate.deepCopy(), vars).toString();

        // Substitute in URL
        String resolvedUrl = substitute(url, vars);

        return HttpService.INSTANCE.postAsync(resolvedUrl, body, headers, timeoutSeconds)
                .thenApply(result -> {
                    if (!result.isSuccess()) {
                        return LLMResponse.error("HTTP " + result.statusCode() + ": " + result.body());
                    }
                    return parseByPath(result.body(), result.latencyMs());
                });
    }

    private LLMResponse parseByPath(String responseBody, long latencyMs) {
        try {
            JsonElement root = JsonParser.parseString(responseBody);
            JsonElement current = root;

            // Parse path like "choices[0].message.content" or "result.text"
            String[] segments = responsePath.split("\\.");
            for (String seg : segments) {
                // Check for array index: name[0]
                Pattern arrayPattern = Pattern.compile("(.+)\\[(\\d+)]");
                Matcher matcher = arrayPattern.matcher(seg);
                if (matcher.matches()) {
                    String field = matcher.group(1);
                    int index = Integer.parseInt(matcher.group(2));
                    current = current.getAsJsonObject().get(field);
                    current = current.getAsJsonArray().get(index);
                } else {
                    current = current.getAsJsonObject().get(seg);
                }
                if (current == null) {
                    return LLMResponse.error("Response path '" + responsePath + "' not found at segment '" + seg + "'");
                }
            }

            String content = current.getAsString();
            return LLMResponse.success(content, model, name, 0, 0, latencyMs);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse response at path '" + responsePath + "': " + e.getMessage());
        }
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
        return url != null && !url.isEmpty()
                && responsePath != null && !responsePath.isEmpty();
    }

    @Override
    public String getMaskedKey() {
        if (key == null || key.length() < 4) return "***";
        return key.substring(0, 4) + "***";
    }

    private static String substitute(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static JsonElement substituteJson(JsonElement element, Map<String, String> vars) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String val = element.getAsString();
            // If the entire value is a variable like "${messages}", try to parse as JSON
            if (val.matches("^\\$\\{\\w+}$")) {
                String varName = val.substring(2, val.length() - 1);
                String replacement = vars.getOrDefault(varName, val);
                try {
                    return JsonParser.parseString(replacement);
                } catch (Exception e) {
                    return new JsonPrimitive(replacement);
                }
            }
            return new JsonPrimitive(substitute(val, vars));
        } else if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                result.add(entry.getKey(), substituteJson(entry.getValue(), vars));
            }
            return result;
        } else if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            JsonArray result = new JsonArray();
            for (JsonElement item : arr) {
                result.add(substituteJson(item, vars));
            }
            return result;
        }
        return element;
    }
}
```

- [ ] **Step 4: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add Provider interface with SimpleProvider and RawProvider"
```

---

## Task 6: Configuration & Provider Manager

**Files:**
- Rewrite: `src/main/java/com/liteming/llmjs/config/LLMConfig.java`
- Create: `src/main/java/com/liteming/llmjs/config/ProviderLoader.java`
- Create: `src/main/java/com/liteming/llmjs/provider/ProviderManager.java`

- [ ] **Step 1: Rewrite `LLMConfig.java`**

Change to `ModConfig.Type.SERVER`. New fields: default_provider, timeout, rate_limit, max_prompt_length, log_buffer_size, require_op_level, allow_all_players.

```java
package vibe.liteming.llmjs.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class LLMConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_PROVIDER;
    public static final ForgeConfigSpec.IntValue TIMEOUT;
    public static final ForgeConfigSpec.IntValue RATE_LIMIT;
    public static final ForgeConfigSpec.IntValue MAX_PROMPT_LENGTH;
    public static final ForgeConfigSpec.IntValue LOG_BUFFER_SIZE;

    public static final ForgeConfigSpec.IntValue REQUIRE_OP_LEVEL;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ALL_PLAYERS;

    static {
        BUILDER.push("general");

        DEFAULT_PROVIDER = BUILDER
                .comment("Default provider name (must match a key in providers.json)")
                .define("default_provider", "openai");

        TIMEOUT = BUILDER
                .comment("Per-request timeout in seconds")
                .defineInRange("timeout", 30, 1, 300);

        RATE_LIMIT = BUILDER
                .comment("Max requests per minute globally. 0 = unlimited")
                .defineInRange("rate_limit", 30, 0, 1000);

        MAX_PROMPT_LENGTH = BUILDER
                .comment("Max characters per prompt (prevents abuse via network packets)")
                .defineInRange("max_prompt_length", 10000, 100, 100000);

        LOG_BUFFER_SIZE = BUILDER
                .comment("Number of log entries to keep in ring buffer")
                .defineInRange("log_buffer_size", 200, 10, 10000);

        BUILDER.pop();

        BUILDER.push("permission");

        REQUIRE_OP_LEVEL = BUILDER
                .comment("Minimum OP level required to use LLM features")
                .defineInRange("require_op_level", 2, 0, 4);

        ALLOW_ALL_PLAYERS = BUILDER
                .comment("Server-only: grant LLM request permission to ALL players.\n"
                        + "WARNING: may cause excessive LLM requests from unauthorized players.")
                .define("allow_all_players", false);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
    }
}
```

- [ ] **Step 2: Create `ProviderLoader.java`**

Loads providers from `serverconfig/llmjs/providers.json` and `providers_raw.json`. Generates default files if not present.

```java
package vibe.liteming.llmjs.config;

import com.google.gson.*;
import vibe.liteming.llmjs.LLMjs;
import vibe.liteming.llmjs.provider.Provider;
import vibe.liteming.llmjs.provider.RawProvider;
import vibe.liteming.llmjs.provider.SimpleProvider;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProviderLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Map<String, Provider> loadAll(Path configDir) {
        Map<String, Provider> providers = new LinkedHashMap<>();

        Path simpleFile = configDir.resolve("providers.json");
        Path rawFile = configDir.resolve("providers_raw.json");

        // Create directory and defaults if missing
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(simpleFile)) {
                Files.writeString(simpleFile, getDefaultSimpleConfig());
                LLMjs.LOGGER.info("Created default providers.json");
            }
            if (!Files.exists(rawFile)) {
                Files.writeString(rawFile, getDefaultRawConfig());
                LLMjs.LOGGER.info("Created default providers_raw.json");
            }
        } catch (IOException e) {
            LLMjs.LOGGER.error("Failed to create config directory/files", e);
        }

        // Load simple providers
        loadSimpleProviders(simpleFile, providers);

        // Load raw providers
        loadRawProviders(rawFile, providers);

        return providers;
    }

    private static void loadSimpleProviders(Path file, Map<String, Provider> providers) {
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    String type = obj.has("type") ? obj.get("type").getAsString() : "simple";
                    if (!"simple".equals(type)) continue;

                    String format = obj.has("format") ? obj.get("format").getAsString() : "openai";
                    String url = obj.get("url").getAsString();
                    String key = obj.get("key").getAsString();
                    String model = obj.get("model").getAsString();
                    Double temp = obj.has("temperature") ? obj.get("temperature").getAsDouble() : null;
                    Integer maxTokens = obj.has("max_tokens") ? obj.get("max_tokens").getAsInt() : null;

                    SimpleProvider provider = new SimpleProvider(
                            entry.getKey(), format, url, key, model, temp, maxTokens);
                    if (provider.isValid()) {
                        providers.put(entry.getKey(), provider);
                    }
                } catch (Exception e) {
                    LLMjs.LOGGER.warn("Failed to load provider '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LLMjs.LOGGER.error("Failed to load providers.json", e);
        }
    }

    private static void loadRawProviders(Path file, Map<String, Provider> providers) {
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    JsonObject obj = entry.getValue().getAsJsonObject();

                    String url = obj.get("url").getAsString();
                    String method = obj.has("method") ? obj.get("method").getAsString() : "POST";
                    String responsePath = obj.get("response_path").getAsString();
                    String key = obj.has("key") ? obj.get("key").getAsString() : "";
                    String model = obj.has("model") ? obj.get("model").getAsString() : "";

                    Map<String, String> headers = new LinkedHashMap<>();
                    if (obj.has("headers")) {
                        for (Map.Entry<String, JsonElement> h : obj.getAsJsonObject("headers").entrySet()) {
                            headers.put(h.getKey(), h.getValue().getAsString());
                        }
                    }

                    JsonObject bodyTemplate = obj.has("body_template")
                            ? obj.getAsJsonObject("body_template")
                            : new JsonObject();

                    RawProvider provider = new RawProvider(
                            entry.getKey(), url, method, headers, bodyTemplate,
                            responsePath, key, model);
                    if (provider.isValid()) {
                        providers.put(entry.getKey(), provider);
                    }
                } catch (Exception e) {
                    LLMjs.LOGGER.warn("Failed to load raw provider '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LLMjs.LOGGER.error("Failed to load providers_raw.json", e);
        }
    }

    private static String getDefaultSimpleConfig() {
        JsonObject root = new JsonObject();

        JsonObject openai = new JsonObject();
        openai.addProperty("type", "simple");
        openai.addProperty("format", "openai");
        openai.addProperty("url", "https://api.openai.com/v1/chat/completions");
        openai.addProperty("key", "your-api-key-here");
        openai.addProperty("model", "gpt-4o");
        openai.addProperty("temperature", 0.7);
        openai.addProperty("max_tokens", 1000);
        root.add("openai", openai);

        return GSON.toJson(root);
    }

    private static String getDefaultRawConfig() {
        return GSON.toJson(new JsonObject());
    }
}
```

- [ ] **Step 3: Create `ProviderManager.java`**

```java
package vibe.liteming.llmjs.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import vibe.liteming.llmjs.LLMjs;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.config.ProviderLoader;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.pipeline.LLMResponse;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ProviderManager {
    public static final ProviderManager INSTANCE = new ProviderManager();

    private volatile Map<String, Provider> providers = new ConcurrentHashMap<>();
    private final Map<String, ConnectionStatus> statusCache = new ConcurrentHashMap<>();
    private Path configDir;

    // Rate limiting
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    public record ConnectionStatus(boolean connected, long latencyMs, @Nullable String lastError, long testTime) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("connected", connected);
            obj.addProperty("latency", latencyMs);
            if (lastError != null) obj.addProperty("lastError", lastError);
            return obj;
        }
    }

    public void init(Path serverConfigDir) {
        this.configDir = serverConfigDir.resolve("llmjs");
        reload();
    }

    public void reload() {
        if (configDir == null) return;
        Map<String, Provider> newProviders = ProviderLoader.loadAll(configDir);
        this.providers = new ConcurrentHashMap<>(newProviders);
        LLMjs.LOGGER.info("Loaded {} providers", providers.size());
    }

    public @Nullable Provider getProvider(String name) {
        return providers.get(name);
    }

    public Provider getDefaultProvider() {
        String defaultName = LLMConfig.DEFAULT_PROVIDER.get();
        Provider p = providers.get(defaultName);
        if (p != null) return p;
        // Fallback to first available
        return providers.values().stream().findFirst().orElse(null);
    }

    public List<String> getProviderNames() {
        return new ArrayList<>(providers.keySet());
    }

    public Map<String, Provider> getAllProviders() {
        return Collections.unmodifiableMap(providers);
    }

    /**
     * Send a request with fallback chain support.
     */
    public CompletableFuture<LLMResponse> sendWithFallback(
            List<ApiFormat.Message> messages,
            List<String> providerChain,
            @Nullable Double temperature,
            @Nullable Integer maxTokens,
            int timeoutSeconds) {

        if (providerChain.isEmpty()) {
            return CompletableFuture.completedFuture(LLMResponse.error("No providers specified"));
        }

        // Rate limit check
        if (!checkRateLimit()) {
            return CompletableFuture.completedFuture(LLMResponse.error("Rate limit exceeded"));
        }

        List<LLMResponse.AttemptRecord> attempts = new ArrayList<>();
        return sendWithFallbackRecursive(messages, providerChain, 0,
                temperature, maxTokens, timeoutSeconds, attempts);
    }

    private CompletableFuture<LLMResponse> sendWithFallbackRecursive(
            List<ApiFormat.Message> messages,
            List<String> chain, int index,
            @Nullable Double temperature,
            @Nullable Integer maxTokens,
            int timeoutSeconds,
            List<LLMResponse.AttemptRecord> attempts) {

        if (index >= chain.size()) {
            LLMResponse fail = LLMResponse.error("All providers failed").withAttempts(attempts);
            return CompletableFuture.completedFuture(fail);
        }

        String providerName = chain.get(index);
        Provider provider = providers.get(providerName);
        if (provider == null) {
            attempts.add(new LLMResponse.AttemptRecord(providerName, false, "Provider not found", 0));
            return sendWithFallbackRecursive(messages, chain, index + 1,
                    temperature, maxTokens, timeoutSeconds, attempts);
        }

        // Extract prompt summary for logging
        String promptSummary = messages.isEmpty() ? "" :
                messages.get(messages.size() - 1).content();

        return provider.sendAsync(messages, temperature, maxTokens, timeoutSeconds)
                .thenCompose(response -> {
                    if (response.isSuccess()) {
                        attempts.add(new LLMResponse.AttemptRecord(providerName, true, null, response.getLatencyMs()));
                        LLMLogger.INSTANCE.logInfo(providerName, promptSummary,
                                response.getLatencyMs(), response.getPromptTokens(), response.getCompletionTokens());
                        return CompletableFuture.completedFuture(response.withAttempts(attempts));
                    } else {
                        attempts.add(new LLMResponse.AttemptRecord(providerName, false,
                                response.getError(), response.getLatencyMs()));
                        LLMLogger.INSTANCE.logError(providerName, promptSummary,
                                response.getLatencyMs(), response.getError());
                        // Try next provider
                        return sendWithFallbackRecursive(messages, chain, index + 1,
                                temperature, maxTokens, timeoutSeconds, attempts);
                    }
                });
    }

    public CompletableFuture<ConnectionStatus> testProvider(String name) {
        Provider provider = providers.get(name);
        if (provider == null) {
            ConnectionStatus status = new ConnectionStatus(false, 0, "Provider not found", System.currentTimeMillis());
            return CompletableFuture.completedFuture(status);
        }

        int timeout = LLMConfig.TIMEOUT.get();
        return provider.testConnection(timeout).thenApply(response -> {
            ConnectionStatus status;
            if (response.isSuccess()) {
                status = new ConnectionStatus(true, response.getLatencyMs(), null, System.currentTimeMillis());
            } else {
                status = new ConnectionStatus(false, response.getLatencyMs(), response.getError(), System.currentTimeMillis());
            }
            statusCache.put(name, status);
            return status;
        });
    }

    public @Nullable ConnectionStatus getCachedStatus(String name) {
        return statusCache.get(name);
    }

    public JsonObject getStatusJson() {
        JsonObject result = new JsonObject();
        JsonArray providerArray = new JsonArray();

        for (Map.Entry<String, Provider> entry : providers.entrySet()) {
            JsonObject pJson = new JsonObject();
            pJson.addProperty("name", entry.getKey());
            pJson.addProperty("type", entry.getValue().getType());
            String format = entry.getValue().getFormat();
            if (format != null) pJson.addProperty("format", format);
            pJson.addProperty("model", entry.getValue().getModel());
            pJson.addProperty("maskedKey", entry.getValue().getMaskedKey());

            ConnectionStatus cached = statusCache.get(entry.getKey());
            if (cached != null) {
                pJson.add("status", cached.toJson());
            } else {
                pJson.addProperty("status", "untested");
            }
            providerArray.add(pJson);
        }

        result.add("providers", providerArray);
        result.addProperty("count", providers.size());
        return result;
    }

    private boolean checkRateLimit() {
        int limit = LLMConfig.RATE_LIMIT.get();
        if (limit <= 0) return true;

        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start > 60000) {
            windowStart.set(now);
            requestCount.set(1);
            return true;
        }

        return requestCount.incrementAndGet() <= limit;
    }
}
```

- [ ] **Step 4: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add LLMConfig (SERVER), ProviderLoader, and ProviderManager with fallback support"
```

---

## Task 7: Pipeline System (PostProcessor, RegexPreset, LLMRequest, OutputTarget)

**Files:**
- Create: `src/main/java/com/liteming/llmjs/pipeline/PostProcessor.java`
- Create: `src/main/java/com/liteming/llmjs/pipeline/RegexPreset.java`
- Create: `src/main/java/com/liteming/llmjs/pipeline/LLMRequest.java`
- Create: `src/main/java/com/liteming/llmjs/pipeline/OutputTarget.java`

- [ ] **Step 1: Create `PostProcessor.java`**

```java
package vibe.liteming.llmjs.pipeline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@FunctionalInterface
public interface PostProcessor {
    String process(String input);

    static PostProcessor extract(String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        return input -> {
            Matcher matcher = pattern.matcher(input);
            return matcher.find() ? matcher.group() : input;
        };
    }

    static PostProcessor replace(String regex, String replacement) {
        Pattern pattern = Pattern.compile(regex);
        return input -> pattern.matcher(input).replaceAll(replacement);
    }
}
```

- [ ] **Step 2: Create `RegexPreset.java`**

```java
package vibe.liteming.llmjs.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RegexPreset {
    private static final Map<String, RegexPreset> REGISTRY = new ConcurrentHashMap<>();

    private final String name;
    private final List<PostProcessor> processors;

    public RegexPreset(String name, List<PostProcessor> processors) {
        this.name = name;
        this.processors = new ArrayList<>(processors);
    }

    public String apply(String input) {
        String result = input;
        for (PostProcessor p : processors) {
            result = p.process(result);
        }
        return result;
    }

    public static void register(String name, RegexPreset preset) {
        REGISTRY.put(name, preset);
    }

    public static RegexPreset get(String name) {
        return REGISTRY.get(name);
    }

    public static Map<String, RegexPreset> getAll() {
        return REGISTRY;
    }
}
```

- [ ] **Step 3: Create `OutputTarget.java`**

```java
package vibe.liteming.llmjs.pipeline;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class OutputTarget {

    public static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    public static void actionbar(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    public static void tellraw(ServerPlayer player, String message, String color, boolean bold) {
        Component component = Component.literal(message)
                .withStyle(style -> {
                    if (color != null) {
                        try {
                            style = style.withColor(net.minecraft.ChatFormatting.getByName(color));
                        } catch (Exception ignored) {}
                    }
                    if (bold) style = style.withBold(true);
                    return style;
                });
        player.sendSystemMessage(component);
    }

    public static void broadcast(net.minecraft.server.MinecraftServer server, String message) {
        server.getPlayerList().getPlayers().forEach(p ->
                p.sendSystemMessage(Component.literal(message)));
    }

    public static void broadcastActionbar(net.minecraft.server.MinecraftServer server, String message) {
        server.getPlayerList().getPlayers().forEach(p ->
                p.displayClientMessage(Component.literal(message), true));
    }
}
```

- [ ] **Step 4: Create `LLMRequest.java`**

This is the chainable builder. Single-use, consumed on terminal operation.

```java
package vibe.liteming.llmjs.pipeline;

import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.provider.Provider;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

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

    // Builder methods
    public LLMRequest system(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
    public LLMRequest provider(String name) { this.providerName = name; return this; }
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

    public LLMRequest validate(Predicate<String> validator) {
        this.validator = validator;
        return this;
    }

    public LLMRequest retries(int n) { this.retries = n; return this; }

    public LLMRequest onInvalid(Consumer<String> handler) {
        this.onInvalid = handler;
        return this;
    }

    public LLMRequest maxLength(int n) { this.maxLength = n; return this; }

    public LLMRequest truncateAt(String regex) {
        this.truncatePattern = regex;
        return this;
    }

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

    public void broadcast(net.minecraft.server.MinecraftServer server) {
        execute(content -> OutputTarget.broadcast(server, content));
    }

    public void broadcastActionbar(net.minecraft.server.MinecraftServer server) {
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
        // Build messages
        List<ApiFormat.Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new ApiFormat.Message("system", systemPrompt));
        }
        messages.add(new ApiFormat.Message("user", prompt));

        // Build provider chain
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

                    // Post-processing pipeline
                    String content = response.getContent();
                    for (PostProcessor pp : postProcessors) {
                        content = pp.process(content);
                    }

                    // Truncation
                    if (maxLength > 0 && content.length() > maxLength) {
                        content = truncate(content);
                    }

                    // Validation
                    if (validator != null && !validator.test(content)) {
                        if (attempt < retries) {
                            executeWithRetry(handler, attempt + 1);
                            return;
                        }
                        if (onInvalid != null) {
                            onInvalid.accept(content);
                        }
                        handler.accept(LLMResponse.error("Validation failed after " + (attempt + 1) + " attempts"));
                        return;
                    }

                    // Create final response with processed content
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
            // Find the last match of truncation pattern before maxLength
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(truncatePattern);
            java.util.regex.Matcher matcher = pattern.matcher(content.substring(0, maxLength));
            int lastEnd = -1;
            while (matcher.find()) {
                lastEnd = matcher.end();
            }
            if (lastEnd > 0) {
                return content.substring(0, lastEnd);
            }
        }

        return content.substring(0, maxLength);
    }
}
```

- [ ] **Step 5: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add pipeline system - LLMRequest builder, PostProcessor, RegexPreset, OutputTarget"
```

---

## Task 8: Session & JSON Workflows

**Files:**
- Create: `src/main/java/com/liteming/llmjs/session/ChatSession.java`
- Create: `src/main/java/com/liteming/llmjs/json/JsonMode.java`
- Create: `src/main/java/com/liteming/llmjs/json/SchemaMode.java`
- Create: `src/main/java/com/liteming/llmjs/json/FillMode.java`

- [ ] **Step 1: Create `ChatSession.java`**

```java
package vibe.liteming.llmjs.session;

import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.pipeline.LLMResponse;
import vibe.liteming.llmjs.provider.ProviderManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ChatSession {
    private final String providerName;
    private @Nullable String systemPrompt;
    private double temperature = 0.7;
    private int maxTokens = 1000;
    private final List<ApiFormat.Message> history = new ArrayList<>();
    private int maxHistory = 20;

    public ChatSession(String providerName) {
        this.providerName = providerName;
    }

    public ChatSession system(String prompt) {
        this.systemPrompt = prompt;
        return this;
    }

    public ChatSession temperature(double t) {
        this.temperature = t;
        return this;
    }

    public ChatSession maxTokens(int t) {
        this.maxTokens = t;
        return this;
    }

    public ChatSession maxHistory(int n) {
        this.maxHistory = n;
        return this;
    }

    public void chat(String message, Consumer<LLMResponse> callback) {
        // Build messages with history
        List<ApiFormat.Message> messages = new ArrayList<>();
        if (systemPrompt != null) {
            messages.add(new ApiFormat.Message("system", systemPrompt));
        }
        messages.addAll(history);
        messages.add(new ApiFormat.Message("user", message));

        int timeout = LLMConfig.TIMEOUT.get();

        ProviderManager.INSTANCE.sendWithFallback(
                messages, List.of(providerName), temperature, maxTokens, timeout
        ).thenAccept(response -> {
            if (response.isSuccess()) {
                // Add to history
                history.add(new ApiFormat.Message("user", message));
                history.add(new ApiFormat.Message("assistant", response.getContent()));
                // Trim history
                while (history.size() > maxHistory * 2) {
                    history.remove(0);
                    history.remove(0);
                }
            }
            callback.accept(response);
        });
    }

    public void clear() {
        history.clear();
    }

    public int getHistorySize() {
        return history.size();
    }
}
```

- [ ] **Step 2: Create `JsonMode.java`**

```java
package vibe.liteming.llmjs.json;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.pipeline.LLMResponse;
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

        ProviderManager.INSTANCE.sendWithFallback(
                messages, List.of(providerName), temperature, maxTokens, timeout
        ).thenAccept(response -> {
            if (!response.isSuccess()) {
                callback.accept(null);
                return;
            }

            String content = response.getContent().trim();
            // Strip markdown code blocks if present
            if (content.startsWith("```")) {
                content = content.replaceAll("^```(?:json)?\\n?", "").replaceAll("\\n?```$", "");
            }

            try {
                Object parsed = JsonParser.parseString(content);
                callback.accept(parsed);
            } catch (Exception e) {
                // Retry once
                retryJsonParse(prompt, providerName, temperature, maxTokens, callback);
            }
        });
    }

    private static void retryJsonParse(String prompt, String provider,
                                        @Nullable Double temperature, @Nullable Integer maxTokens,
                                        Consumer<Object> callback) {
        String retryPrompt = prompt + "\n\nYou MUST respond with ONLY valid JSON. No other text whatsoever.";

        List<ApiFormat.Message> messages = new ArrayList<>();
        messages.add(new ApiFormat.Message("system", "Respond with valid JSON only. No markdown formatting."));
        messages.add(new ApiFormat.Message("user", retryPrompt));

        int timeout = LLMConfig.TIMEOUT.get();

        ProviderManager.INSTANCE.sendWithFallback(
                messages, List.of(provider), temperature, maxTokens, timeout
        ).thenAccept(response -> {
            if (!response.isSuccess()) {
                callback.accept(null);
                return;
            }
            String content = response.getContent().trim();
            if (content.startsWith("```")) {
                content = content.replaceAll("^```(?:json)?\\n?", "").replaceAll("\\n?```$", "");
            }
            try {
                callback.accept(JsonParser.parseString(content));
            } catch (Exception e) {
                callback.accept(null);
            }
        });
    }
}
```

- [ ] **Step 3: Create `SchemaMode.java`**

```java
package vibe.liteming.llmjs.json;

import com.google.gson.*;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.provider.ProviderManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class SchemaMode {

    public static void chatWithSchema(String prompt, JsonObject schema,
                                       @Nullable String provider,
                                       @Nullable Double temperature,
                                       @Nullable Integer maxTokens,
                                       Consumer<Object> callback) {
        String schemaDesc = buildSchemaDescription(schema);
        String fullPrompt = prompt + "\n\nRespond with a JSON object matching this schema:\n" + schemaDesc
                + "\n\nRespond with ONLY the JSON object. No markdown, no explanation.";

        List<ApiFormat.Message> messages = new ArrayList<>();
        messages.add(new ApiFormat.Message("system", "You generate JSON matching exact schemas. Respond with valid JSON only."));
        messages.add(new ApiFormat.Message("user", fullPrompt));

        String providerName = provider != null ? provider : LLMConfig.DEFAULT_PROVIDER.get();
        int timeout = LLMConfig.TIMEOUT.get();

        ProviderManager.INSTANCE.sendWithFallback(
                messages, List.of(providerName), temperature, maxTokens, timeout
        ).thenAccept(response -> {
            if (!response.isSuccess()) {
                callback.accept(null);
                return;
            }
            String content = response.getContent().trim();
            if (content.startsWith("```")) {
                content = content.replaceAll("^```(?:json)?\\n?", "").replaceAll("\\n?```$", "");
            }
            try {
                JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
                // Validate against schema
                if (validateSchema(parsed, schema)) {
                    callback.accept(parsed);
                } else {
                    callback.accept(null);
                }
            } catch (Exception e) {
                callback.accept(null);
            }
        });
    }

    private static String buildSchemaDescription(JsonObject schema) {
        StringBuilder sb = new StringBuilder("{\n");
        for (Map.Entry<String, JsonElement> entry : schema.entrySet()) {
            sb.append("  \"").append(entry.getKey()).append("\": ");
            JsonElement val = entry.getValue();
            if (val.isJsonPrimitive()) {
                sb.append(val.getAsString()).append(" (type)");
            } else if (val.isJsonArray()) {
                sb.append("one of ").append(val);
            }
            sb.append(",\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private static boolean validateSchema(JsonObject obj, JsonObject schema) {
        for (Map.Entry<String, JsonElement> entry : schema.entrySet()) {
            if (!obj.has(entry.getKey())) return false;
        }
        return true;
    }
}
```

- [ ] **Step 4: Create `FillMode.java`**

```java
package vibe.liteming.llmjs.json;

import com.google.gson.*;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.provider.ProviderManager;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

public class FillMode {

    public static void fill(JsonObject template, String description,
                            @Nullable String provider,
                            @Nullable Double temperature,
                            @Nullable Integer maxTokens,
                            Consumer<Object> callback) {

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
        messages.add(new ApiFormat.Message("system",
                "You fill in values for templates. Respond with ONLY pipe-separated values. No field names, no explanation."));
        messages.add(new ApiFormat.Message("user", prompt));

        String providerName = provider != null ? provider : LLMConfig.DEFAULT_PROVIDER.get();
        int timeout = LLMConfig.TIMEOUT.get();

        ProviderManager.INSTANCE.sendWithFallback(
                messages, List.of(providerName), temperature, maxTokens, timeout
        ).thenAccept(response -> {
            if (!response.isSuccess()) {
                callback.accept(null);
                return;
            }

            String content = response.getContent().trim();
            String[] values = content.split("\\|", -1);

            if (values.length != keys.size()) {
                callback.accept(null);
                return;
            }

            JsonObject result = new JsonObject();
            for (int i = 0; i < keys.size(); i++) {
                result.addProperty(keys.get(i), values[i].trim());
            }
            callback.accept(result);
        });
    }
}
```

- [ ] **Step 5: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add ChatSession, JsonMode, SchemaMode, and FillMode"
```

---

## Task 9: KubeJS Binding

**Files:**
- Create: `src/main/java/com/liteming/llmjs/kubejs/LLMBinding.java`
- Modify: `src/main/java/com/liteming/llmjs/kubejs/LLMjsPlugin.java`

- [ ] **Step 1: Create `LLMBinding.java`**

This is the main API facade exposed as `LLM` to KubeJS scripts. Implements the overloaded `chat()` method (callback vs builder), session API, JSON workflow, and management methods.

```java
package vibe.liteming.llmjs.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.json.FillMode;
import vibe.liteming.llmjs.json.JsonMode;
import vibe.liteming.llmjs.json.SchemaMode;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.pipeline.*;
import vibe.liteming.llmjs.provider.ProviderManager;
import vibe.liteming.llmjs.session.ChatSession;
import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeObject;
import dev.latvian.mods.rhino.Scriptable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Main API facade exposed to KubeJS scripts as the global `LLM` object.
 *
 * Overloaded chat():
 * - LLM.chat(prompt, callback)         → void (callback form)
 * - LLM.chat(prompt, options, callback) → void (callback form)
 * - LLM.chat(prompt)                   → LLMRequest builder
 * - LLM.chat(prompt, options)           → LLMRequest builder
 */
public class LLMBinding {
    public static final LLMBinding INSTANCE = new LLMBinding();

    // === chat() overloads — handled by argument inspection ===

    public Object chat(Object... args) {
        if (args.length == 0) throw new IllegalArgumentException("chat() requires at least a prompt");

        String prompt = args[0].toString();

        // Check if last argument is a function (callback form)
        if (args.length >= 2 && args[args.length - 1] instanceof BaseFunction) {
            // Callback form
            @SuppressWarnings("unchecked")
            Consumer<LLMResponse> callback = wrapCallback((BaseFunction) args[args.length - 1]);

            if (args.length == 2) {
                // chat(prompt, callback)
                executeDirect(prompt, null, null, null, null, null, callback);
            } else if (args.length >= 3 && args[1] instanceof NativeObject) {
                // chat(prompt, options, callback)
                NativeObject opts = (NativeObject) args[1];
                executeDirect(prompt,
                        getStringOpt(opts, "system"),
                        getStringOpt(opts, "provider"),
                        getDoubleOpt(opts, "temperature"),
                        getIntOpt(opts, "maxTokens"),
                        getStringListOpt(opts, "fallback"),
                        callback);
            }
            return null; // void
        }

        // Builder form
        if (args.length == 1) {
            return new LLMRequest(prompt);
        } else if (args.length >= 2 && args[1] instanceof NativeObject) {
            NativeObject opts = (NativeObject) args[1];
            return new LLMRequest(prompt,
                    getStringOpt(opts, "system"),
                    getStringOpt(opts, "provider"),
                    getDoubleOpt(opts, "temperature"),
                    getIntOpt(opts, "maxTokens"),
                    getStringListOpt(opts, "fallback"));
        }

        return new LLMRequest(prompt);
    }

    // === chatDetailed() ===

    public void chatDetailed(String prompt, Object optionsOrCallback, Object... rest) {
        // chatDetailed(prompt, options, callback) or chatDetailed(prompt, callback)
        NativeObject opts = null;
        BaseFunction callbackFn;

        if (optionsOrCallback instanceof NativeObject) {
            opts = (NativeObject) optionsOrCallback;
            callbackFn = (BaseFunction) rest[0];
        } else {
            callbackFn = (BaseFunction) optionsOrCallback;
        }

        Consumer<LLMResponse> callback = wrapCallback(callbackFn);
        String provider = opts != null ? getStringOpt(opts, "provider") : null;
        Double temp = opts != null ? getDoubleOpt(opts, "temperature") : null;
        Integer maxTokens = opts != null ? getIntOpt(opts, "maxTokens") : null;

        executeDirect(prompt, null, provider, temp, maxTokens, null, callback);
    }

    // === Session API ===

    public ChatSession session(String provider) {
        return new ChatSession(provider);
    }

    public ChatSession session() {
        return new ChatSession(LLMConfig.DEFAULT_PROVIDER.get());
    }

    // === JSON Workflow ===

    public void chatJson(String prompt, Object optionsOrCallback, Object... rest) {
        NativeObject opts = null;
        BaseFunction callbackFn;

        if (optionsOrCallback instanceof NativeObject) {
            opts = (NativeObject) optionsOrCallback;
            callbackFn = (BaseFunction) rest[0];
        } else {
            callbackFn = (BaseFunction) optionsOrCallback;
        }

        String provider = opts != null ? getStringOpt(opts, "provider") : null;
        Double temp = opts != null ? getDoubleOpt(opts, "temperature") : null;
        Integer maxTokens = opts != null ? getIntOpt(opts, "maxTokens") : null;
        JsonObject schema = null;
        if (opts != null && opts.has("schema", opts)) {
            // Convert NativeObject schema to JsonObject — simplified approach
            schema = nativeToJson(opts.get("schema", opts));
        }

        if (schema != null) {
            SchemaMode.chatWithSchema(prompt, schema, provider, temp, maxTokens, wrapObjectCallback(callbackFn));
        } else {
            JsonMode.chatJson(prompt, provider, temp, maxTokens, wrapObjectCallback(callbackFn));
        }
    }

    public void fill(Object template, String description, Object callbackOrOpts, Object... rest) {
        JsonObject templateJson;
        if (template instanceof NativeObject) {
            templateJson = nativeToJson(template);
        } else if (template instanceof JsonObject) {
            templateJson = (JsonObject) template;
        } else {
            throw new IllegalArgumentException("Template must be an object");
        }

        BaseFunction callbackFn;
        String provider = null;
        if (callbackOrOpts instanceof BaseFunction) {
            callbackFn = (BaseFunction) callbackOrOpts;
        } else {
            callbackFn = (BaseFunction) rest[0];
        }

        FillMode.fill(templateJson, description, provider, null, null, wrapObjectCallback(callbackFn));
    }

    // === Regex Presets ===

    public void regex(String name, List<NativeObject> steps) {
        List<PostProcessor> processors = new ArrayList<>();
        for (NativeObject step : steps) {
            String type = step.get("type", step).toString();
            String pattern = step.get("pattern", step).toString();
            if ("extract".equals(type)) {
                processors.add(PostProcessor.extract(pattern));
            } else if ("replace".equals(type)) {
                String replacement = step.has("replacement", step)
                        ? step.get("replacement", step).toString() : "";
                processors.add(PostProcessor.replace(pattern, replacement));
            }
        }
        RegexPreset.register(name, new RegexPreset(name, processors));
    }

    // === Management API ===

    public List<String> providers() {
        return ProviderManager.INSTANCE.getProviderNames();
    }

    public JsonObject status(String name) {
        var cached = ProviderManager.INSTANCE.getCachedStatus(name);
        return cached != null ? cached.toJson() : new JsonObject();
    }

    public JsonObject statusAll() {
        return ProviderManager.INSTANCE.getStatusJson();
    }

    public void test(String provider, BaseFunction callback) {
        Consumer<LLMResponse> cb = wrapCallback(callback);
        ProviderManager.INSTANCE.testProvider(provider).thenAccept(status -> {
            LLMResponse response = status.connected()
                    ? LLMResponse.success("Connection successful", "", provider, 0, 0, status.latencyMs())
                    : LLMResponse.error(status.lastError() != null ? status.lastError() : "Connection failed");
            cb.accept(response);
        });
    }

    public JsonArray logs() {
        return LLMLogger.INSTANCE.toJsonArray(200);
    }

    public JsonArray logs(int count) {
        return LLMLogger.INSTANCE.toJsonArray(count);
    }

    public JsonArray logs(String level) {
        LLMLogger.Level lvl = LLMLogger.Level.valueOf(level.toUpperCase());
        JsonArray arr = new JsonArray();
        for (LLMLogger.LogEntry entry : LLMLogger.INSTANCE.getRecentByLevel(lvl, 200)) {
            arr.add(entry.toJson());
        }
        return arr;
    }

    public void reload() {
        ProviderManager.INSTANCE.reload();
    }

    public void log(String level, String message) {
        LLMLogger.Level lvl = LLMLogger.Level.valueOf(level.toUpperCase());
        LLMLogger.INSTANCE.log(lvl, "script", message, "manual", 0, 0, 0, null);
    }

    private void executeDirect(String prompt, @Nullable String system, @Nullable String provider,
                               @Nullable Double temperature, @Nullable Integer maxTokens,
                               @Nullable List<String> fallback, Consumer<LLMResponse> callback) {
        List<ApiFormat.Message> messages = new ArrayList<>();
        if (system != null && !system.isEmpty()) {
            messages.add(new ApiFormat.Message("system", system));
        }
        messages.add(new ApiFormat.Message("user", prompt));

        List<String> chain;
        if (fallback != null && !fallback.isEmpty()) {
            chain = fallback;
        } else if (provider != null) {
            chain = List.of(provider);
        } else {
            var defaultP = ProviderManager.INSTANCE.getDefaultProvider();
            chain = defaultP != null ? List.of(defaultP.getName()) : List.of();
        }

        int timeout = LLMConfig.TIMEOUT.get();
        ProviderManager.INSTANCE.sendWithFallback(messages, chain, temperature, maxTokens, timeout)
                .thenAccept(callback);
    }

    private Consumer<LLMResponse> wrapCallback(BaseFunction fn) {
        return response -> {
            try {
                Context cx = Context.enter();
                Scriptable scope = fn.getParentScope();
                // Pass response as a NativeObject the script can access
                fn.call(cx, scope, scope, new Object[]{ responseToScriptable(response, cx, scope) });
            } catch (Exception e) {
                vibe.liteming.llmjs.LLMjs.LOGGER.error("Callback error", e);
            } finally {
                Context.exit();
            }
        };
    }

    private Consumer<Object> wrapObjectCallback(BaseFunction fn) {
        return result -> {
            try {
                Context cx = Context.enter();
                Scriptable scope = fn.getParentScope();
                fn.call(cx, scope, scope, new Object[]{ result });
            } catch (Exception e) {
                vibe.liteming.llmjs.LLMjs.LOGGER.error("Callback error", e);
            } finally {
                Context.exit();
            }
        };
    }

    private Scriptable responseToScriptable(LLMResponse response, Context cx, Scriptable scope) {
        NativeObject obj = new NativeObject();
        obj.setParentScope(scope);
        obj.put("success", obj, response.isSuccess());
        obj.put("content", obj, response.getContent());
        obj.put("error", obj, response.getError());
        obj.put("model", obj, response.getModel());
        obj.put("provider", obj, response.getProvider());
        obj.put("promptTokens", obj, response.getPromptTokens());
        obj.put("completionTokens", obj, response.getCompletionTokens());
        obj.put("latencyMs", obj, response.getLatencyMs());
        return obj;
    }

    private static @Nullable String getStringOpt(NativeObject opts, String key) {
        if (!opts.has(key, opts)) return null;
        Object val = opts.get(key, opts);
        return val != null ? val.toString() : null;
    }

    private static @Nullable Double getDoubleOpt(NativeObject opts, String key) {
        if (!opts.has(key, opts)) return null;
        Object val = opts.get(key, opts);
        return val instanceof Number ? ((Number) val).doubleValue() : null;
    }

    private static @Nullable Integer getIntOpt(NativeObject opts, String key) {
        if (!opts.has(key, opts)) return null;
        Object val = opts.get(key, opts);
        return val instanceof Number ? ((Number) val).intValue() : null;
    }

    private static @Nullable List<String> getStringListOpt(NativeObject opts, String key) {
        if (!opts.has(key, opts)) return null;
        Object val = opts.get(key, opts);
        if (val instanceof Scriptable) {
            List<String> list = new ArrayList<>();
            Scriptable arr = (Scriptable) val;
            Object lengthObj = arr.get("length", arr);
            int length = lengthObj instanceof Number ? ((Number) lengthObj).intValue() : 0;
            for (int i = 0; i < length; i++) {
                list.add(arr.get(i, arr).toString());
            }
            return list;
        }
        return null;
    }

    private static JsonObject nativeToJson(Object obj) {
        JsonObject json = new JsonObject();
        if (obj instanceof NativeObject nObj) {
            for (Object id : nObj.getIds()) {
                String key = id.toString();
                Object val = nObj.get(key, nObj);
                if (val instanceof String) {
                    json.addProperty(key, (String) val);
                } else if (val instanceof Number) {
                    json.addProperty(key, (Number) val);
                } else if (val instanceof Boolean) {
                    json.addProperty(key, (Boolean) val);
                }
                // Arrays and nested objects could be added but keeping simple for now
            }
        }
        return json;
    }
}
```

- [ ] **Step 2: Update `LLMjsPlugin.java`**

```java
package vibe.liteming.llmjs.kubejs;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

public class LLMjsPlugin extends KubeJSPlugin {
    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("LLM", LLMBinding.INSTANCE);
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (may need adjustments for Rhino API compatibility — KubeJS 2001.6.x uses a modified Rhino, check imports work)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add LLMBinding KubeJS API facade with chat/session/json/management methods"
```

---

## Task 10: Network Layer

**Files:**
- Create: `src/main/java/com/liteming/llmjs/network/LLMNetwork.java`
- Create: `src/main/java/com/liteming/llmjs/network/PermissionCheck.java`
- Create: `src/main/java/com/liteming/llmjs/network/packet/C2SChatRequestPacket.java`
- Create: `src/main/java/com/liteming/llmjs/network/packet/C2SStatusRequestPacket.java`
- Create: `src/main/java/com/liteming/llmjs/network/packet/S2CChatResponsePacket.java`
- Create: `src/main/java/com/liteming/llmjs/network/packet/S2CStatusResponsePacket.java`
- Create: `src/main/java/com/liteming/llmjs/network/packet/S2CLogPacket.java`

- [ ] **Step 1: Create `PermissionCheck.java`**

```java
package vibe.liteming.llmjs.network;

import vibe.liteming.llmjs.config.LLMConfig;
import net.minecraft.server.level.ServerPlayer;

public class PermissionCheck {

    public static boolean canUse(ServerPlayer player) {
        if (LLMConfig.ALLOW_ALL_PLAYERS.get()) return true;
        return player.hasPermissions(LLMConfig.REQUIRE_OP_LEVEL.get());
    }

    public static boolean isPromptValid(String prompt) {
        return prompt != null && prompt.length() <= LLMConfig.MAX_PROMPT_LENGTH.get();
    }
}
```

- [ ] **Step 2: Create all 5 packet classes**

```java
// === C2SChatRequestPacket.java ===
package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.format.ApiFormat;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class C2SChatRequestPacket {
    private final UUID requestId;
    private final String prompt;
    private final String provider;

    public C2SChatRequestPacket(UUID requestId, String prompt, String provider) {
        this.requestId = requestId;
        this.prompt = prompt;
        this.provider = provider;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeUtf(prompt, 32767);
        buf.writeUtf(provider, 256);
    }

    public static C2SChatRequestPacket decode(FriendlyByteBuf buf) {
        return new C2SChatRequestPacket(buf.readUUID(), buf.readUtf(32767), buf.readUtf(256));
    }

    public static void handle(C2SChatRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!PermissionCheck.canUse(player)) {
                LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CChatResponsePacket(msg.requestId, false, "No permission", null));
                return;
            }
            if (!PermissionCheck.isPromptValid(msg.prompt)) {
                LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CChatResponsePacket(msg.requestId, false, "Prompt too long", null));
                return;
            }

            List<ApiFormat.Message> messages = List.of(new ApiFormat.Message("user", msg.prompt));
            int timeout = LLMConfig.TIMEOUT.get();
            ProviderManager.INSTANCE.sendWithFallback(messages, List.of(msg.provider), null, null, timeout)
                    .thenAccept(response -> {
                        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                                new S2CChatResponsePacket(msg.requestId, response.isSuccess(),
                                        response.isSuccess() ? null : response.getError(),
                                        response.getContent()));
                    });
        });
        ctx.get().setPacketHandled(true);
    }
}

// === C2SStatusRequestPacket.java ===
package vibe.liteming.llmjs.network.packet;

import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class C2SStatusRequestPacket {
    public C2SStatusRequestPacket() {}

    public void encode(FriendlyByteBuf buf) {}

    public static C2SStatusRequestPacket decode(FriendlyByteBuf buf) {
        return new C2SStatusRequestPacket();
    }

    public static void handle(C2SStatusRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PermissionCheck.canUse(player)) return;
            String statusJson = ProviderManager.INSTANCE.getStatusJson().toString();
            LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CStatusResponsePacket(statusJson, false));
        });
        ctx.get().setPacketHandled(true);
    }
}

// === S2CChatResponsePacket.java ===
package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

public class S2CChatResponsePacket {
    private final UUID requestId;
    private final boolean success;
    private final @Nullable String error;
    private final @Nullable String content;

    public S2CChatResponsePacket(UUID requestId, boolean success,
                                  @Nullable String error, @Nullable String content) {
        this.requestId = requestId;
        this.success = success;
        this.error = error;
        this.content = content;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(requestId);
        buf.writeBoolean(success);
        buf.writeUtf(error != null ? error : "", 32767);
        buf.writeUtf(content != null ? content : "", 32767);
    }

    public static S2CChatResponsePacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        boolean success = buf.readBoolean();
        String error = buf.readUtf(32767);
        String content = buf.readUtf(32767);
        return new S2CChatResponsePacket(id, success,
                error.isEmpty() ? null : error,
                content.isEmpty() ? null : content);
    }

    public static void handle(S2CChatResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Client-side: forward to console screen if open
            vibe.liteming.llmjs.client.ClientEventHandler.handleChatResponse(msg.requestId, msg.success, msg.content, msg.error);
        });
        ctx.get().setPacketHandled(true);
    }

    // Getters for client-side access
    public UUID getRequestId() { return requestId; }
    public boolean isSuccess() { return success; }
    public @Nullable String getContent() { return content; }
    public @Nullable String getError() { return error; }
}

// === S2CStatusResponsePacket.java ===
package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class S2CStatusResponsePacket {
    private final String statusJson;
    private final boolean openConsole;

    public S2CStatusResponsePacket(String statusJson, boolean openConsole) {
        this.statusJson = statusJson;
        this.openConsole = openConsole;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(statusJson, 32767);
        buf.writeBoolean(openConsole);
    }

    public static S2CStatusResponsePacket decode(FriendlyByteBuf buf) {
        return new S2CStatusResponsePacket(buf.readUtf(32767), buf.readBoolean());
    }

    public static void handle(S2CStatusResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (msg.openConsole) {
                vibe.liteming.llmjs.client.ClientEventHandler.openConsole(msg.statusJson);
            } else {
                vibe.liteming.llmjs.client.ClientEventHandler.updateStatus(msg.statusJson);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

// === S2CLogPacket.java ===
package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class S2CLogPacket {
    private final String logEntryJson;

    public S2CLogPacket(String logEntryJson) {
        this.logEntryJson = logEntryJson;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(logEntryJson, 32767);
    }

    public static S2CLogPacket decode(FriendlyByteBuf buf) {
        return new S2CLogPacket(buf.readUtf(32767));
    }

    public static void handle(S2CLogPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            vibe.liteming.llmjs.client.ClientEventHandler.handleLogEntry(msg.logEntryJson);
        });
        ctx.get().setPacketHandled(true);
    }
}
```

Note: Each packet class goes in its own file under `network/packet/`. They are shown together here for compactness.

- [ ] **Step 3: Create `LLMNetwork.java`**

```java
package vibe.liteming.llmjs.network;

import vibe.liteming.llmjs.LLMjs;
import vibe.liteming.llmjs.network.packet.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class LLMNetwork {
    private static final String PROTOCOL_VERSION = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(LLMjs.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        CHANNEL.messageBuilder(C2SChatRequestPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SChatRequestPacket::encode)
                .decoder(C2SChatRequestPacket::decode)
                .consumerMainThread(C2SChatRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SStatusRequestPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SStatusRequestPacket::encode)
                .decoder(C2SStatusRequestPacket::decode)
                .consumerMainThread(C2SStatusRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CChatResponsePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CChatResponsePacket::encode)
                .decoder(S2CChatResponsePacket::decode)
                .consumerMainThread(S2CChatResponsePacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CStatusResponsePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CStatusResponsePacket::encode)
                .decoder(S2CStatusResponsePacket::decode)
                .consumerMainThread(S2CStatusResponsePacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CLogPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CLogPacket::encode)
                .decoder(S2CLogPacket::decode)
                .consumerMainThread(S2CLogPacket::handle)
                .add();
    }
}
```

- [ ] **Step 4: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add network layer with 5 packet types, permission check, and SimpleChannel"
```

---

## Task 11: Commands

**Files:**
- Create: `src/main/java/com/liteming/llmjs/command/LLMCommand.java`

- [ ] **Step 1: Create `LLMCommand.java`**

Registers `/llm` command tree with subcommands: `console`, `status`, `test`, `reload`.

```java
package vibe.liteming.llmjs.command;

import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.PermissionCheck;
import vibe.liteming.llmjs.network.packet.S2CStatusResponsePacket;
import vibe.liteming.llmjs.provider.ProviderManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

public class LLMCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("llm")
                .then(Commands.literal("console")
                        .executes(ctx -> openConsole(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> showStatus(ctx.getSource())))
                .then(Commands.literal("test")
                        .then(Commands.argument("provider", StringArgumentType.string())
                                .executes(ctx -> testProvider(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "provider")))))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> reloadConfig(ctx.getSource())))
        );
    }

    private static int openConsole(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Console can only be opened by players"));
            return 0;
        }
        if (!PermissionCheck.canUse(player)) {
            source.sendFailure(Component.literal("No permission to use LLM features"));
            return 0;
        }
        // Send status data to trigger console opening on client
        String statusJson = ProviderManager.INSTANCE.getStatusJson().toString();
        LLMNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CStatusResponsePacket(statusJson, true));
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        var status = ProviderManager.INSTANCE.getStatusJson();
        source.sendSuccess(() -> Component.literal("[LLMjs] Provider status:"), false);
        for (var name : ProviderManager.INSTANCE.getProviderNames()) {
            var provider = ProviderManager.INSTANCE.getProvider(name);
            var cached = ProviderManager.INSTANCE.getCachedStatus(name);
            String statusStr = cached != null ? (cached.connected() ? "OK (" + cached.latencyMs() + "ms)" : "ERROR") : "untested";
            source.sendSuccess(() -> Component.literal("  " + name + " [" + provider.getType() + "] - " + statusStr), false);
        }
        return 1;
    }

    private static int testProvider(CommandSourceStack source, String providerName) {
        if ("*".equals(providerName)) {
            source.sendSuccess(() -> Component.literal("[LLMjs] Testing all providers..."), false);
            for (String name : ProviderManager.INSTANCE.getProviderNames()) {
                testSingle(source, name);
            }
        } else {
            testSingle(source, providerName);
        }
        return 1;
    }

    private static void testSingle(CommandSourceStack source, String name) {
        ProviderManager.INSTANCE.testProvider(name).thenAccept(status -> {
            if (status.connected()) {
                source.sendSuccess(() -> Component.literal("[LLMjs] " + name + ": OK (" + status.latencyMs() + "ms)"), false);
            } else {
                source.sendFailure(Component.literal("[LLMjs] " + name + ": FAILED - " + status.lastError()));
            }
        });
    }

    private static int reloadConfig(CommandSourceStack source) {
        ProviderManager.INSTANCE.reload();
        source.sendSuccess(() -> Component.literal("[LLMjs] Configuration reloaded"), false);
        return 1;
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add /llm command tree (console, status, test, reload)"
```

---

## Task 12: Client UI Console

**Files:**
- Create: `src/main/java/com/liteming/llmjs/client/ClientEventHandler.java`
- Create: `src/main/java/com/liteming/llmjs/client/screen/LLMConsoleScreen.java`
- Create: `src/main/java/com/liteming/llmjs/client/widget/LogPanel.java`
- Create: `src/main/java/com/liteming/llmjs/client/widget/ProviderListPanel.java`
- Create: `src/main/java/com/liteming/llmjs/client/widget/TestPanel.java`

- [ ] **Step 1: Create `ClientEventHandler.java`**

```java
package vibe.liteming.llmjs.client;

import vibe.liteming.llmjs.LLMjs;
import vibe.liteming.llmjs.client.screen.LLMConsoleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = LLMjs.MODID, value = Dist.CLIENT)
public class ClientEventHandler {

    public static void openConsole(String statusJson) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new LLMConsoleScreen(statusJson));
    }

    public static void handleChatResponse(java.util.UUID requestId, boolean success,
                                           String content, String error) {
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof LLMConsoleScreen console) {
            console.onChatResponse(requestId, success, content, error);
        }
    }

    public static void updateStatus(String statusJson) {
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof LLMConsoleScreen console) {
            console.onStatusUpdate(statusJson);
        }
    }

    public static void handleLogEntry(String logEntryJson) {
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof LLMConsoleScreen console) {
            console.onLogEntry(logEntryJson);
        }
    }
}
```

- [ ] **Step 2: Create `LLMConsoleScreen.java`**

```java
package vibe.liteming.llmjs.client.screen;

import vibe.liteming.llmjs.client.widget.LogPanel;
import vibe.liteming.llmjs.client.widget.ProviderListPanel;
import vibe.liteming.llmjs.client.widget.TestPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class LLMConsoleScreen extends Screen {
    private enum Tab { LOG, PROVIDERS, TEST }

    private Tab activeTab = Tab.LOG;
    private LogPanel logPanel;
    private ProviderListPanel providerPanel;
    private TestPanel testPanel;
    private final String initialStatusJson;

    public LLMConsoleScreen(String statusJson) {
        super(Component.literal("LLMjs Console"));
        this.initialStatusJson = statusJson;
    }

    @Override
    protected void init() {
        int tabY = 10;
        int tabW = 80;

        addRenderableWidget(Button.builder(Component.literal("Log"), b -> switchTab(Tab.LOG))
                .pos(width / 2 - 125, tabY).size(tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Providers"), b -> switchTab(Tab.PROVIDERS))
                .pos(width / 2 - 40, tabY).size(tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Test"), b -> switchTab(Tab.TEST))
                .pos(width / 2 + 45, tabY).size(tabW, 20).build());

        int panelY = 35;
        int panelH = height - 45;
        int panelW = width - 20;
        int panelX = 10;

        logPanel = new LogPanel(panelX, panelY, panelW, panelH);
        providerPanel = new ProviderListPanel(panelX, panelY, panelW, panelH, initialStatusJson);
        testPanel = new TestPanel(panelX, panelY, panelW, panelH);

        addRenderableWidget(logPanel);
        addRenderableWidget(providerPanel);
        addRenderableWidget(testPanel);

        switchTab(Tab.LOG);
    }

    private void switchTab(Tab tab) {
        activeTab = tab;
        logPanel.visible = (tab == Tab.LOG);
        providerPanel.visible = (tab == Tab.PROVIDERS);
        testPanel.visible = (tab == Tab.TEST);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 2, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // Packet handlers called from ClientEventHandler
    public void onChatResponse(UUID requestId, boolean success, @Nullable String content, @Nullable String error) {
        testPanel.onResponse(success, content, error);
    }

    public void onStatusUpdate(String statusJson) {
        providerPanel.updateStatus(statusJson);
    }

    public void onLogEntry(String logEntryJson) {
        logPanel.addEntry(logEntryJson);
    }
}
```

- [ ] **Step 3: Create `LogPanel.java`**

```java
package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class LogPanel extends AbstractWidget {
    private final List<LogDisplayEntry> entries = new ArrayList<>();
    private int scrollOffset = 0;
    private static final int LINE_HEIGHT = 12;

    private record LogDisplayEntry(String text, int color) {}

    public LogPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Log"));
    }

    public void addEntry(String logEntryJson) {
        try {
            JsonObject obj = JsonParser.parseString(logEntryJson).getAsJsonObject();
            String level = obj.has("level") ? obj.get("level").getAsString() : "INFO";
            String provider = obj.has("provider") ? obj.get("provider").getAsString() : "?";
            String status = obj.has("status") ? obj.get("status").getAsString() : "";
            long latency = obj.has("latencyMs") ? obj.get("latencyMs").getAsLong() : 0;
            String summary = obj.has("requestSummary") ? obj.get("requestSummary").getAsString() : "";

            int color = switch (level) {
                case "ERROR" -> 0xFF5555;
                case "WARN" -> 0xFFFF55;
                default -> 0xFFFFFF;
            };

            String text = String.format("[%s] %s | %s | %dms | %s", level, provider, status, latency, summary);
            entries.add(new LogDisplayEntry(text, color));

            // Auto-scroll to bottom
            int maxVisible = (height - 4) / LINE_HEIGHT;
            if (entries.size() > maxVisible) {
                scrollOffset = entries.size() - maxVisible;
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Background
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x80000000);

        int maxVisible = (height - 4) / LINE_HEIGHT;
        int startIdx = Math.max(0, scrollOffset);
        int endIdx = Math.min(entries.size(), startIdx + maxVisible);

        for (int i = startIdx; i < endIdx; i++) {
            LogDisplayEntry entry = entries.get(i);
            int drawY = getY() + 2 + (i - startIdx) * LINE_HEIGHT;
            graphics.drawString(net.minecraft.client.Minecraft.getInstance().font,
                    entry.text, getX() + 4, drawY, entry.color, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollOffset -= (int) delta * 3;
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - (height - 4) / LINE_HEIGHT)));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
```

- [ ] **Step 4: Create `ProviderListPanel.java`**

```java
package vibe.liteming.llmjs.client.widget;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SStatusRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class ProviderListPanel extends AbstractWidget {
    private record ProviderEntry(String name, String type, String format, String model, String status, String maskedKey) {}

    private final List<ProviderEntry> providers = new ArrayList<>();
    private static final int ROW_HEIGHT = 16;

    public ProviderListPanel(int x, int y, int width, int height, String statusJson) {
        super(x, y, width, height, Component.literal("Providers"));
        updateStatus(statusJson);
    }

    public void updateStatus(String statusJson) {
        providers.clear();
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("providers");
            if (arr == null) return;
            for (var el : arr) {
                JsonObject p = el.getAsJsonObject();
                String name = p.get("name").getAsString();
                String type = p.has("type") ? p.get("type").getAsString() : "?";
                String format = p.has("format") ? p.get("format").getAsString() : "-";
                String model = p.has("model") ? p.get("model").getAsString() : "?";
                String maskedKey = p.has("maskedKey") ? p.get("maskedKey").getAsString() : "***";

                String status = "untested";
                if (p.has("status") && p.get("status").isJsonObject()) {
                    JsonObject st = p.getAsJsonObject("status");
                    status = st.get("connected").getAsBoolean()
                            ? "OK (" + st.get("latency").getAsLong() + "ms)"
                            : "ERROR";
                }
                providers.add(new ProviderEntry(name, type, format, model, status, maskedKey));
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x80000000);

        // Header
        int y = getY() + 4;
        graphics.drawString(font, "Name", getX() + 4, y, 0xAAAAAA, false);
        graphics.drawString(font, "Type", getX() + 120, y, 0xAAAAAA, false);
        graphics.drawString(font, "Format", getX() + 180, y, 0xAAAAAA, false);
        graphics.drawString(font, "Model", getX() + 250, y, 0xAAAAAA, false);
        graphics.drawString(font, "Status", getX() + 380, y, 0xAAAAAA, false);
        y += ROW_HEIGHT;

        graphics.fill(getX() + 2, y - 2, getX() + width - 2, y - 1, 0xFF555555);

        for (ProviderEntry p : providers) {
            int statusColor = p.status.startsWith("OK") ? 0x55FF55 : (p.status.equals("untested") ? 0xFFFF55 : 0xFF5555);
            graphics.drawString(font, p.name, getX() + 4, y, 0xFFFFFF, false);
            graphics.drawString(font, p.type, getX() + 120, y, 0xCCCCCC, false);
            graphics.drawString(font, p.format, getX() + 180, y, 0xCCCCCC, false);
            graphics.drawString(font, p.model, getX() + 250, y, 0xCCCCCC, false);
            graphics.drawString(font, p.status, getX() + 380, y, statusColor, false);
            y += ROW_HEIGHT;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Click anywhere in the panel to refresh status
        if (isMouseOver(mouseX, mouseY)) {
            LLMNetwork.CHANNEL.sendToServer(new C2SStatusRequestPacket());
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
```

- [ ] **Step 5: Create `TestPanel.java`**

```java
package vibe.liteming.llmjs.client.widget;

import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.network.packet.C2SChatRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class TestPanel extends AbstractWidget {
    private EditBox providerInput;
    private EditBox promptInput;
    private Button sendButton;
    private @Nullable String responseText;
    private boolean waiting = false;

    public TestPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Test"));
    }

    // Called by LLMConsoleScreen.init() after adding to render list
    public void initWidgets(net.minecraft.client.gui.screens.Screen screen) {
        var font = net.minecraft.client.Minecraft.getInstance().font;

        providerInput = new EditBox(font, getX() + 80, getY() + 4, 150, 18, Component.literal("Provider"));
        providerInput.setValue("openai");
        providerInput.setMaxLength(64);

        promptInput = new EditBox(font, getX() + 80, getY() + 28, width - 170, 18, Component.literal("Prompt"));
        promptInput.setMaxLength(1000);
        promptInput.setValue("Hello, this is a test.");

        sendButton = Button.builder(Component.literal("Send"), b -> sendTest())
                .pos(getX() + width - 80, getY() + 28).size(70, 18).build();
    }

    private void sendTest() {
        if (waiting) return;
        waiting = true;
        responseText = "Waiting...";
        UUID requestId = UUID.randomUUID();
        LLMNetwork.CHANNEL.sendToServer(
                new C2SChatRequestPacket(requestId, promptInput.getValue(), providerInput.getValue()));
    }

    public void onResponse(boolean success, @Nullable String content, @Nullable String error) {
        waiting = false;
        responseText = success ? content : ("ERROR: " + error);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x80000000);

        graphics.drawString(font, "Provider:", getX() + 4, getY() + 9, 0xFFFFFF, false);
        graphics.drawString(font, "Prompt:", getX() + 4, getY() + 33, 0xFFFFFF, false);

        if (providerInput != null) providerInput.render(graphics, mouseX, mouseY, partialTick);
        if (promptInput != null) promptInput.render(graphics, mouseX, mouseY, partialTick);
        if (sendButton != null) sendButton.render(graphics, mouseX, mouseY, partialTick);

        // Response area
        int respY = getY() + 55;
        graphics.drawString(font, "Response:", getX() + 4, respY, 0xAAAAAA, false);
        if (responseText != null) {
            // Word-wrap and render
            var lines = font.split(Component.literal(responseText), width - 12);
            int lineY = respY + 12;
            for (var line : lines) {
                if (lineY > getY() + height - 12) break;
                graphics.drawString(font, line, getX() + 4, lineY, 0xFFFFFF, false);
                lineY += 10;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (providerInput != null) providerInput.mouseClicked(mouseX, mouseY, button);
        if (promptInput != null) promptInput.mouseClicked(mouseX, mouseY, button);
        if (sendButton != null) sendButton.mouseClicked(mouseX, mouseY, button);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (providerInput != null && providerInput.isFocused()) return providerInput.keyPressed(keyCode, scanCode, modifiers);
        if (promptInput != null && promptInput.isFocused()) return promptInput.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (providerInput != null && providerInput.isFocused()) return providerInput.charTyped(c, modifiers);
        if (promptInput != null && promptInput.isFocused()) return promptInput.charTyped(c, modifiers);
        return super.charTyped(c, modifiers);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
```

- [ ] **Step 6: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add client UI console with Log, Provider, and Test panels"
```

---

## Task 13: Main Class Wiring & Integration

**Files:**
- Modify: `src/main/java/com/liteming/llmjs/LLMjs.java`

- [ ] **Step 1: Rewrite `LLMjs.java` to wire everything together**

```java
package vibe.liteming.llmjs;

import vibe.liteming.llmjs.command.LLMCommand;
import vibe.liteming.llmjs.config.LLMConfig;
import vibe.liteming.llmjs.log.LLMLogger;
import vibe.liteming.llmjs.network.LLMNetwork;
import vibe.liteming.llmjs.provider.ProviderManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(LLMjs.MODID)
public class LLMjs {
    public static final String MODID = "llmjs";
    public static final Logger LOGGER = LogManager.getLogger();

    public LLMjs() {
        LLMConfig.register();
        LLMNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("LLMjs v2.0 initialized");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Initialize provider manager with server config directory
        var server = event.getServer();
        var configDir = server.getServerDirectory().toPath().resolve("serverconfig");
        ProviderManager.INSTANCE.init(configDir);

        // Resize logger if config specifies different size
        LLMLogger.INSTANCE.resize(LLMConfig.LOG_BUFFER_SIZE.get());

        LOGGER.info("LLMjs providers loaded: {}", ProviderManager.INSTANCE.getProviderNames());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LLMCommand.register(event.getDispatcher());
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: wire LLMjs main class with config, network, commands, and provider init"
```

---

## Task 14: Build Verification & Final Cleanup

- [ ] **Step 1: Full build verification**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL with no warnings

- [ ] **Step 2: Verify generated JAR**

Run: `ls build/libs/`
Expected: `llmjs-2.0.0.jar` exists

- [ ] **Step 3: Check for any remaining old files**

Verify no files from `vibe.liteming.llmjs.util` package remain. Verify `run/config/llmjs/llm_config.json` is deleted.

- [ ] **Step 4: Review mods.toml**

Ensure version is 2.0.0, description is updated, dependencies are correct.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "chore: final cleanup and build verification for LLMjs v2.0.0"
```

---

## Summary

| Task | Description | Files | Est. Steps |
|------|-------------|-------|-----------|
| 1 | Project cleanup & build config | 7 | 7 |
| 2 | Core data types & logger | 2 | 4 |
| 3 | HTTP service | 1 | 3 |
| 4 | API format adapters | 4 | 6 |
| 5 | Provider system | 3 | 5 |
| 6 | Config & provider manager | 3 | 5 |
| 7 | Pipeline system | 4 | 6 |
| 8 | Session & JSON workflows | 4 | 6 |
| 9 | KubeJS binding | 2 | 4 |
| 10 | Network layer | 7 | 5 |
| 11 | Commands | 1 | 3 |
| 12 | Client UI console | 5 | 7 |
| 13 | Main class wiring | 1 | 3 |
| 14 | Final cleanup | 0 | 5 |
| **Total** | | **~44 files** | **69 steps** |
