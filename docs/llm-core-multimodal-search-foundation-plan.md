# LLM Core 多模态与联网能力地基计划

> **状态**：`llm-core 1.4.1` 执行中。purpose 联网授权、Provider capability profile、请求级 Hosted Web
> Search、来源归一化和四种内置搜索方言已完成离线实现与定向测试；Console 实机、真实 Provider EVAL、独立搜索
> 后端以及其余多模态/Tool 卡仍未完成。
>
> **审计基线**：AIjs `purpose-test-routing@0f8eb76`（`llm-core 1.4.0`）；CreatureChat
> `HDRS@5637334`。审计日期：2026-07-30。
>
> **输入**：CreatureChat 0.9.8～1.0.0 计划草案，尤其是 098-03 resolver、099-01
> `DEEP_RESOLUTION`、099-03 单层调查 coordinator、099-04 evidence bundle，以及现有
> `SUPERVISOR_REVIEW` 知识审查路径。
>
> **本文件定位**：记录 AIjs/llm-core 的跨项目能力地基、兼容边界和实施卡。它不修改
> CreatureChat 的版本计划，不授权提前实现 CreatureChat 的 resolver、Agent coordinator 或游戏世界读取。

---

## 0. 目标、边界与完成定义

### 0.1 目标

在不迫使现有下游同步迁移的前提下，使 `llm-core` 能作为以下未来能力的稳定协议与路由层：

1. CreatureChat resolver 把游戏世界的图片、音频、视频片段、文件或结构化证据交给模型；
2. CreatureChat 的 `SUPERVISOR_REVIEW`、未来 `DEEP_RESOLUTION` 等 purpose 显式请求 Provider 托管的
   Web Search，以校准外部知识；
3. CreatureChat 自己运行单层或后续 Agent 链时，能够向模型声明 caller-owned tools，接收结构化
   tool call，再把 tool result 作为下一次请求输入；
4. llm-core 在发送前按实际载荷、purpose 策略和 Provider 能力选择可用链，不能靠 HTTP 报错猜测能力；
5. Console 能解释某次请求请求了什么能力、为何选择/跳过某 Provider、是否实际搜索、使用了哪些来源，
   同时不把大段 Base64 或临时授权 URL 写入日志。

### 0.2 三层所有权

```text
CreatureChat / 其他宿主
  世界快照、resolver registry、权限、Agent 状态机、tool 执行、证据可信度、最终游戏写入
                         │ typed request / inert tool call / typed evidence
                         ▼
llm-core
  多模态与工具协议、能力准入、purpose 策略、Provider 路由、单次模型交换、预算与可观察性
                         │ provider-specific request / response
                         ▼
Provider adapter
  OpenAI-compatible / Claude / Gemini 等协议映射、托管搜索、上传与响应归一化
```

**硬边界**：`llm-core` 可以表达一次模型交换中的 tool definitions、tool calls 和 tool results，但不执行
caller-owned tool，不持有 resolver registry，不决定递归/深度，不持有 Minecraft 对象，也不将搜索结果宣布为事实。
Agent 循环和最终事实裁决属于 CreatureChat。

### 0.3 完成定义

| 阶段 | 可见结果 | 不能冒充完成的情况 |
|---|---|---|
| 兼容地基 | 未修改的 CreatureChat 仍可编译、链接并保持原请求行为 | 只有源码编译通过，没有旧 jar ABI/双加载器验证 |
| 多模态地基 | 新 API 能表达媒体，路由只选择明确支持该载荷的 Provider；至少图片迁移和一个音频纵向切片真实通过 | 只增加 enum/record，所有 adapter 都丢弃载荷 |
| 联网搜索 | 宿主显式请求、管理员显式允许、Provider 明确支持时才搜索；响应带实际使用记录与来源 | 在 prompt 里要求“请联网”，或只检查 `finish_reason` |
| Tool 协议 | core 可往返 function definition/call/result，调用结果可继续送入下一次 exchange | core 自动执行函数、递归调用或创建隐藏 Agent |
| 视频/文件 | 至少一个真实 Provider adapter、大小限制、传输生命周期与清理测试全部成立 | 把任意视频全量 Base64 塞进现有 JSON 即宣称支持 |

### 0.4 明确不做

- 不把 CreatureChat resolver、expression、action 或 Agent coordinator 搬进 llm-core；
- 不让 llm-core 读取世界、实体、客户端屏幕、麦克风、文件系统路径或任意 URL；
- 不在旧 `send()` / `sendStreaming()` 中默认启用搜索、工具或新模态；
- 不用模型名猜能力，不让一次成功 probe 自动改写生产配置；
- 不为所有厂商暴露任意 JSON body passthrough；`RawProvider` 仍是高级逃生口，但不能因此宣称具备通用
  多模态、搜索、tool call、预算或 provenance 契约；
- 不把 URL、搜索摘要或模型引用直接当作已验证事实；宿主必须自行做 evidence policy；
- 不在没有真实 Provider 纵向切片时发布“支持视频/音频输出”等空能力；
- 不借本计划重命名现有 `vibe.liteming.llmjs.*` runtime/Console 包、配置目录、命令或翻译 key。

---

## 1. 当前事实与约束

### 1.1 已经存在的能力

- `LlmMessage` 已是 `role + List<Part>`，现有 `TextPart`、`ImagePart` 可同时出现；
- `LlmOrchestrator` 已能把图片分别序列化为 OpenAI `image_url`、Claude base64 image、Gemini
  `inline_data`；
- `llmcore-mod` 已有截图请求/回传、图片大小限制、Vision probe 和 Console JSON Test；
- `LlmRequest` 已携带 purpose、route override、生成参数和显式 `LlmBillingContext`；
- `LlmOrchestrator` 已拥有 purpose route、Provider fallback、credential retry、token reservation/settlement、
  非流式与 OpenAI 文本流式调用；
- additive `LlmExchangeRequest/Response` 已能表达请求级 `DISABLED/PREFERRED/REQUIRED` Hosted Web Search，
  返回使用状态、typed error、routing decisions、degraded feature 与归一化 `LlmSource`；
- `ProviderProfile/ProviderCapabilities` 已与 legacy `ProviderSpec` 分离；未声明能力的 Provider 默认 text-only；
- Hosted Search 已有 OpenAI Chat、Anthropic Messages、Gemini GenerateContent 与 DashScope/Qwen OpenAI-compatible
  四种 wire adapter；基础聊天 format 与搜索方言互不推断；
