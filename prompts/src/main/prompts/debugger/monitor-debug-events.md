Monitor Debug Events to a File

Register debugger listeners that append events to an NDJSON file on disk, so the agent can monitor IDE state from outside `steroid_execute_code` without holding a coroutine.

## Why a file instead of in-script waiting

`steroid_execute_code` calls are short — they return as soon as the script's coroutine ends. If you need to observe **multiple** debugger events across **multiple** scripts (multiple breakpoint hits, breakpoint added/removed, step completions, session lifecycle), keeping a coroutine alive inside one call is wrong: it blocks the call, eats your timeout, and you still cannot see what happens after it returns.

The pattern below registers IntelliJ event listeners that **append** to a file inside `.idea/mcp-steroid/`. The script returns immediately. The agent then reads the file from outside (`Read`, `tail`, `grep`) between or during subsequent operations. The IDE keeps firing events into the file until the listener is disposed.

## Listener lifecycle and classloader leaks

Each `steroid_execute_code` call runs in a **fresh classloader**. A listener object instantiated in that classloader is an anonymous class loaded by it. The IDE holds a strong reference to the listener once registered, which means the classloader stays alive. If the listener is never unsubscribed, the classloader leaks for the rest of the IDE's lifetime.

The script below avoids the leak with three layered cleanup paths — any of them is enough:

1. **Self-expiry on time.** The listener captures `startedAt` and `maxAgeMs`. On every callback it checks the clock; once expired it writes a final `listener-expired` event and disposes itself.
2. **Self-disposal on session stop.** `sessionStopped()` triggers `Disposer.dispose(...)` — there is nothing left to monitor.
3. **Project-scoped parent.** The disposable is registered as a child of `project` via `Disposer.register(project, ...)`, so closing the project disposes it.

Do **not** rely on cross-script disposal by name or by Key lookup — `Key` identity does not survive across classloaders, so a second script cannot find the first script's user-data entry. Make the listener self-terminate.

## Script — register listener, return immediately

```kotlin
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

val maxAgeMs = 10 * 60_000L  // listener self-disposes after 10 minutes

val basePath = project.basePath ?: error("Project basePath is null")
val eventsFile = File(basePath, ".idea/mcp-steroid/debug-events.ndjson")
eventsFile.parentFile?.mkdirs()

val session = XDebuggerManager.getInstance(project).currentSession
    ?: error("No active debug session. Start one first, then register listeners.")

// One parent disposable — owns both the session listener and the breakpoint listener.
// Registered under project so it dies with the project if nothing else cleans it up.
val parent = Disposer.newDisposable("mcp-steroid-debug-events-${System.currentTimeMillis()}")
Disposer.register(project, parent)

val startedAt = System.currentTimeMillis()
val writeLock = Any()

fun appendEvent(type: String, extras: Map<String, String> = emptyMap()) {
    val obj = buildJsonObject {
        put("ts", System.currentTimeMillis())
        put("type", type)
        for ((k, v) in extras) put(k, v)
    }
    synchronized(writeLock) { eventsFile.appendText(obj.toString() + "\n") }
}

fun scheduleDispose() {
    // Dispose on a later UI tick so the listener that triggered cleanup has returned first.
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        Disposer.dispose(parent)
    }
}

fun maybeExpire() {
    if (System.currentTimeMillis() - startedAt > maxAgeMs) {
        appendEvent("listener-expired")
        scheduleDispose()
    }
}

// 1. XDebugSessionListener — paused/resumed/stopped + step events.
val sessionListener = object : XDebugSessionListener {
    override fun sessionPaused() {
        val pos = session.currentStackFrame?.sourcePosition
        val extras = if (pos != null) mapOf("file" to pos.file.name, "line" to (pos.line + 1).toString()) else emptyMap()
        appendEvent("session-paused", extras)
        maybeExpire()
    }
    override fun sessionResumed() {
        appendEvent("session-resumed")
        maybeExpire()
    }
    override fun sessionStopped() {
        appendEvent("session-stopped")
        scheduleDispose()
    }
    override fun stackFrameChanged() {
        appendEvent("stack-frame-changed")
        maybeExpire()
    }
}
session.addSessionListener(sessionListener, parent)

// 2. XBreakpointListener — added/removed/changed on the project bus.
val bpListener = object : XBreakpointListener<XBreakpoint<*>> {
    override fun breakpointAdded(bp: XBreakpoint<*>) {
        appendEvent("breakpoint-added", mapOf("bp" to bp.toString()))
        maybeExpire()
    }
    override fun breakpointRemoved(bp: XBreakpoint<*>) {
        appendEvent("breakpoint-removed", mapOf("bp" to bp.toString()))
        maybeExpire()
    }
    override fun breakpointChanged(bp: XBreakpoint<*>) {
        appendEvent("breakpoint-changed", mapOf("bp" to bp.toString()))
        maybeExpire()
    }
}
project.messageBus.connect(parent).subscribe(XBreakpointListener.TOPIC, bpListener)

appendEvent("listener-registered", mapOf("maxAgeMs" to maxAgeMs.toString()))
println("Listener registered. Events: ${eventsFile.absolutePath}")
println("Auto-disposes after ${maxAgeMs}ms, when the debug session stops, or when the project closes.")
```

