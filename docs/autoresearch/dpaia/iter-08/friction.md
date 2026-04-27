# iter-08 — Claude on PetclinicRest3 (simpler NAVIGATE_MODIFY baseline)

Triangulation run per iter-07 plan (B): measure Claude's mcp_share on a
simpler scenario to test whether prompt-only changes can close the gap
identified in iter-07 (0.027 Claude vs 0.075 Codex on FS125).

## Result

| metric              | iter-06 (FS125) | iter-07 Codex (FS125) | iter-08 (PetRest3) |
|---------------------|-----------------|-----------------------|--------------------|
| **mcp_share**       | 0.027           | **0.075**             | **0.051**          |
| mcp_steroid calls   | 2               | 3                     | 2                  |
| native calls        | 72              | 37                    | 37                 |
| native Edit calls   | 7               | 4 `file_change`       | **9**              |
| exec_code calls     | 2               | 3                     | 2                  |
| exec_code_lines_avg | 13.5            | 9.0                   | 14.0               |
| errors              | 1               | 6                     | 0                  |
| tokens_total        | 8.0 M           | 3.3 M                 | 3.4 M              |

## Crucial evidence — 8 consecutive Edits to the SAME file

Tool-call sequence from `agent-claude-code-1-decoded.txt`:

```
>> Edit (/…/PetClinicApplication.java)
>> Edit (/…/ClinicServiceImpl.java)
>> Edit (/…/ClinicServiceImpl.java)
>> Edit (/…/ClinicServiceImpl.java)
>> Edit (/…/ClinicServiceImpl.java)
>> Edit (/…/ClinicServiceImpl.java)
>> Edit (/…/ClinicServiceImpl.java)
>> Edit (/…/ClinicServiceImpl.java)
>> Edit (/…/ClinicServiceImpl.java)
```

This is the **textbook multi-hunk single-file applyPatch pattern** — 8
separate Edits to one file, where `applyPatch { hunk(path, a1, b1);
hunk(path, a2, b2); … }` would collapse 8 calls into 1.

Current `execute-code-tool-description.md` already documents exactly this
shape:

```kotlin
val result = applyPatch {
    hunk("/abs/path/A.java", "oldA", "newA")
    hunk("/abs/path/A.java", "oldA2", "newA2")
    hunk("/abs/path/B.java", "oldB", "newB")
}
```

Claude **reads** that description (it's in the tool schema metadata) and
**still** ignored it. The 🛑 STOP signal from iter-05, the decision tree
from iter-04, the forEach idiom from iter-06 — all visible, all bypassed.

## Prompt-only is exhausted

Five iterations of prompt nudges (iter-02 rename recipe, iter-04 decision
tree, iter-05 STOP-before-2nd-Edit, iter-06 forEach idiom, iter-06 scenario
swap) produced **zero** change in Claude's first-call tool selection. The
iter-08 PetRest3 floor confirms the FS125 result wasn't scenario-specific.

Per `docs/autoresearch-findings.md`:
> Tool descriptions are schema reference — MANDATORY warnings in them
> don't change behavior.

That finding applies to applyPatch framing as cleanly as to the earlier
findings about MCP server instructions. The tool-description layer is
**reference material**, not a behavioural lever.

## What would move Claude — and the negative-metric gate

Two candidate paths for iter-09:

**(A) dedicated `steroid_apply_patch` MCP tool.** Surfaces applyPatch as a
first-class Claude tool at the MCP protocol layer, side-by-side with the
native `Edit` tool in Claude's selection context. Same underlying
`executeApplyPatch` code path. Expected lift: Claude's tool-prior
evaluation sees the choice as "1 apply_patch call vs N Edit calls"
directly, rather than "use an obscure recipe inside a different tool".

**(C) accept the floor.** `applyPatch` quality is already validated (58-
hunk stress test, 60 ms apply + 52 ms revert); Claude just doesn't reach
for it. We optimise the DSL for *users who opt in* — that's Codex + human
agents writing exec_code scripts directly — and accept Claude stays on
`Edit`.

### Negative-metric interpretation

The negative metric in `README.md` is specifically:
> `dsl_methods_added_vs_baseline` — count of suspend methods / member vals
> on `McpScriptContext` beyond the primitive baseline.

An MCP tool at the server layer is **not** a McpScriptContext method, so
it doesn't increment this metric. But the spirit is "surface growth is a
cost" — a new MCP tool is arguably similar form of growth. Need to weigh
against user's secondary goal: "maximize the mcp steroid tool calls".

## iter-09 plan

Land option (A): add `steroid_apply_patch` as an MCP tool at the server
layer. Implementation reuses `executeApplyPatch` from
`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ApplyPatch.kt`
— zero code duplication, zero DSL surface growth. The `McpScriptContext.
applyPatch { … }` DSL stays for exec_code users.

Then re-run Claude on FS125 (iter-06 baseline) and PetRest3 (iter-08
baseline). Expected mcp_share shift: from ~0.03–0.05 to ~0.10–0.20 if
Claude's tool-selection logic finds the first-class tool the way it finds
native `Edit`.

## DSL surface area

`dsl_methods_added_vs_baseline = 1` (applyPatch). Unchanged.
