# apply-patch iter-02 autoresearch plan

iter-01 gave us:
- `lsp/rename.md` fixed (regex → `RenameProcessor`)
- `ide/apply-patch.md` born (recipe-only, ~90 lines of Kotlin per caller)
- Claude found the `WriteCommandAction.run` deadlock
- Both agents asked for per-hunk line:col audit

iter-02 target: the DSL lands (`applyPatch { hunk(...) }` on `McpScriptContext`).
Agent payload drops from ~90 lines to ~5. Want to validate the DSL shape
directly — not the old recipe body — with both Claude and Codex.

## What changed since iter-01

- New `McpScriptContext.applyPatch { hunk(path, old, new); ... }` — method on
  the script context, auto-available, no imports.
- Recipe (`prompts/src/main/prompts/ide/apply-patch.md`) now shows only the
  data: `applyPatch { hunk(...); hunk(...) }`. The Kotlin body that moved into
  the plugin is plugin-owned, tested, invariant.
- Tool description (`skill/execute-code-tool-description.md`) teases the DSL
  inline so agents discover it on their first `steroid_execute_code` call.
- VFS refresh automatic (awaited pre-compile + fire-and-forget post-exec).

## Evaluator prompt

Same file as iter-01:
`ij-plugin/src/integrationTest/resources/apply-patch/evaluator-prompt.md`.
Still asks for a 5-part review (works-as-advertised / defaults / ergonomics /
failure modes / concrete improvement suggestions).

No prompt changes this round — we want an apples-to-apples comparison to
iter-01. The CHANGE under evaluation is the DSL; the evaluator prompt stays
constant so the delta is unambiguous.

## Execution plan

1. Claude first — `CliClaudeIntegrationTest.testApplyPatchRecipeEvaluation`
2. Codex second (sequential — never two integration tests concurrently)
3. Save both reviews to `docs/autoresearch/apply-patch/iter-02/{claude,codex}-review.md`
4. Diff vs iter-01 reviews — expect:
   - "~90 lines of Kotlin" criticism drops to "~5 lines"
   - "WriteCommandAction.run deadlocks" concern gone (we use Dispatchers.EDT +
     runWriteCommandAction now, or absent from the recipe entirely)
   - line:column audit present in both reviews' success evidence
5. Merge notes → iter-02/merge.md
6. If agents request further changes and they're narrow + safe, apply and
   re-run iter-03. If reviews converge on "looks good", close the loop.

## Success criteria

- Zero "recipe is boilerplate-heavy" complaints.
- Zero write-access / deadlock complaints.
- At least one agent confirms end-to-end execution with a realistic multi-file
  patch (ideally 3+ hunks across 2+ files).
- Both agents' §1 verdicts characterize apply-patch as a category-(a) win.
