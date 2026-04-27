# iter-01 analysis → iter-02 plan

## Did iter-01's edit land?

Yes. Claude iter-01 §3.9:
> Built-in Edit is strictly simpler and equivalent for single-file rename.
> *The MCP Steroid LSP rename template does not use semantic rename.*

And §3.10:
> Could use IntelliJ's `RenameRefactoring` processor **(not exposed in the provided template**,
> but accessible via `RefactoringFactory.getInstance(project).createRename(element, newName)`).

Iter-01 correctly named `RefactoringFactory.createRename` from our *skill* guide and
called out that the *LSP template* does not route to it. The iter-01 prompt edit
(quick-reference rows) succeeded at its narrow goal — small-edit / private-rename
are now correctly classed as built-in territory. But Claude found a stronger,
adjacent defect: the `lsp/rename.md` recipe is itself text-based (`Regex("""\b${name}\b""") + document.replaceString`),
i.e. a degenerate implementation of `textDocument/rename`.

## Top gap for iter-02

The `lsp/rename.md` recipe teaches the wrong semantics. A fix to the recipe is
higher-leverage than any wording change in the skill guide, because the recipe
is what `steroid_fetch_resource` returns when the agent asks "how do I do an
LSP rename?" — it directly drives the call chain the agent will execute.

## iter-02 edit (one narrow change)

Replace the body of `prompts/src/main/prompts/lsp/rename.md` with a
`RenameProcessor`-based recipe:

1. Resolve the offset to a `PsiNamedElement` via `PsiTreeUtil.getParentOfType`
   or `reference.resolve()` — the pattern already used in the skill-guide
   refactoring snippet.
2. Construct `RenameProcessor(project, named, newName, false, false)`.
3. Use `processor.findUsages()` for the dry-run preview (semantic, through the
   type system — lists every real reference, not textual matches).
4. On apply, call `processor.run()` inside `writeAction { }` — atomic under
   `CommandProcessor`.
5. Keep the same public inputs (`filePath`, `line`, `column`, `newName`,
   `dryRun`) so agents who already use the recipe need no adaptation beyond
   the fact that the behaviour is now correct.
6. Explicit fallback note: when no `PsiNamedElement` resolves at the position
   (plain text, unsupported language), the recipe prints a diagnostic and tells
   the agent to use `Edit(replace_all=true)` — this aligns with the iter-01
   quick-reference routing.

## What we expect iter-02 to change

- §3.9 / §3.10: Claude should no longer describe the LSP template as text-based.
  Cross-file rename value should now be observable from a single resource fetch,
  not from cross-referencing the skill guide.
- §7 (unique capabilities): "atomic cross-file rename" should strengthen as
  a category-(a) win because the routing is now a one-fetch answer.
- §4 (token efficiency): Kotlin-script overhead discussion should still flag
  the small-edit crossover but stop misclassifying rename as text-only.

## Risk / tradeoff

The new recipe is longer (~85 lines vs the prior ~107) and uses slightly more
intricate PSI idioms. But the semantic improvement is strictly larger than the
extra parsing cost — every call either succeeds end-to-end or emits a clear
fallback diagnostic.
