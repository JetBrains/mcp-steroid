JPS Build Errors

Run an IDE JPS build and collect structured compiler diagnostics.

`CompilerManager.make(...)` explicitly starts an IDE JPS compilation, including
when IDE Build actions are delegated to Gradle or Maven. This is the
structured-diagnostics companion to `ProjectTaskManager`: the task result flags
say whether a build failed, while `CompileContext` carries the compiler messages.

For Gradle- or Maven-delegated projects, treat this as a fast JPS check, not the
authoritative build. JPS does not reproduce the external build's complete task
graph, plugins, generated-source pipeline, or custom compiler configuration. Run
the matching build-tool task before claiming the delegated build is clean, and
keep the result vocabulary from `mcp-steroid://ide/verify-after-edit`.

```kotlin[AI,IC,IU]
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

val moduleName: String? = null // TODO: set to a module name for a scoped build
val timeout = 5.minutes

data class JpsBuildResult(
    val aborted: Boolean,
    val errors: Int,
    val warnings: Int,
    val context: CompileContext,
)

fun messagePayload(message: CompilerMessage): Map<String, Any?> {
    val descriptor = message.navigatable as? OpenFileDescriptor
    val file = message.virtualFile ?: descriptor?.file
    return mapOf(
        "category" to message.category.name,
        "path" to file?.path,
        "line" to descriptor?.line?.plus(1),
        "column" to descriptor?.column?.plus(1),
        "message" to message.message,
        "moduleNames" to message.moduleNames.toList(),
        "renderTextPrefix" to message.renderTextPrefix,
        "exportTextPrefix" to message.exportTextPrefix,
    )
}

val compilerManager = CompilerManager.getInstance(project)
val modules = ModuleManager.getInstance(project).modules
val targetModule = moduleName?.let { name ->
    modules.firstOrNull { it.name == name }
}
if (moduleName != null && targetModule == null) {
    printJson(
        mapOf(
            "status" to "did_not_run",
            "check_ran" to false,
            "target" to moduleName,
            "message" to "Module was not found.",
        )
    )
    return
}

val scope = if (targetModule != null) {
    compilerManager.createModuleCompileScope(targetModule, true)
} else {
    compilerManager.createProjectCompileScope(project)
}

val finished = CompletableDeferred<JpsBuildResult>()
withContext(Dispatchers.EDT) {
    // IU-261 check: CompilerManager.make is public/stable and the wrapper itself
    // does not expose a direct thread assertion; initiate it from the EDT to match
    // the IDE build action path, then await completion off the EDT below.
    compilerManager.make(
        scope,
        object : CompileStatusNotification {
            override fun finished(aborted: Boolean, errors: Int, warnings: Int, compileContext: CompileContext) {
                finished.complete(JpsBuildResult(aborted, errors, warnings, compileContext))
            }
        }
    )
}

val result = withTimeoutOrNull(timeout) { finished.await() }
if (result == null) {
    printJson(
        mapOf(
            "status" to "check_failed",
            "check_ran" to true,
            "target" to (moduleName ?: "project"),
            "message" to "JPS build did not finish within $timeout.",
        )
    )
    return
}

val diagnostics = result.context.getMessages(CompilerMessageCategory.ERROR)
    .map(::messagePayload)
val warningCount = result.context.getMessageCount(CompilerMessageCategory.WARNING)

val status = when {
    result.aborted -> "check_failed"
    diagnostics.isNotEmpty() || result.errors > 0 -> "errors"
    else -> "clean"
}

printJson(
    mapOf(
        "status" to status,
        "check_ran" to true,
        "target" to (moduleName ?: "project"),
        "aborted" to result.aborted,
        "errorCount" to result.errors,
        "warningCount" to warningCount,
        "diagnostics" to diagnostics.take(100),
        "diagnosticsTruncated" to (diagnostics.size > 100),
    )
)
```

Pitfalls:

- `ProjectTaskManager.Result` is not a diagnostic container. It gives success,
  error, and aborted flags; it does not aggregate `file:line:message`.
- This recipe uses public compiler APIs: `CompilerManager.make(...)`,
  `CompileStatusNotification`, `CompileContext.getMessages(...)`, and
  `CompilerMessage`. It does not cast to compiler implementation classes.
- `CompilerMessage` exposes file and message publicly. Line and column are only
  available when `message.navigatable` is a public `OpenFileDescriptor`; keep
  them nullable.
- `CompilerManager.make(...)` always requests a JPS compilation; IDE delegation
  settings do not turn this call into a Gradle or Maven build. Its
  `CompileContext` diagnostics are valid for that JPS run, but the result can
  differ from the external build because its task graph, plugins, generated
  sources, and compiler configuration are not necessarily reproduced. Use the
  corresponding build-tool runner as the final authority and return the same
  `status` / `check_ran` shape.

# See also

- [Verify After Edit](mcp-steroid://ide/verify-after-edit) - Canonical verification status vocabulary.
- [Execute Code: Gradle Patterns](mcp-steroid://skill/execute-code-gradle) - Gradle runner and failure-tail patterns.
- [Execute Code: Maven Patterns](mcp-steroid://skill/execute-code-maven) - Maven runner patterns.
