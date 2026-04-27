# iter-11 — evidence: `MCP_TOOL_TIMEOUT` is not a thing in Claude CLI

Tested hypothesis from iter-10: lifting `MCP_TOOL_TIMEOUT=600000` via
docker exec env would unblock applyPatch completion. **Hypothesis
rejected.**

## Evidence

Set in `test-helper/.../DockerClaudeSession.kt`:

```kotlin
put("MCP_TOOL_TIMEOUT", "600000")
put("MCP_TIMEOUT", "60000")
```

Re-ran DpaiaFeatureService125 Claude+MCP. Result:
- `applyPatch called: false` (regressed vs iter-10 which attempted it)
- native_edit_calls: 4 (Claude favoured Write for bulk rewrites)
- 1 error: `"The operation timed out."` on an 11-line buildAllModules
  compile check

The timeout error still fired, same 60s window. The env var was
correctly propagated via `docker exec -e` but **Claude CLI does not
recognise `MCP_TOOL_TIMEOUT`**. Verified via two independent
`claude-code-guide` queries:

- Only `MCP_TIMEOUT` is documented, and it governs **server startup**
  not per-tool-call duration.
- No `--mcp-request-timeout` CLI flag exists.
- Historical issues (#3033, #20335, #43791) show timeout config has been
  "inconsistently honored, particularly in SSE connections."
- `MCP_TIMEOUT` is sometimes honoured **via `~/.claude/settings.json`**
  inside the container — not via env var.

## Reverted

`MCP_TOOL_TIMEOUT` and `MCP_TIMEOUT` env puts removed from
`DockerClaudeSession.kt` (no-op). Leaving the env minimal.

## Comparison across FS125 runs

| metric              | iter-06 | iter-10 | iter-11 |
|---------------------|---------|---------|---------|
| applyPatch called   | no      | **yes** | no      |
| mcp_steroid_calls   | 2       | 3       | 2       |
| native_edit_calls   | 7       | 7       | 4       |
| native_write_calls  | 2       | 5       | 9       |
| mcp_share           | 0.027   | 0.041   | 0.029   |
| errors              | 1       | 2       | 1       |
| Fix success         | yes     | yes     | yes     |

The Write count trended UP (2 → 5 → 9). Claude is increasingly choosing
Write for bulk file rewrites in place of Edit or applyPatch. Run-to-run
variance on Claude's tool selection is high: three runs of the same
scenario with identical prompts yielded three different shapes.

## iter-12 plan

Prompt-level workaround: in the arena prompt, instruct agents to split
applyPatch calls of >5 hunks into multiple calls, each kept short enough
to compile in <30 s so the 60s client timeout isn't hit.

Alt path considered and parked for later: test writing
`~/.claude/settings.json` with `MCP_TIMEOUT=600000` BEFORE invoking
claude. Possibly server-startup only; needs empirical check.

## DSL surface area

`dsl_methods_added_vs_baseline = 1`. Unchanged.
