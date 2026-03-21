# LLMjs v2.0 Complete Refactor Design

## Overview

LLMjs is a KubeJS addon mod for Minecraft 1.20.1 Forge that enables KubeJS scripts to make HTTP requests to LLM APIs. Version 2.0 is a complete refactor addressing all issues from v1.x and adding major new capabilities.

**Target:** Minecraft 1.20.1 / Forge 47.2.0+ / KubeJS 2001.6.0+

## Design Decisions

- **Architecture:** Dual-track Provider model (Simple + RAW)
- **API format support:** OpenAI Compatible (base), Claude (Anthropic native), Gemini (Google native)
- **Threading:** All-async, no synchronous blocking API
- **Client-Server model:** Server holds all config/keys and executes requests; client proxies via network packets; server-side scripts call ProviderManager directly (no network hop)
- **Configuration:** Forge Config for globals + JSON files for providers (hot-reloadable)
- **KubeJS API:** Both functional style and session-based style
- **Builder pattern:** `LLMRequest` chainable builder with post-processing pipeline

## 1. Package Structure

```
com.liteming.llmjs/
├── LLMjs.java                         // @Mod entry point
├── provider/
│   ├── Provider.java                  // Interface: sendAsync(messages, options) → CompletableFuture<LLMResponse>
│   ├── SimpleProvider.java            // Simple mode: url+key+model + ApiFormat adapter
│   ├── RawProvider.java               // RAW mode: user-defined request template + variable substitution
│   └── ProviderManager.java           // Pool management, hot-reload, status queries
├── format/
│   ├── ApiFormat.java                 // Interface: buildHttpRequest() / parseHttpResponse()
│   ├── OpenAiFormat.java              // OpenAI compatible (Bearer token, /chat/completions)
│   ├── ClaudeFormat.java              // Anthropic native (x-api-key + anthropic-version headers, /messages)
│   └── GeminiFormat.java              // Google native (URL param key, /generateContent)
├── http/
│   └── HttpService.java              // java.net.http.HttpClient wrapper, fully async
├── json/
│   ├── JsonMode.java                  // Force JSON output + auto-parse
│   ├── SchemaMode.java                // Schema injection into prompt + validation
│   └── FillMode.java                  // Fill mode: template → delimiter prompt → reassemble
├── session/
│   └── ChatSession.java              // Multi-turn conversation, maintains history
├── config/
│   ├── LLMConfig.java                // Forge Config (global settings)
│   └── ProviderLoader.java           // Load providers from JSON, file-watch hot-reload
├── network/
│   ├── LLMNetwork.java               // SimpleChannel registration
│   ├── packet/
│   │   ├── C2SChatRequestPacket.java  // Client→Server: LLM call request
│   │   ├── C2SStatusRequestPacket.java// Client→Server: query provider status
│   │   ├── S2CChatResponsePacket.java // Server→Client: LLM response
│   │   ├── S2CStatusResponsePacket.java// Server→Client: status data
│   │   └── S2CLogPacket.java         // Server→Client: push log entries
│   └── PermissionCheck.java          // Permission verification logic
├── command/
│   └── LLMCommand.java               // /llm console, /llm status, /llm test, /llm reload
├── client/
│   ├── screen/
│   │   └── LLMConsoleScreen.java     // Console main screen (tabbed: Log, Providers, Test)
│   ├── widget/
│   │   ├── LogPanel.java             // Scrolling log panel, color-coded by level
│   │   ├── ProviderListPanel.java    // Provider list with status indicators
│   │   └── TestPanel.java            // Quick test panel: select provider, input prompt, send
│   └── ClientEventHandler.java       // Client command registration, keybind
├── log/
│   └── LLMLogger.java                // Ring buffer logger, last N entries, level filtering
├── pipeline/
│   ├── LLMRequest.java               // Chainable request builder with post-processing pipeline
│   ├── LLMResponse.java              // Response wrapper: content, metadata, provider info, attempts
│   ├── PostProcessor.java            // Interface for post-processing steps
│   ├── RegexPreset.java              // Named reusable regex pipeline
│   └── OutputTarget.java             // Terminal operations: tell, actionbar, broadcast, callback
└── kubejs/
    └── LLMjsPlugin.java              // KubeJS binding, registers LLM global object
```

