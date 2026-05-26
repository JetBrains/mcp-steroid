Wait for Debugger Suspend

Wait for one suspension via XDebugSessionListener (event-driven). For multi-event monitoring across calls, see `mcp-steroid://debugger/monitor-debug-events`.

This script waits for a **single** suspension within the current `steroid_execute_code` call (breakpoint hit or step completion), then returns. When you need to observe many events across multiple scripts, register a file-based listener via `mcp-steroid://debugger/monitor-debug-events` and read the NDJSON file from outside the IDE.

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebugSessionListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

val debuggerManager = XDebuggerManager.getInstance(project)

// Wait for the debug session to appear — the JVM/runtime process needs time to start.
// ProgramRunnerUtil.executeConfiguration() returns immediately; the session is registered later.
var session = debuggerManager.currentSession
if (session == null) {
    println("Waiting for debug session to start...")
    repeat(40) {
        if (debuggerManager.currentSession != null) return@repeat
        delay(500)
    }
    session = debuggerManager.currentSession
}

if (session == null) {
    // `currentSession == null` can mean two different things — split them
    // so the agent picks the right recovery path:
    //
    //   (a) `debugSessions` IS non-empty → a session exists but is not
    //       the *current* (selected) one. Pick it explicitly and continue
    //       waiting. This happens when multiple debug runs ran in the
    //       same project and selection ended up on a different one.
    //
    //   (b) `debugSessions` is empty → the launcher did not produce any
    //       session. The most common causes are:
    //         - A context-menu action (`DebugClass` / `DebugContextAction`)
    //           was fired with the caret in an ambiguous spot, so IntelliJ
    //           silently showed a "nothing here" popup that the dialog
    //           killer dismissed.
    //         - The configuration was launched with `DefaultRunExecutor`
    //           (Run) instead of `DefaultDebugExecutor` (Debug). Same
    //           symptom — no `XDebugSession` is registered.
    //         - The `JUnitConfiguration` was invalid (no module,
    //           unresolved class). The deterministic path in
    //           `mcp-steroid://debugger/demo-debug-test` calls
    //           `checkConfiguration()` to catch this.
    //
    //   Distinct from above: a session that exists but never suspends —
    //   the 30 s listener timeout below handles that. Recovery there is
    //   to diagnose the breakpoint, not the launcher.
    val candidates = debuggerManager.debugSessions
    if (candidates.isNotEmpty()) {
        session = candidates.first()
        println("Selected non-current session: ${session.sessionName} (was not the current debug selection)")
    } else {
        println("FAILED: no debug session after 20 s — the launcher did not produce one.")
        println("Recovery checklist:")
        println("  1. Did you launch with `DefaultDebugExecutor.getDebugExecutorInstance()` (Debug), not `DefaultRunExecutor` (Run)?")
        println("  2. Did you fire a context-menu action? It silently no-ops when caret is on a keyword. Use the JUnitConfiguration recipe in `mcp-steroid://debugger/demo-debug-test` instead.")
        println("  3. Did `junitConfig.checkConfiguration()` raise an error you swallowed? An invalid module/class also yields a silent no-launch.")
        return
    }
}

// Check if already suspended (handles race where breakpoint was hit before this script runs)
if (session.isSuspended) {
    val frame = session.currentStackFrame
    val pos = frame?.sourcePosition
    val fileName = pos?.file?.name ?: "?"
    val line = (pos?.line ?: -1) + 1
    println("Already suspended at: $fileName:$line")
    return
}

// Event-driven: use XDebugSessionListener + CompletableDeferred
// sessionPaused() fires for both breakpoint hits and step completions
val suspendedDeferred = CompletableDeferred<Unit>()
val listener = object : XDebugSessionListener {
    override fun sessionPaused() {
        suspendedDeferred.complete(Unit)
    }
    override fun sessionStopped() {
        suspendedDeferred.completeExceptionally(Exception("Debug session stopped before suspension"))
    }
}

session.addSessionListener(listener)
try {
    withTimeout(30.seconds) { suspendedDeferred.await() }

    val frame = session.currentStackFrame
    val pos = frame?.sourcePosition
    val fileName = pos?.file?.name ?: "?"
    val line = (pos?.line ?: -1) + 1
    println("Debugger suspended at: $fileName:$line")
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    println("Debugger did not suspend within 30 seconds.")
    println("Session: ${session.sessionName}, stopped: ${session.isStopped}")
} finally {
    session.removeSessionListener(listener)
}
```

## How it works

```kotlin
// How it works:
//
// XDebugSessionListener.sessionPaused() fires when the debugger suspends for ANY reason:
// - Breakpoint hit (process calls session.breakpointReached())
// - Step completion (step-over/step-into/step-out calls session.positionReached())
// - Manual pause (session.pause())
//
// This is more efficient than polling session.isSuspended in a loop —
// the listener fires immediately when suspension occurs, with no delay.
//
// Always check isSuspended BEFORE registering the listener to handle the race
// where the breakpoint was hit between launching the debug session and running this script.
//
// Always handle sessionStopped() to avoid hanging if the debug process terminates.
println("See the code block above for the complete wait-for-suspend pattern")
```

# See also

Related debugger operations:
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Set breakpoint before waiting
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debug session
- [Evaluate Expression](mcp-steroid://debugger/evaluate-expression) - Evaluate variables once suspended
- [Monitor Debug Events](mcp-steroid://debugger/monitor-debug-events) - Watch many events across many calls via a file

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