## Read the events from the agent side

Between (or during) subsequent `steroid_execute_code` calls, read the file with native tools. Each line is one JSON object; lines never rewrite — they only append.

- Show the latest events: `cat .idea/mcp-steroid/debug-events.ndjson`
- Wait for the next `session-paused` entry while stepping: `tail -f .idea/mcp-steroid/debug-events.ndjson`
- Count breakpoint hits without re-reading old ones: `grep -c session-paused .idea/mcp-steroid/debug-events.ndjson`

The agent can also call a second `steroid_execute_code` script that reads the file via `java.io.File` and acts on the contents — useful when the agent is already mid-flow inside the IDE.

## Force early cleanup

If you need to stop monitoring before the timeout, write a sentinel event marking the session as no-longer-interesting and let `sessionStopped` clean up naturally — call `session.stop()` via `mcp-steroid://debugger/debug-session-control`. The listener disposes itself.

If the previous run leaked a listener (for example a script crashed before disposing), close and reopen the project once — the project-scoped `Disposer.register(project, parent)` parent guarantees cleanup at project close.

## Important constraints

```kotlin
// Constraints — read these before adapting the script:
//
// 1. APPEND-ONLY. Always use eventsFile.appendText(...) (or FileOutputStream(file, append=true)).
//    Never rewrite the file from inside a callback — the agent may be reading it concurrently.
//
// 2. WRITE FROM INSIDE THE CALLBACK. The whole point of this pattern is to do as much work as
//    possible in the event handler — formatting, lookups, JSON encoding — so the agent
//    only has to read final lines. Do NOT push work back into the script that registered
//    the listener; the script has already returned by then.
//
// 3. SYNCHRONIZE WRITES. Multiple listeners can fire on different threads (the breakpoint
//    bus and the session listener are independent). Wrap appendText() in a synchronized
//    block on a shared lock object.
//
// 4. DO NOT DISPOSE FROM INSIDE A CALLBACK SYNCHRONOUSLY. Use invokeLater { Disposer.dispose(parent) }
//    so the listener that triggered disposal has already returned by the time Disposer
//    walks the parent's children.
//
// 5. ONE PARENT DISPOSABLE PER SCRIPT. Don't register listeners with no parent —
//    addSessionListener(listener) without a Disposable can never be unsubscribed
//    across classloader boundaries.
//
// 6. CLASSLOADER REMINDER. The listener object's class is loaded by this script's
//    classloader. The classloader stays alive as long as the IDE holds a reference to
//    the listener instance. Disposing the parent disposable is what breaks that
//    reference — without it, the classloader leaks until the IDE restarts.
println("See script above for the full listener+file pattern")
```

# See also

Related debugger operations:
- [Wait for Suspend](mcp-steroid://debugger/wait-for-suspend) - In-script event-driven wait (single call, single event)
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Stop a session (also triggers listener cleanup)
- [Add Breakpoint](mcp-steroid://debugger/add-breakpoint) - Set breakpoints before listening

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Async monitoring across calls
