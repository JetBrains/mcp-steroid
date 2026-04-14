Coding with IntelliJ: McpScriptContext API Reference

Full API reference for McpScriptContext: project, output methods, read/write actions, search scopes, file helpers, and IDE utilities.

## McpScriptContext API Reference

The `McpScriptContext` is the receiver (`this`) of your script body. It provides access to the project, output methods, and utility functions.

**Source**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt`](../../kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt)

### Core Properties

```kotlin
// Core properties available on McpScriptContext (this):
//   project: Project         — IntelliJ Project instance
//   params: JsonElement       — Original tool execution parameters (JSON)
//   disposable: Disposable   — Parent Disposable for resource cleanup
//   isDisposed: Boolean      — Check if context is disposed
println("Project: ${project.name}, disposed: $isDisposed")
```

### Output Methods

```kotlin
// Output methods:
println("Hello", "World")               // Print space-separated values
printJson(mapOf("key" to "value"))       // Serialize to pretty JSON (uses Jackson)
progress("Processing step 1...")         // Report progress (throttled to 1/sec)
val execId = takeIdeScreenshot()         // Capture IDE screenshot, returns execution_id
try { error("demo") } catch (e: Exception) { printException("oops", e) } // Report error without failing
```

### Built-in Read/Write Actions (NO IMPORTS NEEDED!)

```kotlin
// Built-in read/write actions (no imports needed):
val text = readAction { "read under lock" }         // Execute under read lock (PSI/VFS reads)
writeAction { /* modify PSI/VFS under write lock */ }  // Execute under write lock
val smart = smartReadAction { "smart + read" }       // Wait for smart mode + read action
println("read=$text, smart=$smart")
```

**Important**: These are **built-in** - you do NOT need to import `readAction` or `writeAction` from IntelliJ Platform!

### Built-in Search Scopes (NO IMPORTS NEEDED!)

```kotlin
// Built-in search scopes (no imports needed):
val projScope = projectScope()  // Project files only (no libraries)
val everything = allScope()     // Project + libraries
println("Project scope: $projScope")
```

### File Access Helpers

```kotlin
// File access helpers:
val vf = findFile("/tmp/test.txt")                   // VirtualFile by absolute path
val psi = findPsiFile("/tmp/test.txt")                // PsiFile by absolute path (suspend)
val projFile = findProjectFile("build.gradle.kts")    // VirtualFile relative to project
val projPsi = findProjectPsiFile("build.gradle.kts")  // PsiFile relative to project (suspend)
println("file=$vf, psi=$psi, projFile=$projFile, projPsi=$projPsi")
```

> **⚠️ `findProjectFile()` pitfall for resource files**: This function requires the **full relative path** from the project root (e.g., `"src/main/resources/application.properties"`). Calling it with just a filename (`findProjectFile("application.properties")`) **always returns null** — causing NPE on `!!`. For files under `src/main/resources/`, use `FilenameIndex.getVirtualFilesByName()` which searches by filename without requiring the full path:
```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val scope = GlobalSearchScope.projectScope(project)
val appProps = readAction {
    FilenameIndex.getVirtualFilesByName("application.properties", scope)
        .firstOrNull { it.path.contains("src/main/resources") }
} ?: error("application.properties not found in src/main/resources")
println(String(appProps.contentsToByteArray(), appProps.charset))
```

### IDE Utilities

```kotlin
// IDE utilities:
waitForSmartMode()                        // Wait for indexing (called automatically before script)
doNotCancelOnModalityStateChange()        // Disable auto-cancel on modal dialogs

// Check if editor highlighting has completed for a file:
val buildFile = findProjectFile("build.gradle.kts")
if (buildFile != null) {
    println("Highlighting done: ${isEditorHighlightingCompleted(buildFile)}")

    // Inspections on a file (RECOMMENDED — works regardless of window focus):
    val problems = runInspectionsDirectly(buildFile) // Map<toolId, List<ProblemDescriptor>>
    for ((tool, descs) in problems) {
        println("$tool: ${descs.size} problems")
    }
}
```

### Daemon Analysis & Highlights

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import kotlin.time.Duration.Companion.seconds

val file = findProjectFile("src/Main.kt") ?: error("File not found")

// Open file in editor (required for daemon analysis)
withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(file, true)
}

// Wait for editor highlighting to complete (default timeout: 30s)
val completed = waitForEditorHighlighting(file, timeout = 30.seconds)
println("Highlighting completed: $completed")

// Get highlights (warnings/errors) when daemon finishes
// NOTE: may return stale results if IDE window is not focused — use runInspectionsDirectly() instead
val highlights = getHighlightsWhenReady(file)
highlights.forEach { info ->
    println("${info.severity}: ${info.description}")
}
```

### File Discovery by Glob Pattern

```kotlin
// Find project files matching a glob pattern (relative to project root)
val kotlinFiles = findProjectFiles("**/*.kt")
println("Found ${kotlinFiles.size} Kotlin files")

val testFiles = findProjectFiles("src/test/**/*Test.kt")
testFiles.forEach { println(it.path) }
```

### Disposable Hierarchy

The context provides a `disposable` property for resource cleanup:
```kotlin
import com.intellij.openapi.util.Disposer

// Access the execution's parent Disposable
val execDisposable = disposable

// Register your own cleanup
val myResource = Disposer.newDisposable("my-resource")
Disposer.register(execDisposable, myResource)

// myResource will be disposed when execution completes (success, error, or timeout)
```

---
