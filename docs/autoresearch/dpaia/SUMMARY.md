# DPAIA autoresearch — 18-iteration summary

Goal: optimise Claude's adoption of MCP Steroid's apply-patch
capability against the DPAIA arena benchmark. Secondary goal per user:
"maximise the mcp steroid tool calls". Negative metric: new McpScriptContext
methods.

## Starting state (iter-00, 88 historical runs)

- `applyPatch_called`: **0 / 88**
- `native_edit_calls`: **avg 8.7 per run**
- `fetch_resource_calls`: **0 / 88**
- mcp_share (avg): 2.1 MCP steroid calls / run

Red flags: agents reached for native `Edit` 4:1 over `steroid_execute_code`,
and never read the skill/apply-patch guides.

## End state (iter-15 / iter-17)

**Claude on PetRest3 (iter-15)**: `steroid_apply_patch` called once
with 26 hunks, applied atomically in **69 ms**. mcp_share 0.100 (first
crossing of 0.10 threshold), zero errors, 6m 16s run, native Edits
dropped from 9 → 1.

**Claude on Microshop-2 (iter-17)**: `steroid_apply_patch` called once
with 8 hunks × 8 files, applied atomically in **66 ms**. **Zero** native
Edits (first time across entire loop). 5m 38s run.

## Three-layer architecture that unlocked it

### Layer 1 — Arena prompt (iter-09)

Two pre-applyPatch directives in `ArenaTestRunner.buildPrompt()` were
actively sabotaging adoption. Lines (180, 217 in `ArenaTestRunner.kt`)
told Claude **"Do NOT use steroid_execute_code to modify EXISTING files
— use the Edit tool instead"** and **"For multi-file edits, use Grep +
Read + Edit"**. These predated the DSL and inverted the intended
routing. iter-09 rewrote both to steer toward `applyPatch { hunk(…) }`
(later updated to `steroid_apply_patch`).

### Layer 2 — Dedicated MCP tool (iter-13)

