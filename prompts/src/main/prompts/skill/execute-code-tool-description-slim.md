Execute Code Tool (slim router)

Compact router variant of the steroid_execute_code tool description, served when the slim variant is selected.

###_NO_AUTO_TOC_###
Execute Kotlin code directly in IntelliJ's runtime with full API access — builds, tests, refactoring, inspections, debugging, navigation.

## Route the task before reaching for a native tool

The IDE path keeps VFS + PSI consistent and one call replaces 3–5 chained native-tool calls. It is not
the answer to every step: an `exec_code` call costs you a hand-written Kotlin script, so it pays where
the work touches the VFS, the PSI, the indexes, or a build — and it does not pay for a one-shot look at
a file you are not about to change.

**Take the fork first.** About to edit, inspect, walk PSI, or search the indexes for this file? Do it
in ONE `exec_code` script and read the file *inside* that script — the read is free once you are there.
Only need to look at a file, with no IDE operation on it next? Native `Read` costs you one line instead
of a script, and nothing goes stale — reading does not write. The staleness rule below is about
`Edit` / `Write`, which must never touch a project file.

Then check the table for the shape you picked. Every row is actionable as written — the URI adds depth,
it is not a prerequisite.

| Task shape | IDE path |
|---|---|
| **Edit a file, any size** | the recipe at the bottom of this description. Never native `Edit` — it writes behind the VFS and leaves PSI stale for every later semantic query. |
| **Two or more edits, one or many files** | ONE script: read + replace each file, pre-check every anchor, then save all inside a single `writeAction { }`. Grouped recipe: `mcp-steroid://skill/execute-code-overview` |
| **An existing unified diff, or literal anchors that keep failing** | the IDE's tolerance-matching patch engine: `mcp-steroid://ide/apply-unified-diff`. Escape hatch for COMPLEX changes only. |
| **Create a file** | `VfsUtil.createDirectoryIfMissing` + `dir.createChildData` + `VfsUtil.saveText`, all in one `writeAction { }` — indexes immediately. `mcp-steroid://skill/execute-code-overview` |
| **Find files by extension or exact name** | `readAction { FilenameIndex.getAllFilesByExt(project, "java", projectScope()) }` or `readAction { FilenameIndex.getVirtualFilesByName("UserService.java", projectScope()) }` — O(1) indexed lookup, not `Bash find` |
| **Read a file you are about to edit, inspect or walk** | in the SAME script, before the change: `val vf = findProjectFile(p) ?: error("not found: $p")`, then `String(vf.contentsToByteArray(), vf.charset)` — relative or absolute paths; re-reads from disk at script top level |
| **Read a file only to look at it** | native `Read` — fewer tokens than typing a script for content you will not act on inside the IDE. Do NOT pre-`Read` a file you are about to change with the recipe below; that read already happens in the script. |
| **Grep content inside project files** | `readAction { }` over `FilenameIndex.getAllFilesByExt(...)` plus `Regex(pat).findAll(...)` — all in ONE call |
| **Find all references to a symbol** | `readAction { ReferencesSearch.search(psiElement, projectScope()).findAll() }` — type-aware; Grep over source text is the fallback, not the default |
| **Run Maven / Gradle tests** | the IDE runner, as a two-call launch-then-poll pattern — a single call is cancelled at ~60 s. `mcp-steroid://skill/execute-code-maven`, `mcp-steroid://skill/execute-code-gradle` |
| **Compile check** | `ProjectTaskManager.getInstance(project).buildAllModules().await()`, then read `hasErrors()` AND `isAborted()`. `errors=false, aborted=true` means the runner never started — NOT a clean build; sync first via the Maven/Gradle article. |
| **Structured build diagnostics** | `mcp-steroid://ide/jps-build-errors` — an empty diagnostics list is not "clean" unless `check_ran=true` |
| **Verify after an edit** | `mcp-steroid://ide/verify-after-edit` |
| **Inspections on a file** | all enabled: `runInspectionsDirectly(file)`. One named inspection plus its quick-fix: `mcp-steroid://ide/inspect-and-fix` |
| **Find duplicate / cloned / copy-paste code (DRY violations)** | a PSI task, NOT a text search — do not start with `grep` / `rg`. `mcp-steroid://ide/find-duplicates`; its PSI body-comparison recipe is the default and needs no warm index. `CLUSTERS_FOUND: 0` from the inspection cross-check alone is ambiguous — not proof that no duplicates exist. |
| **Tabular output (references, hierarchies, search hits, symbols)** | `printCsv(headers, rows, dictColumns = setOf("path"))` or `printToon(value)`. Signatures differ: `printCsv` takes a `headers` list plus parallel `List<Any?>` rows, `printToon` takes one value and is cheapest on a `List<Map<String, Any?>>`. Passing a `List<Map>` to `printCsv` is a common compile error. |
| **Git, Docker, shell** | native `Bash` — genuinely outside the IDE |

## Rules that cost you a turn if you skip them

**Output — the #1 reason agents believe a call "returned empty".** The last expression's value is NOT
auto-printed; this is a script, not a REPL. A script ending in a bare `myList` prints nothing, and the
response looks identical to one that returned no value at all. Always end with an explicit
`println(value)` or `printJson(value)`. For inspection and report tasks print compact `KEY: value`
lines or `printJson` on the FIRST run, so you never spend a second call reshaping verbose IDE output.
Print the slice you will act on, never a whole-source dump: a result large enough to be truncated costs
you the turns you then spend re-reading your own saved tool result.

