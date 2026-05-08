IDE: Find Duplicate Code
[IU]
Run the bundled `DuplicatedCode` inspection across the project and walk each clone cluster (main + duplicates) with typed access — no reflection, no `setAccessible(true)`.

# When to use this

Whenever an agent is asked to "find and refactor duplicate code", "extract a common helper for repeated logic", or "scan for clones". The `DuplicatedCode` inspection is the right tool: it is bundled in IntelliJ IDEA Ultimate, runs on Java, Kotlin, Python, Groovy, JavaScript, Ruby, and other supported languages via the same `DuplicateProblemDescriptor` payload, and the descriptor exposes a public `getTextClone()` getter so a script can enumerate every clone cluster typed.

**Pick println vs printJson before you start.** The base recipe ends with `println` for human-readable cluster reports. If you're an agent piping the result into a follow-up step (count check, file-hit assertion, summary generation), jump straight to the **Structured output (printJson)** section below — same dedup, machine-readable shape, no second exec_code pass to reshape verbose output.

**Clusters can be intra-file or cross-file.** Two methods inside one class with the same body are reported the same way as a method in file A duplicating a method in file B. **And the inspection emits the same logical cluster N times** (once per fragment-as-`main`), so a 2-fragment pair surfaces twice — the recipe deduplicates by hashing the unordered set of `(path:startLine-endLine)` ranges. Skip the dedup and your `CLUSTERS_FOUND` count is roughly N× too large.

**Line numbers are 1-based.** `TextFragment.lines.first` and `TextFragment.lines.last` are 1-based and ready to show to a user (the IDE does `getLineNumber(offset) + 1` internally). `path:startLine-endLine` lines you print are clickable in IDE/editor consoles without conversion.

**`.kt` vs `.kts`.** A Kotlin/Gradle project usually has both — `.kt` for source and `.kts` for build scripts. The recipe scans whichever extensions you put in `targetExtensions`. If the user asks about *source* files, leave `.kts` out; for a full project audit, add it.

**Source-only path filter** — if your build pollutes `src/` with generated files or you want to skip tests, narrow with the `pathFilter` lambda already wired into the recipe. Common variants:

- Production source only: `pathFilter = { it.contains("/src/main/") }`
- Source + tests: `pathFilter = { "/src/main/" in it || "/src/test/" in it }`
- Skip `build/` and other generated trees: `pathFilter = { "/build/" !in it && "/.gradle/" !in it }`

