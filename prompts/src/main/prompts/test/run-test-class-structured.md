Test: Run Test Class and Return Structured Results

Run one test class in a single steroid_execute_code call and print structured pass/fail results collected from the platform SM test runner.

Use this when you need one bounded verification call after an edit: launch a specific
test class, wait for completion, collect SM test-runner events, and return a
machine-readable result.

###_IF_IDE[AI,IC,IU]_###

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

Gradle-specific pitfalls:
- Do not use direct `JUnitConfiguration` for IntelliJ Platform plugin tests unless you also
  reproduce the Gradle/IPGP test JVM args. Missing `--add-opens` flags cause launcher failures
  such as `java.desktop does not open java.awt`, unrelated to the code under test.
- `isRunAsTest = true` is required. Without it, the Gradle process can run while no
  `SMTRunnerEventsListener.TEST_STATUS` events are published.
- Remove the temporary run configuration in `finally`, otherwise repeated agent calls leave
  stale Gradle configurations behind.

###_ELSE_###

The structured-collection machinery is pure platform API: every SM-runner-based test
configuration — pytest/unittest in PyCharm, `go test` in GoLand, Jest/Mocha/Karma in
WebStorm, Google Test/Catch2 in CLion, RSpec/Minitest in RubyMine — publishes the same
`SMTRunnerEventsListener.TEST_STATUS` events. The recipe below launches an existing test
run configuration by name and returns the same machine-readable summary. First point a
configuration at the one test class you need (e.g. a pytest target
`tests/test_foo.py::TestFoo`, a Go test pattern, a Jest test-path filter) — enumerate
what exists via [List Run Configurations](mcp-steroid://test/list-run-configurations).

Honest limits:
- **Rider:** native .NET unit tests run through Rider's own test runner and do NOT publish
  SM `TEST_STATUS` events — launch via the caret context action instead
  ([Run Test at Caret](mcp-steroid://test/run-test-at-caret)) and read results from Rider's
  Unit Test tool window. The recipe below applies in Rider only to SM-based configurations
  (e.g. JavaScript tests).
- **DataGrip:** no test-framework integration ships out of the box; the recipe applies only
  when an installed plugin contributes an SM-based test run configuration.

```kotlin
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.ui.RunContentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

val configurationName = "MyTests" // TODO: existing test run configuration targeting one test class
val timeoutMs = 180_000L
val descriptorTimeoutMs = 45_000L
val terminationGraceMs = 5_000L
val maxFailuresToPrint = 20
val maxStackTraceLines = 20

data class TestFailureInfo(
    val name: String?,
    val durationMs: Long?,
    val errorMessage: String?,
    val stackTrace: String?,
    val locationUrl: String?,
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

// CopyOnWriteArrayList: the TEST_STATUS listener adds from the IDE test thread while the suspend
// body below scans with firstOrNull — add() is atomic and iteration sees a stable snapshot, so
// no external lock is needed. Runs that finished before this call subscribed never appear here;
// runs of the same configuration still in progress at launch time are excluded by the
// pre-launch handler snapshot below.
val startedRoots = CopyOnWriteArrayList<SMTestProxy.SMRootTestProxy>()
val finishedRoots = CopyOnWriteArrayList<SMTestProxy.SMRootTestProxy>()

// Subscribe before launch. TEST_STATUS is a typed project message-bus topic; no reflection is
// required. The topic is project-wide, so roots are correlated below by public
// SMRootTestProxy.getHandler() identity with a RunContentDescriptor of the launched
// configuration — a concurrent unrelated test run cannot be mistaken for this one.
val connection = project.messageBus.connect(disposable)
connection.subscribe(
    SMTRunnerEventsListener.TEST_STATUS,
    object : SMTRunnerEventsListener {
        override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
            startedRoots += testsRoot
        }

        override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
            finishedRoots += testsRoot
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

val settings = RunManager.getInstance(project).allSettings.firstOrNull { it.name == configurationName }
    ?: error("No run configuration named '$configurationName'. Create one for the test class, or list existing configurations first.")

val contentManager = RunContentManager.getInstance(project)
// Snapshot process handlers that already exist BEFORE this launch. launchedHandler() and
// matchesTarget() exclude them, so a run of the same configuration that was already in
// progress when this call started is never reported as this launch's result and never
// receives destroyProcess() on timeout.
val preLaunchHandlers = contentManager.allDescriptors.mapNotNull { it.processHandler }
fun isPreLaunch(handler: ProcessHandler): Boolean = preLaunchHandlers.any { it === handler }

try {
    withContext(Dispatchers.EDT) {
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    val startedAt = System.currentTimeMillis()
    val deadline = startedAt + timeoutMs

    // The run-content tab of an existing configuration reuses the configuration name.
    fun launchedHandler() = contentManager.allDescriptors
        .filter { it.displayName == configurationName }
        .firstNotNullOfOrNull { d ->
            d.processHandler?.takeIf { !it.isProcessTerminated && !isPreLaunch(it) }
        }

    fun matchesTarget(root: SMTestProxy.SMRootTestProxy): Boolean {
        val rootHandler = root.handler ?: return false
        if (isPreLaunch(rootHandler)) return false
        return contentManager.allDescriptors.any {
            it.displayName == configurationName && it.processHandler === rootHandler
        }
    }

    fun finishedTargetRoot(): SMTestProxy.SMRootTestProxy? = finishedRoots.firstOrNull(::matchesTarget)

    var handler = launchedHandler()
    var root = finishedTargetRoot()
    while (handler == null && root == null && System.currentTimeMillis() - startedAt < descriptorTimeoutMs) {
        delay(250)
        handler = launchedHandler()
        root = finishedTargetRoot()
    }

    if (handler == null && root == null) {
        printJson(
            mapOf(
                "status" to "did_not_run",
                "check_ran" to false,
                "configuration" to configurationName,
                "message" to "No live RunContentDescriptor for the configuration appeared within ${descriptorTimeoutMs}ms.",
            )
        )
        return
    }

    var terminationGraceDeadline = 0L
    while (root == null && System.currentTimeMillis() < deadline) {
        if (handler == null || handler.isProcessTerminated) {
            // onTestingFinished can arrive shortly after process exit — allow a short grace window.
            if (terminationGraceDeadline == 0L) {
                terminationGraceDeadline = System.currentTimeMillis() + terminationGraceMs
            }
            if (System.currentTimeMillis() >= terminationGraceDeadline) break
        }
        delay(250)
        root = finishedTargetRoot()
    }

    if (root == null) {
        if (handler != null && !handler.isProcessTerminated) {
            handler.destroyProcess()
        }
        val matchingStarted = startedRoots.any(::matchesTarget)
        printJson(
            mapOf(
                "status" to if (matchingStarted || handler == null || handler.isProcessTerminated) "check_failed" else "did_not_run",
                "check_ran" to matchingStarted,
                "configuration" to configurationName,
                "exitCode" to handler?.exitCode,
                "message" to if (matchingStarted) {
                    "The matching SM test runner started but did not publish onTestingFinished before process exit or timeout."
                } else {
                    "No SM test-runner events were observed — this run configuration may not use the platform SM test runner."
                },
            )
        )
        return
    }

    val tests = root.allTests
        .filterIsInstance<SMTestProxy>()
        .filter { !it.isSuite }
    val failed = tests.filter { it.isDefect }

    printJson(
        if (tests.isEmpty()) {
            mapOf(
                "status" to "did_not_run",
                "check_ran" to true,
                "configuration" to configurationName,
                "exitCode" to handler?.exitCode,
                "message" to "The matching SM test runner finished, but no test leaf events were observed.",
            )
        } else {
            mapOf(
                "status" to if (failed.isEmpty()) "passed" else "failed",
                "check_ran" to true,
                "configuration" to configurationName,
                "exitCode" to handler?.exitCode,
                "total" to tests.size,
                "passed" to tests.count { it.isPassed },
                "failed" to failed.size,
                "ignored" to tests.count { it.isIgnored },
                "durationMs" to root.duration,
                "failuresShown" to failed.take(maxFailuresToPrint).map(::failurePayload),
                "failureOutputTruncated" to (failed.size > maxFailuresToPrint),
            )
        }
    )
} finally {
    connection.disconnect()
}
```

###_END_IF_###

Pitfalls in every IDE:
- `SMTRunnerEventsListener.TEST_STATUS` is project-wide. Correlate each root's public
  `SMRootTestProxy.getHandler()` identity with the launched descriptor's process handler;
  otherwise a concurrent test run can complete your latch with the wrong result.
- Do not unwrap `executionConsole` wrappers reflectively. Reflection is only for exploration in
  this repo's prompt corpus. The typed event listener above is the shipped recipe.
- A massive failure log is not a useful structured result. Keep `failuresShown` and stack traces
  capped, and use `failureOutputTruncated=true` to signal that more failures exist.

# See also

- [Run Test at Caret](mcp-steroid://test/run-test-at-caret) - IDE-agnostic context-action test launch.
- [List Run Configurations](mcp-steroid://test/list-run-configurations) - Enumerate existing run configurations by name and type.
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Inspect results from an existing run.
- [Gradle Patterns](mcp-steroid://skill/execute-code-gradle) - Gradle run configuration patterns.
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - General test runner workflow and pitfalls.
