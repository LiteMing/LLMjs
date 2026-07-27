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

Console access is split into viewer and administrator roles. The existing
`allow_all_players` and `require_op_level` settings grant viewer access, which
only exposes the Log tab and live/recent traffic. Provider metadata, URLs,
masked keys, routing, setup, and test operations are not sent to viewers.
Logs include complete LLM request and response content, so viewer access should
only be granted to trusted players.

Administrator access is granted to OP level 4, the integrated-server owner, or
UUIDs listed in `admin_uuid_whitelist`. Administrators can use every Console tab
and the `status`, `test`, `reload`, and `setkey` commands. The server validates
the administrator role for every management packet; client-side tab state is
only a usability measure. UUID whitelisting assumes authenticated online-mode
identities and must not be treated as secure on an offline-mode server.

OP level 4, the integrated-server owner, and the dedicated-server console can
change the administrator list with `/llm whitelist <player> <true|false>`.
Whitelisted administrators cannot delegate this permission to other players.