- `PurposeRegistry` 是字符串 key，CreatureChat 已注册业务 purpose；core 不需要拥有
  `DEEP_RESOLUTION` 的业务语义。

### 1.2 尚不存在的能力

- `LlmMessage.Part` 是 sealed interface，只允许文本和图片；没有音频、视频、文件、tool result；
- 新 exchange 目前只覆盖 Hosted Web Search；尚不能表达通用 requested modalities、function tools、accepted output
  modalities 或独立搜索后端；
- capability filter 目前只覆盖 Hosted Search，尚未从媒体载荷推导 image/audio/video/file 能力；
- `LlmExchangeResponse` 已能回传搜索来源，但尚不能表达通用 tool calls、多段 typed output 或音频输出；
- Anthropic/Gemini 普通响应已拼接全部可见 text block；只有 tool call 而没有 text 的响应仍会被当成无效响应；
- `sendStreaming(..., Consumer<String>)` 只能传文本 delta；
- `finish_reason=tool_calls` 只做字符串归一化，不构成工具协议；
- 请求/响应日志当前可保留完整 wire body，扩大到音视频后会造成体积、隐私和临时 URL 泄露风险；
- 现有 token estimator 对每张图片固定预留 256，不能覆盖音频、视频、文件和 hosted search 成本。

### 1.3 真实下游兼容边界

CreatureChat 当前对 `llm-core 1.4.0` 有编译期和运行期依赖，并在 `FrozenContractTests` 中冻结关键签名：

- `LlmMessage` 构造器、`role/parts/content/hasImage`；
- `LlmRequestContext` 的两个历史构造器与现有 accessors；
- `LlmRequest` 八参数构造器、billing accessor 与 `withBillingContext`；
- `LlmOrchestrator` 构造器、`send/sendStreaming`、routing、finalizer 与 budget API；
- `PurposeRegistry` 与 Console Test bridge。

CreatureChat 开发环境通过 Gradle composite build 直接消费 `AIjs:core`；发布环境回退到已发布
`vibe.liteming:llm-core:1.4.0`。Fabric shadow-relocate 并内嵌 core，Forge 则依赖外置 `llmcore` mod。
所以兼容验收必须同时覆盖：

1. 旧 CreatureChat 源码对新 core 编译；
2. 已按 1.4.0 编译的最小 consumer fixture 对新 jar 链接与运行；
3. Fabric 内嵌/重定位后只有预期的一份 API；
4. Forge 外置组合没有 duplicate class、`NoSuchMethodError` 或版本范围误报；
5. 旧请求 JSON、routing schema 和 `send()` 行为不因新能力自动变化。

---

## 2. 核心架构裁决

### 2.1 冻结 legacy façade，新增并行 exchange API

以下 1.4.0 类型和行为视为 legacy compatibility façade：

- `LlmRequest`、`LlmResponse`；
- `LlmOrchestrator.send()`、`sendStreaming(..., Consumer<String>)`；
- `ProviderSpec`、`PriorityRoutingConfig`、`LlmRouteOptions` 的现有 record component 与构造器；
- `LlmMessage.TextPart`、`ImagePart` 以及现有文本/图片行为。

不向这些 record 的 canonical shape 直接追加字段。新增并行类型，暂定名称：

```text
LlmExchangeRequest
  messages: List<LlmExchangeMessage>
  context: LlmRequestContext
  route/billing/generation: 复用现有值对象或显式快照
  featureRequest: LlmFeatureRequest
  functionTools: List<LlmFunctionTool>

LlmExchangeResponse
  outcome: TEXT | TOOL_CALLS | MIXED | REFUSAL | ERROR
  parts: List<LlmOutputPart>
  toolCalls: List<LlmToolCall>
  sources: List<LlmSource>
  featureUsage / tokenUsage / attempts / diagnostics
  legacyView(): LlmResponse

LlmOrchestrator.exchange(...)
LlmOrchestrator.exchangeStreaming(..., Consumer<LlmStreamEvent>)
```

最终命名由 `CORE-F0` ADR 冻结。关键原则是新 API 与旧 API 并存，而不是不断给旧 record 加组件和兼容构造器。

旧 `send()` 在迁移期保持原实现和 wire golden；待新 exchange 经同 Provider 契约测试证明等价后，才允许内部委托，
且委托必须把功能集合固定为“legacy text/image only、无 hosted tool、无 function tool、text output”。

### 2.2 内容模型使用可扩展 kind，不为每种未来模态改一次核心协议

新 exchange 消息不复用 sealed `LlmMessage.Part` 作为唯一扩展点。采用非 sealed 的 typed part 接口和稳定
字符串 kind；内置标准值至少包括：

```text
text
media(image)
media(audio)
media(video)
file
tool_call
tool_result
```

媒体对象至少携带：

- modality、MIME type；
- source kind：`inline`、`remote_uri`、`provider_file`；
- byte length、可选 duration/dimensions、内容 SHA-256；
- detail/quality 等标准 hint；
- source owner/lifetime，供上传清理和日志判断使用。

规则：

- `inline` 数据在对象构造时完成不可变快照和大小校验；
- core 不接受任意本地文件路径，也不替宿主读取 Minecraft 资源；
- `remote_uri` 默认禁用，启用时只接受明确 scheme/host policy，不记录 query/fragment；
- `provider_file` 必须绑定 Provider/credential scope，不能跨 fallback 复用；
- 未知 kind 可在中立消息对象中保留，供未来经 ADR 批准并显式注册的 adapter 使用；当前内置 adapter 必须
  fail closed，不能 `asText()` 后悄悄降级，也不因此提前开放公共第三方 adapter SPI；
- 现有 `ImagePart` 通过明确 legacy bridge 映射到标准 image media，不删除、不改构造器。

游戏中的方块、实体、关系、位置等 resolver 事实通常应作为有 provenance 的结构化文本/tool result 传递，
而不是为了“多模态”强行发明 Minecraft 专用 part。

### 2.3 能力由 Adapter、Provider 配置和请求三方共同决定

能力判定不能只看厂商协议，也不能只看模型名。有效能力为：

```text
adapter 能实现
  ∩ Provider/model 显式声明
  ∩ purpose 管理策略允许
  ∩ 本次请求显式请求或由实际载荷推导
```

新增独立 `ProviderCapabilities` / `ProviderProfile`，不改变 `ProviderSpec` record shape。建议能力维度：

- input modalities：text/image/audio/video/file；
- output modalities：text/image/audio；
- caller-owned function tools；
- hosted tools：web search；
- citations/grounding metadata；
- typed streaming event kinds；
- 每类媒体允许的 source kind、MIME、单 part/总量上限。

