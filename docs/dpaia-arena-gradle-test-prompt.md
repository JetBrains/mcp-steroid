# Create Gradle Test Execution Integration Test

You are an **implementation agent**. Create a minimal integration test that validates Gradle test execution via IntelliJ APIs works correctly inside a Docker IntelliJ container.

## Context

MCP Steroid exposes `steroid_execute_code` which runs Kotlin code in IntelliJ's runtime. We need to verify that `GradleRunConfiguration` + `setRunAsTest(true)` + `SMTRunnerEventsListener` works for executing Gradle tests programmatically. This is a critical path — arena agents should use this instead of `Bash ./gradlew test`.

## Instructions

1. **Research existing test infrastructure** using MCP Steroid (`steroid_execute_code` on project `mcp-steroid`):
   - Find existing Gradle-based test projects in `test-integration/src/test/docker/test-project/`
   - Find how tests call `mcpExecuteCode()` to run Kotlin inside the container
   - Check `WhatYouSeeTest` or similar for Gradle project patterns

2. **The test project** at `test-integration/src/test/docker/test-project/` is a Gradle project with `build.gradle.kts` and JUnit tests. Use it.

3. **Create the integration test** at `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/GradleTestExecutionTest.kt`:
   - Use `IntelliJContainer.create()` to start a Docker IntelliJ container
   - Wait for project ready with `buildSystem = BuildSystem.GRADLE`
   - Call `mcpExecuteCode()` with Kotlin code that:
     a. Subscribes to `SMTRunnerEventsListener.TEST_STATUS`
     b. Creates a `GradleRunConfiguration` via `GradleExternalTaskConfigurationType`
     c. Sets `externalProjectPath`, `taskNames = listOf(":test")`, `setRunAsTest(true)`
     d. Executes via `ProgramRunnerUtil.executeConfiguration()`
     e. Waits for `onTestingFinished` via `CompletableDeferred`
     f. Prints `GRADLE_TEST_PASSED=true/false`
   - Assert the output contains `GRADLE_TEST_PASSED=`

4. **API signatures** (confirmed via MCP Steroid research):
   - `GradleRunConfiguration` extends `ExternalSystemRunConfiguration`, NOT deprecated
   - `setRunAsTest(true)` — enables SMTRunner integration
   - `GradleExternalTaskConfigurationType.getInstance().configurationFactories[0]` — factory
   - `config.settings.externalProjectPath`, `config.settings.taskNames`, `config.settings.scriptParameters`

5. **Verify** the test compiles: `./gradlew :test-integration:compileTestKotlin`
   - Do NOT run the test (it needs Docker) — just verify compilation

6. **Commit** with message: `test: add Gradle test execution integration test`

## Constraints

- Follow existing test patterns (check how other tests in `test-integration/src/test/kotlin/` are structured)
- Use `@Test` with descriptive backtick names per CLAUDE.md
- Do NOT use `@ParameterizedTest`
- Use MCP Steroid for all codebase research