## 2. Provider System

### 2.1 Provider Interface

```java
public interface Provider {
    String getName();
    CompletableFuture<LLMResponse> sendAsync(List<Message> messages, RequestOptions options);
    CompletableFuture<ConnectionStatus> testConnection();
    boolean isValid();
}
```

### 2.2 SimpleProvider

For users who just need url + key + model. Delegates request construction and response parsing to an `ApiFormat` implementation.

- Configured via `serverconfig/llmjs/providers.json`
- Each entry specifies `"type": "simple"` and `"format": "openai"|"claude"|"gemini"`
- The format adapter handles all protocol differences (headers, body structure, response parsing)

### 2.3 RawProvider

For users who need full control over HTTP requests. User defines the complete request template in JSON; the mod only does variable substitution (`${key}`, `${model}`, `${messages}`, `${temperature}`, `${max_tokens}`) and sends.

- Configured via `serverconfig/llmjs/providers_raw.json`
- `"type": "raw"`
- `body_template`: JSON object with `${...}` placeholders
- `headers`: custom headers with `${...}` placeholders
- `response_path`: JSON Path expression to extract content from response (e.g., `"result.text"`, `"choices[0].message.content"`)

### 2.4 ProviderManager

- Loads providers from both JSON files on server start
- Watches files for changes → hot-reload without restart
- Maintains provider pool with connection status cache
- Atomic reference swap on reload (no clear+rebuild race condition)
- Thread-safe via ConcurrentHashMap + volatile reference

## 3. API Format Adapters

### 3.1 ApiFormat Interface

```java
public interface ApiFormat {
    HttpRequest buildHttpRequest(String url, String key, String model,
                                  List<Message> messages, RequestOptions options);
    LLMResponse parseHttpResponse(String responseBody);
}
```

### 3.2 OpenAiFormat

- Header: `Authorization: Bearer ${key}`
- Body: `{ "model": "...", "messages": [...], "temperature": ..., "max_tokens": ... }`
- Response: `choices[0].message.content`, `usage.prompt_tokens/completion_tokens`
- Covers: OpenAI, Ollama, vLLM, LM Studio, most Chinese LLM providers

### 3.3 ClaudeFormat

- Header: `x-api-key: ${key}`, `anthropic-version: 2023-06-01`, `content-type: application/json`
- Body: `{ "model": "...", "messages": [...], "system": "...", "max_tokens": ... }`
- System prompt extracted from messages and placed in top-level `system` field
- Response: `content[0].text`, `usage.input_tokens/output_tokens`

### 3.4 GeminiFormat

- URL: `${url}/${model}:generateContent?key=${key}`
- Body: `{ "contents": [{ "role": "user"|"model", "parts": [{ "text": "..." }] }], "systemInstruction": {...}, "generationConfig": {...} }`
- Role mapping: `assistant` → `model`
- Response: `candidates[0].content.parts[0].text`, `usageMetadata.promptTokenCount/candidatesTokenCount`

## 4. KubeJS API

### 4.0 Execution Context

LLM API calls can originate from two contexts:

**Server-side scripts** (`server_scripts/`): The most common case. KubeJS server event handlers (e.g., `PlayerEvents.chat`, `ItemEvents.rightClicked`, `ServerEvents.tick`) run on the server. LLM calls go directly to `ProviderManager.sendAsync()` — no network packets involved. Post-processing pipeline (extract, replace, validate, etc.) also runs server-side in the same script context.

**Client-side UI** (Console Test Panel, `/llm test`): The client has no direct access to providers or API keys. These requests serialize to `C2SChatRequestPacket`, the server executes the LLM call, and returns the raw response via `S2CChatResponsePacket`. Post-processing is not available for client-originated requests (test panel shows raw response only).

