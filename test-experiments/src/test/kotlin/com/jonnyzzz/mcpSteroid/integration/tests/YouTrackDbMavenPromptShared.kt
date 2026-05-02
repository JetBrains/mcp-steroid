/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

/**
 * Shared task prompt for [YouTrackDbMavenTest] and its IDE-version-pinned siblings
 * ([YouTrackDbMaven253Test], [YouTrackDbMaven261Test]).
 *
 * The prompt forces the agent to drive Maven through IntelliJ's own integration
 * (`MavenRunConfigurationType` + `SMTRunnerEventsListener`) instead of shelling
 * out to `./mvnw`. That is the entire point of MCP Steroid: agents should control
 * the IDE, not bypass it. A "passes" result here is meaningless if the agent
 * achieved it via Bash — that path doesn't exercise PSI/VFS, the Maven sync
 * machinery, or run-configuration creation.
 */
internal fun buildYouTrackDbMavenPrompt(): String = buildString {
    appendLine("The youtrackdb Java project is open in IntelliJ IDEA. It is a multi-module Apache Maven project.")
    appendLine()
    appendLine("Your task: pick exactly ONE fast unit-test METHOD, and run it through IntelliJ's Maven integration.")
    appendLine()
    appendLine("Test selection: a plain JUnit method, ideally in the `core` module — should compile and run in tens of seconds.")
    appendLine("Avoid integration tests, anything named `*IT` / `*ITest`, anything using `@Testcontainers`, anything that boots a server, and anything that touches the network or large disk fixtures.")
    appendLine("Pick ONE method, not the whole class.")
    appendLine()
    appendLine("Execution: BEFORE your first execution attempt, fetch `mcp-steroid://skill/execute-code-maven` via `steroid_fetch_resource` and follow the *Agent: Run One Maven Test Method (two-call pattern)* recipe verbatim.")
    appendLine("- It uses TWO scripts: call 1 launches via `MavenRunConfigurationType` and stores a `CompletableDeferred` in `project.userData`; call 2 polls that deferred with `withTimeoutOrNull(30.seconds)`.")
    appendLine("- Re-issue the polling script every ~30s until it prints `TEST_RESULT: PASSED` or `TEST_RESULT: FAILED`. A Maven test on a fresh checkout typically takes 30–120 seconds total — so expect 1–4 polling calls.")
    appendLine("- Each individual `steroid_execute_code` script must finish in under 60 seconds (claude-code's MCP HTTP client cancels longer requests). Do NOT try a single-call recipe with `withTimeout(5.minutes) { deferred.await() }` — it will be cancelled.")
    appendLine("Bash and `ProcessBuilder` for Maven are BANNED — the recipe and the surrounding resource explain why.")
    appendLine()
    appendLine("After your first `steroid_execute_code` call, copy the `execution_id:` line so we can verify MCP was used:")
    appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    appendLine()
    appendLine("At the end of your final response, output these markers on their own lines:")
    appendLine("TEST_CLASS: <fully qualified test class name>")
    appendLine("TEST_METHOD: <test method name>")
    appendLine("MAVEN_MODULE: <Maven submodule path you targeted, e.g. core>")
    appendLine("EXECUTION_VIA: <the IntelliJ class you invoked — must contain `MavenRunConfigurationType` or `MavenRunner`>")
    appendLine("TEST_RESULT: <PASSED or FAILED — the value of `testsRoot.isPassed` from SMTRunnerEventsListener>")
    appendLine("YOUTRACKDB_MAVEN_TEST_RAN: yes")
}

/**
 * Verifies the agent (a) used `steroid_execute_code`, (b) drove Maven through
 * IntelliJ classes (not Bash/mvnw), and (c) reported a passing test.
 */
internal fun assertYouTrackDbMavenAgentSucceeded(combined: String) {
    check(combined.contains("YOUTRACKDB_MAVEN_TEST_RAN: yes", ignoreCase = false)) {
        "Agent did not report YOUTRACKDB_MAVEN_TEST_RAN: yes.\nOutput:\n$combined"
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

    // The agent's script must mention the IntelliJ Maven entry-point class — proving
    // it was driven through the IDE, not shelled out to `./mvnw`.
    val intellijMavenClasses = listOf("MavenRunConfigurationType", "MavenRunner")
    check(intellijMavenClasses.any { combined.contains(it) }) {
        "Agent did not drive Maven through IntelliJ. Expected one of $intellijMavenClasses " +
            "to appear in the agent's exec_code script (proves use of IDE Maven integration, " +
            "not Bash/mvnw).\nOutput:\n$combined"
    }

    check(combined.contains("EXECUTION_VIA:")) {
        "Agent did not emit the EXECUTION_VIA marker.\nOutput:\n$combined"
    }
    val executionViaLine = combined.lineSequence()
        .firstOrNull { it.trimStart().startsWith("EXECUTION_VIA:") && !it.contains("<") }
        ?: error("Agent did not emit a concrete EXECUTION_VIA marker (only the placeholder).\nOutput:\n$combined")
    check(intellijMavenClasses.any { executionViaLine.contains(it) }) {
        "EXECUTION_VIA marker must name an IntelliJ Maven class ($intellijMavenClasses). " +
            "Got: '$executionViaLine'\nOutput:\n$combined"
    }

    check(combined.contains("TEST_RESULT: PASSED")) {
        "Agent did not report TEST_RESULT: PASSED — the chosen test must actually pass " +
            "(captured via testsRoot.isPassed inside SMTRunnerEventsListener).\nOutput:\n$combined"
    }

    for (marker in listOf("TEST_CLASS:", "TEST_METHOD:", "MAVEN_MODULE:")) {
        check(combined.contains(marker)) {
            "Agent did not emit the `$marker` marker.\nOutput:\n$combined"
        }
    }
}
