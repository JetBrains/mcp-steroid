# Create Top-10 MCP Steroid Task Tests

You are an **implementation agent**. Create 5 integration tests that validate MCP Steroid can replace the top Bash tasks identified in arena analysis.

## Context

Analysis of 666 Bash calls across 51 arena runs shows 95% are replaceable by MCP Steroid. We need tests proving each IntelliJ API works inside Docker IntelliJ containers.

## Tests to Create

All tests go in `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/`.
Use MCP Steroid (`steroid_execute_code` on project `mcp-steroid`) to research existing tests as patterns.

### 1. DockerCheckTest.kt
Validates that checking Docker availability via `exec_code` works (replaces 71 Bash `docker info` calls).
```
Code to execute via mcpExecuteCode:
  val dockerOk = java.io.File("/var/run/docker.sock").exists()
  println("DOCKER_AVAILABLE=$dockerOk")
```
Assert output contains `DOCKER_AVAILABLE=`.

### 2. FileDiscoveryTest.kt  
Validates `FilenameIndex` works inside Docker IntelliJ (replaces 66 Bash `ls`/`find` calls).
```
Code to execute via mcpExecuteCode:
  import com.intellij.psi.search.FilenameIndex
  import com.intellij.psi.search.GlobalSearchScope
  val scope = GlobalSearchScope.projectScope(project)
  val javaFiles = readAction { FilenameIndex.getAllFilesByExt(project, "java", scope) }
  println("JAVA_FILES=${javaFiles.size}")
  val ktFiles = readAction { FilenameIndex.getAllFilesByExt(project, "kt", scope) }
  println("KT_FILES=${ktFiles.size}")
```
Assert output contains `JAVA_FILES=` or `KT_FILES=` with count > 0.

### 3. MavenCompileTest.kt
Validates `ProjectTaskManager.build()` compiles a Maven project (replaces 48 Bash `mvnw test-compile` calls).
Use the `test-project-maven/` fixture.
```
Code to execute via mcpExecuteCode:
  import com.intellij.task.ProjectTaskManager
  import com.intellij.openapi.module.ModuleManager
  import org.jetbrains.concurrency.await
  val modules = ModuleManager.getInstance(project).modules
  val result = ProjectTaskManager.getInstance(project).build(*modules).await()
  println("BUILD_ERRORS=${result.hasErrors()}")
  println("BUILD_ABORTED=${result.isAborted}")
```
Assert output contains `BUILD_ERRORS=false`.

### 4. MavenInstallTest.kt
Validates `MavenRunner.run()` with install goal works (replaces 7 Bash `mvnw install` calls).
Use the `test-project-maven/` fixture.
```
Code to execute via mcpExecuteCode:
  import org.jetbrains.idea.maven.execution.MavenRunner
  import org.jetbrains.idea.maven.execution.MavenRunnerParameters
  import org.jetbrains.idea.maven.execution.MavenRunnerSettings
  import kotlinx.coroutines.CompletableDeferred
  import kotlinx.coroutines.withTimeout
  import kotlin.time.Duration.Companion.minutes
  
  val done = CompletableDeferred<Boolean>()
  val params = MavenRunnerParameters(true, project.basePath!!, "pom.xml",
      listOf("install", "-DskipTests"), emptyList())
  val runner = MavenRunner.getInstance(project)
  val settings = runner.settings.clone()
  runner.run(params, settings) { done.complete(true) }
  val ok = withTimeout(3.minutes) { done.await() }
  println("MAVEN_INSTALL=$ok")
```
Assert output contains `MAVEN_INSTALL=true`.

### 5. GradleCompileTest.kt
Validates `ProjectTaskManager.build()` compiles a Gradle project (replaces 4 Bash `gradlew compile` calls).
Use the existing `test-project/` fixture (Gradle).
```
Code to execute via mcpExecuteCode (same as MavenCompileTest but on Gradle project):
  import com.intellij.task.ProjectTaskManager
  import com.intellij.openapi.module.ModuleManager
  import org.jetbrains.concurrency.await
  val modules = ModuleManager.getInstance(project).modules
  val result = ProjectTaskManager.getInstance(project).build(*modules).await()
  println("BUILD_ERRORS=${result.hasErrors()}")
```
Assert output contains `BUILD_ERRORS=false`.

## Pattern to Follow

Look at `MavenTestExecutionTest.kt` and `GradleTestExecutionTest.kt` for the pattern:
- `IntelliJContainer.create()` with appropriate project + build system
- `waitForProjectReady()`
- `mcpExecuteCode()` with the Kotlin code
- Assert on output

## Verification

```bash
./gradlew :test-integration:compileTestKotlin
```
Do NOT run the tests (Docker needed). Just verify compilation.

## Commit

```
git add test-integration/ && git commit -m "test: add top-10 MCP Steroid task validation tests (Docker, FileIndex, Compile, Install)"
```
