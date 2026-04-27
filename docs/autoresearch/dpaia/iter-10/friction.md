# iter-10 — FS125 validation + 60s Claude MCP-tool timeout discovery

Goal: cross-validate iter-09 arena prompt change on a different scenario
(FS125, iter-06 baseline). Secondary: confirm the positive effect isn't
PetRest3-specific.

## Comparison against iter-06 baseline (same scenario)

| metric                | iter-06 (old prompt) | iter-10 (iter-09 prompt) |
|-----------------------|----------------------|---------------------------|
| **applyPatch called** | false                | **true** (5 hunks)        |
| mcp_steroid_calls     | 2                    | 3                         |
| native_edit_calls     | 7                    | 7                         |
| mcp_share             | 0.027                | **0.041** (+52%)          |
| exec_code_lines_avg   | 13.5                 | 45                        |
| exec_code_lines_max   | 18                   | 109                       |
| Fix success           | yes                  | yes                       |
| errors                | 1                    | 2                         |

Positive signal: Claude **reached for applyPatch** on this scenario too,
confirming the iter-09 prompt change generalises. BUT the `native_edit_
calls` number stayed at 7 because the applyPatch call was cut short —
Claude fell back to per-file `Edit` after the timeout.

## The timeout — Claude CLI's 60s MCP_TOOL_TIMEOUT default

IDE log (`ide-timing.log`) of the "Applying patches" execution:

```
06:38:51,868  Starting execution ...Applying patches...
06:38:51→56   DialogKiller: 5 iterations killing 'Confirm Restart' + 'Manage Subscriptions' (5 s)
06:38:56,168  Review approved → CodeEvalManager compile starts
06:39:28,686  Compile DONE (32 s for 108 script lines with big string payload)
06:39:28,686  ScriptExecutor: Running with timeout 600s
06:39:51,***  Claude CLI: "The operation timed out."
```

**60 seconds** from tool_use to client-side timeout. That matches
Claude CLI's default `MCP_TOOL_TIMEOUT=60_000ms`. Our MCP Steroid server
timeout is 600s; the bottleneck is Claude's default tool-call timeout.

Breakdown of the 60s:
- 5 s — dialog killer (5 × "Confirm Restart" / "Manage Subscriptions")
- 32 s — kotlinc compile (the applyPatch script had a 3843-char payload)
- 23 s — script exec began, Claude gave up

## What would unblock this

**(A) Set `MCP_TOOL_TIMEOUT` in DockerClaudeSession.** One-line env var
addition in `test-helper/.../DockerClaudeSession.kt`. Matches how Claude
Code docs recommend configuring for slower MCP tools. Test-infrastructure
fix, not a prompt change — within the 20-iteration scope.

**(B) Shrink applyPatch script compile time.** Cache compile results per
content hash, or use a pre-compiled applyPatch "receiver" that takes a
serialised spec. Bigger change, affects ij-plugin code, crosses the
negative-metric boundary.

**(C) Stream heartbeat output.** MCP progress notifications keep the
client awake; MCP Steroid already has `McpProgressReporter` — maybe we
need to actually send one during long compiles so Claude's timeout
resets per-notification.

## iter-11 plan

Land (A): add `MCP_TOOL_TIMEOUT=600000` (10 minutes) to
`DockerClaudeSession.runInContainer`. Re-run FS125. Expected signal:
applyPatch call succeeds end-to-end, `native_edit_calls` drops from 7
toward 1.

## DSL surface area

`dsl_methods_added_vs_baseline = 1`. Unchanged.
