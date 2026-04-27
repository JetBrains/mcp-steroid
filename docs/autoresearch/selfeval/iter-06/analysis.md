# iter-06 plan

## iter-05 status

- BUILD FAILED: post-test teardown race (`com.intellij.util.lang.CompoundRuntimeException:
  Context has been disposed`). Infrastructure, not an assertion failure. The agent's
  full report reached sys-out (9 sections, 21 verdicts, 26.7KB); iter-05 analysis landed.
- Iter-05 signals:
  - §4 small-edit ratio: 4x → 1.5-2.5x (the compact recipe in tool description shrunk
    the perceived MCP Steroid overhead meaningfully).
  - §1 (b) count: 3 → 2 (file-reading bullet dropped; only small-edit + text-search left).
  - §2 added value list: 6 positive items (structural overview, name-path addressing,
    atomic refactoring, hierarchy, external deps, invocation overhead trade-off).

## Gap for iter-06

iter-05 §5 "Limitations observed":
> "The Kotlin API has a learning curve; incorrect threading (readAction/writeAction)
> produces runtime errors"

This matches what tripped my own iter-02 rename.md recipe (caught by
LspExamplesExecutionTest — fixed at b37d173d): I used `writeAction { processor.run() }`
instead of the correct `writeIntentReadAction { processor.run() }`. The tool
description currently lists these only as *after-error* diagnostics:
  - `Write access is allowed from write thread only` → wrap in `writeAction { }`
  - `Read access is allowed from inside read-action only` → wrap in `readAction { }`

Nothing tells the agent the rule *preventively*. Every incorrect wrap costs a
retry turn — the agent counts this against MCP Steroid's "learning curve" tax.

## iter-06 edit

Add a "Threading rules — apply preventively" quick-card to
`skill/execute-code-tool-description.md` (right under Quick Start, so every
`steroid_execute_code` call carries it):

- PSI read / tree walk / reference navigation → `readAction { }`
- VFS write (VfsUtil.saveText, setBinaryContent) → `writeAction { }`
- Refactoring processor `.run()` (Rename / Move / SafeDelete / Inline /
  ChangeSignature / Extract*) → `writeIntentReadAction { }` — NOT `writeAction`;
  the processor manages its own actions, and `writeAction` deadlocks
- Commit pending document edits to PSI → `writeAction { PsiDocumentManager.commitAllDocuments() }`
- CommandProcessor.executeCommand blocks sit *inside* an action, not the other way round

## Expected report change

- §5 "Limitations observed" should drop or soften the "incorrect threading…" item
- §2 item 6 "Invocation overhead (10-30 lines per operation)" should tick down
  — because the agent no longer has to keep defensive "might-be-wrong-threading"
  reasoning in its recipe budget.
- §6 workflow cost should shrink: fewer retry-to-fix-threading turns.
