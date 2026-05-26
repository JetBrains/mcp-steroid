Remove Line Breakpoint

Remove all breakpoints at a specific file/line.

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import java.nio.file.Paths

val filePath = "src/main/kotlin/com/example/MyClass.kt"
val lineNumberInEditor = 49  // TODO: Set your value

val projectRoot = project.basePath ?: error("Project basePath is null")
val absolutePath = Paths.get(projectRoot, filePath).toString()
val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
    ?: error("File not found: $absolutePath")

val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
val lineIndex = lineNumberInEditor - 1

// Find and remove all breakpoints at this line
val removed = breakpointManager.allBreakpoints
    .filterIsInstance<XLineBreakpoint<*>>()
    .filter { it.fileUrl == virtualFile.url && it.line == lineIndex }

if (removed.isEmpty()) {
    println("No breakpoint at $filePath:$lineNumberInEditor")
} else {
    // `removeBreakpoint` does not assert write-intent the way
    // `addLineBreakpoint` does, but the breakpoint platform's
    // presentation-update listeners can route through Kotlin PSI on
    // removal — the WIRA wrap is precautionary and symmetric with the
    // add recipe. Safe to drop if a future change proves the wrap
    // unnecessary at this site.
    writeIntentReadAction {
        removed.forEach { breakpointManager.removeBreakpoint(it) }
    }
    println("Removed ${removed.size} breakpoint(s) at $filePath:$lineNumberInEditor")
}
```

```kotlin
// Important notes:
// - fileUrl is VirtualFile.getUrl() format (e.g., "file:///path/to/File.java")
// - Line numbers are 0-indexed in the API (editor line 7 = API line 6)
// - Default breakpoints (like "Any Exception") are just disabled, not actually removed
println("See code block above for the complete remove-breakpoint pattern")
```

# See also

Related debugger operations:
- [Add Breakpoint](mcp-steroid://debugger/add-breakpoint) - Add breakpoint idempotently
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Combined add/remove reference
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Stop debug session

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
