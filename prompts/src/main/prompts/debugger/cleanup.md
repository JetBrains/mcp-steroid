Debugger Cleanup After Verification

Remove temporary breakpoints and delete temporary debug run configurations once the bug is confirmed.

After a debug session has answered the question you opened it for —
breakpoint hit, evaluation done, behavior confirmed — leave the project
in the same state you found it. Temporary breakpoints accumulate in the
project breakpoint table and persist across IDE restarts via
`.idea/workspace.xml`; temporary `JUnitConfiguration` instances clutter
the "Recent" run-config dropdown. The recipe below covers both removals
in one script.

## Remove temporary breakpoints + delete a debug run configuration

```kotlin[IU]
import com.intellij.execution.RunManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

// 1. Stop any still-running debug sessions on this configuration BEFORE
//    deleting the run config or breakpoints. Stopping requires EDT (see
//    `mcp-steroid://debugger/debug-session-control`).
val sessionNameToStop = "Debug-MyTest"  // TODO: name set by your launch recipe
withContext(Dispatchers.EDT) {
    XDebuggerManager.getInstance(project).debugSessions
        .filter { it.sessionName == sessionNameToStop && !it.isStopped }
        .forEach { it.stop() }
}

// 2. Remove the line breakpoints you added during this run. Address them
//    by file URL + 0-indexed line so the cleanup is idempotent across
//    reruns and survives the IDE having reordered the breakpoint list.
val cleanupTargets = listOf(
    // (fileRelativePath, editorLineNumber)  ← 1-based, as it appears in the gutter
    "src/main/kotlin/com/example/MyClass.kt" to 94,
)

val basePath = project.basePath ?: error("Project basePath is null")
val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager

// `removeBreakpoint` itself does not assert write-intent, but the
// breakpoint platform's presentation-update listeners can route through
// Kotlin PSI on removal. WIRA here is precautionary and symmetric with
// the matching add recipe.
var removedCount = 0
for ((relPath, editorLine) in cleanupTargets) {
    val absolutePath = "$basePath/$relPath"
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: continue
    val lineIndex = editorLine - 1

    val matches = readAction {
        breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .filter { it.fileUrl == virtualFile.url && it.line == lineIndex }
    }
    if (matches.isEmpty()) continue
    writeIntentReadAction {
        matches.forEach { breakpointManager.removeBreakpoint(it) }
    }
    removedCount += matches.size
}
println("Removed $removedCount temporary breakpoint(s)")

// 3. Delete the temporary debug run configuration. RunManager owns the
//    settings; remove via `removeConfiguration(...)`. Idempotent.
val runManager = RunManager.getInstance(project)
val tempConfigNames = listOf("Debug-MyTest")  // TODO: names your launch recipe created
val deleted = tempConfigNames.mapNotNull { name ->
    runManager.allSettings.firstOrNull { it.name == name }?.also { runManager.removeConfiguration(it) }
}
println("Deleted ${deleted.size} run configuration(s): ${deleted.map { it.name }}")
```

## When to skip cleanup

- The breakpoints / run configurations are part of the project's
  long-lived debug toolbox (e.g. a configuration committed to
  `.idea/runConfigurations/`). In that case, leave them alone — the
  cleanup recipe is for *temporary* debug scaffolding you created
  inside this `steroid_execute_code` session.
- The debug session is still running and you intend to come back to it
  in a subsequent script. Cleanup will stop the session.

## Companion artifacts

If you registered a file-based listener via
[`mcp-steroid://debugger/monitor-debug-events`](mcp-steroid://debugger/monitor-debug-events)
during the run, that listener self-disposes on `sessionStopped` but the
NDJSON file (`.idea/mcp-steroid/debug-events.ndjson`) stays on disk.
Delete it as part of the cleanup when the verification is done:

```kotlin
import java.nio.file.Files
import java.nio.file.Path

val ndjson = Path.of(project.basePath ?: ".", ".idea", "mcp-steroid", "debug-events.ndjson")
if (Files.exists(ndjson)) {
    Files.delete(ndjson)
    println("Deleted debug-events.ndjson")
}
```

# See also

Related debugger operations:
- [Add Breakpoint](mcp-steroid://debugger/add-breakpoint) - The matching add recipe
- [Remove Breakpoint](mcp-steroid://debugger/remove-breakpoint) - Standalone breakpoint removal
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause/resume/stop
- [Demo Debug Test](mcp-steroid://debugger/demo-debug-test) - End-to-end recipe that creates the artifacts this script cleans up
- [Monitor Debug Events](mcp-steroid://debugger/monitor-debug-events) - File-based listener whose NDJSON artifact this recipe optionally deletes

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
- [Debugger Overview](mcp-steroid://debugger/overview) - All debugger examples
