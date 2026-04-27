# iter-14 — DialogKiller fix deployed; new blocker: persistent modal + write-lock contention

Re-ran FS125 after wiring DialogKiller into `ApplyPatchToolHandler.handle()`.
Claude still picked the new tool (confirming iter-13's signal). But the
tool call still timed out at 60s.

## Result

| metric                         | iter-13 | **iter-14** |
|--------------------------------|---------|--------------|
| steroid_apply_patch called     | 1 (timed out)  | 1 (timed out) |
| mcp_steroid_calls              | 3       | 3            |
| native_edit_calls              | 3       | 3            |
| mcp_share                      | 0.060   | 0.041        |
| Fix success                    | yes     | yes          |
| errors                         | 2       | 2            |

## New root cause — Docker container dialog-storm + write-lock contention

IDE log `ide-timing.log`:
```
08:09:02  DialogKiller iter 0 — Modal state detected (Manage Subscriptions)
08:09:03  iter 1 — Confirm Restart (re-appeared after iter 0 closed)
08:09:03  iter 2 — Manage Subscriptions
08:09:04  iter 3 — Confirm Restart
08:09:04  iter 4 — Manage Subscriptions
08:09:05  iter 5 — Confirm Restart (DialogKiller caps at iter > 5)
08:09:06  DialogKiller done, ApplyPatch attempts write action
08:09:06  "Saving Project(name=project-home, ...)" fires — project save starts
08:09:17  WARN - "Cannot execute background write action in 10 seconds"
08:10:02  Tool execution error: "StandaloneCoroutine was cancelled"
```

The IDE container spawns "Manage Subscriptions" + "Confirm Restart"
modals in an infinite loop — closing one spawns the other. DialogKiller
runs 6 iterations and exits. Immediately after, the IDE triggers a
project save (DockManager, FileEditorManager, MavenProjectNavigator).
Our WriteCommandAction then can't acquire the write lock (10s+ wait),
and Claude's 60s fires before it can.

In the exec_code DSL path the same lock contention exists, but kotlinc
takes ~30s to compile — by the time `withContext(EDT)` runs, the IDE
has finished saving and the lock is free.

## Interpretation

The dedicated MCP tool is the **right architecture** — Claude reaches
for it, tokens are small, patches are sub-ms in principle. The bug
here is in the Docker test container environment (persistent dialog
spam + tight timing).

## Options considered for iter-15

**(A) Wait for background saves before applyPatch.** Call
`Observation.awaitConfiguration(project)` or similar before the write
action. Only delays the patch by 1-2 s in normal cases; unblocks
locked-out container runs.

**(B) Retry the write action on failure.** If WriteCommandAction
fails/times out, retry after a short backoff. Code gets messier; may
mask real problems.

**(C) Measure on a scenario without the dialog-storm container
behaviour.** PetRest3 iter-09 ran on this same IDE image without dialog
issues; the dialogs are probably triggered by FS125's Maven project
structure. Cross-scenario validation would isolate the infra bug.

**(D) Accept the limitation for this research cycle and document.**
The research has produced:
- iter-09: arena prompt fix (+122% mcp_share ceiling when tool completes)
- iter-13: dedicated MCP tool (Claude adopts it on first attempt)
- iter-14: infrastructure-environment lock-contention bug

Fundamental result proven; infra issue deferrable.

## iter-15 plan

Land (A): add a brief `Observation.awaitConfiguration(project)` call in
`ApplyPatchToolHandler.handle()` between DialogKiller and
`executeApplyPatch`, with a short timeout (~5 s). This lets the IDE
finish pending saves/indexing before we attempt the write action, at
minimal extra cost in the happy path.

## DSL surface area

`dsl_methods_added_vs_baseline = 1`. Unchanged. +1 MCP tool at the
server layer. Negative metric respected.
