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
contract without a coordinated release. Existing `config/llmjs`,
`serverconfig/llmjs`, translation keys, and the `/llm` command also remain
stable. New installations store credentials in `llmcore.secret`; existing
`llmjs.secret` files remain a supported fallback during migration.

The Gradle `check` tasks enforce artifact ownership and reject adapter-only
KubeJS dependencies in `llmcore`.

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

OP level 4, the integrated-server owner, and the dedicated-server console can
change the administrator list with `/llm whitelist <player> <true|false>`.
Whitelisted administrators cannot delegate this permission to other players.
On a dedicated server with `online-mode=false`, every change prints a spoofing
warning and requires the same operation to be repeated within 30 seconds. The
confirmation is single-use and is bound to the executor, target UUIDs, and
requested `true`/`false` state.
