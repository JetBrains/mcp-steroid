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

## Run Gradle Tests with ExternalSystemUtil

This is the preferred Gradle runner from `steroid_execute_code`. It uses the IDE Gradle integration and returns pass/fail without a nested Gradle daemon in the IDE JVM:

```kotlin[IU]
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()
val settings = ExternalSystemTaskExecutionSettings().apply {
    externalProjectPath = project.basePath!!
    externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
    taskNames = listOf(
        ":api:test",
        "--tests",
        "com.example.api.ProductControllerTest",
        "--rerun-tasks",
        "--no-daemon",
        "--console=plain",
    )
}

ExternalSystemUtil.runTask(
    settings,
    com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
    project,
    GradleConstants.SYSTEM_ID,
    object : TaskCallback {
        override fun onSuccess() { result.complete(true) }
        override fun onFailure() { result.complete(false) }
    },
    ProgressExecutionMode.IN_BACKGROUND_ASYNC,
    false,
)

val ok = withTimeout(5.minutes) { result.await() }
println("Gradle result: success=$ok")
```

### Targeting Rules

- Always use the subproject task path for targeted tests: `:api:test --tests com.example.MyTest`.
- For tests in multiple subprojects, batch them in one Gradle invocation with repeated `:subproject:test --tests FQCN` pairs.
- Add `--rerun-tasks` to the first Gradle test run after writing new source files. Without it, Gradle may report `UP-TO-DATE`, skip tests, and still print `BUILD SUCCESSFUL`.
- Keep the full suite as a final separate run after targeted tests pass.

## Inspect Gradle Test Failures from JUnit XML

When the IDE Gradle runner returns `success=false`, inspect Gradle's XML results before retrying. This is cheaper and more precise than immediately rerunning Gradle:

```kotlin[IU]
import com.intellij.openapi.vfs.LocalFileSystem

val root = java.io.File(project.basePath!!).toPath()
val resultFiles = java.nio.file.Files.walk(root)
    .filter { it.fileName.toString().startsWith("TEST-") }
    .filter { it.fileName.toString().endsWith(".xml") }
    .filter { it.toString().contains("/build/test-results/") }
    .toList()

println("Gradle XML result files: ${resultFiles.size}")
for (path in resultFiles.take(20)) {
    val text = java.nio.file.Files.readString(path)
    val failures = Regex("<failure[^>]*>(.+?)</failure>", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .map { it.groupValues[1].take(500) }
        .toList()
    if (failures.isNotEmpty()) {
        println("FAIL ${root.relativize(path)}")
        println(failures.first())
    }
}
LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
```

## ProcessBuilder("./gradlew") Is Banned Inside steroid_execute_code

Do not spawn `./gradlew test` through `ProcessBuilder` inside `steroid_execute_code`.

Why:
- It spawns a nested Gradle daemon from inside the IDE JVM.
- It can inherit the wrong classpath or JDK environment.
- It often costs more tokens because agents print or summarize huge Gradle output.

If the IDE Gradle runner is unavailable or times out after you gathered evidence, use the Bash tool outside `steroid_execute_code`, for example `JAVA_HOME=<Recommended JAVA_HOME> ./gradlew :api:test --tests com.example.api.ProductControllerTest --rerun-tasks --no-daemon --console=plain`.
