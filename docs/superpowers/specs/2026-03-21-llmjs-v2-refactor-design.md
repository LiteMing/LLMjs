# LLMjs v2.0 Complete Refactor Design

## Overview

LLMjs is a KubeJS addon mod for Minecraft 1.20.1 Forge that enables KubeJS scripts to make HTTP requests to LLM APIs. Version 2.0 is a complete refactor addressing all issues from v1.x and adding major new capabilities.

**Target:** Minecraft 1.20.1 / Forge 47.2.0+ / KubeJS 2001.6.0+

## Design Decisions

- **Architecture:** Dual-track Provider model (Simple + RAW)
- **API format support:** OpenAI Compatible (base), Claude (Anthropic native), Gemini (Google native)
- **Threading:** All-async, no synchronous blocking API
- **Client-Server model:** Server holds all config/keys and executes requests; client proxies via network packets
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

### 4.1 Functional API

```js
// Simplest call — callback style, fully async
LLM.chat("hello", result => {
    player.tell(result)
})

// With options
LLM.chat("hello", {
    provider: "my-claude",
    temperature: 0.5,
    maxTokens: 500,
    system: "You are an NPC blacksmith"
}, result => {
    player.tell(result)
})

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

session.chat("hello", reply => { player.tell(reply) })
session.chat("what do you sell?", reply => { player.tell(reply) })  // auto carries history
session.clear()  // clear history, start fresh
```

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

### 4.4 Request Builder Pipeline

The core of the v2 API. `LLM.chat()` returns an `LLMRequest` builder. Terminal operations (`.tell()`, `.actionbar()`, `.callback()`) trigger the actual async request.

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

// Register reusable regex presets
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

File: `serverconfig/llmjs-server.toml`

```toml
[general]
default_provider = "openai"
timeout = 30              # 1-300 seconds, per-request timeout
rate_limit = 30           # max requests per minute globally, 0 = unlimited
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

`response_path` uses dot notation to extract content from response JSON.

### 5.4 Hot Reload

`ProviderLoader` uses `WatchService` to monitor `serverconfig/llmjs/` directory. On file change:
1. Parse new JSON
2. Build new provider map
3. Atomic swap via volatile reference (no clear+rebuild race)
4. Log reload event

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

The complete lifecycle of a request:

```
1. KubeJS script calls LLM.chat(...) or builder method
2. LLMRequest builder accumulates options:
   - provider / fallback chain
   - post-processors (extract, replace, pipe)
   - validators
   - truncation rules
   - output target
3. Terminal operation called (.tell(), .callback(), etc.)
4. [Client] Serialize to C2SChatRequestPacket → send to server
5. [Server] PermissionCheck → rate limit check
6. [Server] ProviderManager resolves provider (or fallback chain)
7. [Server] Provider.sendAsync() → HttpService
8. [Server] For SimpleProvider: ApiFormat.buildHttpRequest() → HTTP call → ApiFormat.parseHttpResponse()
   For RawProvider: template substitution → HTTP call → response_path extraction
9. [Server] On failure + fallback: retry with next provider in chain
10. [Server] S2CChatResponsePacket → client
11. [Client] Post-processing pipeline:
    a. extract(regex) — extract matching content
    b. replace(regex, str) — regex replacements
    c. pipe("preset") — apply named regex preset
    d. validate(fn) — check output validity
       - if invalid + retries remaining → go to step 4 with retry flag
       - if invalid + no retries → call onInvalid()
    e. maxLength(n) + truncateAt(regex) — truncate
12. [Client] Output to target (tell/actionbar/broadcast/callback)
13. [Server] Log entry written to LLMLogger ring buffer
14. [Server] If client has console open → push S2CLogPacket
```

Note: Post-processing (step 11) runs client-side since it may involve JS functions (validate callbacks) that only exist in the KubeJS script context. The server sends raw LLM response; client handles pipeline.
