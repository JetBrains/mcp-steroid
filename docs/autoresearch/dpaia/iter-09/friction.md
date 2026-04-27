# iter-09 — Arena prompt rewritten: applyPatch-first

Biggest finding of this research loop.

## What changed

`test-experiments/.../arena/ArenaTestRunner.kt` buildPrompt() carried two
lines that were **pre-applyPatch directives**:

| before (line 182) | after |
|---|---|
| "Do NOT use `steroid_execute_code` to modify EXISTING files — use the Edit tool instead." | "Multi-site edits → `applyPatch` inside ONE `steroid_execute_code`. … For the SAME old→new applied to N files, use `listOf(paths).forEach { hunk(it, old, new) }`." |

| before (line 217) | after |
|---|---|
| "For simple multi-file edits (renames, annotation changes), use `Grep` + `Read` + `Edit`" | "For multi-file edits, use `applyPatch { listOf(paths).forEach { hunk(it, old, new) } }` inside ONE `steroid_execute_code` — NOT a chain of `Edit` calls." |

That was it. No McpScriptContext change, no MCP tool added. Pure prompt.

## Result — first applyPatch adoption across the entire research

Comparing **identical scenario** (DpaiaPetclinicRest3, cache management
feature add):

| metric                       | iter-08 (old arena prompt) | iter-09 (new arena prompt) |
|------------------------------|----------------------------|----------------------------|
| **applyPatch_called**        | false                      | **true** (1 call, 26 hunks) |
| native_edit_calls            | **9**                      | **1** (-89%)               |
| mcp_steroid_calls            | 2                          | 3 (+50%)                   |
| exec_code_lines_max          | 18                         | 342 (the big patch)        |
| exec_code_lines_avg          | 14                         | 123                        |
| calls_total                  | 39                         | 65                         |
| mcp_share (ratio)            | 0.051                      | 0.046                      |
| tokens_total                 | 3.4 M                      | 4.6 M                      |
| Fix success                  | YES (217/217)              | **YES (217/217)**          |

## Reading the mcp_share drop

The ratio fell even though the *behaviour we wanted* improved dramatically.
The denominator grew: 34 Read + 12 Bash + 8 Glob (iter-09) vs 14 Read +
5 Bash + 3 Glob (iter-08). Claude explored more aggressively under the
new prompt (an `Agent` subagent was spawned for exploration too).

**mcp_share is a poor headline metric** when the edit axis moves
independently of exploration. A better axis pair:

- `native_edit_calls` — dropped 9 → 1 (-89%)
- `applyPatch_called` + `hunks_estimate` — 0 → 26 hunks atomic

Per user's "maximize the mcp steroid tool calls" directive, absolute
`mcp_steroid_calls` is the target: +50% (2 → 3).

## What the agent said

Verbatim from `agent-claude-code-1-decoded.txt`:

> "Now let me add caching annotations to `ClinicServiceImpl`.
> **I'll use `applyPatch` for the multi-site edits.**"

Exactly the tool-selection shift the prompt aims for. The previous arena
prompt was anti-applyPatch by accident (it predated the DSL); the new
prompt maps directly onto the DSL's affordances.

## Residual friction — timeout on #3 steroid call

The 3rd steroid call (VFS refresh + `ProjectTaskManager.buildAllModules()`)
returned "The operation timed out." Default `ExecCodeParams` timeout is
600 s (`mcp.steroid.execution.timeout` registry); likely a Claude-CLI-side
client timeout, not the script itself. The applyPatch call immediately
before it (341 script lines, 26 hunks) **succeeded** — that's why
subsequent `./mvnw test-compile` reported no compile errors.

After the timeout on the compile-check call, Claude defensively re-read
`ClinicServiceImpl.java` and issued one Write to rewrite it. Content
matched, so no corruption, just one redundant Write.

## iter-10 directions

**(A) Validate on FS125 (iter-06 baseline).** Same arena-prompt fix,
measure applyPatch adoption on a larger 8-edit scenario — confirms the
effect isn't PetRest3-specific.

**(B) Investigate #3 call timeout.** The compile-check recipe in the arena
prompt may be exceeding a Claude-side timeout; if so, shorten the recipe
or split the refresh and build into two smaller calls.

Plan: land (A) first — cheaper, directly validates reproducibility.

## DSL surface area

`dsl_methods_added_vs_baseline = 1` (applyPatch). Unchanged. No server
tools added. All gains from a prompt edit that removed a pre-DSL directive.
