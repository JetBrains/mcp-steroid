# apply-patch autoresearch iter-01 — merge & changes

Two parallel reviews (saved at `claude-review.md`, `codex-review.md`) evaluating
the initial `mcp-steroid://ide/apply-patch` recipe.

## Convergent findings — applied

1. **Richer success output**. Both agents flagged the original
   `N hunks across M file(s)` message as weak auditing: it doesn't tell the
   caller WHICH sites were rewritten. Claude suggested line numbers; Codex
   suggested matched-snippet or offsets. Applied: capture `(line, column)` for
   every hunk inside the pre-flight read action, emit
   `[#i] path:line:column  (oldLen→newLen chars)` per hunk. One extra line
   per hunk of output, zero extra tool calls.

2. **Uniqueness-check rationale**. Both agents confirmed the strictness is
   correct for an atomic patcher. No change; updated the "Why Design B"
   section in the recipe to credit the autoresearch round.

3. **Descending-offset ordering** confirmed correct by both. No change.

## Claude-only finding — applied (critical)

4. **`WriteCommandAction.run { }` deadlocks when EDT is blocked.** Observed
   silently in the CliClaudeIntegrationTest harness. Fix: switch the write
   phase from

       WriteCommandAction.writeCommandAction(project).run<Exception> { … }

   to the suspend-friendly

       writeAction {
           CommandProcessor.getInstance().executeCommand(project, { … }, commandName, null)
           PsiDocumentManager.getInstance(project).commitAllDocuments()
       }

   `writeAction { }` dispatches through the coroutine's own thread pool,
   doesn't re-enter the EDT, so there's no re-entry deadlock. The command
   name is preserved so the undo stack still reads "apply-patch (N hunks)".

## Codex-only findings — deferred

Codex suggested two additions:

- Bootstrap fixture for empty projects. **Deferred** — the fixture limitation
  that made Codex's run inconclusive is environmental; adding a bootstrap to
  the production recipe would bloat it for every real user to help one test
  harness. Document-only alternative: add a note to the autoresearch README
  explaining that evaluations benefit from a seeded project.
- `try/catch { printException(…) }` debug variant. **Deferred** — agents
  already see stack traces on failure via MCP Steroid's ExceptionCaptureService;
  a wrapper adds noise to the recipe without new observability. Re-evaluate if
  iter-02 shows agents struggling to diagnose failures.

## Test-extractor bug (caught mid-iteration)

The Kotlin assertion used `.substringAfter("APPLY_PATCH_REVIEW_START")` which
returns content after the FIRST occurrence. The preamble we hand the agent
contains an example marker pair, so the first pair is the preamble echo, not
the agent's actual review.

Fix: switch to `substringAfterLast` / `substringBeforeLast`. Applied to both
`testApplyPatchRecipeEvaluation` and (defensively) the equivalent extractor in
`testSerenaSelfEvalPrompt`.

## Summary

| Change | Driver |
|---|---|
| write via `writeAction { CommandProcessor.executeCommand }` | Claude §1, §5 — deadlock observation |
| per-hunk line:column in success output | Claude §5, Codex §2 — audit trail |
| capture line/column under same read action | Claude §2 — recipe ergonomics |
| `substringAfterLast` in test | test infrastructure — caught while analysing Codex's failed assertion |

No API changes. No deferred work opens new issues. Next iter-02 run (both
agents) should see no deadlock signal and richer success output.