`ProviderConfigLoader.load()` 保持旧返回值和语义；新增 profile loader 读取 `providers.json` 中可选的
`capabilities` 节点。缺省 profile 对新 exchange **只声明 text**，不能按模型名乐观猜测。现有 legacy image
请求仍保持旧行为，直到宿主主动迁入 exchange。

probe 只用于 Console 诊断，结果带时间和实际错误，不自动持久化为能力真源。管理员确认后才写配置。

### 2.4 新路由先做能力准入，再发生 HTTP 与 attempt 计费

新 exchange 的链解析顺序固定为：

```text
purpose/explicit route
  → 载荷推导 required modalities
  → purpose feature policy
  → adapter/provider capability filter
  → source/大小/预算校验
  → credential 选择与 reservation
  → HTTP
```

- 明确不支持的 Provider 记为 routing decision，不伪造真实 HTTP attempt，也不扣 provider-call/token 预算；
- 能力链为空返回稳定 `NO_CAPABLE_PROVIDER` / `FEATURE_DISABLED` / `UNSUPPORTED_MEDIA_SOURCE` 等 code；
- 不能因 fallback 而丢图片、搜索、tool schema 或 accepted output modality；
- feature 可标记 `REQUIRED` 或 `PREFERRED`。`REQUIRED` 不允许静默降级；`PREFERRED` 若降级，必须在响应和
  Console 中显式列出 degraded feature；
- CreatureChat 知识审查需要“已搜索才可声称校准”时必须用 `REQUIRED`，不能使用 prompt 约定代替。

### 2.5 Hosted Web Search 与 caller-owned tools 分开建模

两者名字相近，但所有权和执行方式不同：

| 类型 | 谁执行 | core 的职责 | CreatureChat 的职责 |
|---|---|---|---|
| Hosted Web Search | Provider 在一次 exchange 内执行 | 请求映射、能力/策略/次数上限、usage、来源归一化 | 决定何时请求、验证/使用来源，不把结果直接当世界事实 |
| Function tool / resolver | CreatureChat | 声明 schema、解析 inert tool call、序列化 tool result | registry、allowlist、权限、主线程 snapshot、执行、超时、Agent 状态与深度 |

Hosted Web Search 使用明确的 `LlmHostedWebSearchRequest`，不伪装成普通 function tool。`1.4.1` 首版只冻结
`DISABLED/PREFERRED/REQUIRED`；max uses、allowed/blocked domains 和 search context size 等选项只有在 adapter 能严格
执行时才加入。adapter 不支持的非空选项必须拒绝，不能静默忽略。厂商特有参数只有出现两个真实消费者或稳定标准后
才进入公共 API。

Function tool 首版只需要 JSON Schema definition、tool choice、结构化 `LlmToolCall` 和按 call id 关联的
`LlmToolResult`。core 不提供 `ToolExecutor`、callback registry、循环次数或递归调度器。

#### 2.5.1 搜索 adapter 按 wire 方言复用，不按模型名枚举

基础聊天协议与 Hosted Search 方言是两个正交维度：

```text
Provider/model profile
  ├─ format: openai / claude / gemini
  └─ capabilities.webSearch.adapter: 明确的 Hosted Search wire 方言
```

- `format=openai` 只表示普通聊天请求兼容 OpenAI Chat，不能据此推断搜索字段、搜索是否发生或来源位置；
- Qwen/百炼首版使用独立 `dashscope_openai_chat_web_search` 方言；不能因其基础 format 是 OpenAI-compatible 就套用
  OpenAI `web_search_options`；
- DeepSeek、MiMo 以及普通 OpenAI-compatible 聚合平台默认 text-only。官方或第三方平台若为这些模型提供搜索，必须按
  **平台实际 wire 契约**选择已有 adapter，或新增一个带离线 fixture 的小型 adapter；
- 例如 Grok/第三方平台托管的 DeepSeek 若真实接受 OpenAI 搜索字段并返回 URL citation，可以声明
  `openai_chat_web_search`；若使用自有字段，则新增平台方言，不能按 `model=deepseek-*` 猜测；
- 未知 adapter id 可以被配置 loader 保留以便诊断，但 capability admission 会判定 unavailable，不产生 credential
  选择、HTTP、attempt 或 reservation；首版不开放动态插件 SPI。

首版 profile 配置：

```json
{
  "qwen": {
    "format": "openai",
    "url": "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    "model": "qwen-plus",
    "capabilities": {
      "input": ["text"],
      "output": ["text"],
      "webSearch": {
        "enabled": true,
        "adapter": "dashscope_openai_chat_web_search"
      }
    }
  }
}
```

#### 2.5.2 独立 Search Backend 与 Hosted Search 并列

为使没有 Hosted Search 的 DeepSeek、MiMo、本地模型也能使用外部知识，后续新增 llmcore-owned、模型无关的 typed
Search Backend；它与 Provider Hosted Search 并列，不互相伪装：

| 路径 | 调用形态 | 优点 | 约束 |
|---|---|---|---|
| Provider Hosted Search | 一次模型 exchange 内由 Provider 搜索 | 模型原生使用搜索与 citation | 受 Provider wire、费用和 usage 证据限制 |
| Independent Search Backend | core 单独调用搜索服务并返回 `SearchResponse` | 与模型无关，可服务 DeepSeek/MiMo/本地模型 | 宿主决定是否再调用 LLM；core 不创建 Agent loop |

独立接口至少携带 purpose、query、结果上限、domain policy、deadline 和 billing/root，并返回 backend、稳定状态、
`LlmSource` 列表、routing decisions 与 typed error。core 负责搜索后端选择、联网策略、超时、结果上限、来源归一化和
可观察性；CreatureChat 负责何时搜索、证据审查、是否把结果交给模型以及后续 coordinator 生命周期。

首个低门槛后端优先选择管理员自托管的 SearXNG JSON API；Brave/Tavily/Exa 等正式 API 可作为可选 adapter。仓库不
内嵌公共共享 key，不抓取不稳定搜索网页，不默认选用第三方公共实例，也不因升级自动联网。可提供示例配置，但所有
backend 仍按 purpose fail closed。

### 2.6 Purpose 能力策略独立于 routing 参数

保留 `routing.json` schema 2 和 `PriorityRoutingConfig` 的现有兼容面。新增 llmcore-owned、Console 管理的
`capability-policy.json`（最终文件名由 ADR 冻结），按 purpose 控制：