> **Before submitting the recipe, ensure `steroid_execute_code` is callable in your session.** If your client lazy-loads MCP tool schemas (e.g. Claude Code's deferred tools), call `ToolSearch` (or the equivalent schema-load step for your client) for `mcp__mcp-steroid__steroid_execute_code` first. Without the schema loaded the call will fail with `InputValidationError` and you will lose a turn.

# Why direct typed access works (no reflection needed)

`steroid_execute_code` compiles your Kotlin against every loaded plugin's classloader files (`ScriptClassLoaderFactory.ideClasspath()` flattens `descriptor.pluginClassLoader.files` for every loaded plugin and its content modules). In IDEA Ultimate the duplicates-detector module (`intellij.platform.duplicatesDetector.jar`) is bundled, so `com.jetbrains.clones.DuplicateProblemDescriptor`, `com.jetbrains.clones.structures.TextClone`, and `com.jetbrains.clones.structures.TextFragment` are all on the script classpath. **Import them directly and cast.** Do **not** look the class up via `JavaPsiFacade` — that queries the *user project's* classpath, where the plugin classes never live, and a `null` result there is a known false negative that has historically led agents into reflection.

# The recipe (copy-paste)

Submit this as a single `steroid_execute_code` call. Adjust `targetExtensions` to whatever your project uses. Everything else is fully self-contained.

```kotlin[IU]
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.PairProcessor
import com.jetbrains.clones.DuplicateInspection
import com.jetbrains.clones.DuplicateProblemDescriptor
import com.jetbrains.clones.structures.TextClone
import com.jetbrains.clones.structures.TextFragment

data class CloneRange(val path: String, val startLine: Int, val endLine: Int)
data class CloneCluster(val main: CloneRange, val duplicates: List<CloneRange>)

fun TextFragment.toRange() = CloneRange(file.path, lines.first, lines.last)

// Adjust to your task. The recipe reports file:line ranges only — to also capture
// the duplicated source text, see "Reporting the duplicated source text" below.
// For unfamiliar polyglot projects, run the extension-probe snippet (under
// "Discovering which file types exist") first to see what to scan.
val targetExtensions = listOf("java", "kt", "py")
val maxClustersToReport = 20
// Sane default: skip generated trees that are usually full of noise. Widen to `{ true }`
// for a full audit, or narrow to e.g. `{ "/src/main/" in it }` for production-only.
val pathFilter: (String) -> Boolean = { "/build/" !in it && "/.gradle/" !in it && "/out/" !in it }

val scope = GlobalSearchScope.projectScope(project)
val files = readAction {
    targetExtensions.flatMap { ext -> FilenameIndex.getAllFilesByExt(project, ext, scope) }
        .filter { pathFilter(it.path) }
}
println("Scanning ${files.size} file(s) for clones")

val wrapper = LocalInspectionToolWrapper(DuplicateInspection())

// IMPORTANT — `DuplicatedCode` emits ONE descriptor per cluster *per file containing
// its main fragment*. A 2-fragment cluster surfaces twice — once with fragment A as
// `main` and B as a duplicate, then with the roles swapped (this happens for both
// cross-file pairs AND for two methods inside the same file). Deduplicate before
// reporting or you will over-count.
val seenKeys = mutableSetOf<String>()
val clusters = mutableListOf<CloneCluster>()

for (vf in files) {
    if (clusters.size >= maxClustersToReport) break
    val perFile = readAction {
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@readAction emptyList<CloneCluster>()
        val raw: List<ProblemDescriptor> = InspectionEngine.inspectEx(
            listOf(wrapper),
            psiFile,
            psiFile.textRange,
            psiFile.textRange,
            false,
            false,
            true,
            EmptyProgressIndicator(),
            PairProcessor<LocalInspectionToolWrapper, Any> { _, _ -> true },
        ).values.flatten()

        raw.filterIsInstance<DuplicateProblemDescriptor>().mapNotNull { dpd ->
            val tc: TextClone = dpd.textClone
            val main = tc.main.toRange()
            val dups = tc.duplicates.map { it.toRange() }
            // Cluster identity = unordered set of all fragments. Sort the ranges, then join.
            val key = (listOf(main) + dups)
                .map { "${it.path}:${it.startLine}-${it.endLine}" }
                .sorted()
                .joinToString("|")
            if (seenKeys.add(key)) CloneCluster(main = main, duplicates = dups) else null
        }
    }
    clusters += perFile
}

println("CLUSTERS_FOUND: ${clusters.size}")
clusters.forEachIndexed { i, c ->
    println("Cluster #${i + 1} (${c.duplicates.size + 1} occurrences)")
    println("  main ${c.main.path}:${c.main.startLine}-${c.main.endLine}")
    c.duplicates.forEach { d -> println("  dup  ${d.path}:${d.startLine}-${d.endLine}") }
}
```

# Structured output (printJson)

For pipelines or follow-up code that needs to consume the result programmatically, swap the trailing `println` block for a single `printJson` call. Stable shape: `clusterCount`, `clusters[].occurrences`, `clusters[].fragments[].{path, startLine, endLine}`. Same dedup logic as the base recipe — only the final emission changes.

```kotlin[IU]
// Drop into the base recipe — replaces the trailing println loop. The local data classes
// here mirror the ones in the base recipe; **delete the data class and `val clusters`
// stubs below when merging** — they exist only so this block stands alone for KtBlock
// compilation. The real `clusters` comes from the base recipe.
data class CloneRange(val path: String, val startLine: Int, val endLine: Int)
data class CloneCluster(val main: CloneRange, val duplicates: List<CloneRange>)
val clusters: List<CloneCluster> = emptyList()  // populated by the base recipe

val payload: Map<String, Any> = mapOf(
    "clusterCount" to clusters.size,
    "clusters" to clusters.map { c ->
        mapOf(
            "occurrences" to (c.duplicates.size + 1),
            "fragments" to (listOf(c.main) + c.duplicates).map { r ->
                mapOf("path" to r.path, "startLine" to r.startLine, "endLine" to r.endLine)
            },
        )
    },
)
printJson(payload)
```

# Reporting the duplicated source text

`CloneRange` only carries path + line numbers — useful for navigation, not for
showing the user *what* is duplicated. When the task is "summarize the
duplication for a human", read the snippet from the `Document` while you still
hold the read action:

```kotlin[IU]
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.jetbrains.clones.structures.TextFragment

fun TextFragment.snippet(maxChars: Int = 300): String {
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: return ""
    return range.substring(doc.text).take(maxChars)
}
// inside the same readAction { } that owns the descriptor:
//   val text = tc.main.snippet()
```

Combine with the recipe above: replace `CloneRange` with a richer record that
also stores `snippet`, and call `tc.main.snippet()` / `it.snippet()` while the
read action is still open. (Touching `Document` outside `readAction { }` will
throw `ReadAccessException` — see `mcp-steroid://skill/coding-with-intellij-threading`.)

When the user is asking *what* is duplicated (not just *where*), include the
snippet of each cluster's `main` fragment in your reply so they can judge
relevance without opening every file. For pure counting / triage tasks the
file:line markers from the base recipe are enough — skip the snippet helper.

# Discovering which file types exist in the project

The default `targetExtensions = listOf("java", "kt", "py")` is a sensible
starting point. For an unfamiliar project, ask the IDE which extensions are
actually present before running the recipe at scale:

```kotlin[IU]
import com.intellij.psi.search.GlobalSearchScope

val byExtCount = readAction {
    com.intellij.psi.search.FilenameIndex
        .processAllFileNames({ true }, GlobalSearchScope.projectScope(project), null)
}
// or simpler — just probe the candidates you care about:
val present = listOf("java", "kt", "kts", "py", "groovy", "js", "ts", "rb")
    .associateWith { ext ->
        readAction {
            com.intellij.psi.search.FilenameIndex
                .getAllFilesByExt(project, ext, GlobalSearchScope.projectScope(project)).size
        }
    }
println(present.filterValues { it > 0 })
```

Use the non-empty extensions to populate `targetExtensions` so the inspection
loop visits files the project actually has. For an autonomous run where you
want a single round-trip, inline the probe into the main recipe — replace the
hard-coded `targetExtensions` with the result of the probe, then continue with
the inspection loop unchanged.

# How it works

- `DuplicateInspection` is a `LocalInspectionTool` (`shortName = "DuplicatedCode"`, registered with `runForWholeFile="true"`). Per-file `checkFile` looks up a `DuplicateScopeExtension` for the file's language, queries the project-wide `HashFragmentIndex`, and emits a `DuplicateProblemDescriptor` for each clone where the inspected file holds the cluster's `main` fragment.
- **Same logical cluster surfaces multiple times.** A 2-fragment cluster is reported twice — fragment A as `main` + B as duplicate, then B as `main` + A as duplicate. An N-fragment cluster appears N times, once per fragment-as-`main`. The recipe deduplicates by hashing the unordered set of `(path:startLine-endLine)` ranges. Skip the dedup and your `CLUSTERS_FOUND` count is roughly 2× too large.
- `maxClustersToReport` caps **unique** clusters (post-dedup) — the `seenKeys` guard ensures the loop's break runs against deduped count, not raw descriptor count.
- `DuplicateProblemDescriptor.getTextClone()` returns a `TextClone(main: TextFragment, duplicates: List<TextFragment>)`. `TextFragment` exposes `file: VirtualFile`, `range: TextRange`, and `lines: IntRange` — everything you need to render `path:startLine-endLine` and pull the snippet text from the document.
- Indexing must be ready. The script's bootstrap calls `waitForSmartMode()` automatically; if you trigger any reindexing in the same call, await `Observation.awaitConfiguration(project)` before the inspection runs.
- **Runtime scales with project size.** A small fixture finishes in 2-5 s; a 500-file project takes 30-60 s; multi-thousand-file monorepos can take 2-5 minutes (the `HashFragmentIndex` query inside `checkFile` is project-wide). For large projects bump `steroid_execute_code` `timeout` (default 600 s, registry-configurable via `mcp.steroid.execution.timeout`) and narrow `pathFilter` to a target subtree.

# Language coverage (Java, Kotlin, Python, ...)

The same `DuplicatedCode` inspection works across every language that registers a `duplicateScope` extension:

| Language | Module providing the scope | IDE that bundles it |
|----------|---------------------------|---------------------|
| Java     | `intellij.java.duplicatesDetection`     | IDEA Ultimate |
| Kotlin   | `intellij.kotlin.duplicate.scope`       | IDEA Ultimate (Kotlin plugin) |
| Python   | `intellij.python.duplicatesDetection`   | PyCharm Pro / IDEA Ultimate with Python plugin |
| Groovy   | `intellij.groovy.duplicatesDetection`   | IDEA Ultimate |
| JS / TS  | `intellij.javascript.duplicatesDetection` | WebStorm / IDEA Ultimate |
| XML      | `intellij.xml.duplicatesDetection`      | IDEA Ultimate |
| Ruby     | `intellij.ruby.duplicatesDetection`     | RubyMine |

If `DuplicateScopeExtension.findDuplicateScope(fileType)` returns `null` for the file's language, `checkFile` returns no problems — the file is silently skipped. No language-specific code change to the script above is required; just adjust `targetExtensions` to whatever the project uses.

# When the direct import does not compile

The recipe's typed imports work in **IDEA Ultimate, PyCharm Pro, RubyMine, and other commercial IDEs that bundle the duplicates-detector module** (the bulk of agent traffic). If `steroid_execute_code` reports `unresolved reference: DuplicateProblemDescriptor`, the module isn't loaded in the running IDE — most likely a Community / EAP build, or an unusual configuration. The fix is **not** reflection. Try in this order:

1. **Just retry without any preflight.** The `steroid_execute_code` script classpath is built from every loaded plugin's classloader files, and the module's classes appear there as soon as the IDE has loaded the plugin. The first compile failure can be transient (e.g. you fired the call before the IDE finished loading after a fresh restart).
2. **Confirm the module is loaded.** Use the "Find Plugin by ID" recipe in `mcp-steroid://skill/coding-with-intellij-patterns`. The relevant module is `com.intellij.modules.duplicatesDetector`. If it shows as loaded, the typed import will work — there is nothing to declare.
3. **`required_plugins` — last resort, with caveat.** `required_plugins` runs a preflight check that *can reject the module-style ID format* `com.intellij.modules.duplicatesDetector`. Try the typed import without `required_plugins` first; only if the class is genuinely missing AND the module isn't loaded should you declare it — and even then, expect to discover the right ID format empirically (the preflight error will name accepted IDs). Do not assume one specific value will pass.
4. **Do not switch to reflection.** Private-field renames silently break next IDE release. The public `getTextClone()` getter is the answer.

> **Reflection is for exploration, not for the recipe you ship.** If you reached for `Class.getDeclaredField("myTextClone")` + `setAccessible(true)` to extract the clone pair, stop — that path is brittle (private-field renames in the next IDE release silently break the script) and unnecessary. The public `getTextClone()` getter and the typed `import` above are the right answer. See the reflection-policy note in `mcp-steroid://skill/coding-with-intellij`.

# See also

- [Inspection + Quick Fix](mcp-steroid://ide/inspect-and-fix) — single-file inspection + quick fix pattern this recipe extends
- [Inspection Summary](mcp-steroid://ide/inspection-summary) — list inspections enabled in the project
- [Apply Patch](mcp-steroid://ide/apply-patch) — atomic multi-site edits, the natural follow-up after locating a clone cluster
- [Common Patterns](mcp-steroid://skill/coding-with-intellij-patterns) — cross-classloader fallback and find-by-extension recipes used above
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) — top-level skill index
