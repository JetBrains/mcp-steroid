Evaluate Expression at Breakpoint

Evaluate a variable or expression when the debugger is suspended at a breakpoint.

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.intellij.xdebugger.impl.ui.tree.nodes.XValuePresentationUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import javax.swing.Icon
import kotlin.time.Duration.Companion.seconds

// --- Reusable helper: copy this into your scripts ---

/**
 * Evaluates a debugger expression and returns its formatted string value.
 *
 * Uses CompletableDeferred to bridge two async callback APIs:
 * 1. XDebuggerEvaluator.evaluate() -> XValue via XEvaluationCallback
 * 2. XValue.computePresentation() -> formatted text via XValueNode callback
 *
 * IMPORTANT: Do NOT await value.isReady before computePresentation.
 * In Rider/DotNetValue, isReady only completes INSIDE computePresentation's async
 * coroutine — awaiting it first causes a 30-second timeout deadlock that crashes
 * the MCP server. The retry loop handles JVM "Collecting data..." cases instead.
 *
 * IMPORTANT: All callback overrides use block bodies { }, NOT expression bodies.
 * Expression bodies like `= deferred.complete(value)` cause type mismatch errors
 * because CompletableDeferred.complete() returns Boolean, not Unit.
 */
suspend fun eval(
    evaluator: XDebuggerEvaluator,
    expr: String,
    pos: com.intellij.xdebugger.XSourcePosition? = null,
    timeout: Long = 30
): String {
    // Phase 1: Evaluate expression -> XValue
    val valueDeferred = CompletableDeferred<XValue>()
    evaluator.evaluate(
        XExpressionImpl.fromText(expr),
        object : XDebuggerEvaluator.XEvaluationCallback {
            // IMPORTANT: Use block body { }, not expression body =
            override fun evaluated(value: XValue) { valueDeferred.complete(value) }
            override fun errorOccurred(msg: String) { valueDeferred.completeExceptionally(Exception(msg)) }
        },
        pos
    )
    val value = try {
        withTimeout(timeout.seconds) { valueDeferred.await() }
    } catch (e: Exception) {
        return "ERR: ${e.message}"
    }

    // Phase 2: Get formatted text via computePresentation callback.
    // Do NOT await value.isReady first — in Rider/DotNetValue, isReady only completes
    // INSIDE this call's async coroutine, so awaiting it first deadlocks for 30 seconds.
    // The retry loop handles JVM "Collecting data..." cases instead.
    val presDeferred = CompletableDeferred<String>()
    value.computePresentation(object : XValueNode {
        override fun setPresentation(icon: Icon?, type: String?, text: String, hasChildren: Boolean) {
            presDeferred.complete(text)
        }
        override fun setPresentation(icon: Icon?, pres: XValuePresentation, hasChildren: Boolean) {
            presDeferred.complete(XValuePresentationUtil.computeValueText(pres))
        }
        override fun setFullValueEvaluator(e: XFullValueEvaluator) {}
        override fun isObsolete() = false
    }, XValuePlace.TOOLTIP)

    val result = try {
        withTimeout(timeout.seconds) { presDeferred.await() }
    } catch (e: Exception) {
        return "ERR: Presentation timeout - ${e.message}"
    }

    // Phase 3: Retry if "Collecting data..." (async JDI value loading in JVM debugger)
    if (result.contains("Collecting data")) {
        repeat(10) {
            delay(200)
            val retry = CompletableDeferred<String>()
            value.computePresentation(object : XValueNode {
                override fun setPresentation(icon: Icon?, type: String?, text: String, hasChildren: Boolean) {
                    retry.complete(text)
                }
                override fun setPresentation(icon: Icon?, pres: XValuePresentation, hasChildren: Boolean) {
                    retry.complete(XValuePresentationUtil.computeValueText(pres))
                }
                override fun setFullValueEvaluator(e: XFullValueEvaluator) {}
                override fun isObsolete() = false
            }, XValuePlace.TOOLTIP)
            val text = try { withTimeout(5.seconds) { retry.await() } } catch (_: Exception) { return result }
            if (!text.contains("Collecting data")) return text
        }
    }
    return result
}
// --- End of helper ---

val session = XDebuggerManager.getInstance(project).currentSession
    ?: error("No debug session. Start one first.")
