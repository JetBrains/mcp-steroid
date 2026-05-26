Control Debug Session

Pause/resume/stop the current debug session.

> **Session-control APIs require EDT.** `session.pause()`, `session.resume()`,
> `session.stepOver(...)`, `session.stop()` all dispatch through Swing's
> event queue under the hood; calling them from a `steroid_execute_code`
> body throws `Access is allowed from Event Dispatch Thread (EDT) only`
> at runtime. Always wrap in `withContext(Dispatchers.EDT) { … }` (the
> recipes below do this for you). The same rule applies to step recipes
> at `mcp-steroid://debugger/step-over` and to
> `XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() }`
> used for cleanup.

```kotlin
import com.intellij.xdebugger.XDebuggerManager

val session = XDebuggerManager.getInstance(project).currentSession
    ?: error("No debug session. Start one first.")

println("Session:", session.sessionName, "suspended:", session.isSuspended)

withContext(Dispatchers.EDT) {
    session.pause()
}
println("Pause requested. Suspended:", session.isSuspended)

withContext(Dispatchers.EDT) {
    session.resume()
}
println("Resume requested. Suspended:", session.isSuspended)

// session.stop() will terminate the debugged process.
```

# See also

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs

Related test operations:
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
