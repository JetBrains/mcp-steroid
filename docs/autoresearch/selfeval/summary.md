# Autoresearch iteration summary — LOOP COMPLETE

10 iterations of prompt-tuning driven by Claude's self-eval report. Constraint:
only the MCP server's prompt resources (files under `prompts/src/main/prompts/`) may
change. The Serena self-eval prompt is reproduced verbatim and never edited.

Metric legend:
- **(a)** tasks where MCP Steroid adds capability (§1 bullet count)
- **(b)** tasks where MCP Steroid applies but offers no clear improvement (§1 bullet count)
- **ratio**: §4 token payload ratio for small 1-3 line edits, built-in vs MCP Steroid
- **verdict**: count of recognizable `**Verdict**` lines
- **wall**: testSerenaSelfEvalPrompt wall time (mm:ss)

| iter | commit   | (a) | (b) | ratio     | verdict | wall   | status  | edit                                                               |
|------|----------|-----|-----|-----------|---------|--------|---------|--------------------------------------------------------------------|
| 00   | 534c008c | 6   | 3   | 4x        | 32      | 20:31  | ✅ baseline | — |
| 01   | ac8a0182 | 5   | ~2  | —         | 24      | 12:58  | ✅ (superseded) | route small edits to built-ins                         |
| 02   | ed24e475 | 5   | 2   | —         | 21      | 12:39  | ✅ | semantic RenameProcessor in lsp/rename.md                          |
| 03   | 209097e0 | —   | —   | —         | —       | —      | 🗑 reverted | mcp-steroid-info.md scope split (policy pivot)                    |
| 03'  | f0ff0af1 | 5   | 3   | —         | 28      | 22:32  | ✅ | compact VfsUtil.saveText in coding-with-intellij.md Quick Ref      |
| —    | b37d173d | —   | —   | —         | —       | —      | 🔧 fix | rename recipe readAction + writeIntentReadAction                    |
| 04   | 17de0af9 | 5   | 3   | 4x        | 31      | 16:30  | ✅ | correct auto-imports doc in execute-code-overview.md               |
| 05   | beeccdc4 | 4   | 2   | 1.5-2.5x  | 21      | 13:41* | ✅ | compact in-place edit recipe in tool description                   |
| 06   | 41b66090 | 4   | 2   | —         | 24      | 19:36  | ✅ | preventive threading-rules card in tool description                |
| 07   | d53c6ca0 | **8** | 3 | ~5x (mis-count) | 24 | 12:04  | ✅ | cement always-IDE-for-edits policy in mcp-steroid-info             |
| 08   | e75cb2b0 | 6   | 2   | parity    | 23      | 23:19  | ✅ **final state** | payload-accounting clarification                      |
| 09   | be54005f | —   | —   | —         | —       | —      | ⏱ timeout, reverted bab1e426 | batch-multi-edit recipe (caused Claude CLI to hit 1500s per-prompt wall) |

*iter-05 build failed in post-test teardown race; report reached sys-out cleanly.

## Outcomes vs baseline (iter-00 → iter-08)

- **§1 (a)** (capability wins): stabilized around 5–6 items; spiked to 8 at iter-07 when
  agent pulled inspections/quick-fixes/signature help/run configs/debugger out of §7
  into §1 scope.
- **§1 (b)** (apply-but-no-improvement): 3 → 2. Residual items:
  - Small 1–3 line edits — structural Kotlin floor vs `Edit(old,new)`. Addressed
    long-term by **parked apply-patch task #4**.
  - Single-file unique-name rename — equivalence; not worth fighting.
- **§4 small-edit token ratio**: 4× → 1.5–2.5× → **parity** once the agent stopped
  double-counting IDE-side `saveText` file content against MCP payload.
- **§5 Limitations observed**: converged to fixture/configuration-only issues
  (index scope, temp:/// VFS write timeout, PSI refresh) — **no more API learning
  curve / threading complaints** since iter-06 added the preventive threading card.
- **§7 Unique capabilities**: grew to 6 items; agent autonomously discovered
  `runInspectionsDirectly()` without our pointing at it (iter-06).

## Headline verdict evolution

- iter-00 (baseline): "MCP Steroid adds genuine, high-value capabilities for semantic
  code operations (refactoring, navigation, hierarchy, dependency resolution) that
  have no built-in equivalent, while built-ins remain the natural choice for text-level
  edits, non-code files, and shell operations."
- iter-08 (final state): "MCP Steroid's primary value is semantic code intelligence
  and atomic multi-file refactoring — capabilities that have no built-in equivalent;
  its value for simple text edits and non-code tasks is zero to marginal."

The framing sharpened: "natural choice" → "no built-in equivalent"; "while built-ins
remain the natural choice" → "zero to marginal" for the complementary scope. The
asymmetry became more explicit in MCP Steroid's favour.

## Validation

`:prompts:test` + `:ij-plugin:test` green on every iteration where it ran. One
mid-loop regression caught and fixed: iter-02's RenameProcessor recipe used
`writeAction { processor.run() }` where every other refactoring recipe uses
`writeIntentReadAction { processor.run() }` — regression surfaced by
`LspExamplesExecutionTest.testRenameExampleExecutes`, fixed at b37d173d (also
updated the test assertion to match the new recipe heading).

## What's parked

- **Task #4**: apply-patch recipe — research `~/Work/intellij` for
  `ApplyPatchDefaultExecutor`, `GenericPatchApplier`, `CommandProcessor` + multi-VFS
  refresh; implement `prompts/src/main/prompts/ide/apply-patch.md` so agents get a
  single-call path that matches `Edit(old, new)`'s ergonomics for small edits AND
  closes the last structural §1(b) finding. When that lands the "small text edits"
  residual disappears and the loop's convergence is complete.
