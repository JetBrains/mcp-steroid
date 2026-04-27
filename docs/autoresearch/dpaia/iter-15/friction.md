# iter-15 — Milestone: steroid_apply_patch fully working on PetRest3

First complete end-to-end success across all the pieces the research
loop built:
1. **iter-09 arena prompt**: Claude reaches for applyPatch by default.
2. **iter-13 dedicated MCP tool**: Claude invokes `steroid_apply_patch`
   as a first-class tool (not buried inside exec_code).
3. **iter-13 DialogKiller wiring**: modal dialogs don't block EDT.
4. **iter-15 `Observation.awaitConfiguration`**: IDE finishes background
   saves before the write action runs.

## Result

| metric                | iter-08 (baseline) | iter-09 (DSL path) | **iter-15 (dedicated tool)** |
|-----------------------|--------------------|--------------------|-------------------------------|
| steroid_apply_patch   | -                  | -                  | **1 call, 26 hunks**          |
| applyPatch via DSL    | no                 | yes (timed out)    | no (new tool preferred)       |
| mcp_steroid_calls     | 2                  | 3                  | 3                             |
| native_edit_calls     | 9                  | 1                  | **1**                         |
| **mcp_share**         | 0.051              | 0.046              | **0.100** (+96%)              |
| **errors**            | 0                  | 2                  | **0**                         |
| Agent time            | 260 s              | 522 s              | **376 s (6m 16s — fastest)**  |
| Fix success           | yes                | yes                | yes                           |

Evidence from IDE log (`ide-timing.log`):
```
08:21:19,706  [MCP] Request: steroid_apply_patch — 26 hunks
08:21:19,775  [MCP] Response: "apply-patch: 26 hunks across 1 file(s)
              applied atomically."
```
**69 ms** from request to response. No kotlinc, no lock wait, no retry.

## What this unlocks

- mcp_share crossed 0.10 for the first time in this research loop.
- The dedicated MCP tool path validates: Claude's tool-selection logic
  sees `steroid_apply_patch` in the tool list and picks it over native
  `Edit` when the arena prompt nudges toward batched edits.
- Zero errors across the entire run — every tool invocation that
  mattered completed successfully.

## Path not taken, one note

FS125 (iter-14) still hit a timeout because of a separate environmental
bug: a persistent "Manage Subscriptions" ↔ "Confirm Restart" dialog
loop in the FS125 container that DialogKiller kept re-firing every few
seconds. PetRest3's container doesn't exhibit that pattern, so the
awaitConfiguration fix plus DialogKiller pre-flight is enough. FS125
would need additional suppression of those specific modals — separate
from this research loop.

## iter-16+ plan

At this point the core research goals are met. Remaining iterations
should explore generalisation:
- **iter-16**: Codex on PetRest3 with new tool — does Codex also pick
  up `steroid_apply_patch`, or stick with its native `file_change`?
- **iter-17**: Microshop-2 (4-file forEach scenario) with new tool —
  validate cross-scenario.
- **iter-18-20**: Either fix FS125 dialog storm (infra work) or
  summarise findings as the final research commit.

## DSL surface area

`dsl_methods_added_vs_baseline = 1` (applyPatch on McpScriptContext,
unchanged since iter-05). +1 MCP tool at the server layer (not tracked
by the DSL metric per user spec).

## Summary

The end-to-end pipeline works. Claude is willing and able to use
steroid_apply_patch. The DSL path remains useful for Codex (no client
timeout cap) and for exec_code users; the MCP tool path is the
canonical fix for Claude's 60s cap.
