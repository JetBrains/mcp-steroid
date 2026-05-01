Execute Code Tool

MCP tool description for the steroid_execute_code tool.

###_NO_AUTO_TOC_###
Execute Kotlin code directly in IntelliJ's runtime with full API access — builds, tests, refactoring, inspections, debugging, navigation.

## 🛑 STOP before the 2nd native `Edit` — use `steroid_apply_patch`

If you are about to make similar edits across **two or more files** (same pattern, different paths), **do not chain `Edit` calls**. Use the dedicated `steroid_apply_patch` MCP tool instead of wrapping the patch in `steroid_execute_code`:

Required shape: `project_name`, `task_id`, optional `reason`, and `hunks`, where each hunk has `file_path`, `old_string`, and `new_string`. Use absolute file paths. A 3-hunk call is three hunk objects, not three native `Edit` calls.

The dedicated tool uses the same atomic patch engine as the script-context `applyPatch` DSL, but it bypasses kotlinc compilation. Large multi-file patches complete in tens of ms instead of spending a full `steroid_execute_code` compile cycle.

Pre-flight catches missing or non-unique anchors before any edit lands, so keep `old_string` to the shortest unique signature (30–60 chars usually — no need for the full 300-char safety block). Native `Edit` chains bypass the VFS, leave PSI stale, and cost one tool call per site.

**Heuristic**: before the 2nd `Edit` in the same task, stop and ask: "Am I applying the same or similar change to 2+ sites?" If yes, use `steroid_apply_patch`. Use the older script-context `applyPatch` DSL only when the patch must run inside the same `steroid_execute_code` script as surrounding IntelliJ API work.

## Decision tree — pick the IDE path before reaching for a native tool

| Task shape | One-line IDE call |
|---|---|
| **Two or more literal-text edits, same or different files** | `steroid_apply_patch` — atomic undo, pre-flight validation, PSI commit, no kotlinc compile cycle. Use whenever an `Edit`/`Edit`/`Edit` chain is tempting. |
| **One literal-text edit, single file** | `val vf = findProjectFile(p)!!; writeAction { VfsUtil.saveText(vf, String(vf.contentsToByteArray(), vf.charset).replace(OLD, NEW)) }` |
| **Find files by extension** | `readAction { FilenameIndex.getAllFilesByExt(project, "java", projectScope()) }` — not `Bash find … -name "*.java"` |
| **Find files by exact name** | `readAction { FilenameIndex.getVirtualFilesByName("UserService.java", projectScope()) }` |
| **Find all references to a symbol** | `readAction { ReferencesSearch.search(psiElement, projectScope()).findAll() }` — type-aware; Grep over source text is a fallback |
| **Read file content (any size)** | `String(findProjectFile(p)!!.contentsToByteArray(), charset)` — stays inside the IDE; the next semantic query sees what you read |
| **Grep content inside project files** | `readAction { FilenameIndex.getAllFilesByExt(project, ext, scope).flatMap { vf -> Regex(pat).findAll(String(vf.contentsToByteArray(), vf.charset)) … } }` in ONE call |
| **Run Maven / Gradle tests** | IDE runner — see `mcp-steroid://skill/execute-code-maven` and `mcp-steroid://skill/execute-code-gradle`; Bash is only for shell-level final verification or IDE-runner fallback |
| **IDE build aborted (`errors=false, aborted=true`)** | Fetch `mcp-steroid://skill/execute-code-gradle` or `mcp-steroid://skill/execute-code-maven` and run the matching sync pattern before Bash fallback. |
| **Compile check after an edit** | `ProjectTaskManager.getInstance(project).buildAllModules().await()` |
| **Git / Docker CLI / shell** | native `Bash` — genuinely outside the IDE |

If your next instinct is a native `Read` / `Edit` / `Grep` / `Glob` / `Bash` call, check this table first. The IDE path keeps VFS + PSI consistent, reuses the warm JVM, and one call reliably replaces 3-5 chained native-tool calls.

**Before your first call, read the guide for your task** with `steroid_fetch_resource`:
- Building/testing → `mcp-steroid://prompt/test-skill`
- Debugging → `mcp-steroid://prompt/debugger-skill`
- Any IDE task → `mcp-steroid://prompt/skill`

**Quick Start:**
- Code is a suspend function body (never use runBlocking)
- `waitForSmartMode()` runs automatically
- Available: `project`, `println()`, `printJson()`, `progress()`

**Output rules — the #1 reason agents think a call "returned empty":**
- The last expression's value is NOT auto-printed (this is a Kotlin script, not a REPL).
- To surface anything to the caller, wrap it in `println(value)` for plain text or `printJson(value)` for structured data.
- A script that ends with `myList` (or any bare expression) prints nothing — you will see only `execution_id: …` in the response, identical to a script that returned no value at all. Always end with an explicit `println(...)` or `printJson(...)` of what the agent needs to see.