### 4.1 API Method Signatures

`LLM.chat()` is overloaded:
- **With callback** (last arg is a function) → executes immediately, returns void: `LLM.chat(prompt, callback)`, `LLM.chat(prompt, options, callback)`
- **Without callback** → returns `LLMRequest` builder for chaining: `LLM.chat(prompt)`, `LLM.chat(prompt, options)`

Error handling for callback form: the callback receives an `LLMResult` object with `.content` (string or null), `.success` (boolean), `.error` (string or null). On failure, `.success` is false and `.error` contains the error message.

```js
// Callback form — fires immediately, returns void
LLM.chat("hello", result => {
    if (result.success) player.tell(result.content)
    else player.tell("Error: " + result.error)
})

// Callback form with options
LLM.chat("hello", {
    provider: "my-claude",
    temperature: 0.5,
    maxTokens: 500,
    system: "You are an NPC blacksmith"
}, result => {
    player.tell(result.content)
})

// Builder form — returns LLMRequest for chaining
LLM.chat("hello")
    .fallback(["openai", "claude"])
    .tell(player)

// Detailed response (includes token usage metadata)
LLM.chatDetailed("hello", { provider: "openai" }, response => {
    // response.content, response.model, response.promptTokens,
    // response.completionTokens, response.provider, response.latencyMs
})
```

### 4.2 Session API

```js
let session = LLM.session("openai")
    .system("You are a blacksmith NPC, gruff but kind")
    .temperature(0.8)

session.chat("hello", reply => { player.tell(reply.content) })
session.chat("what do you sell?", reply => { player.tell(reply.content) })  // auto carries history
session.clear()  // clear history, start fresh
```

Session lifecycle: sessions are held in memory with a configurable max history length (default 20 messages). Sessions do not survive script reloads or server restarts. Sessions are identified by the variable reference — each `LLM.session()` call creates a new independent session.

### 4.3 JSON Workflow

#### Layer 1: Basic JSON Mode

```js
LLM.chatJson("generate a random weapon", { provider: "openai" }, item => {
    // item is already a JS object: { name: "Flame Sword", damage: 15, ... }
})
```

Implementation: Appends "respond in JSON format" instruction to system prompt. Parses response with `JSON.parse()`, retries once on parse failure.

#### Layer 2: Schema-Constrained Mode

```js
LLM.chatJson("generate a random weapon", {
    schema: {
        name: "string",
        damage: "int",
        element: ["fire", "ice", "lightning"],
        lore: "string"
    }
}, item => {
    // guaranteed to have name/damage/element/lore with correct types
})
```

Implementation: Converts schema to natural language description injected into prompt. Validates response fields and types. Retries once on validation failure.

#### Layer 3: Fill Mode

```js
LLM.fill({
    name: "_",
    damage: "_",
    element: "_",
    lore: "_"
}, "a legendary weapon forged by an ancient dragon", filled => {
    // filled = { name: "Dragon's Bane", damage: 285, element: "fire", lore: "..." }
})
```

Implementation: Sends optimized prompt instructing AI to return only values separated by `|` in key order. AI responds with e.g. `Dragon's Bane|285|fire|The eternal flame...`. Mod splits by delimiter and reassembles into original template structure. Saves tokens by not repeating the JSON structure.

Known limitation: if a value itself contains `|`, parsing may break. For v2.0 this is accepted as a known limitation. Workarounds: use a different delimiter via options, or use Schema mode for values likely to contain pipe characters.

### 4.3.1 JSON Methods and Builder Relationship

`LLM.chatJson()` and `LLM.fill()` are standalone convenience methods (callback-only, not builder-chainable). For combining JSON processing with the builder pipeline, use `.pipe("clean_json")` or `.validate()` on a regular `LLM.chat()` builder.

### 4.4 Request Builder Pipeline

The core of the v2 API. `LLM.chat()` without a callback returns an `LLMRequest` builder (single-use, not reusable). Terminal operations (`.tell()`, `.actionbar()`, `.callback()`) trigger the actual async request and consume the builder.

