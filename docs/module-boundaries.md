# Module boundaries

The repository builds three artifacts with distinct ownership:

- `core` (`llm-core`): plain Java API and orchestration, including
  `LlmOrchestrator`. It does not depend on Minecraft, Forge, or KubeJS.
- `llmcore-mod` (`llmcore`): Forge runtime services, provider configuration,
  routing, logging, commands, networking, tests, vision support, and the
  administrator Console. It does not depend on KubeJS, Rhino, or Architectury.
- Root project (`llmjs`): the KubeJS binding and script-facing adapters. It
  depends on `llmcore` and does not own the Console or provider runtime.

## Compatibility during the move

Runtime and Console classes moved to the `llmcore` artifact before their Java
packages are renamed. Their existing `vibe.liteming.llmjs.*` binary names stay
available from `llmcore` so linked mods can continue using the current handoff
contract without a coordinated release. Translation keys and the `/llm` command
remain stable. LLM Core owns `config/llmcore`, `serverconfig/llmcore`, and the
root `llmcore.secret`. On upgrade, known core-owned JSON files are moved out of
the old `llmjs` directories when their canonical targets are absent. A lone
`llmjs.secret` is renamed once to `llmcore.secret`; it is never read as a
fallback, and when both files exist only `llmcore.secret` is authoritative.

The Gradle `check` tasks enforce artifact ownership and reject adapter-only
KubeJS dependencies in `llmcore`.

On Forge, `llmcore-mod` constructs the only configured `LlmOrchestrator`. A
successful reload publishes the fully initialized instance through
`SharedLlmRuntime`; consumer mods receive only that read-only runtime reference.
They do not read provider, credential, routing, or capability-policy files and
cannot reach `ProviderManager` or configuration writes through the shared API.
Server shutdown clears the published instance by identity so an old lifecycle
cannot remove a newer runtime.

## Console permissions

Console access is split into viewer, delegated Test, and administrator roles. The existing
`allow_all_players` and `require_op_level` settings grant viewer access, which
only exposes the Log tab and live/recent traffic. Provider metadata, URLs,
masked keys, routing, setup, and test operations are not sent to viewers.
Logs include complete LLM request and response content, so viewer access should
only be granted to trusted players.

Trusted server mods can issue a five-minute delegated Test grant through
`LlmConsoleTestBridge`. A grant is bound to one player UUID, request UUID, and
the complete server-approved handoff. Delegated users receive only that
purpose's effective read-only summary. The server executes its stored request,
not client-edited provider, purpose, routing, prompt, or parameter fields; the
Console disables those controls and does not expose logs or management tabs.

Administrator access is granted to OP level 4, the integrated-server owner, or
UUIDs listed in `admin_uuid_whitelist`. Administrators can use every Console tab
and the `status`, `test`, `reload`, and `setkey` commands. The server validates
the administrator role for every management packet; client-side tab state is
only a usability measure. Online-mode UUIDs are authenticated by Minecraft.
On an offline-mode server, LLM Core cannot validate any external account system
and the server owner remains responsible for preventing name/UUID spoofing.

Console Test's Form and Request JSON views edit one unsent `ConsoleTestRequest`.
The JSON view exposes the complete messages, parts, overrides, and metadata sent
through the existing Test packet; malformed or structurally invalid drafts are
blocked client-side, then the server repeats schema, purpose, request-id, and
permission validation. Delegated Test users may inspect but cannot edit that
server-authorized JSON, and execution still uses the request stored in their grant.

OP level 4, the integrated-server owner, and the dedicated-server console can
change the administrator list with `/llm whitelist <player> <true|false>`.
Whitelisted administrators cannot delegate this permission to other players.
On a dedicated server with `online-mode=false`, every change prints a spoofing
warning and requires the same operation to be repeated within 30 seconds. The
confirmation is single-use and is bound to the executor, target UUIDs, and
requested `true`/`false` state.

## Personal token budgets

`llmcore-mod` records cumulative input, output, conservatively estimated tokens,
and optional player-specific limits by UUID in
`<world>/llmcore/personal-budget.json`. A player limit has four states: omitted
inherits the server default, `-1` is explicitly unlimited, `0` disables personal
LLM access, and a positive value is a finite token quota. The server default uses
the same `-1` / `0` / positive vocabulary and starts at `-1`; a new server shows
a prominent Console warning until an owner explicitly confirms or changes it.
Finite limits include in-flight reservations and reject an over-budget attempt
before its provider HTTP call.

Billing is independent from diagnostic context. Every provider-bound
`LlmRequest` must carry an explicit `LlmBillingContext` with a typed principal,
causal root, maximum provider calls, and maximum tokens for that root. Code must
never infer billing from `audience`, observer, responder, or `triggerSource`.
Legacy request constructors remain linkable but produce `UNSPECIFIED`; the
installed server policy rejects those requests before provider access.

Interactive Console requests use the invoking player's UUID. KubeJS requests
default to `SCRIPT_SYSTEM`; a script can delegate explicitly only with a real
`ServerPlayer` through `billingPlayer` or a builder/session `billTo(player)`.
Provider fallback and automatic repair reuse the original causal root. Provider-
reported usage is authoritative. A successful response without usage metadata
settles its full conservative reservation under `estimatedTokens`; a failed,
cancelled, or not-started attempt releases its player reservation without a
second charge. Stale pending reservations have a final TTL recovery path, and a
late settlement after recovery is idempotently ignored.

The ledger is world-scoped and atomically replaced after each update. Malformed
data or a runtime persistence failure makes the ledger unavailable. Every
player-attributed request then fails closed, even when the server default is
unlimited, because the unreadable file may contain a disabling override. System
principals remain governed by their causal-chain ceilings. `/llm budget` shows
the caller's own aggregate; only owner-level administrators can list/reset usage,
change the server default, confirm it, or set a player's inherit/unlimited/
disabled/finite override. On offline-mode servers these UUIDs are not
authenticated by Minecraft, so a personal budget can only be trusted when an
external account system prevents identity spoofing.

The Console Budget tab is a read-only projection of this ledger plus in-memory
settlement totals grouped by `PLAYER`, `SERVER_AMBIENT`,
`SERVER_MAINTENANCE`, and `SCRIPT_SYSTEM`. It shows request count, average tokens,
in-flight reservations, effective limit/source, and the estimated-token share;
it does not create another persistence file or time-series source.
