# Autoresearch: Create Fast Iteration Test for MavenRunner Adoption

You are an **implementation agent**. Create a small, fast integration test in `test-integration` that measures whether an AI agent uses `MavenRunConfigurationType` (via exec_code) instead of Bash `./mvnw test` when running Maven tests.

## Goal

We need a test that:
1. Starts a Docker IntelliJ container with the Maven test project
2. Gives the agent a simple prompt: "Run the Maven tests in this project and report results"
3. Checks whether the agent used `steroid_execute_code` with `MavenRunConfigurationType` or fell back to `Bash ./mvnw test`
4. Reports the tool usage pattern as a PASS/FAIL

This test should run in ~2-3 minutes (vs 5-15 min for arena scenarios), enabling fast iteration on prompt changes.

## Instructions

1. **Use MCP Steroid** (`steroid_execute_code` on project `mcp-steroid`) to research:
   - How `WhatYouSeeTest` or `DialogKillerTest` work in `test-integration/src/test/kotlin/`
   - How `ConsoleAwareAgentSession.runPrompt()` returns agent output
   - How to check the decoded log for specific tool calls

2. **Create test** at `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/MavenRunnerAdoptionTest.kt`:

   The test should:
   - Start IntelliJ container with the Maven test project (`test-project-maven/`)
   - Wait for project ready (Maven build system)
   - Run a Claude agent with a simple prompt: "Run all Maven tests in this project. Report the test results (pass count, fail count)."
   - After the agent completes, parse the decoded log for:
     - Lines containing `steroid_execute_code` with reason containing "test" or "maven"
     - Lines containing `>> Bash` with `./mvnw test`
   - Assert that the agent used exec_code for test execution (not Bash)
   - Print metrics: exec_code_calls, bash_calls, duration

3. **Test name**: `` `maven runner adoption - claude uses exec_code for test execution`() ``

4. **Key assertion**:
   ```kotlin
   // The agent should use MavenRunConfigurationType via exec_code, not Bash ./mvnw test
   val execCodeTestCalls = decodedLog.lines().count { 
       it.contains("steroid_execute_code") && (it.contains("test") || it.contains("Maven"))
   }
   val bashMvnCalls = decodedLog.lines().count {
       it.contains(">> Bash") && it.contains("mvnw test")
   }
   assertTrue(execCodeTestCalls > 0, "Agent should use exec_code for Maven test execution")
   // Note: bashMvnCalls > 0 is acceptable as fallback, but execCodeTestCalls should be > 0
   ```

5. **Verify compilation**: `./gradlew :test-integration:compileTestKotlin`

6. **Commit** with message: `test: add MavenRunner adoption fast-iteration test`

## Constraints

- Follow existing test patterns in `test-integration/src/test/kotlin/`
- Use `@Test` with backtick name
- The test must be runnable independently: `./gradlew :test-integration:test --tests '*MavenRunnerAdoptionTest*'`
- Do NOT run the test (needs Docker + API key) — just verify compilation
- Use MCP Steroid for all codebase navigation