- 允许的 input/output modalities；
- hosted web search 的 `disabled/allowed` 与硬 `maxUses`；
- function tools 是否允许；
- inline/remote/provider-file source policy；
- 每请求媒体 byte/duration 上限。

默认值：text allowed；所有新的外部联网、remote URI、音频、视频、文件和 function tools disabled。图片是否沿用
legacy 默认只影响旧 `send()`；新 exchange 必须显式配置。这样升级 llmcore 不会让既有 purpose 突然把更多数据
发送给 Provider 或访问互联网。

调用者提出 feature request，管理员策略只做授权/上限，不能主动给普通请求添加搜索。业务 purpose 仍由各宿主注册；
AIjs 不内置 `SUPERVISOR_REVIEW` 或 `DEEP_RESOLUTION`。

### 2.7 结构化响应、来源与流式事件

新响应保留原始顺序的 typed output parts，并单独归一化：

- text/refusal/reasoning summary（不暴露隐藏 chain-of-thought）；
- tool calls；
- media/file outputs；
- hosted tool invocation records；
- citations/sources，包括 URI、标题、可选 snippet、引用 span 和 Provider metadata；
- token usage、cached/reasoning tokens（若 Provider 提供）、hosted tool usage、finish outcome。

`legacyView()` 只拼接可见 text part；不会把 tool call、Base64、URL 或 provider JSON 塞进字符串。旧 `send()`
只请求 text，因此不应遇到无 text 的成功结果。

新流式接口传 `LlmStreamEvent`，至少区分 text delta、tool-call delta、usage、completed、error。旧
`Consumer<String>` 只收到 text delta，行为保持不变。音频 chunk streaming 等到真实 Provider 消费者出现再扩展，
首版不预建空事件。

### 2.8 日志、隐私与预算

扩大模态前必须先建立 payload-aware logging：

- request/response wire body 在进入持久/内存日志前做结构化清洗；
- inline media 只记录 modality、MIME、bytes、duration/dimensions、SHA-256，不记录 Base64；
- remote URI 默认只记录 scheme/host 与 hash，不记录 query、fragment 或签名；
- provider file id、tool secret、授权 header 永不进入 Console；
- 搜索 query、来源标题/URL/摘要分别受 Console 权限和保留策略控制，不能混在普通 response preview；
- Console Test 可显示媒体占位和 typed parts，但 packet/result 都有独立 byte/part/count 上限。

现有 call/token reservation 继续生效。新 exchange 另带 feature ceilings：inline bytes、media duration、hosted tool
max uses、function tool definitions/calls/results 大小。只有 Provider 能在请求中接受并遵守 max uses，或 adapter 能从
协议上证明硬上限时，才允许把 hosted search 标为 bounded；否则该 Provider 不满足需要硬上限的 purpose。

core 只统计实际 Provider exchange 和 Provider 报告的 hosted-tool usage。CreatureChat resolver 调用数、脚本超时、
世界 snapshot 大小仍由 CreatureChat coordinator 预算，二者通过同一 causal root 关联但不混成一个 owner。

---

## 3. 实施卡与依赖

### 3.0 `llm-core 1.4.1` 首个执行切片

`1.4.1` 当前实际切片如下：

| 子项 | 状态 | 当前结果 |
|---|---|---|
| purpose Web Search policy | 已实现，待实机 | schema-1 原子存储、默认关闭、Routing 管理员开关 |
| Provider capability profile | 已实现，离线测试通过 | additive loader；缺省 text-only；非法字段 fail closed；Console status 只读回传 |
| 请求级 Hosted Search | 已实现，离线测试通过 | `DISABLED/PREFERRED/REQUIRED`；准入后再 credential/HTTP/accounting |
| Hosted Search adapters | 已实现，待真实 EVAL | OpenAI Chat、Anthropic Messages、Gemini、DashScope/Qwen |
| 来源回传 | 已实现，离线测试通过 | usage evidence、URI/title/snippet/span/provider metadata；不声明来源可信 |
| CreatureChat 采用 | 未开始 | 不修改 CChat；等业务卡原子采用 typed exchange |
| 独立 Search Backend | 计划中 | 见 `CORE-S1`；不属于本次已实现范围 |
| 通用媒体与 Tool 协议 | 未开始 | 继续按 M/T 卡执行 |

已交付行为：

- 新增独立、schema-1、原子保存的 `capability-policy.json`，按 purpose 保存 Web Search 授权；
- 未配置、文件缺失、文件损坏或字段非法时一律关闭 Web Search，并记录服务端诊断；
- Console Routing 的 purpose 编辑区提供“允许 Web Search”开关，与当前路由草稿一起校验和保存；
- `LlmOrchestrator` 暴露 effective policy、Provider profile 查询和 typed `exchange()`；
- 请求只有在显式请求、purpose policy 允许、Provider 明确声明、adapter 存在且兼容基础 format 时才发送搜索字段；
- `REQUIRED` 无搜索使用证据时继续 fallback 并最终明确失败；`PREFERRED` 才能显式标记 degraded 后返回普通文本；
- 搜索来源只表示 Provider 返回的 provenance，不表示可信、正确或可直接写入知识库；
- 保持 `LlmRequest`、`LlmResponse`、legacy `send/sendStreaming`、`routing.json` schema 2 和旧 Provider wire body 不变。

本切片只在新 `exchange()` 满足三重准入后发送搜索参数；管理员开关本身不会使 CreatureChat、LLMjs legacy
`send()` 或普通 Provider 请求联网。独立 Search Backend、多模态与 caller-owned tool 尚未实现。

**剩余验收**：真实 Console 操作；四种已声明方言的显式环境变量 Provider EVAL；完整 core/mod build；未修改
CreatureChat 的关键契约与双 loader 构建。离线测试已覆盖默认关闭、capability skip 无 HTTP/accounting、严格
REQUIRED fallback、PREFERRED degraded、四种 request/response fixture、来源归一化和 legacy body 不注入搜索字段。
CreatureChat 的“解析版本必须等于声明版本”护栏会在 composite build 中按预期拒绝尚未声明的 1.4.1，只有正式采用
1.4.1 时才原子更新其版本常量与冻结测试，不为本地 foundation 验证提前修改下游。