**Threading rules — apply preventively, not after an error:**

The wrap is required on EVERY new script — the IDE forgets the previous script's coroutine context. A `readAction { }` block in script #1 does not exempt the same API call in script #2.

| You are about to… | Wrap the call in… |
|---|---|
| Read any PSI element / walk a PSI tree / navigate references | `readAction { }` |
| Use `FilenameIndex.*` (`getAllFilesByExt`, `getVirtualFilesByName`, `processAllFileNames`) | `readAction { }` |
| Use `PsiSearchHelper.*`, `ReferencesSearch.*`, `ClassInheritorsSearch.*` | `readAction { }` |
| Walk a VFS tree — `vf.children`, `vf.parent`, `vf.findChild(name)`, recursive `walk { }` | `readAction { }` |
| Resolve `PsiManager.getInstance(project).findFile(vf)` / `findDirectory(vf)` and read `psiFile.text` / `firstChild` / `name` | `readAction { }` |
| Get a `Document` from a `VirtualFile` via `FileDocumentManager.getInstance().getDocument(vf)` and read its text | `readAction { }` |
| Read `ProjectRootManager.contentRoots` / `ModuleRootManager.*` / `LibraryTable.*` | `readAction { }` |
| Touch `ChangeListManager.allChanges` / VCS model | `readAction { }` |
| Write to a VFS file (`VfsUtil.saveText`, `vf.setBinaryContent`) | `writeAction { }` |
| Invoke a refactoring processor's `.run()` (Rename / Move / SafeDelete / Inline / ChangeSignature / Extract*) | `writeIntentReadAction { }` — NOT `writeAction`; the processor manages its own actions internally, and `writeAction` deadlocks |
| Commit pending document edits to PSI | `writeAction { PsiDocumentManager.getInstance(project).commitAllDocuments() }` (usually as the line *after* the refactor) |
| Use a `CommandProcessor.executeCommand { … }` block (undo-grouping) | put the command inside the appropriate read/write action — `executeCommand` itself is *not* an action |

A correctly-wrapped call produces the right result on the first try. An incorrectly-wrapped call throws `Read access is allowed from inside read-action only` or hangs indefinitely — both waste a retry turn.

`LocalFileSystem.getInstance().findFileByPath(path)` itself is safe outside `readAction { }` — it just resolves the `VirtualFile`. The wrap is required as soon as you start reading the file's *structure* (children, document, PSI) or accessing the PSI of any other model.

**Inside `steroid_execute_code` always go through the IntelliJ API.** The following are NOT correct shortcuts — they bypass the IDE's VFS, leave subsequent semantic queries (PSI, indexes, inspections) seeing stale content, and are explicitly out of scope for this tool:

- `java.io.File("…").walk()` / `listFiles()` / `exists()` — use `FilenameIndex.*` (or `LocalFileSystem.findFileByPath` + `vf.children` inside `readAction { }`).
- `java.nio.file.Files.*`, `Path.toFile()`, `Files.walk(...)` — same reason, same replacement.
- Spawning external processes from inside the script (`ProcessBuilder("…").start()`, `Runtime.exec(...)`) — banned in `steroid_execute_code` for classpath/lock-isolation reasons.
- Reading file content via `FileReader` / `BufferedReader.readText()` — use `String(vf.contentsToByteArray(), vf.charset)` so the IDE's VFS stays the source of truth.

The correct shape for "list test files" inside `steroid_execute_code`:

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

val testFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("/core/src/test/") && it.name.endsWith("Test.java") }
        .map { it.path }
        .take(20)
        .toList()
}
println(testFiles.joinToString("\n"))
```

**Compile check** (use after every edit — do NOT use `./mvnw compile`):

```kotlin
import com.intellij.task.ProjectTaskManager
import org.jetbrains.concurrency.await

val result = ProjectTaskManager.getInstance(project).buildAllModules().await()
println("Compile errors: ${result.hasErrors()}, aborted: ${result.isAborted()}")

// If aborted == true and errors == false, the IDE build runner did not start.
// In Maven/Gradle projects, first fetch mcp-steroid://skill/execute-code-gradle
// or mcp-steroid://skill/execute-code-maven and run Sync + Observation.awaitConfiguration(project).
// Use Bash only if sync fails or times out.
```

**Run tests via the IDE runner, not Bash.** `./mvnw test` / `./gradlew test` cold-start ~31 s per invocation. The IDE runner keeps the JVM warm and returns structured pass/fail:

```kotlin[IU]
// Maven — single test class or method via the IDE's Maven runner:
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.RunManager

val cfg = MavenRunConfigurationType.getInstance().configurationFactories.single()
    .createTemplateConfiguration(project) as org.jetbrains.idea.maven.execution.MavenRunConfiguration
