Test: Run Gradle Test Class and Return Structured Results

Run one Gradle-backed JVM test class in a single steroid_execute_code call and print structured pass/fail results.

Use this when you need one bounded verification call after an edit: launch a specific
JUnit/JVM test class through the project's Gradle test task, wait for completion, collect
SM test-runner events, and return a machine-readable result.

Prefer this over direct `JUnitConfiguration` for IntelliJ Platform plugin tests and other
Gradle-managed JVM projects. Gradle supplies the test JVM args, classpath, and module
opens that a raw IDE JUnit configuration may miss.

```kotlin[AI,IC,IU]
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

val gradleTestTaskPath = ":ij-plugin:test" // TODO: subproject test task
val testPattern = "com.example.MyTest" // TODO: class or method, e.g. com.example.MyTest.myMethod
val timeoutMs = 180_000L
val descriptorTimeoutMs = 45_000L
val maxFailuresToPrint = 20
val maxStackTraceLines = 20

data class TestFailureInfo(
    val name: String?,
    val durationMs: Long?,
    val errorMessage: String?,
    val stackTrace: String?,
    val locationUrl: String?,
)

data class TestSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val ignored: Int,
    val durationMs: Long?,
    val failuresShown: List<TestFailureInfo>,
    val failureOutputTruncated: Boolean,
)

fun failurePayload(test: SMTestProxy): TestFailureInfo =
    TestFailureInfo(
        name = test.name,
        durationMs = test.duration,
        errorMessage = test.errorMessage,
        stackTrace = test.stacktrace
            ?.lineSequence()
            ?.take(maxStackTraceLines)
            ?.joinToString("\n"),
        locationUrl = test.locationUrl,
    )

fun summarize(root: SMTestProxy.SMRootTestProxy): TestSummary {
    val tests = root.allTests
        .filterIsInstance<SMTestProxy>()
        .filter { !it.isSuite }
    val failed = tests.filter { it.isDefect }
    return TestSummary(
        total = tests.size,
        passed = tests.count { it.isPassed },
        failed = failed.size,
        ignored = tests.count { it.isIgnored },
        durationMs = root.duration,
        failuresShown = failed.take(maxFailuresToPrint).map(::failurePayload),
        failureOutputTruncated = failed.size > maxFailuresToPrint,
    )
}

// CopyOnWriteArrayList: the TEST_STATUS listener adds from the IDE test thread while the suspend
// body below scans with firstOrNull — both are individually thread-safe on COW, so no external
// lock is needed (add() is atomic; iteration sees a stable snapshot and never throws CME).
val startedRoots = CopyOnWriteArrayList<SMTestProxy.SMRootTestProxy>()
val finishedRoots = CopyOnWriteArrayList<SMTestProxy.SMRootTestProxy>()
val targetProcessHandler = AtomicReference<com.intellij.execution.process.ProcessHandler?>()
val targetRoot = AtomicReference<SMTestProxy.SMRootTestProxy?>()
val matchingSummary = AtomicReference<TestSummary?>()
val processFinished = CompletableDeferred<Int?>()

fun isTargetRoot(root: SMTestProxy.SMRootTestProxy): Boolean {
    val handler = targetProcessHandler.get()
    return handler != null && root.handler === handler
}

fun rememberStarted(root: SMTestProxy.SMRootTestProxy) {
    startedRoots += root
    if (isTargetRoot(root)) {
        targetRoot.compareAndSet(null, root)
    }
}

fun rememberFinished(root: SMTestProxy.SMRootTestProxy) {
    finishedRoots += root
    if (isTargetRoot(root)) {
        targetRoot.compareAndSet(null, root)
        matchingSummary.compareAndSet(null, summarize(root))
    }
}

// Subscribe before launch. TEST_STATUS is a typed project message-bus topic; no reflection is
// required. The topic is project-wide, so events are accepted only after they are correlated
// by public SMRootTestProxy.getHandler() identity with the process handler belonging to the
// RunContentDescriptor launched below. This also works when Gradle wraps the SM console in BuildView;
// BuildView.getConsoleView() is @ApiStatus.Internal and is deliberately not used.
val connection = project.messageBus.connect(disposable)
connection.subscribe(
    SMTRunnerEventsListener.TEST_STATUS,
    object : SMTRunnerEventsListener {
        override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
            rememberStarted(testsRoot)
        }

        override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
            rememberFinished(testsRoot)
        }

        override fun onTestStarted(test: SMTestProxy) {}
        override fun onTestFinished(test: SMTestProxy) {}
        override fun onTestFailed(test: SMTestProxy) {}
        override fun onTestIgnored(test: SMTestProxy) {}
        override fun onSuiteStarted(suite: SMTestProxy) {}
        override fun onSuiteFinished(suite: SMTestProxy) {}
        override fun onTestsCountInSuite(count: Int) {}
        override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
        override fun onCustomProgressTestStarted() {}
        override fun onCustomProgressTestFinished() {}
        override fun onCustomProgressTestFailed() {}
        override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
        override fun onSuiteTreeStarted(suite: SMTestProxy) {}
    }
)

val basePath = project.basePath ?: error("Project base path is not set")
val factory = GradleExternalTaskConfigurationType.getInstance().configurationFactories.single()
val runConfig = factory.createTemplateConfiguration(project) as ExternalSystemRunConfiguration
val runName = "MCP Gradle test $testPattern ${System.currentTimeMillis()}"
runConfig.name = runName
runConfig.settings.externalProjectPath = basePath
runConfig.settings.taskNames = listOf(
    gradleTestTaskPath,
    "--tests", testPattern,
    "--rerun-tasks",
    "--console=plain",
)

// Critical: without isRunAsTest=true, the Gradle process can run while no SM
// test-runner events are published.
(runConfig as GradleRunConfiguration).isRunAsTest = true

val runManager = RunManager.getInstance(project)
val settings = runManager.createConfiguration(runConfig, factory)
runManager.addConfiguration(settings)

try {
    withContext(Dispatchers.EDT) {
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    val contentManager = RunContentManager.getInstance(project)
    val startedAt = System.currentTimeMillis()
    val deadline = startedAt + timeoutMs
    fun remainingMs(): Long = deadline - System.currentTimeMillis()

    var descriptor = contentManager.allDescriptors.firstOrNull { it.displayName == runName }
    while (descriptor == null && System.currentTimeMillis() - startedAt < descriptorTimeoutMs) {
        delay(250)
        descriptor = contentManager.allDescriptors.firstOrNull { it.displayName == runName }
    }

    if (descriptor == null) {
        printJson(
            mapOf(
                "status" to "did_not_run",
                "check_ran" to false,
                "testPattern" to testPattern,
                "gradleTask" to gradleTestTaskPath,
                "message" to "RunContentDescriptor was not created within ${descriptorTimeoutMs}ms.",
            )
        )
        return
    }

    val handler = descriptor.processHandler
    if (handler == null) {
        printJson(
            mapOf(
                "status" to "did_not_run",
                "check_ran" to false,
                "testPattern" to testPattern,
                "gradleTask" to gradleTestTaskPath,
                "message" to "RunContentDescriptor has no process handler.",
            )
        )
        return
    }

    targetProcessHandler.set(handler)
    // Catch up on any root that started/finished before the handler was known. No lock: the lists
    // are CopyOnWriteArrayList, so firstOrNull scans a stable snapshot even under concurrent adds.
    startedRoots.firstOrNull(::isTargetRoot)?.let { targetRoot.compareAndSet(null, it) }
    finishedRoots.firstOrNull(::isTargetRoot)?.let { root ->
        targetRoot.compareAndSet(null, root)
        matchingSummary.compareAndSet(null, summarize(root))
    }

    handler.addProcessListener(
        object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                processFinished.complete(event.exitCode)
            }
        }
    )
    if (handler.isProcessTerminated) {
        processFinished.complete(handler.exitCode)
    }

    var summary = matchingSummary.get()
    while (summary == null && !processFinished.isCompleted && remainingMs() > 0) {
        delay(250)
        summary = matchingSummary.get()
    }

    val matchingStarted = targetRoot.get() != null
    var exitCode = if (processFinished.isCompleted) processFinished.await() else null

    if (summary == null) {
        if (exitCode == null && remainingMs() <= 0 && !handler.isProcessTerminated) {
            handler.destroyProcess()
            exitCode = withTimeoutOrNull(5_000L) { processFinished.await() }
        }

        printJson(
            mapOf(
                "status" to if (matchingStarted || (exitCode != null && exitCode != 0)) "check_failed" else "did_not_run",
                "check_ran" to matchingStarted,
                "testPattern" to testPattern,
                "gradleTask" to gradleTestTaskPath,
                "exitCode" to exitCode,
                "message" to if (matchingStarted) {
                    "The matching SM test runner started but did not publish onTestingFinished before process exit or timeout."
                } else {
                    "No matching SM test runner events were observed; check Gradle task path, --tests pattern, and isRunAsTest=true."
                },
            )
        )
        return
    }

    exitCode = if (processFinished.isCompleted) {
        processFinished.await()
    } else {
        withTimeoutOrNull(remainingMs().coerceAtLeast(0L)) { processFinished.await() }
    }
    if (exitCode == null) {
        if (!handler.isProcessTerminated) {
            handler.destroyProcess()
        }
        printJson(
            mapOf(
                "status" to "check_failed",
                "check_ran" to true,
                "testPattern" to testPattern,
                "gradleTask" to gradleTestTaskPath,
                "message" to "SM test runner finished, but the Gradle process did not terminate within the remaining ${timeoutMs}ms budget.",
                "total" to summary.total,
                "passed" to summary.passed,
                "failed" to summary.failed,
                "ignored" to summary.ignored,
            )
        )
        return
    }

    printJson(
        if (summary.total == 0) {
            mapOf(
                "status" to "did_not_run",
                "check_ran" to true,
                "testPattern" to testPattern,
                "gradleTask" to gradleTestTaskPath,
                "exitCode" to exitCode,
                "message" to "The matching SM test runner finished, but no test leaf events were observed.",
            )
        } else {
            mapOf(
                "status" to if (summary.failed == 0 && exitCode == 0) "passed" else "failed",
                "check_ran" to true,
                "testPattern" to testPattern,
                "gradleTask" to gradleTestTaskPath,
                "exitCode" to exitCode,
                "total" to summary.total,
                "passed" to summary.passed,
                "failed" to summary.failed,
                "ignored" to summary.ignored,
                "durationMs" to summary.durationMs,
                "failuresShown" to summary.failuresShown,
                "failureOutputTruncated" to summary.failureOutputTruncated,
            )
        }
    )
} finally {
    connection.disconnect()
    runManager.removeConfiguration(settings)
}
```

