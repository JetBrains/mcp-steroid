/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

internal data class RealWorldMavenProjectPrompt(
    val projectName: String,
    val projectUrl: String,
    val description: String,
    val testSelection: String,
    val moduleExample: String,
    val successMarker: String,
)

internal fun buildRealWorldMavenProjectPrompt(config: RealWorldMavenProjectPrompt): String = buildString {
    appendLine("The ${config.projectName} project (${config.projectUrl}) is open in IntelliJ IDEA. ${config.description}")
    appendLine()
    appendLine("Your task: pick exactly ONE fast unit-test METHOD, and run it through IntelliJ's Maven integration.")
    appendLine()
    appendLine("Test selection: ${config.testSelection}")
    appendLine("Avoid integration tests, anything named `*IT` / `*ITest`, anything using `@Testcontainers`, anything that boots a server, anything that needs Docker, and anything that touches the network or large disk fixtures.")
    appendLine("Pick ONE method, not the whole class.")
    appendLine()
    appendLine("Execution: BEFORE your first execution attempt, fetch `mcp-steroid://skill/execute-code-maven` via `steroid_fetch_resource` and follow the *Agent: Run One Maven Test Method (two-call pattern)* recipe verbatim.")
    appendLine("- It uses TWO scripts: call 1 launches via `MavenRunConfigurationType.createRunnerAndConfigurationSettings` + `ProgramRunnerUtil.executeConfiguration` on `Dispatchers.EDT`; call 2 polls `descriptor.processHandler` and reads the surefire `<TestClass>.txt` summary.")
    appendLine("- Re-issue the polling script every ~30s until it prints `TEST_RESULT: PASSED` or `TEST_RESULT: FAILED`. A Maven test on a fresh checkout typically takes 30-180 seconds total, so expect 1-6 polling calls.")
    appendLine("- Each individual `steroid_execute_code` script must finish in under 60 seconds. Do not use a single-call recipe with `withTimeout(...) { deferred.await() }` because it will be cancelled.")
    appendLine("- If the first run fails because a sibling Maven module is missing from `~/.m2`, use the *Sibling-install fallback* from the recipe: install just that one module via the same `createRunnerAndConfigurationSettings` shape with goals `install -pl <missing-module> -DskipTests`. Then retry the targeted test.")
    appendLine("Bash and `ProcessBuilder` for Maven are BANNED; this test is only useful when Maven is driven through IntelliJ.")
    appendLine()
    appendLine("After your first `steroid_execute_code` call, copy the `execution_id:` line so we can verify MCP was used:")
    appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    appendLine()
    appendLine("At the end of your final response, output these markers on their own lines:")
    appendLine("TEST_CLASS: <fully qualified test class name>")
    appendLine("TEST_METHOD: <test method name>")
    appendLine("MAVEN_MODULE: <Maven submodule path you targeted, e.g. ${config.moduleExample}>")
    appendLine("EXECUTION_VIA: <the IntelliJ class you invoked - must contain `MavenRunConfigurationType` or `MavenRunner`>")
    appendLine("TEST_RESULT: <PASSED or FAILED - based on `processHandler.exitCode == 0` after the run terminates>")
    appendLine("${config.successMarker}: yes")
}

internal fun assertRealWorldMavenAgentSucceeded(combined: String, successMarker: String) {
    check(combined.contains("$successMarker: yes", ignoreCase = false)) {
        "Agent did not report $successMarker: yes.\nOutput:\n$combined"
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

    val intellijMavenClasses = listOf("MavenRunConfigurationType", "MavenRunner")
    check(intellijMavenClasses.any { combined.contains(it) }) {
        "Agent did not drive Maven through IntelliJ. Expected one of $intellijMavenClasses " +
            "to appear in the agent's exec_code script.\nOutput:\n$combined"
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
        "Agent did not report TEST_RESULT: PASSED - the chosen test must actually pass.\nOutput:\n$combined"
    }

    for (marker in listOf("TEST_CLASS:", "TEST_METHOD:", "MAVEN_MODULE:")) {
        check(combined.contains(marker)) {
            "Agent did not emit the `$marker` marker.\nOutput:\n$combined"
        }
    }
}