Fallback can be specified either in the options object or via the builder method. Builder method takes precedence if both are set.

```
LLM.chat(prompt, options)       → build LLMRequest
    .fallback([...])            → set provider fallback chain
    .extract(regex)             → add post-processing: extract matching content
    .replace(regex, str)        → add post-processing: regex replace
    .pipe("preset_name")        → reference a named regex preset
    .validate(fn)               → set output validator function
    .retries(n)                 → retry count on validation failure
    .onInvalid(fn)              → callback when all retries exhausted
    .maxLength(n)               → truncation length limit
    .truncateAt(regex)          → truncation boundary pattern
    .tell(player)               → terminal: send + pipeline + output to chat
    .actionbar(player)          → terminal: send + pipeline + output to actionbar
    .tellraw(player, style)     → terminal: send + pipeline + output with formatting
    .broadcast()                → terminal: tellraw to all players
    .broadcastActionbar()       → terminal: actionbar to all players
    .callback(fn)               → terminal: send + pipeline + raw callback
```

#### Fallback Chain

```js
LLM.chat("hello", { timeout: 10 })
    .fallback(["openai", "my-claude", "local-llama"])
    .tell(player)
// Tries openai first; on failure/timeout → claude; on failure → local
// result.provider shows which actually responded
// result.attempts shows the full attempt history
```

Implementation: `HttpService` tries providers in array order. Network errors, timeouts, and non-2xx status codes trigger fallback to next. All providers failing triggers error callback.

#### Regex Management

