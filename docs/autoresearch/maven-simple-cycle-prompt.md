# Autoresearch: Maven Simple Cycle — Fast Iteration on MavenRunner Adoption

You are an **evaluation agent**. Run a fast test cycle to measure whether agents adopt the IDE MavenRunner pattern.

## Setup

The `MavenTestExecutionTest` at `test-integration/src/test/kotlin/.../tests/MavenTestExecutionTest.kt` validates that `MavenRunConfigurationType.runConfiguration()` + `SMTRunnerEventsListener` works inside Docker IntelliJ.

The `MavenRunnerAdoptionTest` at `test-integration/src/test/kotlin/.../tests/MavenRunnerAdoptionTest.kt` validates that a Claude agent uses exec_code for Maven test execution instead of Bash.

## Instructions

1. First verify both tests compile:
   ```bash
   ./gradlew :test-integration:compileTestKotlin 2>&1 | tail -5
   ```

2. Run the `MavenTestExecutionTest` (validates the API works):
   ```bash
   ./gradlew :test-integration:test --tests '*MavenTestExecutionTest*' --rerun-tasks 2>&1 | tee /tmp/maven-test-exec.log
   ```

3. If it passes, run the `MavenRunnerAdoptionTest` (validates agent behavior):
   ```bash
   ./gradlew :test-integration:test --tests '*MavenRunnerAdoptionTest*' --rerun-tasks 2>&1 | tee /tmp/maven-adoption.log
   ```

4. After each test, extract the result and log to message bus at `/Users/jonnyzzz/Work/mcp-steroid/docs/autoresearch/MESSAGE-BUS.md`:
   ```
   MAVEN_CYCLE: test=MavenTestExecutionTest result=<PASS/FAIL> duration=<N>s
   MAVEN_CYCLE: test=MavenRunnerAdoptionTest result=<PASS/FAIL> duration=<N>s exec_code_test_calls=<N> bash_mvn_calls=<N>
   ```

5. If MavenRunnerAdoptionTest fails (agent uses Bash instead of exec_code), analyze the decoded log to understand WHY the agent ignored the MavenRunner guidance, and suggest a specific prompt change.

## Constraints

- Run ONE test at a time (Docker container exclusivity)
- Do NOT modify test code — only observe and report
- If tests fail to compile, report the error and stop
