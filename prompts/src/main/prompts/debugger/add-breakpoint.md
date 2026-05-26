Add Line Breakpoint (Idempotent)

Add a line breakpoint at a specific statement inside a method body — fast, scoped, recommended.

## Pick the right breakpoint location

**Always set a line breakpoint on a specific statement inside the method body.** This is by far the cheapest stopping primitive — IntelliJ creates a single JDI `BreakpointRequest` scoped to one bytecode location.

Avoid two patterns that look convenient but cost runtime performance:

1. **Method breakpoints (entry/exit of a whole method).** IntelliJ implements these via JDI `MethodEntryRequest` / `MethodExitRequest`, which are JVM-wide event filters that fire on **every** method entry/exit and are then post-filtered by IntelliJ. The IDE itself surfaces this as the warning *"Method breakpoints may dramatically slow down debugging"* (`JavaDebuggerBundle.method.breakpoints.slowness.warning`). If you want to stop on entry to `foo()`, place a line breakpoint on the first executable statement inside `foo()` — same observation point, orders of magnitude cheaper.

2. **Whole-line breakpoint when several statements share a line.** A line breakpoint stops at the line's **first** executable instruction. For Kotlin/Java lines like `collection.map { it.someMethod().plus(42) }` the JVM has multiple positions on the same source line:
   - `collection.map { ... }` — the outer call site (whole-line position)
   - `it.someMethod().plus(42)` — inside the lambda body
   These need different stopping locations. Use `mcp-steroid://debugger/add-inline-breakpoint` to choose a specific *variant* (lambda body, conditional return, etc.) instead of accepting the default whole-line position.

The script below handles the simple case: one statement per line, plain method body.

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import java.nio.file.Paths

val filePath = "src/main/kotlin/com/example/MyClass.kt"
val lineNumberInEditor = 49  // TODO: Set your value (a statement inside the method body)

val projectRoot = project.basePath ?: error("Project basePath is null")
val absolutePath = Paths.get(projectRoot, filePath).toString()
val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
    ?: error("File not found: $absolutePath")

val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
val lineIndex = lineNumberInEditor - 1  // API is 0-indexed

// Find the correct breakpoint type for this file+line (Java, Kotlin, C#, etc.)
val breakpointType = readAction {
    XDebuggerUtil.getInstance().lineBreakpointTypes
        .firstOrNull { it.canPutAt(virtualFile, lineIndex, project) }
} ?: error("No breakpoint type available for $filePath:$lineNumberInEditor")

// Cast to star-projection — works for ALL IDEs (Java, Kotlin, C#/Rider, etc.)
// Do NOT cast to Nothing? — Rider uses DotNetLineBreakpointProperties, not Void
@Suppress("UNCHECKED_CAST")
val bpType = breakpointType as XLineBreakpointType<XBreakpointProperties<*>>

// Check if breakpoint already exists (idempotent — safe to call repeatedly).
// `findBreakpointsAtLine` reads a lock-guarded breakpoint table, not PSI;
// it does not require a `readAction` wrap.
val existing = breakpointManager.findBreakpointsAtLine(bpType, virtualFile, lineIndex)
if (existing.isNotEmpty()) {
    println("Breakpoint already exists at $filePath:$lineNumberInEditor")
    println("Breakpoint:", existing.first())
} else {
    // Property creation reads PSI/VFS for the target line → `readAction`.
    val properties = readAction { bpType.createBreakpointProperties(virtualFile, lineIndex) }
    // `addLineBreakpoint` is the contract the breakpoint platform's Kotlin
    // listeners assert. The fix the issue reported (#53 gap 1) is to call
    // it under `writeIntentReadAction` — bare `readAction` fails the
    // write-intent assertion the Kotlin breakpoint type emits during
    // install, and `writeAction` is the wrong contract for this site.
    val breakpoint = writeIntentReadAction {
        breakpointManager.addLineBreakpoint(bpType, virtualFile.url, lineIndex, properties)
    }
    println("Created breakpoint at $filePath:$lineNumberInEditor")
    println("Breakpoint:", breakpoint)
}
```

```kotlin
// WARNING: Do NOT use toggleLineBreakpoint for "ensure breakpoint exists".
// toggleLineBreakpoint is a TOGGLE — it REMOVES an existing breakpoint if present.
// The find-then-add pattern above is idempotent and always safe.
//
// - Line numbers are 0-indexed in the API (editor line 7 = API line 6)
// - addLineBreakpoint does NOT deduplicate — always check with findBreakpointsAtLine first
// - In Rider, breakpoints are registered asynchronously via the RD protocol
println("See code block above for the complete idempotent add-breakpoint pattern")
```

# See also

Related debugger operations:
- [Add Inline Breakpoint](mcp-steroid://debugger/add-inline-breakpoint) - Choose a specific variant when several statements share a line (lambda body, conditional return)
- [Remove Breakpoint](mcp-steroid://debugger/remove-breakpoint) - Remove breakpoints from a line
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Combined add/remove reference
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debug session

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