```js
// Inline post-processing
LLM.chat("generate weapon as JSON")
    .extract(/\{[\s\S]*\}/)
    .replace(/```json\n?/, "")
    .replace(/\n?```$/, "")
    .tell(player)

// Register reusable regex presets (call in startup_scripts for global availability)
// If two scripts register the same preset name, the last one wins
LLM.regex("clean_json", [
    { type: "extract", pattern: /\{[\s\S]*\}/ },
    { type: "replace", pattern: /```json\n?/, replacement: "" },
    { type: "replace", pattern: /\n?```$/, replacement: "" }
])

// Use by name
LLM.chat("generate data").pipe("clean_json").tell(player)

// Chain multiple presets
LLM.chat("generate content").pipe("clean_json").pipe("my_filter").tell(player)
```

#### Output Truncation & Validation

```js
// Truncate at sentence boundary
LLM.chat("tell a story")
    .maxLength(200)
    .truncateAt(/[。！？.!?]\s*/)
    .tell(player)

// Validate + retry
LLM.chat("respond in JSON")
    .validate(result => {
        try { JSON.parse(result); return true }
        catch(e) { return false }
    })
    .retries(2)
    .onInvalid(raw => {
        player.tell("AI response format error")
        LLM.log("warn", "output validation failed: " + raw)
    })
    .tell(player)

// Full combo
LLM.chat("generate weapon", { fallback: ["openai", "claude"] })
    .pipe("clean_json")
    .validate(r => JSON.parse(r).name != null)
    .retries(2)
    .maxLength(500)
    .tell(player)
```

### 4.5 Debug & Management API

```js
LLM.providers()              // → ["openai", "my-claude", "my-gemini"]
LLM.status("openai")         // → { connected: true, latency: 230, lastError: null }
LLM.statusAll()              // → all provider status objects

LLM.logs()                   // → last N log entries
LLM.logs(20)                 // → last 20
LLM.logs("error")            // → error level only

LLM.test("openai", result => {
    // result.success, result.latencyMs, result.error
})

LLM.reload()                 // hot-reload config (requires OP)
```

## 5. Configuration System

### 5.1 Forge Config — Global Settings

File: `serverconfig/llmjs-server.toml` (registered as `ModConfig.Type.SERVER`, changed from v1.x COMMON type to prevent key syncing to clients)

```toml
[general]
default_provider = "openai"
timeout = 30              # 1-300 seconds, per-request timeout
rate_limit = 30           # max requests per minute globally, 0 = unlimited
max_prompt_length = 10000 # max characters per prompt (prevents abuse via network packets)
log_buffer_size = 200     # ring buffer capacity

[permission]
require_op_level = 2      # minimum OP level to use LLM features
# Server-only: grant LLM request permission to ALL players.
# WARNING: may cause excessive LLM requests from unauthorized players.
allow_all_players = false
```

### 5.2 Provider JSON — Simple Mode

File: `serverconfig/llmjs/providers.json`

```json
{
  "openai": {
    "type": "simple",
    "format": "openai",
    "url": "https://api.openai.com/v1/chat/completions",
    "key": "sk-xxx",
    "model": "gpt-4o",
    "temperature": 0.7,
    "max_tokens": 1000
  },
  "my-claude": {
    "type": "simple",
    "format": "claude",
    "url": "https://api.anthropic.com/v1/messages",
    "key": "sk-ant-xxx",
    "model": "claude-sonnet-4-20250514"
  },
  "my-gemini": {
    "type": "simple",
    "format": "gemini",
    "url": "https://generativelanguage.googleapis.com/v1beta/models",
    "key": "AIza-xxx",
    "model": "gemini-2.0-flash"
  },
  "local-ollama": {
    "type": "simple",
    "format": "openai",
    "url": "http://localhost:11434/v1/chat/completions",
    "key": "not-required",
    "model": "llama3"
  }
}
```

### 5.3 Provider JSON — RAW Mode

File: `serverconfig/llmjs/providers_raw.json`

```json
{
  "custom-api": {
    "type": "raw",
    "url": "https://my-api.com/generate",
    "method": "POST",
    "headers": {
      "Authorization": "Token ${key}",
      "Content-Type": "application/json"
    },
    "body_template": {
      "prompt": "${messages}",
      "params": {
        "temp": "${temperature}",
        "max": "${max_tokens}"
      }
    },
    "response_path": "result.text",
    "key": "my-secret-key",
    "model": "custom-model"
  }
}
```

Variables available in templates: `${key}`, `${model}`, `${messages}` (JSON array), `${temperature}`, `${max_tokens}`, `${system}` (system prompt text).

`response_path` uses a simple dot-bracket notation to extract content from response JSON: dot for object access (`result.text`), brackets for array indexing (`choices[0].message.content`). This is a custom mini-parser, not full JSONPath.

### 5.4 Hot Reload

`ProviderLoader` uses `WatchService` to monitor `serverconfig/llmjs/` directory. On file change:
1. Parse new JSON
2. Build new provider map
3. Atomic swap via volatile reference (no clear+rebuild race)
4. Log reload event

Note: `WatchService` on Windows may have latency (2-10s). `/llm reload` command serves as the reliable immediate alternative.

Also triggered by `/llm reload` command or `LLM.reload()` script call.

## 6. Network & Permissions

### 6.1 Network Packets

| Packet | Direction | Purpose |
|--------|-----------|---------|
| `C2SChatRequestPacket` | Client→Server | LLM call request (prompt, options, provider, request ID) |
| `C2SStatusRequestPacket` | Client→Server | Query provider pool status |
| `S2CChatResponsePacket` | Server→Client | LLM response (content, metadata, request ID) |
| `S2CStatusResponsePacket` | Server→Client | Provider status data |
| `S2CLogPacket` | Server→Client | Push log entries to console UI |

All packets use a request ID for async correlation.

### 6.2 Permission Flow

```
Client sends request → C2SChatRequestPacket → Server
  → Validate prompt length (≤ max_prompt_length, reject oversized)
  → PermissionCheck:
     1. allow_all_players == true → allow
     2. player.hasPermission(require_op_level) → allow
     3. otherwise → deny, return error packet
  → Rate limit check (global counter)
  → Execute LLM call via ProviderManager
  → S2CChatResponsePacket → Client
```

### 6.3 Security

- API keys are NEVER sent to clients — only the server holds keys
- `getAllConfigs()` masks keys (shows first 4 chars + `***`)
- `ProviderConfig.toString()` masks keys to prevent accidental log leakage
- Network packets do not include key data in any direction

## 7. Client UI Console

Opened via `/llm console` command. Uses Minecraft native Screen/Widget API (no external GUI library). Client-only code under `client/` package.

### 7.1 Log Panel (default tab)

- Real-time rendering from ring buffer
- Color-coded by level: normal=white, warning=yellow, error=red
- Each entry shows: timestamp, provider name, request summary (truncated), status code, latency, token usage
- Scrollable, auto-scrolls to bottom on new entries
- Data source: `S2CLogPacket` pushed from server when console is open

### 7.2 Provider List Panel

- Table of all loaded providers
- Columns: name, type (Simple/RAW), format, connection status (untested/ok/error), last test latency
- Click a provider to run connection test
- Status refreshed via `C2SStatusRequestPacket` / `S2CStatusResponsePacket`

### 7.3 Test Panel

- Dropdown to select provider
- Text input for prompt
- Send button
- Response display area showing raw response text
- Shows: latency, token usage, provider that responded (relevant for fallback chains)

## 8. Client Commands

```
/llm console              — open console UI
/llm status               — print all provider status summary to chat
/llm test <provider>      — test specific provider connection
/llm test *               — test all providers
/llm reload               — hot-reload config (requires OP)
```

## 9. Logging System

`LLMLogger` — ring buffer (default 200 entries, configurable).

Each log entry contains:
- timestamp
- level (INFO, WARN, ERROR)
- provider name
- request summary (first 100 chars of prompt)
- response status (success/error/timeout)
- latency in ms
- token usage (prompt + completion)
- error message (if any)

Accessible from:
- KubeJS scripts: `LLM.logs()`, `LLM.logs(n)`, `LLM.logs("error")`
- Console UI: Log Panel (real-time push)
- Commands: `/llm status` (summary only)

## 10. Cleanup from v1.x

Remove:
- OkHttp dependency from `build.gradle` (unused)
- MixinGradle configuration (no mixins)
- `run/config/llmjs/llm_config.json` (orphaned file)
- Old hardcoded provider config fields in `LLMConfig`
- `enabled_providers` config value (unused)
- All old Java source files (complete rewrite)

## 11. Request Processing Pipeline

### 11.1 Server-side script path (primary use case)

```
1. KubeJS server script calls LLM.chat(...) or builder method
2. LLMRequest builder accumulates options
3. Terminal operation called (.tell(), .callback(), etc.)
4. ProviderManager resolves provider (or fallback chain) — direct call, no network
5. Provider.sendAsync() → HttpService → HTTP call
6. For SimpleProvider: ApiFormat.buildHttpRequest() → HTTP → ApiFormat.parseHttpResponse()
   For RawProvider: template substitution → HTTP → response_path extraction
7. On failure + fallback: retry with next provider in chain
8. Post-processing pipeline (runs in same server script context):
   a. extract(regex), replace(regex, str), pipe("preset")
   b. validate(fn) → if invalid + retries → go to step 4
   c. maxLength(n) + truncateAt(regex)
9. Output to target (tell/actionbar/broadcast/callback)
10. Log entry written to LLMLogger ring buffer
11. If any client has console open → push S2CLogPacket
```

### 11.2 Client-side path (Console UI test panel only)

```
1. User types prompt in Test Panel, clicks Send
2. Serialize to C2SChatRequestPacket → send to server
3. Server: validate prompt length → PermissionCheck → rate limit
4. Server: ProviderManager → Provider.sendAsync() → HTTP
5. Server: S2CChatResponsePacket (raw response) → client
6. Client: display raw response in Test Panel (no post-processing pipeline)
7. Server: log entry → push to console if open
```

Note: The builder pipeline (fallback, extract, replace, validate, etc.) is only available in server-side scripts, not from the client test panel. The test panel is for quick configuration validation only.
