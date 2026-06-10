# TODO

- [x] Fix `steroid_open_project` to trust a project path before opening it and cover the no-modal path with an integration test.
- [x] Agent-driven backend management: evaluated new MCP tool vs CLI passthrough vs hybrid (judge panel). **Decision: no new MCP tool** — agents manage backends via the existing `devrig backend …` CLI (fails the PHILOSOPHY 3-gate for a new tool; the CLI already does it). Shipped `mcp-steroid://open-project/managing-backends` recipe + aligned `open_project` to prefer a running devrig-managed backend (`DevrigProjectRoutingService.openProjectTargetIde()`).
- [ ] Backend management follow-ups (deferred, surfaced during the design):
  - Stream download progress to the agent (downloads can take minutes; CLI is silent until done).
  - Consider enriching `backend --json` / `backend download --json` with release date + download channel so agents can reason about staleness; consider exposing `IdeProduct` metadata (license tier, launcher) for richer IDE choice.
  - Optional explicit `open_project` target (by managed-backend id / pid) for the case where the agent wants a specific backend even when several are running — today the global lock makes "prefer managed" sufficient.

- **plugins[] enumeration (follow-up to closed #88):** surface more IDE plugins on `BackendInfo.plugins[]`
  (e.g. the built-in IDE MCP server as `kind: "intellij-native-mcp"`). Needs an additive wire extension:
  optional `PidMarker.plugins: List<PluginInfo>? = null` (ij-plugin writes relevant plugins; old devrig
  ignores unknown key; new devrig falls back to the singular `plugin` field), devrig-side id→kind
  classification, PidMarker contract-test updates. Spec in the #88 closing comments.
