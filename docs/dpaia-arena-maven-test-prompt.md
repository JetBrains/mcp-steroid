# Create Maven Test Execution Integration Test

You are an **implementation agent**. Create a minimal integration test that validates Maven test execution via IntelliJ APIs works correctly inside a Docker IntelliJ container.

## Context

MCP Steroid exposes `steroid_execute_code` which runs Kotlin code in IntelliJ's runtime. We need to verify that `MavenRunConfigurationType.runConfiguration()` + `SMTRunnerEventsListener` works for executing Maven tests programmatically. This is a critical path — arena agents should use this instead of `Bash ./mvnw test`.

## Instructions

1. **Research existing test infrastructure** using MCP Steroid (`steroid_execute_code` on project `mcp-steroid`):
   - Find `DpaiaScenarioBaseTest` or similar base classes in `test-experiments/src/test/kotlin/`
   - Find how tests call `mcpExecuteCode()` to run Kotlin inside the container
   - Find the test project in `test-integration/src/test/docker/test-project/` — check if it has a Maven `pom.xml` or only Gradle

2. **Check if a Maven test project exists**:
   - Look in `test-integration/src/test/docker/` for any Maven project
   - If none exists, you'll need to create a minimal one with one passing JUnit test

3. **Create the integration test** at `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/MavenTestExecutionTest.kt`:
   - Use `IntelliJContainer.create()` to start a Docker IntelliJ container
   - Wait for project ready with `buildSystem = BuildSystem.MAVEN`
   - Call `mcpExecuteCode()` with Kotlin code that:
     a. Subscribes to `SMTRunnerEventsListener.TEST_STATUS`
     b. Calls `MavenRunConfigurationType.runConfiguration()` with a test goal
     c. Waits for `onTestingFinished` via `CompletableDeferred`
     d. Prints `MAVEN_TEST_PASSED=true/false`
   - Assert the output contains `MAVEN_TEST_PASSED=true`

4. **API signatures** (confirmed via MCP Steroid research):
   - `MavenRunConfigurationType.runConfiguration(project, params, settings, runnerSettings, callback)` — static, NOT deprecated
   - `MavenRunnerParameters(isPomExecution=true, workingDirPath, pomFileName, goals, profiles)` — use 5-arg constructor
   - `SMTRunnerEventsListener.TEST_STATUS` — project-level Topic, NOT deprecated

5. **Verify** the test compiles: `./gradlew :test-integration:compileTestKotlin`
   - Do NOT run the test (it needs Docker) — just verify compilation

6. **Commit** with message: `test: add Maven test execution integration test`

## Constraints

- Follow existing test patterns (check how other tests in `test-integration/src/test/kotlin/` are structured)
- Use `@Test` with descriptive backtick names per CLAUDE.md
- Do NOT use `@ParameterizedTest`
- Use MCP Steroid for all codebase research
