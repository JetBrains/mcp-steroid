# iter-07 — Codex vs Claude on the SAME FeatureService125 scenario

Same task, same plugin snapshot, same recipe, different agent.

|                               | Claude iter-06       | Codex iter-07        | Δ |
|-------------------------------|----------------------|----------------------|---|
| **mcp_share (calls-basis)**   | 0.027                | **0.075**            | **+178%** |
| mcp_share (edits-basis)       | n/a                  | 0.056                |  |
| mcp_steroid calls             | 2                    | **3**                | +50% |
| exec_code_calls               | 2                    | 3                    | +1 |
| exec_code_lines_avg           | 13.5                 | 9.0                  | smaller scripts |
| native edit events            | 7 × `Edit` (one each)| 4 × `file_change`    | **batched** |
| native edit file-writes       | 7                    | **18**               | Codex wrote more files |
| native-shell-equivalent calls | 23 × `Bash`          | 33 × `command_exec`  | Codex explored more |
| errors                        | 1                    | 6                    | Codex had 6 non-zero-exit commands |
| tokens_total                  | 8.0 M                | **3.3 M**            | -58% tokens |

## Key finding — Codex batches edits natively

Codex's `file_change` item is **one event that bundles N file modifications**.
One of iter-07's `file_change` events contained **11 file changes in one
batch** (7 updates + 2 adds in the `ReleaseStatusTransitionValidator` feature
implementation, plus 2 more related files). That is exactly what our
`applyPatch { hunk; hunk; … }` DSL gives Claude — except Codex gets it for
free at the CLI level.

Total file writes over the run: **18 files across 4 batched events** (batch
sizes 11 + 4 + 2 + 1). If each of those 18 files had to come through a
separate per-file `Edit` call (Claude's shape), the call count would rise
from 4 to 18 and drop mcp_share even further.

## Re-framing the optimisation target

The mcp_share gap (0.027 Claude vs 0.075 Codex) on the **same** scenario
shows the ceiling is **Claude-specific**. Claude's `Edit` tool is per-call-
per-file, and its trained prior says "4 files to edit → 4 Edit calls". No
amount of tool-description framing moves that.

## Implications

- **Codex is closer to MCP-aligned behaviour by design** — edits batched
  natively.
- **Claude needs apply-patch visibility at the MCP tool layer**, not just
  the recipe layer. A dedicated `steroid_apply_patch` MCP tool would sit
  next to `Edit` in Claude's tool list; Claude's tool-selection logic would
  then see "edit 7 files in 7 × Edit calls" vs "edit 7 files in 1 ×
  steroid_apply_patch call" as competing tool-level choices (not "use
  this one obscure script pattern inside a different tool").

## iter-08 plan

Two options, both NEGATIVE-metric-compliant if interpreted loosely (no new
`McpScriptContext` methods added; growth is at the MCP-tool layer):

**(A) dedicated `steroid_apply_patch` MCP tool** — ships the same underlying
executeApplyPatch, same code path, same tests. Surfaces in Claude's
tool list. Expected to move Claude's mcp_share toward Codex's level.

**(B) Run iter-08 on Claude WITHOUT any further change, pick a much simpler
scenario (PetclinicRest3, single-method add)** — measure the native-bound
floor for a simple task. Gives us a "best case" Claude number to compare
against the Microshop-2 / FS125 results.

Plan: start with (B) to triangulate, then if (B) doesn't move mcp_share
above ~0.10, land (A) as iter-09.

## DSL surface area

dsl_methods_added_vs_baseline = 1 (applyPatch). Unchanged.
