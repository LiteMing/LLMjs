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