The DSL path (`applyPatch { }` inside `steroid_execute_code`) hits a
hard ceiling on Claude: kotlinc compilation of a 100-line applyPatch
script takes ~30 s. Claude Code CLI has a hardcoded 60 s per-tool MCP
timeout (issues #3033, #16837, #22542) that **cannot be overridden via
env var or CLI flag**; progress notifications don't reset it either.
A dedicated `steroid_apply_patch` MCP tool bypasses kotlinc entirely,
ships hunks as JSON, and runs the same underlying `executeApplyPatch`
engine. Negative metric respected: **no new McpScriptContext method**,
only a new MCP tool at the server layer.

### Layer 3 — Dialog killer + configuration settle (iter-13, iter-15)

The direct MCP tool path bypasses `ExecutionManager`, so `DialogKiller`
does NOT run pre-flight. Without it, modal dialogs (e.g. the Docker
test container's "Manage Subscriptions" / "Confirm Restart" spam) block
`withContext(EDT + ModalityState.nonModal())` dispatch in
`executeApplyPatch` until the modal dismisses — exceeding the 60 s cap.

**Two pre-flight steps added to `ApplyPatchToolHandler.handle()`**:

1. `dialogKiller().killProjectDialogs(project, executionId, log, null)`
2. `withTimeoutOrNull(5_000L) { Observation.awaitConfiguration(project) }`

Together these mirror (a subset of) `ExecutionManager`'s pre-flight
without the 30 s kotlinc tax.

## Evidence table — Claude runs, like-for-like scenarios

| iter | scenario       | arena prompt | tool             | apply_patch called | native Edits | mcp_share | errors | time |
|------|----------------|--------------|------------------|--------------------|--------------|-----------|--------|------|
| 06   | FS125          | pre-iter-09  | -                | no                 | 7            | 0.027     | 1      | -    |
| 08   | PetRest3       | pre-iter-09  | -                | no                 | 9            | 0.051     | 0      | 260s |
| 09   | PetRest3       | **iter-09**  | DSL              | yes (timeout)      | 1            | 0.046     | 2      | 522s |
| 10   | FS125          | iter-09      | DSL              | yes (timeout)      | 7            | 0.041     | 2      | 673s |
| 12   | Microshop-2    | iter-09      | DSL              | yes (timeout)      | 8            | 0.045     | 1      | -    |
| 13   | FS125          | iter-09      | **new tool**     | yes (timeout)      | 3            | 0.060     | 2      | -    |
| 14   | FS125          | iter-09      | tool+DialogKiller| yes (timeout)      | 3            | 0.041     | 2      | -    |
| **15** | **PetRest3**   | iter-09      | **tool+full fix**| **yes (69 ms)**    | **1**        | **0.100** | **0**  | **376s** |
| **17** | **Microshop-2**| iter-09      | **tool+full fix**| **yes (66 ms)**    | **0**        | 0.043     | 2†     | **338s** |

† Microshop-2 iter-17 errors were native Bash/Read failures — zero MCP tool errors.

## Codex comparison (iter-07, iter-16)

| iter | scenario    | Codex uses   | mcp_share | native_edit | Fix |
|------|-------------|--------------|-----------|-------------|-----|
| 07   | FS125       | file_change  | 0.075     | 4 batched   | yes |
| 16   | PetRest3    | file_change  | 0.087     | 8 paths/1 batch | yes |

Codex's built-in `file_change` event natively batches multi-file edits,
so Codex doesn't adopt `steroid_apply_patch`. Not a problem — Codex
doesn't need the correction; its tool is equivalent. Accept two paths.

**First time Claude outperforms Codex on mcp_share for the same
scenario: iter-15 PetRest3 (0.100 vs 0.087)**. The dedicated MCP tool
is the equaliser.

## Evidence iterations (what didn't work)

- **iter-02**: LSP rename recipe — added surface without clear win.
  Reverted.
- **iter-03**: scope-split directive — redirected agents away from MCP.
  Reverted.
- **iter-11**: `MCP_TOOL_TIMEOUT` env var — not a real Claude CLI variable.
  Reverted.
- **iter-14**: `DialogKiller` alone on FS125 — still timed out because
  the container's modal storm outlasts DialogKiller's iteration cap.
  (iter-15 added `awaitConfiguration` which helps for clean scenarios
   but not for persistent modal storms — FS125 remains infrastructure-blocked.)

Each evidence iteration is committed with its own `friction.md`
documenting the hypothesis, data, and why it failed.

## Prompt changes that stuck

- `ArenaTestRunner.buildPrompt()` lines 182 + 217: multi-site edits →
  `steroid_apply_patch` (iter-09 + iter-13 update).
- `prompts/src/main/prompts/skill/execute-code-tool-description.md`:
  "🛑 STOP before 2nd Edit" nudge with decision tree.
- `prompts/src/main/prompts/ide/apply-patch.md`: `forEach` idiom +
  shortest-unique-anchor guidance.
- `prompts/src/main/prompts/lsp/rename.md`: `RenameProcessor` +
  `writeIntentReadAction` reference.

## Code changes that stuck

- `execution/ApplyPatch.kt` (from earlier session): `executeApplyPatch`
  engine with pre-flight single-occurrence validation, descending-offset
  ordering per file, PSI commit inside the write action.
- `execution/VfsRefreshService.kt` (from earlier session): fire-and-forget
  async VFS refresh on every exec_code tail + awaited pre-compile refresh.
- **iter-13**: `server/ApplyPatchToolHandler.kt` — new MCP tool handler.
  Reuses `executeApplyPatch`; accepts `{project_name, task_id, reason,
  hunks: [{path, old_string, new_string}]}`.
- **iter-13**: `plugin.xml` — mcpRegistrar registration.
- **iter-13**: DialogKiller pre-flight inside handler.
- **iter-15**: `Observation.awaitConfiguration` pre-flight (5 s timeout)
  inside handler.

## Metrics additions

- `metrics.py` `--dsl-methods` mode — counts `McpScriptContext` methods
  beyond baseline (negative metric reporter).
- **iter-18**: `metrics.py` now detects `steroid_apply_patch` MCP tool
  invocations (JSON hunks array) in addition to the DSL-string pattern
  inside exec_code.
- Codex-format support (`_is_codex_format` sniffer, item.completed +
  mcp_tool_call/command_execution/file_change event types).

## Open items

- **FS125 dialog storm**: Docker container exhibits persistent
  "Manage Subscriptions" ↔ "Confirm Restart" modal loop that DialogKiller
  cannot outrun. Unblocks independently of autoresearch scope.
- **Wider scenario coverage**: The dedicated MCP tool has been validated
  on PetRest3 and Microshop-2. FS125 blocked by infra. Other DPAIA
  scenarios (PetRest14/37, Petclinic Microservices5, etc.) untested
  with the new tool; likely work identically.
- **mcp_share as a metric**: exploration calls (Read/Glob/Bash)
  dominate the denominator, so mcp_share is noisy when comparing
  scenarios with different file counts. Edit-shape specific metric
  (`native_edit / (native_edit + mcp_apply_patch_hunks)`) is a better
  proxy for the actual goal.

## DSL surface

`dsl_methods_added_vs_baseline = 1` (applyPatch on McpScriptContext,
added before this research loop). Unchanged across all 18 iterations.
**+1 MCP tool** at the server layer (`steroid_apply_patch`) — not
tracked by the DSL metric per user spec.
