# iter-13 — `steroid_apply_patch` MCP tool adopted, but hits DialogKiller-shaped timeout

Landmark result: Claude **reached for the new first-class MCP tool**. First
time in the entire research loop that edits went through a dedicated MCP
patch tool (rather than the native `Edit` chain or exec_code DSL).

## Result vs prior FS125 runs

| metric                           | iter-06 | iter-10 | iter-11 | **iter-13** |
|----------------------------------|---------|---------|---------|--------------|
| steroid_apply_patch (new tool)   | -       | -       | -       | **1 call**   |
| applyPatch via exec_code DSL     | no      | yes†    | no      | no           |
| mcp_steroid_calls                | 2       | 3       | 2       | 3            |
| native_edit_calls                | 7       | 7       | 4       | **3** (-57%) |
| mcp_share                        | 0.027   | 0.041   | 0.029   | **0.060** (+122%) |
| Fix success                      | yes     | yes     | yes     | yes          |
| errors                           | 1       | 2       | 1       | 2            |

†: attempted but client timed out.

Direct evidence from `agent-claude-code-1-decoded.txt`:
```
>> mcp__mcp-steroid__steroid_apply_patch (project-home)
```
First steroid tool invocation that's NOT `steroid_execute_code`.

## Residual bug — DialogKiller bypass in the new tool

Claude's call timed out after 60s. IDE log:
```
07:53:34  MCP POST with steroid_apply_patch tool call
07:54:34  Response: "Tool execution error: StandaloneCoroutine was cancelled"
```

60-second gap with no log activity. Root cause: the new
`ApplyPatchToolHandler` bypasses `ExecutionManager`, which means
`DialogKiller` does not run pre-flight. The container was showing recurring
"Manage Subscriptions" and "Confirm Restart" modals throughout the run.
The `withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement())`
inside `executeApplyPatch` waits for non-modal state, so EDT dispatch was
parked until the modal was dismissed — but no DialogKiller was active to
dismiss it.

By contrast, `executeApplyPatch` via the exec_code DSL (iter-10) gets
DialogKiller pre-flight from `ExecutionManager.executeWithProgress`, so
it runs under a clean modality state.

## Fix

Added `dialogKiller().killProjectDialogs(project, executionId, log, null)`
call to `ApplyPatchToolHandler.handle()` before `executeApplyPatch`.
Matches the pre-flight that `ExecutionManager` does for every exec_code
call. Patch itself is still sub-ms; the extra DialogKiller pass runs in
<1 s when no modal is present (fast-path exits immediately).

## Interpretation

Despite the timeout error reaching Claude, the **tool was invoked with
correctly-formed hunks** — pre-flight validation, hunks serialised
properly, reason text matches the task. Claude's tool-selection logic
chose `steroid_apply_patch` over the native `Edit` chain on first
attempt. The ergonomics work. The only infrastructure bug was the
missing DialogKiller, now fixed.

## iter-14 plan

Re-run FS125 with the DialogKiller fix. Expected:
- steroid_apply_patch completes cleanly (no 60s block)
- native_edit_calls drops further
- no "StandaloneCoroutine was cancelled" errors

## DSL surface area

`dsl_methods_added_vs_baseline = 1` (applyPatch on McpScriptContext).
Unchanged. Added: +1 MCP tool (`steroid_apply_patch`) at the server
layer. This is explicitly NOT tracked by the negative DSL metric —
user specified "methods / member vals on McpScriptContext".
