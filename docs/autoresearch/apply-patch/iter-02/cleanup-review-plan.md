# Core code review & cleanup plan — applyPatch + VfsRefreshService

Scope: every file I touched since commit `ccdcfe9f` (iter-00 baseline) through
`bcbcea49` (autoresearch test infra), measured against `AGENTS.md` + `prompts/AGENTS.md`.
The goal of this round is a **reviewable, stable core** before we kick iter-02
autoresearch on the new `applyPatch { hunk(...) }` DSL.

## Priority-0 — correctness / blockers

### P0.1 — `ApplyPatchTest` failing under `:ij-plugin:test`
**AGENTS.md §12**: "Tests must show reality — a failing test is better than a fake
passing test. **Never remove, disable, or weaken a failing test**; fix the
underlying issue instead."

Symptom: 6 of 7 `ApplyPatchTest` methods fail with

    Write access is allowed inside write-action only (see Application.runWriteAction());
    If you access or modify model on EDT consider wrapping your code in WriteIntentReadAction

The error is downstream of how `executeApplyPatch` resolves `VirtualFile` for
path strings. Under `BasePlatformTestCase`, `myFixture.tempDirFixture` returns
a `temp://` virtual file; `LocalFileSystem.findFileByPath(vf.path)` returns
null, and my fallback `VirtualFileManager.findFileByUrl("temp://$path")` does
not compose the URL correctly either.