cfg.name = "Run PetRestControllerTests"
cfg.runnerParameters.workingDirPath = project.basePath!!
cfg.runnerParameters.goals = listOf("test", "-Dtest=PetRestControllerTests", "-Dspotless.check.skip=true")
val settings = RunManager.getInstance(project).createConfiguration(cfg, cfg.factory!!)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
```

For deeper patterns (SMTRunner listeners that block until tests finish + emit structured JSON results) fetch `mcp-steroid://skill/coding-with-intellij-spring`. Bash `./mvnw test` is only OK as a last-resort when the IDE runner has genuinely failed for the scenario.

**After a compile error**: fix and retry. Common fixes:
- `suspension functions can only be called within coroutine body` → mark helper as `suspend fun`
- `unresolved reference` → add missing import
- `Write access is allowed from write thread only` → wrap in `writeAction { }`
- `Read access is allowed from inside read-action only` → wrap in `readAction { }`

**File discovery**: `readAction { FilenameIndex.getAllFilesByExt(project, ext, projectScope()) }` or `readAction { FilenameIndex.getVirtualFilesByName(name, projectScope()) }` inside `steroid_execute_code` — O(1) indexed lookup over the same VFS your next write will touch. The `readAction { }` wrap is mandatory; without it the call throws `Read access is allowed from inside read-action only` and the script aborts.
**File reading**: `String(findProjectFile(relPath)!!.contentsToByteArray(), charset)` inside `steroid_execute_code` — single call, stays inside the IDE so PSI is consistent if you read the same file again later. The native `Read` tool is a valid alternative but imposes the Read-before-Edit contract only it tracks; staying inside `steroid_execute_code` avoids that coupling entirely.
**In-place file editing (ANY size, 1–1000+ lines)**: use steroid_execute_code — do NOT use the native `Edit` tool. The native `Edit` writes to disk bypassing IntelliJ, leaving VFS + PSI stale; every following semantic query sees the old content until you force a refresh. The IDE-side recipe below is ~5 lines of real code, same payload shape as `Edit(old, new)`, reads+writes inside one call, and the VFS auto-refreshes so PSI stays consistent:

```kotlin
val vf = findProjectFile("src/main/java/com/example/MyClass.java")!!
val content = String(vf.contentsToByteArray(), vf.charset)  // read
val updated = content.replace("OLD_STRING", "NEW_STRING")
check(updated != content) { "no match for OLD_STRING — verify with Grep first" }
writeAction { VfsUtil.saveText(vf, updated) }               // write + VFS refresh
```

For exactly-one-occurrence replace: `.replace(OLD, NEW).also { check(… == 1 occurrence) }`. For regex: `Regex(pattern).replace(content, replacement)`. Do NOT pre-Read the file via the native tool before using this recipe — the `vf.contentsToByteArray()` read already covers that.

**Two or more edits in one or more files**: use the dedicated `steroid_apply_patch` MCP tool. It applies N literal-text substitutions as one undoable command with all-or-nothing pre-flight validation and PSI commit, without compiling a Kotlin script. Read `mcp-steroid://skill/apply-patch-tool-description` for the JSON schema and semantics.

The older script-context `applyPatch` DSL inside `steroid_execute_code` is a fallback only when you need the patch to run in the same script as surrounding IntelliJ API operations.

**VFS refresh before and after every call.** MCP Steroid schedules two refreshes for you:
- **Before** kotlinc compiles your script, the plugin **awaits** a `VfsUtil.markDirtyAndRefresh` on the project root so the compiler sees every on-disk change made by a peer process or the previous call. Blocking, capped at 30 s.
- **After** your script returns — from a `finally` block, so this runs on success AND failure paths — the plugin fires a non-blocking async refresh. The MCP response returns immediately; the next semantic query sees the up-to-date state on the `RefreshQueue` thread.

You do **not** need to schedule VFS refresh yourself. You still need `PsiDocumentManager.getInstance(project).commitAllDocuments()` inside your script if the same script both writes and reads back PSI — the tail auto-refresh runs _after_ your script finishes.

**Payload accounting for this recipe.** The `steroid_execute_code` tool input carries only the Kotlin **script source** (typically ~200–400 chars for an in-place edit — 5 lines of code + a path + OLD/NEW strings). The file bytes that `vf.contentsToByteArray()` reads and the `updated` content that `saveText(vf, updated)` writes live inside the IDE JVM and never cross the MCP boundary — do NOT double-count them against the payload budget. For a 1-line change in a 160-line file, the `Edit` tool ships old_string + new_string (~60 bytes) and the recipe ships ~300 bytes of script — roughly 5× on the script itself, but you save the otherwise-required pre-Read (~3600 bytes for that 160-line file) and keep the IDE's VFS consistent. Net payload is **smaller**, not larger.

💡 Call `steroid_execute_feedback` after execution to rate success.