```text
CORE-F0 兼容与所有权 ADR
  ├─ CORE-F1 1.4.0 ABI/行为基线
  └─ CORE-F2 内部 Provider adapter 分层
       └─ CORE-X1 additive exchange 模型
            ├─ CORE-C1 capability profile 与准入路由
            ├─ CORE-M1 媒体模型 + legacy image parity
            │    └─ CORE-M2 payload 安全、日志与预算
            │         ├─ CORE-M3 音频输入纵向切片
            │         └─ CORE-M4 视频/文件条件切片
            ├─ CORE-T1 caller-owned tool wire protocol
            │    └─ CORE-T2 typed streaming events
            └─ CORE-W1 hosted Web Search + sources
                 └─ CORE-W2 purpose capability policy + Console
                      └─ CORE-S1 independent Search Backend

CORE-C1 + CORE-M2 + CORE-T1 + CORE-W2
  └─ CORE-I1 CreatureChat 不改代码兼容门
       └─ CORE-I2 CreatureChat 0.9.8/0.9.9 采用契约
            └─ CORE-R 发布门
```

`CORE-M4` 是条件卡，不阻塞首个 foundation release；没有真实视频/文件 Provider fixture 时只保留 ADR 和测试
向量，不发布 supported capability。

### CORE-F0｜P0｜跨仓所有权与演进 ADR

**目标**：冻结 legacy façade、新 exchange 边界、功能默认值和 Agent 所有权，避免实现时把 core 扩成游戏 Agent。

**范围**：

- 列出 1.4.0 保留的类、构造器、方法、行为和 wire/config schema；
- 冻结 `LlmExchangeRequest/Response`、content part、capability、hosted tool、function tool、source、stream event
  的最小语义；
- 冻结 request feature、admin purpose policy、Provider capability 三者的合并和拒绝顺序；
- 冻结 legacy image bridge 与新 API 默认全部 opt-in；
- 明确 CreatureChat owns resolver/Agent/tool execution，llm-core owns single exchange；
- 对 OpenAI-compatible Chat、OpenAI Responses、Claude、Gemini 当前官方协议做逐项映射审计，记录不能统一的差异，
  不用最低公分母掩盖 unsupported option；
- 决定新增 API 的 Java package 与 `@since` 版本，禁止借机改旧包名。

**验收**：每个新增 public type 都至少映射到一个计划中的真实 Provider slice 和 CreatureChat 0.9.8/0.9.9 消费点；
没有 executor、Minecraft 类型、任意 vendor JSON 或隐式联网入口。

### CORE-F1｜P0｜llm-core 1.4.0 ABI、源码与行为基线

**目标**：把“尽可能不影响下游”变成自动化门，而不是依赖人工记忆。

**范围**：

- 引入 ABI/API comparison（Revapi、japicmp 或等价工具）对比发布的 1.4.0 基线；若基线尚未进入仓库/Maven，
  生成可审计的 API signature manifest，不提交不明来源二进制；
- 建立按 1.4.0 编译、在新 jar 上运行的 consumer fixture，覆盖 CreatureChat 已冻结签名；
- 为 legacy text/image 的 OpenAI/Claude/Gemini 非流式 body、响应、错误、fallback 和文本流建立 golden/contract tests；
- 锁定 `routing.json` schema 2、旧 providers/secrets 路径和现有构造器行为；
- 增加与未修改 CreatureChat composite build 的兼容任务；不反向修改 CChat 测试来迁就破坏。

**程序验收**：删除/改签旧 API、改变 legacy request body、默认增加搜索字段、改变文本 delta 或破坏 1.4.0 fixture
均能稳定失败。

### CORE-F2｜P0｜内部 Provider adapter 分层与零行为迁移

**目标**：把 `LlmOrchestrator` 内的 format switch、body builder、response parser、stream parser 移到内部 adapter，
为新增协议能力提供明确 owner。

**范围**：

- 建立 package-private/internal adapter contract：build request、headers/URL、parse response、parse stream、
  adapter protocol capabilities；
- 原子迁移 OpenAI Chat、Claude Messages、Gemini GenerateContent；
- orchestrator 继续拥有 route、credential health、retry、budget、logging，adapter 不自行重试或另建 accounting；
- 不在本卡发布公共第三方 provider SPI；出现第二个真实仓外 adapter 前保持 internal；
- `RawProvider` 暂不并入 typed capability 路线，旧行为保持。

**验收**：CORE-F1 全部 golden 不变；同一逻辑请求的 Provider/credential/fallback/usage/finish reason 不变；
无新 public API。

### CORE-X1｜P0｜并行的 typed single-exchange API

**目标**：新增可承载未来 part/tool/source 的请求响应模型，同时不改变 legacy records。

**范围**：

- 实现 immutable `LlmExchangeMessage`、`LlmExchangeRequest`、`LlmExchangeResponse` 和 stable outcome/error code；
- 明确 legacy ↔ exchange bridge，仅转换已有文本/图片/文本响应；
- 新响应保留有序 typed parts、tool calls、sources、usage、attempt/routing decisions；
- `exchange()` 一次只做一次逻辑模型交换和既有 Provider fallback，不自动把 tool call 回送模型；
- 所有集合防御性复制，byte/media source 不暴露可变 backing storage；
- 未知 part/response type fail closed，并保留可诊断 code。

**验收**：旧 CChat 无源码修改可继续使用 legacy API；纯文本 exchange 与 legacy 结果等价；只有 tool call、mixed、
refusal、空响应、畸形 response 都有稳定结果，不再因 `content=null` 统一报 JSON parse error。

### CORE-C1｜P0｜Provider capability profile 与准入路由

**目标**：请求在发送前知道链中哪些 Provider 真正有资格处理当前载荷和 feature。

**范围**：

- 新增独立 profile loader 和显式配置 schema，不改变 `ProviderSpec` canonical shape 或旧 loader；
- 能力是 adapter 上限与管理员声明的交集；缺省 text-only；
- 从实际 message parts 自动推导 required modalities，调用者不能少报以绕过 policy；
- explicit route、purpose route、default fallback 均保持顺序，只过滤不合格项；
- 输出 typed routing decisions：unsupported modality/tool/source/option、policy disabled、no credential；
- capability probe 只读、带时效，不自动修改配置。

**验收**：图片不能 fallback 到 text-only Provider；required search 不会 fallback 为普通文本；跳过项无 HTTP、无
reservation、无 credential cooldown；legacy `resolveChain/send()` 行为不变。

### CORE-M1｜P0｜通用媒体 part 与 legacy image parity

**目标**：用一个稳定媒体模型承载 image/audio/video，而不是持续扩大 sealed legacy part。

**范围**：

