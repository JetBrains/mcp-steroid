Execute Code: Gradle Patterns

Running Gradle syncs and tests through IntelliJ ExternalSystem APIs instead of nested ProcessBuilder.

# Execute Code: Gradle Patterns

Use this resource when a `steroid_execute_code` script must run Gradle work from inside IntelliJ. For simple final verification from an agent shell, the Bash tool can run `./gradlew` directly. Inside `steroid_execute_code`, never spawn a nested Gradle process with `ProcessBuilder("./gradlew")`; use IntelliJ's Gradle ExternalSystem APIs.

## Sync after build.gradle.kts Change

After modifying `build.gradle`, `build.gradle.kts`, or `settings.gradle.kts`, trigger a Gradle re-import and wait for final import tasks before compiling, running tests, or using indexed PSI:

```kotlin[IU]
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlin.time.Duration.Companion.minutes

val gradleProjectPath = project.basePath ?: error("Project base path is not set")
val importDone = CompletableDeferred<Unit>()
val importConnection = project.messageBus.connect(disposable)

fun isCurrentGradleProject(path: String?): Boolean =
    path == null || path == gradleProjectPath

importConnection.subscribe(
    ProjectDataImportListener.TOPIC,
    object : ProjectDataImportListener {
        override fun onFinalTasksFinished(projectPath: String?) {
            if (isCurrentGradleProject(projectPath)) {
                importDone.complete(Unit)
            }
        }

        override fun onImportFailed(projectPath: String?, t: Throwable) {
            if (isCurrentGradleProject(projectPath)) {
                importDone.completeExceptionally(t)
            }
        }
    }
)
importDone.invokeOnCompletion { importConnection.disconnect() }

ExternalSystemUtil.refreshProject(
    gradleProjectPath,
    ImportSpecBuilder(project, GradleConstants.SYSTEM_ID).build()
)
withTimeout(8.minutes) { importDone.await() }
waitForSmartMode()
println("Gradle sync complete")
```

Key points:
- `ProjectDataImportListener.onFinalTasksFinished` is the Gradle import boundary; `onImportFinished` is too early because final import tasks still run afterward.
- Subscribe before calling `ExternalSystemUtil.refreshProject(...)` so the listener cannot miss a fast import event.
- `waitForSmartMode()` after final tasks lets indexing settle before follow-up indexed reads.
- Use the two-argument `ExternalSystemUtil.refreshProject(path, importSpec)` form; older overloads are deprecated.
- If sync fails, fix the Gradle/JDK/import problem. Do not continue with unresolved dependencies.

## Agent: Run Gradle Tests (two-call pattern, polling)

The preferred Gradle test runner from `steroid_execute_code`. Uses `GradleRunConfiguration.isRunAsTest = true` so per-test results land in IntelliJ's standard SM test-runner data model. **Read that model by polling**, not by subscribing to events — the polling shape is much shorter and survives retries cleanly.

> ⚠️ **Each call must finish in under 60 seconds.** A typical Gradle test on a fresh checkout (cold daemon, dependency resolve, compile, test execution) easily exceeds that. Do NOT try a single-call recipe that awaits the whole run; it will be cancelled by the client mid-await even though the IDE-side script timeout is much larger.

### Call 1 — launch the Gradle test, return immediately

```kotlin[IU]
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration

val factory = GradleExternalTaskConfigurationType.getInstance().configurationFactories.single()
val runConfig = factory.createTemplateConfiguration(project) as ExternalSystemRunConfiguration
runConfig.name = "Gradle test (MCP)"
runConfig.settings.externalProjectPath = project.basePath
runConfig.settings.taskNames = listOf(
    ":api:test",
    "--tests", "com.example.api.ProductControllerTest",
    "--rerun-tasks",                  // never trust UP-TO-DATE on first run after a code edit
    "--console=plain",
)
// Critical: without isRunAsTest=true the SM test-runner data model is empty.
(runConfig as GradleRunConfiguration).isRunAsTest = true

val settings = RunManager.getInstance(project).createConfiguration(runConfig, factory)
RunManager.getInstance(project).addConfiguration(settings)

withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
println("Gradle test launched: ${runConfig.settings.taskNames}")
println("EXECUTION_VIA: GradleRunConfiguration")
println("Call the polling script next; expected total runtime 30–180s for cold daemon.")
```

