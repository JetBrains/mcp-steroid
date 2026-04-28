# TODO: apply-patch feature

The `steroid_apply_patch` MCP tool is **disabled on `main`** as of commit
`47e03ef2` — its `<mcpRegistrar>` line was removed from `plugin.xml` and the
matching integration test was deleted. The full feature is preserved on the
`apply-patch` branch (origin only).

This file collects work items related to the apply-patch feature so the
main `TASKS.md` (DPAIA / autoresearch flow) does not carry them.

## Re-enabling on `main`

To turn the feature back on, restore one line in `plugin.xml`:

```xml
<mcpRegistrar implementation="com.jonnyzzz.mcpSteroid.server.ApplyPatchToolHandler"/>
```

…and restore `ApplyPatchToolIntegrationTest.kt` from the `apply-patch`
branch (or from history at `5a0486ea`).

## Outstanding items

- [ ] **Decide the contract on `main`.** The MCP tool is unregistered, but
  these prompt resources still teach `steroid_apply_patch` as a callable
  tool. Pick one path and apply it consistently:
  (a) gate the prompts behind a feature flag,
  (b) re-route the prompts to the `applyPatch { }` DSL inside
      `steroid_execute_code`, or
  (c) re-register the tool on `main`.

  Affected files (all on `main`):
  - `prompts/src/main/prompts/ide/apply-patch.md` — entire article
    describes the disabled tool.
  - `prompts/src/main/prompts/skill/apply-patch-tool-description.md` —
    ships the tool description for an unregistered tool.
  - `prompts/src/main/prompts/skill/coding-with-intellij.md:32` — table row
    "Multi-site literal edit" routes to `steroid_apply_patch`.
  - `prompts/src/main/prompts/skill/execute-code-tool-description.md:10` —
    "STOP before the 2nd Edit" guidance recommends `steroid_apply_patch`.

- [ ] **`test-experiments/.../arena/ArenaTestRunner.kt:201,249`** — the arena
  prompt prepares agents to call `steroid_apply_patch`. Decide whether
  arena tests on `main` should target the new (no-tool) contract or stay
  pinned to the feature branch and assert tool availability before running.

- [ ] **`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ApplyPatchToolHandler.kt:171,183`**
  — `runCatching { analyticsBeacon.capture(...) }` swallows analytics
  exceptions silently. Per `CLAUDE.md` "fail fast and log problems":
  rewrite as `try { } catch (e: Exception) { log.warn(...) }`, or
  document why fire-and-forget is acceptable. Currently dead on `main`,
  active on `apply-patch` branch — fix on the feature branch first so the
  fix is in place when re-enabled.

- [ ] **`kotlin-cli/src/main/kotlin/com/jonnyzzz/mcpSteroid/koltinc/CodeWrapperForCompilation.kt:32-34`**
  — re-exports `ApplyPatchBuilder` / `ApplyPatchException` /
  `ApplyPatchResult` for prompt examples. Compiles fine; the surrounding
  comment assumes the prompts teach the tool as available, which is no
  longer true on `main`. Trim the comment or remove the re-exports if the
  feature stays disabled.

- [ ] **Long-term:** delete the `apply-patch` branch when the contract is
  finalized. If re-enabled on `main`, merge from the branch then delete it.
  If permanently dropped, delete the branch and remove the dead handler /
  engine / tests / prompts from the tree.

## Verification baseline (recorded 2026-04-28, commit `47e03ef2`)

- `:ij-plugin:test --tests '*ApplyPatchTest*'` — engine-level: **9/9 pass**
  on `main`. The DSL `applyPatch { }` still works inside
  `steroid_execute_code` because the engine code (`ApplyPatch.kt`,
  `McpScriptContext.applyPatch`) was deliberately left intact.
- `:ij-plugin:test --tests '*McpServerIntegrationTest*'` — **30/30 pass**.
  `tools/list` no longer advertises `steroid_apply_patch`; no other test
  referenced the removed tool.
- `:ij-plugin:test --tests '*McpServerCoreTest*' --tests '*McpProtocolTest*' --tests '*FetchResourceToolTest*'`
  — **36/36 pass**.
- No force push: `origin/main` `349d649c` → `47e03ef2` (fast-forward),
  `jb/main` synced via the documented `jb-merge` procedure.
- `apply-patch` branch published to `origin` only at `349d649c` (preserves
  the full feature; no jb mirror).