- 实现 media modality/source/metadata 值对象和构造校验；
- 把 legacy `ImagePart` 明确桥接到 inline image；
- 三个现有 adapter 先完成 image 的新旧 API wire parity；
- Console Test codec 新增版本化 typed parts，旧 JSON Test 文本/图片仍可读；
- finalizer 对媒体使用 Provider/profile estimator；未知费用使用有上界的保守值或拒绝，不固定伪造 256 tokens；
- `content()` / text preview 不输出 Base64、文件 id 或完整 URL。

**验收**：混排 text+image、多个 image、system/user/assistant roles、错误 MIME、空数据、超限、未知 source 全覆盖；
同一 legacy image 的三家 wire body 与迁移前等价。

### CORE-M2｜P0｜媒体安全、日志清洗、大小与生命周期

**目标**：在音视频进入生产前建立可证明的资源和隐私边界。

**范围**：

- 单 part、单 message、单 request 的 decoded bytes、encoded chars、duration、dimensions/count 上限；
- 构造、Console packet、adapter build、HTTP 前四层都不信任上游声明的 byteSize；
- 结构化日志 sanitizer 替换 Base64、provider file id、signed URL；请求摘要只留 metadata/hash；
- remote URI 默认禁用并校验 scheme/host；core 永不主动抓取 URI；
- 若 adapter 需要上传，上传句柄绑定 Provider/credential/request，成功/失败/取消/超时均清理；不能跨 fallback
  误用 file id；
- 取消 future 时释放临时资源和 accounting reservation；
- Console 明确提示媒体/搜索会发送给第三方 Provider。

**验收**：伪造 byteSize、Base64 bomb、超长 URI、恶意 MIME、日志泄漏、取消、上传后 fallback、清理失败都有测试；
日志中搜索不到原始 fixture Base64、secret query 和 provider file id。

### CORE-M3｜P1｜音频输入真实纵向切片

**启动条件**：选定一个可在自动 fixture 或显式真实 EVAL 中调用的 Provider/protocol，确认输入格式、大小、时长和
usage 语义。

**范围**：

- 只做 audio input → typed text output；首版不做麦克风采集、实时音频会话或语音播放；
- 支持该 adapter 真实需要的 inline 或 upload source；
- MIME allowlist、duration/bytes 限制、capability route、日志清洗和 usage 全链路；
- 提供小型许可清晰的音频 fixture 和 stub response；真实 Provider EVAL 用显式环境变量启用。

**验收**：支持/不支持 Provider、错误 MIME、过长音频、取消、fallback、无 usage metadata 均可重放；未声明 audio
能力的 Provider 在 HTTP 前拒绝。

### CORE-M4｜P2 条件卡｜视频/文件输入与上传生命周期

**启动条件**：至少一个 Provider adapter 有真实可运行 fixture，且 CreatureChat 已定义一个需要视频片段或文件的
resolver 消费场景。没有二者时不发布 capability，只保留设计记录。

**范围**：

- 优先支持短、有界视频片段或 Provider file upload，不默认 inline 全量 Base64；
- 明确宿主截帧/压缩与 core 传输的所有权；core 不做视频编码；
- provider file 的创建、轮询 ready、使用、过期、清理和 fallback scope；
- 文件类型只按明确 allowlist，不提供任意本地文件读取 API。

**验收**：大文件不会完整复制多次；失败和取消后无残留上传；Provider file 绝不跨 Provider/credential；没有真实
slice 时 capability status 仍为 unsupported。

### CORE-T1｜P0｜caller-owned function tool wire protocol

**目标**：为 CreatureChat 0.9.9 coordinator 提供“声明工具 → 收到调用 → 回填结果”的中立协议，不实现 Agent。

**范围**：

- function tool 定义含稳定 name、description、JSON Schema；大小、数量、名称和 schema 深度有界；
- tool choice 支持 none/auto/required/指定 tool；
- adapter 归一化 call id、name、arguments JSON 与 Provider 原始 finish outcome；
- tool result 必须关联已存在的 call id，标记 success/error，并可承载有界 text/JSON/media；
- OpenAI/Claude/Gemini 至少两种真实协议通过，以证明抽象不是单厂商 JSON 换名；
- tool call 只是 inert data；API 中不得出现 executor/callback/registry。

**验收**：未知 tool、重复 call id、畸形 arguments、并行 calls、tool error、mixed text+calls、result 对错轮次均有测试；
一次 `exchange()` 无论返回多少 tool calls 都不会触发第二次模型请求。

### CORE-T2｜P1｜typed streaming events

**目标**：流式响应不再把所有协议事件压成字符串，为 future tool/audio output 保留正确边界。

**范围**：

- 新增 text delta、tool-call delta、usage、completed、error 事件；
- 每个逻辑响应只产生一个终态；Provider 中途失败且已输出内容时不切换 Provider 造成重复；
- tool argument fragments 只在 completed 后形成可执行的 immutable call；
- legacy text streaming adapter 只转发 text delta，现有回调时序保持。

**验收**：分片 UTF-8/JSON、交错多 tool call、usage-only 尾帧、取消、中途断流、空流和重复终态测试全绿。

### CORE-W1｜P0｜Provider 托管 Web Search 与来源归一化

**目标**：让业务 purpose 显式请求一次 Provider 托管搜索，并可靠知道它是否发生、引用了什么。

**范围**：

- 新增独立 hosted web search request/options，不与 function tool 混用；
- 为至少两个协议 adapter 实现真实映射；若只能完成一个，则 API 保持 experimental，不能宣称 provider-neutral；
- 解析 hosted invocation、query（Provider 提供时）、sources/citations/grounding、usage 和 refusal；
- 搜索来源保留 Provider provenance，不做“可信/正确”布尔判断；
- REQUIRED 搜索没有执行记录时，即使返回文本也标记 `REQUIRED_FEATURE_NOT_USED`，不能伪装成功；
- search max uses 只有在协议可硬限制时才接受；否则不满足要求有硬上限的 purpose。

**验收**：搜索成功无引用、引用成功、未搜索直接作答、搜索拒绝、domain filter 不支持、来源畸形、Provider fallback、
usage 缺失均有稳定结果；旧 `send()` request body 永不出现搜索字段。

### CORE-W2｜P0｜按 purpose 的 capability policy 与 Console 控制面

**目标**：管理员能对 `SUPERVISOR_REVIEW`、未来 `DEEP_RESOLUTION` 等分别允许、限制和关闭外部联网/多模态。

**范围**：