check(session.isSuspended) { "Session is not suspended. Wait for breakpoint hit." }

val frame = session.currentStackFrame ?: error("No current stack frame")
val evaluator = frame.evaluator ?: error("No evaluator for current frame")
val pos = frame.sourcePosition

// Evaluate variables at the current breakpoint
val playersValue = eval(evaluator, "players", pos)
println("players =", playersValue)

val sizeValue = eval(evaluator, "players.size", pos)
println("players.size =", sizeValue)

// Evaluate complex expressions
// NOTE: Use the language of the project being debugged:
//   Kotlin/Java: players.sortedByDescending { it.score }
//   C#/.NET:     players.OrderByDescending(p => p.Score).ToList()
val sortedValue = eval(evaluator, "players.sortedByDescending { it.score }", pos)
println("sorted result =", sortedValue)
```

## Kotlin / K2 evaluation caveats

Kotlin evaluation goes through the source-level PSI evaluator, but the
**compiled JDI frame** is the limiting factor — if the source-level
receiver (`this@OuterClass`) isn't bound to a slot in the current frame,
the evaluator cannot reach it. What works reliably vs what may fail:

- ✅ **Local variables** of the current frame (`event`, `distinctId`, etc.) — work.
- ✅ **Simple static / companion calls** (`ProxyVersionMetadata.getProxyVersion()`) — work. For Java-static lookup from Kotlin, spell the companion explicitly (`MyClass.Companion.method()`) or use the FQN.
- ✅ **`ph.javaClass.name`-style reflective lookups on locals** — work.
- ⚠ **Receiver / outer-property access** (`homePaths.home.resolve(...)`,
   `this@MyClass.field`) — may fail with
   `Cannot find local variable 'this@MyClass'`. The three usual triggers:
   - **Lambda body** frames — the source receiver may be elided in the
     compiled lambda class.
   - **Inline-function call sites** — locals are inlined into the caller
     and original receiver bindings disappear.
   - **`suspend` continuation frames** — `this` lives in a generated
     `$this` field on the continuation object, not in a JDI local slot.

Recovery for the receiver-access case:
- Capture the receiver into a local one line above the breakpoint
  (`val outer = this@MyClass`) and evaluate via the local. Most reliable.
- Move the breakpoint one line *up* into a frame where the receiver is
  bound as a normal local — the calling frame, or before the inline
  expansion. Suspend continuation frames usually have a parent frame
  where `this` is still a regular local; step out one level.

## Common mistakes to avoid

```kotlin
// Common mistakes to avoid:
//
// 1. Expression body type mismatch:
//    BAD:  override fun evaluated(value: XValue) = valueDeferred.complete(value)
//    GOOD: override fun evaluated(value: XValue) { valueDeferred.complete(value) }
//    complete() returns Boolean; the override expects Unit. With -Werror this is a hard error.
//
// 2. Awaiting value.isReady BEFORE computePresentation (Rider deadlock):
//    BAD:  value.isReady.await()       // blocks forever in Rider!
//          value.computePresentation(...)
//    GOOD: value.computePresentation(...)   // triggers isReady completion in Rider
//          // retry loop handles JVM "Collecting data..." cases
//    In Rider/DotNetValue, readyFuture.complete() is called INSIDE computePresentation's
//    async coroutine. Awaiting isReady first deadlocks for 30 seconds.
//    The retry loop in eval() already handles JVM placeholder text.
//
// 3. Wrong XValuePresentation import:
//    WRONG: com.intellij.xdebugger.frame.XValuePresentation
//    RIGHT: com.intellij.xdebugger.frame.presentation.XValuePresentation
//
// 4. Scope after stepping:
//    After step-over, old XValue instances are invalidated. Get fresh evaluator
//    from session.currentStackFrame after each step. Variables from the calling
//    scope may not be accessible if the debugger is inside a different method —
//    use `this.fieldName` to access instance state at method boundaries.
println("See eval() helper above for correct implementation")
```

# See also

Related debugger operations:
- [Wait for Suspend](mcp-steroid://debugger/wait-for-suspend) - Wait for breakpoint hit before evaluating
- [Step Over](mcp-steroid://debugger/step-over) - Step to next line and re-evaluate
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause, resume, stop

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
- [Debugger Overview](mcp-steroid://debugger/overview) - All debugger examples