Pitfalls:
- Do not use direct `JUnitConfiguration` for IntelliJ Platform plugin tests unless you also
  reproduce the Gradle/IPGP test JVM args. Missing `--add-opens` flags cause launcher failures
  such as `java.desktop does not open java.awt`, unrelated to the code under test.
- `isRunAsTest = true` is required. Without it, the Gradle process can run while no
  `SMTRunnerEventsListener.TEST_STATUS` events are published.
- `SMTRunnerEventsListener.TEST_STATUS` is project-wide. Correlate each root's public
  `SMRootTestProxy.getHandler()` identity with the launched descriptor's process handler;
  otherwise a concurrent test run can complete your latch with the wrong result.
- Do not unwrap `executionConsole` wrappers reflectively. Reflection is only for exploration in
  this repo's prompt corpus. The typed event listener above is the shipped recipe.
- Remove the temporary run configuration in `finally`, otherwise repeated agent calls leave
  stale Gradle configurations behind.
- A massive failure log is not a useful structured result. Keep `failuresShown` and stack traces
  capped, and use `failureOutputTruncated=true` to signal that more failures exist.

# See also

- [Run Test at Caret](mcp-steroid://test/run-test-at-caret) - IDE-agnostic context-action test launch.
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Inspect results from an existing run.
- [Gradle Patterns](mcp-steroid://skill/execute-code-gradle) - Gradle run configuration patterns.
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - General test runner workflow and pitfalls.
