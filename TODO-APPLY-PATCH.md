# TODO: apply-patch feature

The `steroid_apply_patch` MCP tool was fully removed from `main` in the
"cleanup & simplification round" (May 2026). See `TASKS.md` section
`C3 — Drop steroid_apply_patch; reinforce deep IDE features` for the
rationale and `docs/PHILOSOPHY.md` Tenet 1 → *Worked example* for the
canonical statement.

## What was removed

- Spec: `mcp-steroid-server/.../server/ApplyPatchTool.kt` (incl. `ApplyPatchRequest`).
- IJ handler: `ij-plugin/.../server/ApplyPatchToolHandler.kt`.
- Schema test: `ApplyPatchToolSpecSchemaTest`.
- Integration test: `ApplyPatchToolIntegrationTest`.
- Devrig bridge: `DevrigApplyPatchToolHandler` in `npx-kt`.
- Prompt source: `prompts/.../skill/apply-patch-tool-description.md`.

## What stays

- In-script DSL: `McpScriptContext.applyPatch { hunk(...) }` on every
  `steroid_execute_code` call. Defined in
  `ij-plugin/.../execution/ApplyPatch.kt` (`ApplyPatchHunk` moved here
  from the deleted tool's data classes).
- Recipe article: `mcp-steroid://ide/apply-patch` — the agent-facing
  multi-site-edit guide, now framed as a DSL-inside-`steroid_execute_code`
  pattern.
- Engine semantics tests: `ij-plugin/.../execution/ApplyPatchTest.kt`
  pins atomicity / ordering / preflight / dryRun behavior.

## Restoring the MCP tool later

Read `git log --grep="apply_patch"` for the removal commits and `git show`
each in reverse to bring the surface back. Five removal commits, in order:

1. `PHILOSOPHY: drop steroid_apply_patch from tool list (10→9)`
2. `execute_code: wrap user-script body in McpEditingGuard`
3. `apply_patch: drop the MCP tool surface (keep in-script DSL)`
4. `arena: route multi-site edits through applyPatch { } DSL`
5. `prompts: route corpus through applyPatch { } DSL`

Don't restore wholesale — re-evaluate against Tenet 1's three gates first.