**Threading — apply preventively, not after an error.** The wrap is required in EVERY new script: the
IDE does not carry over the previous script's coroutine context, so a `readAction { }` in script #1
does not exempt the same call in script #2. Use `readAction { }` to read PSI, walk a PSI tree, call
`FilenameIndex.*` / `ReferencesSearch.*` / `PsiSearchHelper.*`, walk a VFS tree, or read a `Document`;
`writeAction { }` to mutate a VFS file; `writeIntentReadAction { }` to run a refactoring processor —
NOT `writeAction`, which deadlocks. Skipping the wrap throws
`Read access is allowed from inside read-action only` or hangs indefinitely.
`LocalFileSystem.getInstance().findFileByPath(path)` is safe unwrapped; the wrap starts when you read
structure. Full API-to-wrap table: `mcp-steroid://skill/coding-with-intellij-threading`.

**Code shape.** The `code` parameter is a suspend function body — never `runBlocking`. Use `project`
directly; no `context.` prefix exists. Mark your own helpers `suspend fun` when they call suspend APIs.

**Do not invent helpers.** `buildProject()`, `compileProject()`, `createProjectFile()`, `projectDir`,
`findProjectDir()` and a top-level `readText(vf)` do NOT exist; for the project root use
`project.basePath` or `project.guessProjectDir()`. Real names and their replacements:
`mcp-steroid://skill/coding-with-intellij-context-api` → "Real helpers vs invented names".

**Do not reach project files through `java.io` / `java.nio` / `FileReader`,** and never spawn a process
from inside the script. They bypass the VFS, so every later PSI, index and inspection query sees stale
content. Paths the IDE does not model — a docker socket, say — are fine.
`mcp-steroid://skill/execute-code-overview`.

**Do not call daemon-highlighting internals** (`DaemonCodeAnalyzerImpl`, `DaemonProgressIndicator`,
`HighlightingSession`) — they need state a script context does not have; use
`runInspectionsDirectly(file)`. Never `printJson` its raw result, which holds live PSI/VFS references:
snapshot the fields you need inside `readAction { }` and print a DTO, always including
`result.failedTools` — a non-empty `failedTools` means the check is NOT clean even when the findings
map is empty.

**No VFS refresh needed around a call.** The plugin awaits one before compiling your script and fires
an async one after it returns. You do still need
`PsiDocumentManager.getInstance(project).commitAllDocuments()` when the same script writes a file and
then reads it back through PSI.

## In scope, no imports needed

`project`, `readAction`, `writeAction`, `smartReadAction`, `writeIntentReadAction`, `findFile`,
`findProjectFile`, `findProjectFiles`, `findPsiFile`, `findProjectPsiFile`, `runInspectionsDirectly`,
`projectScope`, `allScope`, `waitForSmartMode`, `closeModalDialogs`, `monitorAndCloseModalDialogs`,
`allowModalDialog`, `syncDocuments`, `println`, `printJson`, `printCsv`, `printToon`, `progress`,
`printException`, `takeIdeScreenshot`, `disposable`.

Everything else needs an explicit import: `com.intellij.psi.search.*`, `com.intellij.refactoring.*`,
`com.intellij.task.*` and the non-core `openapi` roots are not auto-imported. `McpScriptContext` will
not grow new helpers — call IntelliJ APIs directly (`mcp-steroid://skill/design-philosophy`).

## Modality (`modal`)

Leave it at the default `smart_non_modal` unless you have a specific reason not to — read-only
navigation included. Per-mode semantics are in this tool's `modal` parameter description; the
finer-grained primitives (`closeModalDialogs()`, `monitorAndCloseModalDialogs()`,
`allowModalDialog()`, `syncDocuments()`, `waitForSmartMode()`) are in
`mcp-steroid://skill/coding-with-intellij-context-api`.

Three things that surprise agents. There is intentionally no "close a mid-run dialog and keep going"
mode — a mid-run modal always fails the call. Under `smart_non_modal` the call can FAIL **before your
script body runs** (the pre-flight gate, or the bounded commit / smart-mode wait hitting its
deadlock-safety timeout, which the `timeout` parameter does not govern) — documented behaviour, not a
bug in your Kotlin. And every modality failure writes a screenshot plus thread dump to the execution's
storage folder and puts the paths in the result text; read those instead of retrying blindly, because
`steroid_take_screenshot` captures *current* state, not the failure state.

## Editing a file in place — any size, 1 to 1000+ lines

```kotlin
val path = "src/main/java/com/example/MyClass.java"   // relative or absolute both work
val vf = findProjectFile(path) ?: error("not found: $path")
val content = String(vf.contentsToByteArray(), vf.charset)  // read
val updated = content.replace("OLD_STRING", "NEW_STRING")
check(updated != content) { "no match for OLD_STRING — widen the anchor" }
writeAction { VfsUtil.saveText(vf, updated) }               // write, VFS refreshes itself
```

Do NOT pre-`Read` the file with the native tool first: `vf.contentsToByteArray()` already covers it,
and the file bytes never cross the MCP boundary this way. For a regex edit use
`Regex(pattern).replace(content, replacement)`; for two or more edits see the routing table above.

**Before your first call on an unfamiliar kind of task, fetch the guide:** building or testing →
`mcp-steroid://prompt/test-skill`; debugging → `mcp-steroid://prompt/debugger-skill`; any IDE task →
`mcp-steroid://prompt/skill`, which carries the full resource index.

💡 Call `steroid_execute_feedback` after execution to rate success.