### Call 2 — poll the SM test-runner data model (re-issue every 20–30s)

The Gradle test run produces a `RunContentDescriptor` whose `executionConsole` is an `SMTRunnerConsoleView`. That view exposes `resultsViewer.testsRootNode: SMTestProxy.SMRootTestProxy`, which has `.allTests`, `.isInProgress`, `.isPassed`, and `.isDefect` — exactly the same data the SMT events would have given, but readable on demand.

```kotlin[IU]
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentManager

val descriptor = RunContentManager.getInstance(project).allDescriptors
    .firstOrNull { it.displayName?.contains("Gradle test (MCP)") == true }
val handler = descriptor?.processHandler
if (handler == null) {
    println("No Gradle run in flight; run the launch script first.")
} else if (!handler.isProcessTerminated) {
    println("Gradle run still in flight; call this script again.")
} else {
    val exit = handler.exitCode ?: -1
    val root = (descriptor.executionConsole as? SMTRunnerConsoleView)
        ?.resultsViewer?.testsRootNode
    val tests = root?.allTests ?: emptyList()
    val total = tests.size
    val failed = tests.count { it.isDefect }
    val passed = exit == 0 && failed == 0 && (root?.isPassed == true || total > 0)
    println("EXECUTION_VIA: GradleRunConfiguration")
    println("PROCESS_EXIT_CODE: $exit")
    println("TEST_TOTAL: $total")
    println("TEST_FAILED: $failed")
    println("TEST_RESULT: ${if (passed) "PASSED" else "FAILED"}")
}
```

### Targeting Rules

- Always use the subproject task path for targeted tests: `:api:test --tests com.example.MyTest`.
- For tests in multiple subprojects, batch them in one Gradle invocation with repeated `:subproject:test --tests FQCN` pairs.
- Add `--rerun-tasks` to the first Gradle test run after writing new source files. Without it, Gradle may report `UP-TO-DATE`, skip tests, and still print `BUILD SUCCESSFUL`.
- Keep the full suite as a final separate run after targeted tests pass.
- `isRunAsTest = true` is the difference between "I get per-test pass/fail" and "I get only a build-success boolean". Always set it for test runs.

### Build-only Gradle goals (no test framework)

For non-test goals like `:assemble`, `:build`, `:check`, where SMT events are irrelevant, the `ExternalSystemUtil.runTask` + `TaskCallback` path remains useful — it gives a boolean success without a UI run config. Same `withTimeoutOrNull(30.seconds)` polling shape applies.

## Inspect Gradle Test Failures from the Data Model

When the polling script reports `TEST_FAILED > 0`, read failure details directly off the same `testsRootNode.allTests` you already have access to — no need to walk the filesystem for `TEST-*.xml`:

```kotlin[IU]
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentManager

val descriptor = RunContentManager.getInstance(project).allDescriptors
    .firstOrNull { it.displayName?.contains("Gradle test (MCP)") == true }
val tests = (descriptor?.executionConsole as? SMTRunnerConsoleView)
    ?.resultsViewer?.testsRootNode?.allTests
    ?: emptyList()

for (t in tests.filter { it.isDefect }) {
    println("FAIL ${t.locationUrl ?: t.name}")
    t.errorMessage?.let { println("  message: ${it.lines().firstOrNull() ?: ""}") }
    t.stacktrace?.lines()?.take(8)?.forEach { println("  $it") }
}
```

`SMTestProxy` exposes `.errorMessage` (assertion message), `.stacktrace` (truncate to a few lines), `.locationUrl` (for navigation), `.duration` (in ms), and `.isDefect` (failed or errored). Same data the Run tool window's tree shows.

## ProcessBuilder("./gradlew") Is Banned Inside steroid_execute_code

Do not spawn `./gradlew test` through `ProcessBuilder` inside `steroid_execute_code`.

Why:
- It spawns a nested Gradle daemon from inside the IDE JVM.
- It can inherit the wrong classpath or JDK environment.
- It often costs more tokens because agents print or summarize huge Gradle output.

If the IDE Gradle runner is unavailable or times out after you gathered evidence, use the Bash tool outside `steroid_execute_code`, for example `JAVA_HOME=<Recommended JAVA_HOME> ./gradlew :api:test --tests com.example.api.ProductControllerTest --rerun-tasks --no-daemon --console=plain`.