- 新建原子、严格、带 schemaVersion 的 policy store；未知字段/越界值可诊断，不静默归零；
- Console Routing 或独立紧凑区域显示 Provider capabilities 与 purpose effective policy；
- capability 是管理员确认的配置，不因 probe 或模型返回自动升级；
- 修改权限沿用 administrator，viewer/delegated Test 只能看其授权范围的只读 effective 摘要；
- Test 能构造 search/media/tool 请求并显示 routing decisions、实际 feature usage 和 sources；
- policy 文件加载失败时新 feature fail closed，legacy text/image 请求不受影响；
- 日志按 purpose/request/root 关联搜索和 exchange，不建立第二份持久流量真源。

**验收**：默认关闭、逐 purpose 开关、max uses、Provider 无能力、reload、原子写失败、旧 Console client/packet、权限
拒绝和双语 lang parity 全覆盖；关闭搜索后 CChat 业务能得到明确拒绝而非普通回答。

### CORE-S1｜P1｜模型无关的独立 Search Backend

**目标**：让没有 Provider Hosted Search 的 DeepSeek、MiMo、本地模型和第三方 OpenAI-compatible 服务也能取得有来源
的外部搜索结果，同时不在 core 内创建第二次模型调用或 Agent loop。

**范围**：

- 新增独立 `SearchRequest/SearchResponse` typed 契约，携带 purpose、query、maxResults、domain policy、deadline、
  billing principal/root、backend decisions 和稳定 error code；
- 复用有界 `LlmSource` 值对象，但用明确 source/backend metadata 区分 independent search 与 Provider citation；
- 首个 adapter 优先自托管 SearXNG JSON API；至少再用一个正式 API fixture 证明 backend-neutral 边界；
- 搜索 backend 配置与模型 Provider profile 分离；purpose policy 默认关闭，backend 不可用时 fail closed；
- core 只执行一次搜索交换并返回结果，不自动选模型、拼 prompt、总结结果、重试 LLM 或执行 resolver；
- 不内置公共 key、公共代理或网页抓取 fallback，不将免费服务等同于无成本/无限额/可默认启用。

**验收**：无 Hosted Search 的 DeepSeek/MiMo fixture 能经“搜索 → 宿主决定后续模型调用”取得相同 typed sources；默认
关闭、domain policy、结果上限、超时、取消、backend fallback、计费拒绝、来源畸形、日志清洗和零自动 LLM 调用均有
测试；与 Hosted Search 并存时调用路径和 provenance 可明确区分。

### CORE-I1｜P0｜CreatureChat 零改动兼容门

**目标**：证明 foundation 发布不会强迫当前 CreatureChat 迁移。

**范围**：

- 对审计基线的 CreatureChat 不改生产代码执行 composite compile/test；
- 用发布 artifact 再执行一次非 composite 依赖解析；
- Fabric shadow/relocate 与 Forge 外置 llmcore 两条 1.20.1 构建路径；
- 运行 CreatureChat `FrozenContractTests`、Gateway/request/finalizer/routing/billing 定向测试；
- 验证 `CHAT`、`VISION`、`SUPERVISOR_REVIEW` 等现有 purpose 的 legacy request body、usage、fallback 和日志未
  自动增加 capability/search 字段；
- 记录新 llm-core 最低/推荐版本，但不提前修改 CreatureChat version constant。

**验收**：未修改 CChat 源码即可通过；任何必要的 CChat 源码变化说明 core 兼容目标失败，必须回到对应卡修正，
不能在本卡补兼容桥掩盖。

### CORE-I2｜跨仓采用卡｜CreatureChat 0.9.8/0.9.9 接线契约

**目标**：只在 CreatureChat 对应任务卡启动时做原子跨仓采用，不让 AIjs 的空 API进入生产调用。

**0.9.8 resolver 接线**：

- resolver/世界 snapshot owner 仍在 CChat；输出可以形成 text/JSON/tool result 或有界 media part；
- CChat Gateway 是唯一调用入口，继续显式传 purpose、billing principal/root、ContextToken；
- core 只验证和发送 immutable payload，不持有实体或 reload 生命周期。

**0.9.9 调查接线**：

- `DEEP_RESOLUTION` 由 CChat `PurposeRegistrar` 注册；AIjs Console 自动显示，不在 core 内置；
- CChat coordinator 根据 allowlist 构造 function tools、执行 resolver、回填 tool result，并权威限制单层深度；
- 每次 exchange 复用父 principal/root；CChat 预算 resolver/tool steps，core 预算实际 Provider attempts；
- `SUPERVISOR_REVIEW` 或专门知识校准 purpose 只有在业务 ADR 要求时请求 hosted web search；搜索禁用/失败走
  CChat 明确的诚实降级，不把未搜索回答记成已校准；
- evidence bundle 保留 resolver provenance 与 web source provenance，二者不混成同一种“事实”。

**原子提交边界**：采用某个新 public API 时，同一交付更新 AIjs compatibility manifest、CChat Gateway/consumer、
两仓测试和两仓权威文档；不保留反射双路线或基于 class presence 的静默 fallback。

### CORE-R｜Foundation 发布门

**首个必需范围**：`CORE-F0/F1/F2/X1/C1/M1/M2/T1/W1/W2/I1`。`M3` 可按实际 Provider 准备情况进入同版或
下一小版本；`M4` 不阻塞；`T2` 若首个真实 Agent 消费者不需要流式 tool call，可延期但必须明确非支持项。

**自动门**：

- `:core:test`、`compileJava`、`compileTestJava`、`llmcore-mod` ownership/check/build 全绿；
- ABI/API comparison 与 1.4.0 consumer fixture 全绿；
- legacy 三协议 text/image golden、fallback、stream、accounting 全绿；
- capability admission、typed response、tool wire、hosted search、policy schema、payload sanitizer 全绿；
- CChat composite/non-composite、Fabric shadow、Forge external 四类兼容验证全绿；
- jar 中不混入 Minecraft/KubeJS 到 core，不重复打包 core runtime。

**真实 Provider 门**：

- 每个宣称 supported 的新 capability 至少有一个显式环境变量开启的真实 EVAL；
- provider-neutral 的 tool/search 抽象至少有两个协议 adapter；
- EVAL 记录 request feature、实际 invocation、usage、sources、latency 与清洗后的诊断，不提交 secret/原始媒体；
- 无凭证环境的常规单测完全离线、确定性可重放。

**人工门**：

- Console 能看出“未请求 / 被 policy 禁止 / Provider 不支持 / 已实际使用 / 已降级”五种状态；
- 开关某 purpose 的 Web Search 后下一请求立即生效；关闭时无网络搜索；
- 图片和音频 Test 不在日志、packet error 或 UI 中显示 Base64；
- CChat 现有聊天、Vision、知识审查在未采用新 API 时体验不变。