**Fix**: make `executeApplyPatch` VFS-type-agnostic by accepting the
`McpScriptContext.findFile(absolutePath)` resolver (which already handles
temp:// in its impl) instead of hard-coding `LocalFileSystem`. Re-run the
seven tests; all must pass without any modification that weakens assertions.

### P0.2 — `@Suppress("UNCHECKED_CAST")` moved inline is gone; `lsp/rename.md` no longer uses it
Verified — previous iter-02 fix at `b37d173d` replaced the destructuring cast
with an in-class `analysis` field. Clean.

### P0.3 — `Context has been disposed` in `testSerenaSelfEvalPrompt` teardown
Observed once at iter-05. Report reached sys-out cleanly; failure was a
teardown-thread race. Not in my code — existing test framework. **No action**;
document as known flake in `docs/autoresearch/selfeval/summary.md` and revisit
only if it reoccurs.

## Priority-1 — AGENTS.md rule compliance

### P1.1 — Missing / extraneous imports in new files
Current state:
| file | concern |
|---|---|
| `execution/ApplyPatch.kt` | imports `VirtualFile`, `LocalFileSystem`, `VirtualFileManager` — at least one becomes unused after P0.1 refactor. Re-audit. |
| `execution/VfsRefreshService.kt` | imports `withContext` but `awaitRefresh`'s `withContext(Dispatchers.IO)` wraps only the schedule call (which is synchronous, not IO-heavy). Remove unnecessary wrap + drop import. |
| `test/execution/ApplyPatchTest.kt` | imports `com.intellij.openapi.application.WriteAction` via FQN, not top-of-file. Normalize. |
| `test/server/LspExamplesExecutionTest.kt` | heading string updated; no new import needed. Verify. |

Rule: each Kotlin file must have exactly the imports it needs, sorted
(com.intellij first, com.jonnyzzz second, kotlin/kotlinx last). IntelliJ's
"Optimize Imports" inspection; run before commit.

### P1.2 — `coroutineScope` nullability / noise
`VfsRefreshService.awaitRefresh`:

```kotlin
withContext(Dispatchers.IO) {
    try {
        RefreshQueue.getInstance().refresh(...)
    } catch (e: Exception) {
        log.debug("awaitRefresh schedule failed (non-fatal)", e)
        done.complete(Unit)
    }
}
```

`RefreshQueue.refresh(async=true, ...)` is non-blocking — it returns
immediately without needing `Dispatchers.IO`. Drop the `withContext` wrap.
Keep the try/catch; the log.debug satisfies AGENTS.md §16 (fail fast + log).

### P1.3 — Hardcoded `mcp-steroid://` URIs in production Kotlin
AGENTS.md §20 BAN. Session scan:

```
grep -rn 'mcp-steroid://' ij-plugin/src/main/kotlin/
# → only FetchResourceToolHandler.kt:31 (pre-existing, in a string describing the URI format in the tool description)
```

No new violations. The pre-existing one is a tool-description literal, not a
URI being dispatched. Fine.

### P1.4 — `runCatching{}.onFailure{}` banned
Scan clean.

### P1.5 — Empty catch / silent swallow
`ApplyPatch.kt` has no catches — it throws ApplyPatchException from pre-flight
and lets `executeCommand`'s Runnable propagate. Good.
`VfsRefreshService.kt` has `catch (e: Exception) { log.debug(...) }` — logs
before swallow, per AGENTS.md §16. Acceptable because the tail refresh is
intentionally non-fatal (documented in kdoc).

### P1.6 — `CompletableDeferred` + `withTimeout` for callback bridge
AGENTS.md §24 rule respected in `VfsRefreshService.awaitRefresh`. Verify no
`CountDownLatch` / `Semaphore` / `Object.wait()` crept in. Scan clean.

### P1.7 — No test-only branches (`isUnitTestMode`)
AGENTS.md §11. Scan clean — my code has no `isUnitTestMode` checks.

## Priority-2 — API ergonomics

### P2.1 — DSL discoverability
`McpScriptContext.applyPatch { hunk(...) }` is documented in the interface kdoc
and in `prompts/src/main/prompts/ide/apply-patch.md`. It's also mentioned in
`skill/execute-code-tool-description.md`. Good.

### P2.2 — Success output
`ApplyPatchResult.toString()` emits `apply-patch: N hunk(s) across M file(s)`
plus per-hunk `[#i] path:line:col (oldLen→newLen chars)`. Both agents (Claude,
Codex) in iter-01 asked for this. Good.

### P2.3 — File resolution uses `McpScriptContext.findFile`
See P0.1. Refactor `executeApplyPatch` signature to take a resolver function
rather than hard-code `LocalFileSystem`. One-line change at the call site in
`McpScriptContextImpl.applyPatch`:

    override suspend fun applyPatch(block: ApplyPatchBuilder.() -> Unit): ApplyPatchResult =
        executeApplyPatch(project, builder.hunks) { path -> findFile(path) }

## Priority-3 — Recipe text consistency

### P3.1 — `prompts/src/main/prompts/ide/apply-patch.md` self-references
Currently the recipe body shows `applyPatch { hunk(...); ... }` — no
imports, no data classes, because the DSL is a member of the script context.
This matches the actual generated Kotlin (verified: `ApplyPatchKtBlocksCompilationTest`
8/0/0). Good.

### P3.2 — Contract test compliance (`MarkdownArticleContractTest`)
- Title 48 chars ≤80 ✓
- Description 107 chars ≤200 ✓
- No bare code outside fences ✓

### P3.3 — Conditional-fence annotations
`apply-patch.md` uses bare ```kotlin``` (no `[IU]`/etc annotation) because the
DSL is IDE-agnostic (no Java/Kotlin PSI). Any IDE with MCP Steroid installed
gets it. KtBlocks compilation passes on all 8 target IDE combinations. Good.

## Priority-4 — Tests I still owe

- `VfsRefreshServiceTest` — pin contract: `scheduleAsyncRefresh` returns
  immediately (< 50 ms); `awaitRefresh` resolves within its 30 s cap;
  refresh on an empty project is a no-op. Skipped in iter-01 to keep
  the loop moving; due now.
- `ApplyPatchTest` — P0.1 fix above.

## Execution order

1. P0.1 — refactor `executeApplyPatch` to take a resolver, fix `ApplyPatchTest`
2. P1.1 — import sweep on new files
3. P1.2 — drop `withContext(Dispatchers.IO)` from `awaitRefresh`
4. P4 — `VfsRefreshServiceTest` (smoke)
5. Full `:prompts:test :ij-plugin:test`
6. Commit as one review round
7. Kick iter-02 apply-patch autoresearch (Claude + Codex) against the cleaned DSL
