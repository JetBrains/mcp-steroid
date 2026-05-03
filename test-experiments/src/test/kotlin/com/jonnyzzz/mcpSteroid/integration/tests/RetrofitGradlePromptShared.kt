/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

/**
 * Shared task prompt for [RetrofitGradleTest].
 *
 * The agent must drive Gradle through IntelliJ's `GradleRunConfiguration` (NOT
 * `Bash ./gradlew`), capture pass/fail by polling the SM test-runner data model
 * on the run-content descriptor's console, and report the result through the
 * marker contract below.
 *
 * Retrofit is a multi-module Gradle project. The `:retrofit:java-test` subproject
 * holds plain JUnit unit tests (e.g. `retrofit2.HttpExceptionTest`,
 * `retrofit2.CallTest`) that compile and run in tens of seconds. Tests under
 * `retrofit:android-test` need the Android plugin and must be avoided.
 */
internal fun buildRetrofitGradlePrompt(): String = buildString {
    appendLine("The Retrofit Java project (https://github.com/square/retrofit) is open in IntelliJ IDEA. It is a multi-module Gradle project.")
    appendLine()
    appendLine("Your task: pick exactly ONE fast unit-test METHOD, and run it through IntelliJ's Gradle integration.")
    appendLine()
    appendLine("Test selection: a plain JUnit method, ideally in the `:retrofit:java-test` subproject — should compile and run in tens of seconds.")
    appendLine("Avoid integration tests, anything named `*IT` / `*ITest`, anything under `:retrofit:android-test` (needs the Android plugin), and anything that touches the network or large disk fixtures.")
    appendLine("Pick ONE method, not the whole class.")
    appendLine()
    appendLine("Execution: BEFORE your first execution attempt, fetch `mcp-steroid://skill/execute-code-gradle` via `steroid_fetch_resource` and follow the *Agent: Run Gradle Tests (two-call pattern, polling)* recipe verbatim.")
    appendLine("- It uses TWO scripts: call 1 launches via `GradleExternalTaskConfigurationType` + `runConfig.isRunAsTest = true` + `ProgramRunnerUtil.executeConfiguration` on `Dispatchers.EDT`; call 2 polls `descriptor.processHandler` and reads `(descriptor.executionConsole as SMTRunnerConsoleView).resultsViewer.testsRootNode.allTests` for per-test pass/fail.")
    appendLine("- The `isRunAsTest = true` flag is REQUIRED — without it the SM test-runner data model is empty and the polling script returns no results.")
    appendLine("- Re-issue the polling script every ~30s until it prints `TEST_RESULT: PASSED` or `TEST_RESULT: FAILED`. A first-time Gradle test on a fresh checkout (cold daemon, dependency resolve, compile, test) typically takes 60–180 seconds total — so expect 2–6 polling calls.")
    appendLine("- Each individual `steroid_execute_code` script must finish in under 60 seconds. Do NOT try a single-call recipe with `withTimeout(...) { deferred.await() }` — it will be cancelled.")
    appendLine("- Use the subproject task path for targeted tests, e.g. `:retrofit:java-test:test --tests retrofit2.HttpExceptionTest`. Add `--rerun-tasks` so Gradle does not skip the test as UP-TO-DATE.")
    appendLine("Bash and `ProcessBuilder` for Gradle are BANNED — the recipe and the surrounding resource explain why.")
    appendLine()
    appendLine("After your first `steroid_execute_code` call, copy the `execution_id:` line so we can verify MCP was used:")
    appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    appendLine()
    appendLine("At the end of your final response, output these markers on their own lines:")
    appendLine("TEST_CLASS: <fully qualified test class name>")
    appendLine("TEST_METHOD: <test method name>")
    appendLine("GRADLE_SUBPROJECT: <Gradle subproject path you targeted, e.g. :retrofit:java-test>")
    appendLine("EXECUTION_VIA: <the IntelliJ class you invoked — must contain `GradleRunConfiguration`>")
    appendLine("TEST_RESULT: <PASSED or FAILED — based on `processHandler.exitCode == 0` after the run terminates AND `testsRootNode.allTests.none { it.isDefect }`>")
    appendLine("RETROFIT_GRADLE_TEST_RAN: yes")
}

/**
 * Verifies the agent (a) used `steroid_execute_code`, (b) drove Gradle through
 * IntelliJ classes (not Bash/gradlew), and (c) reported a passing test.
 */
internal fun assertRetrofitGradleAgentSucceeded(combined: String) {
    check(combined.contains("RETROFIT_GRADLE_TEST_RAN: yes", ignoreCase = false)) {
        "Agent did not report RETROFIT_GRADLE_TEST_RAN: yes.\nOutput:\n$combined"
    }

    val toolEvidencePatterns = listOf(
        "execution_id:",
        "tool mcp-steroid.steroid_execute_code",
        "steroid_execute_code(",
        "TOOL_EVIDENCE:",
    )
    check(toolEvidencePatterns.any { combined.contains(it, ignoreCase = true) }) {
        "Agent must show evidence of steroid_execute_code usage.\n" +
            "Expected one of: $toolEvidencePatterns\nOutput:\n$combined"
    }

    // The agent's script must mention the IntelliJ Gradle entry-point class — proving
    // it was driven through the IDE, not shelled out to ./gradlew.
    check(combined.contains("GradleRunConfiguration")) {
        "Agent did not drive Gradle through IntelliJ. Expected 'GradleRunConfiguration' " +
            "to appear in the agent's exec_code script (proves use of IDE Gradle integration, " +
            "not Bash/gradlew).\nOutput:\n$combined"
    }

    check(combined.contains("EXECUTION_VIA:")) {
        "Agent did not emit the EXECUTION_VIA marker.\nOutput:\n$combined"
    }
    val executionViaLine = combined.lineSequence()
        .firstOrNull { it.trimStart().startsWith("EXECUTION_VIA:") && !it.contains("<") }
        ?: error("Agent did not emit a concrete EXECUTION_VIA marker (only the placeholder).\nOutput:\n$combined")
    check(executionViaLine.contains("GradleRunConfiguration")) {
        "EXECUTION_VIA marker must name 'GradleRunConfiguration'. Got: '$executionViaLine'\nOutput:\n$combined"
    }

    check(combined.contains("TEST_RESULT: PASSED")) {
        "Agent did not report TEST_RESULT: PASSED — the chosen test must actually pass.\nOutput:\n$combined"
    }

    for (marker in listOf("TEST_CLASS:", "TEST_METHOD:", "GRADLE_SUBPROJECT:")) {
        check(combined.contains(marker)) {
            "Agent did not emit the `$marker` marker.\nOutput:\n$combined"
        }
    }
}