---

## 4. 配置与兼容迁移原则

### 4.1 版本策略

- 新 public exchange API 使用 minor 版本发布，不覆盖 `1.4.0` artifact；具体版本在 CORE-F0 决定；
- 删除 legacy façade、改变 record canonical shape、改变默认联网行为均属于未来 major change，本计划不做；
- `llmcore-mod` 的 Forge `versionRange` 只有 CChat 真正采用新 API 时才协调提升；foundation 发布本身不修改 CChat；
- Fabric 内嵌与 Forge 外置必须指向同一 core API 版本，不能让两个 loader 获得不同 capability semantics。

### 4.2 配置兼容

- 旧 `providers.json`、secret、`routing.json` schema 2 均原样可读；
- 新 Provider capabilities 是可选节点，由新 profile loader 读取，旧 loader 忽略；
- 新 purpose capability policy 使用独立、版本化、原子写文件，避免旧 `RoutingConfigStore.toJson()` 保存时丢字段；
- 新 feature 默认 deny，不需要迁移旧配置即可保持现有行为；
- 不通过模型名、endpoint URL 或曾经 probe 成功推断并落盘能力；
- 配置语法与 Console status payload 同卡升级，旧 client 遇到新字段应忽略，旧 server 收到新 policy packet 应明确
  拒绝版本而非截断保存。

### 4.3 API 兼容

- 不给 legacy records 增加 component；不删除历史 overloaded constructors；
- 不把旧 `LlmMessage.Part` 改成需要下游 exhaustive switch 的新核心扩展面；
- 新 part hierarchy 允许未来 kind 扩展，但内置 adapter 对未知 kind fail closed；
- 不改变旧 `LlmResponse.content()`、`finishReason()`、attempt/accounting 语义；
- 新错误使用新 exchange typed code；映射到 legacy 时提供稳定、可读 error，不伪造成 Provider HTTP failure；
- Javadoc 必须标出 `@since`、线程/生命周期、敏感字段和是否可持久化。

---

## 5. 风险与必须先回答的问题

以下问题由对应 ADR/实现卡自行用代码和 Provider fixture回答，不需要现在扩大产品范围：

1. **Provider API 差异**：OpenAI-compatible Chat、Responses、Claude、Gemini 的 hosted search、tool result、文件上传
   和 citation schema 并不等价；adapter 必须显式暴露 unsupported，不能用一个松散 `Map` 假统一。
2. **费用与硬上限**：托管搜索可能有独立费用且一次模型请求内可能发生多次搜索。Provider 无法接受硬 max uses 时，
   不得用于要求有界搜索的 CChat purpose。
3. **数据外发**：图片、录音、视频和 search query 可能包含聊天、HUD、玩家名、服务器信息。宿主负责采集授权与
   最小化，core 负责 policy、传输限制和日志清洗，二者缺一不可。
4. **引用不是事实**：source/citation 只证明 Provider 返回了某来源；CChat 审查链需要独立判定来源允许范围、冲突、
   新鲜度和是否可进入 active knowledge。
5. **媒体 Token 估算**：不同 Provider 差异大。没有可靠 estimator 时使用 Provider/profile 上界或拒绝，不能沿用
   图片固定 256 的假精度。
6. **上传清理**：Provider file 可能异步处理且删除 API 不统一。没有可证明 cleanup/lifetime 的 adapter 不发布
   `provider_file` 能力。
7. **流式 Tool Call**：如果 CChat 首版调查只用非流式 exchange，typed streaming 可延期，避免为了“完整”阻塞
   0.9.9；但旧文本 streaming 不能承诺支持 tool calls。

---

## 6. 与 CreatureChat 版本草案的映射

| CreatureChat 卡 | AIjs 前置/配合 | 边界 |
|---|---|---|
| 098-03 resolver 直接调用 | 无强制 AIjs 前置；需要媒体时采用 X1/C1/M1/M2 | resolver registry、权限、世界 snapshot 在 CChat |
| 098-08 可运行样例 | 可选 M3 音频或 M1 图片作为真实输入 | core 不拥有玩法样例状态机 |
| 099-00 深度调查 ADR | 引用 F0/T1/W1 能力语义 | 单层深度和失败策略在 CChat |
| 099-01 `DEEP_RESOLUTION` | W2 Console policy 能显示、路由和禁用该 purpose | purpose 由 CChat 注册，core 不内置 |
| 099-03 coordinator | T1 提供 inert call/result；现有 billing/root 继续使用 | coordinator/CAS/ContextToken 在 CChat |
| 099-04 planner/evidence | T1 tool schema/result；W1 sources | resolver allowlist 与 evidence trust 在 CChat |
| 099-04 外部知识检索 | Provider 有托管搜索时采用 W1；任意模型需要独立检索时采用 S1 | CChat 决定检索时机、证据可信度和是否再次调用模型 |
| 099-05 最终回答 | X1 typed response；无额外 core Agent | 最终 NPC 发言与状态提交在 CChat |
| 099-08 离线 EVAL | AIjs 提供 adapter stub/golden；CChat 提供世界 fixture | 两仓各测自己的 owner，不复制 resolver |
| 100-05/06 文档 | capability/privacy/provider boundary 文档可被公开引用 | 不替代 CChat 的玩家/管理员说明 |

计划执行时，AIjs 卡不以“CChat 以后可能用”为唯一验收。每个已发布 capability 必须同时有 core 内真实 adapter
消费和对应的跨仓采用卡；暂时没有消费者的能力只保留在 ADR/计划，不留下启用但不可工作的 public 配置。

---

## 7. 草案批准后的文档动作

1. 先执行 `CORE-F0/F1`，把本文件中的暂定类名、配置文件名和协议选择收敛成 ADR；
2. ADR 批准后，把近期执行卡迁入 AIjs 的单一活跃计划位置；若仓库仍无统一 `NEXT_STEPS_PLAN.md`，本文件继续作为
   该专题唯一任务真源，不再复制第二份 checklist；
3. 行为卡完成时同步 `docs/module-boundaries.md` 与具体能力文档；`docs/vision-primitives.md` 只在脚本可见行为真实
   改变时更新；
4. CreatureChat 只有启动 `CORE-I2` 的具体采用切片时才更新其 `ARCHITECTURE/CODEMAP/NEXT_STEPS`，foundation
   开发期间不提前改写其权威事实；
5. 每张行为卡独立提交、独立回滚；先写验收和 fixture，再改生产代码；不在计划提交中修改版本号或发布说明。
