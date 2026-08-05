/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.arena.AgentToolCall
import com.jonnyzzz.mcpSteroid.integration.arena.decodeAgentFinalResponse
import com.jonnyzzz.mcpSteroid.integration.arena.decodeAgentToolCalls
import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleAwareAgentSession
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Agent-facing usability experiments for devrig's packaged command line.
 *
 * Claude and Codex each take two independent routes through the same clean, preinstalled IDE:
 *  1. a task-first route that must discover the canonical project lister, its requested plural alias,
 *     the legacy singular alias, and code execution without being told their command names;
 *  2. a help-first route that must read root help, use the discoverable `devrig help <command>` route,
 *     and execute every generated tool command with values learned from earlier responses.
 *
 * The agents are deliberately created outside [IntelliJContainer.aiAgents]. The container uses
 * [AiMode.AI_DEVRIG] only to deploy `/home/agent/devrig`; these sessions receive no MCP registration, so
 * every successful IDE action below proves the packaged CLI path rather than a direct MCP-tool shortcut.
 * Assertions decode raw agent NDJSON and correlate native shell calls with their results. The decoded prose
 * transcript is presentation only and is never accepted as execution evidence.
 */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DevrigCliAgentUsabilityExperimentTest {

    @Test
    @Order(1)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `claude discovers and uses the CLI from a task`() = taskFirstExperiment("claude", claude)

    @Test
    @Order(2)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `codex discovers and uses the CLI from a task`() = taskFirstExperiment("codex", codex)

    @Test
    @Order(3)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `claude follows help through missing values to an action`() = helpFirstExperiment("claude", claude)

    @Test
    @Order(4)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `codex follows help through missing values to an action`() = helpFirstExperiment("codex", codex)

    private fun taskFirstExperiment(agentName: String, agent: AiAgentSession) {
        val sentinel = "DEVRIG_TASK_FIRST_${agentName.uppercase()}_OK"
        val prompt = """
            # Task: inspect and exercise an unfamiliar JetBrains IDE command line

            The packaged launcher is `$DEVRIG`. Use only your native shell tool to interact with it.
            Do not call MCP tools directly, and do not inspect repository source code or tests.

            Treat the command syntax as unknown. Start by reading the launcher's root help as a separate
            raw command. From that help, discover the canonical command that lists open IDE projects, its
            plural compatibility alias, and the legacy singular alias retained for older users.

            Run the canonical project-list action and the plural alias as two separate shell commands with
            machine-readable output. Do not pipe, redirect, combine, or transform either command: the raw
            JSON responses are audit evidence. Confirm that both responses carry the same project/backend
            data and that the alias reports the canonical command identity.

            Then, using a project routing key from the canonical response, discover and run the CLI action
            that executes this Kotlin script in the IDE:

                println("$sentinel")

            Use machine-readable output and supply every required audit value the launcher asks for. Run
            every CLI step as a separate shell command; do not use `&&`, `;`, pipelines, redirections, or
            shell wrappers around the launcher.

            Finish with exactly these marker lines:
            CANONICAL_PROJECT_COMMAND: <the canonical command name>
            PROJECT_ALIAS: <the plural compatibility alias>
            LEGACY_PROJECT_ALIAS: <the singular legacy alias>
            ALIAS_EQUIVALENT: yes
            EXECUTION_MARKER: $sentinel
        """.trimIndent()

        val result = agent.runPrompt(prompt, timeoutSeconds = 10 * 60L).awaitForProcessFinish()
        val calls = decodeAgentToolCalls(result.rawStdout)
        assertCliOnly(calls)
        val shellCalls = calls.filter { it.isNativeShellCall() }

        val rootHelpIndex = shellCalls.indexOfFirst { it.invokesRootHelp() && it.succeeded() }
        val canonicalIndex = shellCalls.indexOfFirst {
            it.invokes("list_projects") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val aliasIndex = shellCalls.indexOfFirst {
            it.invokes("projects") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val executeIndex = shellCalls.indexOfFirst {
            it.invokes("execute_code") && it.hasFlag("--json") && !it.hasHelpFlag() &&
                it.succeeded() && sentinel in it.resultText()
        }
        assertOrdered(
            listOf(rootHelpIndex, canonicalIndex, aliasIndex, executeIndex),
            "root help -> canonical list -> plural alias list -> execute_code action",
            shellCalls,
        )

        assertEquals(0, rootHelpIndex, "Root help must be the first task-first shell call. ${summarizeCalls(shellCalls)}")
        val rootHelp = shellCalls[rootHelpIndex].resultText()
        assertTrue("devrig list_projects" in rootHelp) { "Root help did not advertise list_projects:\n$rootHelp" }
        assertTrue("aliases: projects, project" in rootHelp) {
            "Root help did not advertise the plural and legacy project aliases:\n$rootHelp"
        }
        val executeHelpIndex = shellCalls.indexOfFirst {
            it.invokesCommandHelp("execute_code") && it.succeeded()
        }
        if (executeHelpIndex >= 0) {
            assertTrue(executeHelpIndex < executeIndex) {
                "execute_code help was read only after the action. ${summarizeCalls(shellCalls)}"
            }
        }

        val canonicalEnvelope = shellCalls[canonicalIndex].successfulEnvelope("list_projects")
        val aliasEnvelope = shellCalls[aliasIndex].successfulEnvelope("list_projects")
        assertEquals(
            canonicalEnvelope.getValue("data"),
            aliasEnvelope.getValue("data"),
            "projects alias must return exactly the canonical list_projects data",
        )
        val projectName = canonicalEnvelope.firstProjectName()
        assertTrue(projectName in shellCalls[executeIndex].commandText()) {
            "execute_code did not use project_name '$projectName'. ${summarizeCalls(shellCalls)}"
        }
        shellCalls[executeIndex].successfulEnvelope("execute_code")

        val finalResponse = decodeAgentFinalResponse(result.rawStdout).orEmpty()
        assertTrue("CANONICAL_PROJECT_COMMAND: list_projects" in finalResponse) { finalResponse }
        assertTrue("PROJECT_ALIAS: projects" in finalResponse) { finalResponse }
        assertTrue("LEGACY_PROJECT_ALIAS: project" in finalResponse) { finalResponse }
        assertTrue("ALIAS_EQUIVALENT: yes" in finalResponse) { finalResponse }
        assertTrue("EXECUTION_MARKER: $sentinel" in finalResponse) { finalResponse }

        assertAgentExit(agentName, result.exitCode) {
            result.assertExitCode(0) { "$agentName task-first CLI experiment failed" }
        }
    }

    private fun helpFirstExperiment(agentName: String, agent: AiAgentSession) {
        val sentinel = "DEVRIG_HELP_FIRST_${agentName.uppercase()}_OK"
        val taskId = "devrig-help-first-$agentName"
        val prompt = """
            # Task: audit the complete devrig help-to-action route

            The packaged launcher is `$DEVRIG`. Use only your native shell tool. Do not call MCP tools
            directly, inspect repository source code, or inspect tests. Treat all command syntax as unknown.

            First read the launcher root help. It advertises eight generated IDE commands. For each command
            below, read its focused help through the exact `devrig help <command>` route immediately before
            executing its action. Every action must use `--json`, and every help/action must be a separate,
            raw launcher command with no pipes, redirects, variables, aliases, functions, `&&`, or `;`.

            Follow this exact dependency order:

            1. `list_projects`: read help, run it, and retain one real `project_name` plus that project's
               absolute `path` from the raw JSON response.
            2. `list_windows`: read help, run it, and retain the real window id associated with that project.
            3. `execute_code`: read help. Deliberately run it once with only `--json` and no action parameters.
               The non-zero exit is expected; do not hide it. Verify that the structured command-scoped error
               explains how to obtain or choose every missing value. Then immediately recover with the real
               project routing key and run this Kotlin script:

                   println("$sentinel")

               Supply every required audit value, use task id `$taskId`, and retain the returned
               `execution_id`. Reuse that task id in every later command that asks for one.
            4. `execute_feedback`: read help, then rate that exact execution id as successful. Reuse the same
               project and task id; provide a concrete explanation. Before the successful action, run the
               command once with only `--json` and verify that every missing value is explained.
            5. `take_screenshot`: read help, then target the retained project/window and save the image with
               `--out=/tmp/devrig-help-first-$agentName.png`. First run it once with only `--json` and verify
               that every missing value is explained.
            6. `input`: read help, then target the same project/window with the safe, delay-only sequence
               `delay:25`; do not type, press, or click anything. First run it once with only `--json` and
               verify that every missing value is explained.
            7. `fetch_resource`: read help, then fetch `mcp-steroid://prompt/skill` for the retained project.
               First run it once with only `--json` and verify that every missing value is explained.
            8. `open_project`: read help, then safely call it for the already-open absolute project path from
               step 1. First run it once with only `--json` and verify that every missing value is explained.
               Do not open a different path or set optional lifecycle flags.

            Copy values directly into later raw commands; do not use shell variables or command substitution.
            Do not repeat an action or insert unrelated shell commands between a command's help and action.

            Finish with exactly these marker lines:
            HELP_ROUTE: root -> all generated commands
            HELP_COMMAND_ROUTE: complete
            MISSING_PARAMETER_HELP: complete
            EXECUTION_MARKER: $sentinel
        """.trimIndent()

        val result = agent.runPrompt(prompt, timeoutSeconds = 10 * 60L).awaitForProcessFinish()
        val calls = decodeAgentToolCalls(result.rawStdout)
        assertCliOnly(calls)
        val shellCalls = calls.filter { it.isNativeShellCall() }

        val rootHelpIndex = shellCalls.indexOfFirst { it.invokesRootHelp() && it.succeeded() }
        val listHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("list_projects") && it.succeeded() }
        val listActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("list_projects") && it.hasFlag("--json") && it.succeeded()
        }
        val windowsHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("list_windows") && it.succeeded() }
        val windowsActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("list_windows") && it.hasFlag("--json") && it.succeeded()
        }
        val executeHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("execute_code") && it.succeeded() }
        val missingExecuteIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("execute_code") && it.failed()
        }
        val executeActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("execute_code") && it.hasFlag("--json") &&
                it.succeeded() && sentinel in it.resultText()
        }
        val feedbackHelpIndex = shellCalls.indexOfFirst {
            it.invokesHelpCommandRoute("execute_feedback") && it.succeeded()
        }
        val missingFeedbackIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("execute_feedback") && it.failed()
        }
        val feedbackActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("execute_feedback") && it.hasFlag("--json") && it.succeeded()
        }
        val screenshotHelpIndex = shellCalls.indexOfFirst {
            it.invokesHelpCommandRoute("take_screenshot") && it.succeeded()
        }
        val missingScreenshotIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("take_screenshot") && it.failed()
        }
        val screenshotActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("take_screenshot") && it.hasFlag("--json") && it.succeeded()
        }
        val inputHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("input") && it.succeeded() }
        val missingInputIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("input") && it.failed()
        }
        val inputActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("input") && it.hasFlag("--json") && it.succeeded()
        }
        val fetchHelpIndex = shellCalls.indexOfFirst {
            it.invokesHelpCommandRoute("fetch_resource") && it.succeeded()
        }
        val missingFetchIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("fetch_resource") && it.failed()
        }
        val fetchActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("fetch_resource") && it.hasFlag("--json") && it.succeeded()
        }
        val openHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("open_project") && it.succeeded() }
        val missingOpenIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("open_project") && it.failed()
        }
        val openActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("open_project") && it.hasFlag("--json") && it.succeeded()
        }
        assertEquals(
            EXPECTED_HELP_FIRST_SHELL_CALLS,
            shellCalls.size,
            "Help-first must use exactly the required raw shell calls, with no retries or unrelated commands. " +
                summarizeCalls(shellCalls),
        )
        assertEquals(0, rootHelpIndex, "Root help must be the first shell call. ${summarizeCalls(shellCalls)}")
        assertOrdered(
            listOf(
                rootHelpIndex,
                listHelpIndex,
                listActionIndex,
                windowsHelpIndex,
                windowsActionIndex,
                executeHelpIndex,
                missingExecuteIndex,
                executeActionIndex,
                feedbackHelpIndex,
                missingFeedbackIndex,
                feedbackActionIndex,
                screenshotHelpIndex,
                missingScreenshotIndex,
                screenshotActionIndex,
                inputHelpIndex,
                missingInputIndex,
                inputActionIndex,
                fetchHelpIndex,
                missingFetchIndex,
                fetchActionIndex,
                openHelpIndex,
                missingOpenIndex,
                openActionIndex,
            ),
            "root help -> eight generated command help/action routes",
            shellCalls,
        )

        assertImmediatelyBefore(listHelpIndex, listActionIndex, "list_projects", shellCalls)
        assertImmediatelyBefore(windowsHelpIndex, windowsActionIndex, "list_windows", shellCalls)
        assertImmediatelyBefore(executeHelpIndex, missingExecuteIndex, "execute_code missing-value check", shellCalls)
        assertImmediatelyBefore(missingExecuteIndex, executeActionIndex, "execute_code recovery", shellCalls)
        assertRecoverySequence(feedbackHelpIndex, missingFeedbackIndex, feedbackActionIndex, "execute_feedback", shellCalls)
        assertRecoverySequence(
            screenshotHelpIndex,
            missingScreenshotIndex,
            screenshotActionIndex,
            "take_screenshot",
            shellCalls,
        )
        assertRecoverySequence(inputHelpIndex, missingInputIndex, inputActionIndex, "input", shellCalls)
        assertRecoverySequence(fetchHelpIndex, missingFetchIndex, fetchActionIndex, "fetch_resource", shellCalls)
        assertRecoverySequence(openHelpIndex, missingOpenIndex, openActionIndex, "open_project", shellCalls)

        val rootHelp = shellCalls[rootHelpIndex].resultText()
        for (command in GENERATED_COMMANDS) {
            assertTrue("devrig $command" in rootHelp) { "Root help did not advertise $command:\n$rootHelp" }
        }
        for (aliasNote in listOf("aliases: projects, project", "alias: prompt")) {
            assertTrue(aliasNote in rootHelp) { "Root help did not advertise '$aliasNote':\n$rootHelp" }
        }
        assertTrue("devrig help <command>" in rootHelp) {
            "Root help did not advertise the discoverable focused-help route:\n$rootHelp"
        }
        assertCommandHelp(shellCalls[listHelpIndex], "list_projects", "--json")
        assertCommandHelp(shellCalls[windowsHelpIndex], "list_windows", "--json")
        assertCommandHelp(
            shellCalls[executeHelpIndex],
            "execute_code",
            "--project_name",
            "--code",
            "--code-file",
            "--task_id",
            "--reason",
            "--json",
        )
        assertCommandHelp(
            shellCalls[feedbackHelpIndex],
            "execute_feedback",
            "--project_name",
            "--task_id",
            "--execution_id",
            "--success_rating",
            "--explanation",
            "--json",
        )
        assertCommandHelp(
            shellCalls[screenshotHelpIndex],
            "take_screenshot",
            "--project_name",
            "--task_id",
            "--reason",
            "--window_id",
            "--out",
            "--json",
        )
        assertCommandHelp(
            shellCalls[inputHelpIndex],
            "input",
            "--project_name",
            "--task_id",
            "--reason",
            "--window_id",
            "--sequence",
            "--json",
        )
        assertCommandHelp(
            shellCalls[fetchHelpIndex],
            "fetch_resource",
            "--uri",
            "--project_name",
            "--json",
        )
        assertCommandHelp(
            shellCalls[openHelpIndex],
            "open_project",
            "--project_path",
            "--task_id",
            "--reason",
            "--trust_project",
            "--backend_name",
            "--wait",
            "--json",
        )

        val listEnvelope = shellCalls[listActionIndex].successfulEnvelope("list_projects")
        val project = listEnvelope.firstProject()
        val projectName = project.getValue("project_name").jsonPrimitive.content
        val projectPath = project.getValue("path").jsonPrimitive.content
        val windowsEnvelope = shellCalls[windowsActionIndex].successfulEnvelope("list_windows")
        val windowId = windowsEnvelope.windowIdFor(projectName)
        val missingEnvelope = shellCalls[missingExecuteIndex].jsonEnvelope("execute_code", expectedError = true)
        val missingHelp = missingEnvelope.getValue("data").jsonObject
            .getValue("content").jsonArray.single().jsonObject
            .getValue("text").jsonPrimitive.content.lowercase()
        for (requiredText in listOf(
            "usage: devrig execute_code",
            "missing --project_name",
            "devrig list_projects",
            "missing code",
            "--code-file=<path>",
            "missing --task_id",
            "any string works",
            "missing --reason",
            "intent and expected outcome",
        )) {
            assertTrue(requiredText in missingHelp) {
                "Missing execute_code guidance '$requiredText' in expected failure:\n${shellCalls[missingExecuteIndex].resultText()}"
            }
        }
        assertMissingGuidance(
            shellCalls[missingFeedbackIndex],
            "execute_feedback",
            "missing --project_name",
            "devrig list_projects",
            "missing --task_id",
            "any string works",
            "missing --success_rating",
            "0.00..1.00",
            "missing --explanation",
        )
        assertMissingGuidance(
            shellCalls[missingScreenshotIndex],
            "take_screenshot",
            "missing --project_name",
            "devrig list_projects",
            "missing --task_id",
            "any string works",
            "missing --reason",
            "intent and expected outcome",
        )
        assertMissingGuidance(
            shellCalls[missingInputIndex],
            "input",
            "missing --project_name",
            "devrig list_projects",
            "missing --task_id",
            "missing --reason",
            "missing required --window_id",
            "devrig list_windows",
            "missing --sequence",
        )
        assertMissingGuidance(
            shellCalls[missingFetchIndex],
            "fetch_resource",
            "missing --uri",
            "devrig fetch_resource",
            "missing --project_name",
            "devrig list_projects",
        )
        assertMissingGuidance(
            shellCalls[missingOpenIndex],
            "open_project",
            "missing --project_path",
            "absolute directory path",
            "missing --task_id",
            "any string works",
            "missing --reason",
            "intent and expected outcome",
        )

        val executeCall = shellCalls[executeActionIndex]
        assertFlagValue(executeCall, "execute_code", "--project_name", projectName)
        assertFlagValue(executeCall, "execute_code", "--task_id", taskId)
        val executionEnvelope = executeCall.successfulEnvelope("execute_code")
        val executionId = executionEnvelope.executionId()

        val feedbackCall = shellCalls[feedbackActionIndex]
        assertFlagValue(feedbackCall, "execute_feedback", "--project_name", projectName)
        assertFlagValue(feedbackCall, "execute_feedback", "--task_id", taskId)
        assertFlagValue(feedbackCall, "execute_feedback", "--execution_id", executionId)
        assertTrue(feedbackCall.hasFlag("--success_rating") && feedbackCall.hasFlag("--explanation")) {
            "execute_feedback did not carry a rating and explanation. ${summarizeCalls(shellCalls)}"
        }
        feedbackCall.successfulEnvelope("execute_feedback")

        val screenshotCall = shellCalls[screenshotActionIndex]
        val screenshotPath = "/tmp/devrig-help-first-$agentName.png"
        assertFlagValue(screenshotCall, "take_screenshot", "--project_name", projectName)
        assertFlagValue(screenshotCall, "take_screenshot", "--task_id", taskId)
        assertFlagValue(screenshotCall, "take_screenshot", "--window_id", windowId)
        assertFlagValue(screenshotCall, "take_screenshot", "--out", screenshotPath)
        val screenshotEnvelope = screenshotCall.successfulEnvelope("take_screenshot")
        assertEquals(
            screenshotPath,
            screenshotEnvelope.getValue("data").jsonObject.getValue("savedOut").jsonPrimitive.content,
            "take_screenshot did not report the requested savedOut path",
        )

        val inputCall = shellCalls[inputActionIndex]
        assertFlagValue(inputCall, "input", "--project_name", projectName)
        assertFlagValue(inputCall, "input", "--task_id", taskId)
        assertFlagValue(inputCall, "input", "--window_id", windowId)
        assertTrue(Regex("--sequence(?:=|\\s+)(?:['\"])?delay:25(?:['\"])?(?:\\s|$)").containsMatchIn(inputCall.commandText())) {
            "input was not the safe delay-only sequence. ${summarizeCalls(shellCalls)}"
        }
        inputCall.successfulEnvelope("input")

        val fetchCall = shellCalls[fetchActionIndex]
        assertFlagValue(fetchCall, "fetch_resource", "--project_name", projectName)
        assertFlagValue(fetchCall, "fetch_resource", "--uri", "mcp-steroid://prompt/skill")
        fetchCall.successfulEnvelope("fetch_resource")

        val openCall = shellCalls[openActionIndex]
        assertFlagValue(openCall, "open_project", "--project_path", projectPath)
        assertFlagValue(openCall, "open_project", "--task_id", taskId)
        for (optionalFlag in listOf("--wait", "--trust_project", "--no-trust_project", "--backend_name")) {
            assertTrue(!openCall.hasFlag(optionalFlag)) {
                "open_project unexpectedly used optional lifecycle flag $optionalFlag. ${summarizeCalls(shellCalls)}"
            }
        }
        openCall.successfulEnvelope("open_project")

        val finalResponse = decodeAgentFinalResponse(result.rawStdout).orEmpty()
        assertTrue("HELP_ROUTE: root -> all generated commands" in finalResponse) {
            finalResponse
        }
        assertTrue("HELP_COMMAND_ROUTE: complete" in finalResponse) { finalResponse }
        assertTrue("MISSING_PARAMETER_HELP: complete" in finalResponse) { finalResponse }
        assertTrue("EXECUTION_MARKER: $sentinel" in finalResponse) { finalResponse }

        assertAgentExit(agentName, result.exitCode) {
            result.assertExitCode(0) { "$agentName help-first CLI experiment failed" }
        }
    }

    private fun assertCliOnly(calls: List<AgentToolCall>) {
        assertTrue(calls.isNotEmpty()) { "No Claude/Codex tool calls were decoded from raw NDJSON." }
        val nonShellCalls = calls.filterNot { it.isNativeShellCall() }
        assertTrue(nonShellCalls.isEmpty()) {
            "The CLI-only agent used a non-shell tool. ${summarizeCalls(nonShellCalls)}"
        }
    }

    private fun assertOrdered(
        indices: List<Int>,
        expectedRoute: String,
        calls: List<AgentToolCall>,
    ) {
        assertTrue(indices.all { it >= 0 } && indices.zipWithNext().all { (left, right) -> left < right }) {
            "Agent did not follow $expectedRoute; indices=$indices. ${summarizeCalls(calls)}"
        }
    }

    private fun assertImmediatelyBefore(
        firstIndex: Int,
        secondIndex: Int,
        route: String,
        calls: List<AgentToolCall>,
    ) {
        assertEquals(
            firstIndex + 1,
            secondIndex,
            "$route was not performed in consecutive shell calls. ${summarizeCalls(calls)}",
        )
    }

    private fun assertRecoverySequence(
        helpIndex: Int,
        missingIndex: Int,
        actionIndex: Int,
        command: String,
        calls: List<AgentToolCall>,
    ) {
        assertImmediatelyBefore(helpIndex, missingIndex, "$command help -> missing-value check", calls)
        assertImmediatelyBefore(missingIndex, actionIndex, "$command missing-value check -> recovery", calls)
    }

    private fun assertCommandHelp(call: AgentToolCall, command: String, vararg requiredTokens: String) {
        assertTrue(call.invokesHelpCommandRoute(command)) {
            "$command help did not use the exact `devrig help $command` route: ${call.commandText()}"
        }
        assertTrue(call.succeeded()) { "$command help failed: ${call.resultText()}" }
        val help = call.resultText()
        assertTrue("Usage: devrig $command" in help) { "$command returned unfocused help:\n$help" }
        for (token in requiredTokens) {
            assertTrue(token in help) { "$command help did not explain '$token':\n$help" }
        }
    }

    private fun assertFlagValue(call: AgentToolCall, command: String, flag: String, expectedValue: String) {
        assertTrue(call.hasFlagValue(flag, expectedValue)) {
            "$command did not pass $flag with '$expectedValue'. ${summarizeCalls(listOf(call))}"
        }
    }

    private fun assertMissingGuidance(call: AgentToolCall, command: String, vararg requiredText: String) {
        val envelope = call.jsonEnvelope(command, expectedError = true)
        val guidance = envelope.getValue("data").jsonObject
            .getValue("content").jsonArray.single().jsonObject
            .getValue("text").jsonPrimitive.content.lowercase()
        assertTrue("usage: devrig $command" in guidance) {
            "$command missing-value response did not include focused usage:\n$guidance"
        }
        for (expected in requiredText) {
            assertTrue(expected.lowercase() in guidance) {
                "$command missing-value response did not explain '$expected':\n$guidance"
            }
        }
    }

    private fun assertAgentExit(agentName: String, exitCode: Int?, assertNormalExit: () -> Unit) {
        if (agentName == "codex" && exitCode == 137) {
            session.console.writeInfo("Codex exited with 137 after all CLI workflow evidence passed")
        } else {
            assertNormalExit()
        }
    }

    private fun AgentToolCall.isNativeShellCall(): Boolean =
        toolName.equals("Bash", ignoreCase = true) || toolName == "command_execution"

    private fun AgentToolCall.commandText(): String = when (val command = arguments["command"]) {
        is JsonPrimitive -> command.content
        null -> ""
        else -> command.toString()
    }

    private fun AgentToolCall.normalizedRawDevrigCommand(): String? {
        val normalized = commandText().trim()
            .replace("\"$DEVRIG\"", DEVRIG)
            .replace("'$DEVRIG'", DEVRIG)
        if (!Regex("^\\Q$DEVRIG\\E(?:\\s|$)").containsMatchIn(normalized)) return null
        if (normalized.hasUnquotedShellControlSyntax()) return null
        return normalized
    }

    private fun String.hasUnquotedShellControlSyntax(): Boolean {
        var quote: Char? = null
        var escaped = false
        for ((index, ch) in withIndex()) {
            if (ch == '\n' || ch == '\r') return true
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && quote != '\'') {
                escaped = true
                continue
            }
            if (quote == '\'') {
                if (ch == '\'') quote = null
                continue
            }
            if (quote == '"') {
                if (ch == '"') {
                    quote = null
                } else if (ch == '`' || ch == '$' && getOrNull(index + 1) == '(') {
                    return true
                }
                continue
            }
            when (ch) {
                '\'', '"' -> quote = ch
                ';', '|', '&', '<', '>', '`', '$' -> return true
            }
        }
        return false
    }

    private fun AgentToolCall.invokesRootHelp(): Boolean = normalizedRawDevrigCommand() in setOf(
        DEVRIG,
        "$DEVRIG --help",
        "$DEVRIG -h",
        "$DEVRIG help",
    )

    private fun AgentToolCall.invokesCommandHelp(command: String): Boolean =
        normalizedRawDevrigCommand() in setOf(
            "$DEVRIG $command --help",
            "$DEVRIG $command -h",
            "$DEVRIG help $command",
        )

    private fun AgentToolCall.invokesHelpCommandRoute(command: String): Boolean =
        normalizedRawDevrigCommand() == "$DEVRIG help $command"

    private fun AgentToolCall.invokesAction(command: String): Boolean = invokes(command) && !hasHelpFlag()

    private fun AgentToolCall.invokesJsonOnlyAction(command: String): Boolean =
        normalizedRawDevrigCommand() == "$DEVRIG $command --json"

    private fun AgentToolCall.invokes(subcommand: String): Boolean {
        val normalized = normalizedRawDevrigCommand() ?: return false
        return Regex("^\\Q$DEVRIG\\E\\s+\\Q$subcommand\\E(?:\\s|$)").containsMatchIn(normalized)
    }

    private fun AgentToolCall.hasFlag(flag: String): Boolean =
        Regex("(?:^|\\s)\\Q$flag\\E(?:=|\\s|$)").containsMatchIn(commandText())

    private fun AgentToolCall.hasFlagValue(flag: String, value: String): Boolean {
        val escapedFlag = Regex.escape(flag)
        val escapedValue = Regex.escape(value)
        return Regex(
            "(?:^|\\s)$escapedFlag(?:=|\\s+)(?:\"$escapedValue\"|'$escapedValue'|$escapedValue)(?:\\s|$)",
        ).containsMatchIn(commandText())
    }

    private fun AgentToolCall.hasHelpFlag(): Boolean = hasFlag("--help") || hasFlag("-h")

    private fun AgentToolCall.succeeded(): Boolean = result?.isError == false

    private fun AgentToolCall.failed(): Boolean = result?.isError == true

    private fun AgentToolCall.resultText(): String = result?.text.orEmpty()

    private fun AgentToolCall.successfulEnvelope(expectedCommand: String): JsonObject {
        assertTrue(succeeded()) { "$expectedCommand did not succeed: ${resultText()}" }
        return jsonEnvelope(expectedCommand, expectedError = false)
    }

    private fun AgentToolCall.jsonEnvelope(expectedCommand: String, expectedError: Boolean): JsonObject {
        val text = resultText().trim()
        val envelope = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("tool", "command", "isError", "data"), envelope.keys, envelope.toString())
        assertEquals("steroid_$expectedCommand", envelope.getValue("tool").jsonPrimitive.content, envelope.toString())
        assertEquals(expectedCommand, envelope.getValue("command").jsonPrimitive.content, envelope.toString())
        val isError = envelope.getValue("isError").jsonPrimitive
        assertTrue(!isError.isString, "isError must be a JSON boolean: $envelope")
        assertEquals(expectedError.toString(), isError.content, envelope.toString())
        envelope.getValue("data").jsonObject
        return envelope
    }

    private fun JsonObject.toolJson(): JsonObject = getValue("data").jsonObject
            .getValue("content").jsonArray
            .single().jsonObject
            .getValue("json").jsonObject

    private fun JsonObject.firstProject(): JsonObject {
        val projects = toolJson()
            .getValue("projects").jsonArray
        assertTrue(projects.isNotEmpty()) { "list_projects returned no project in the preinstalled IDE: $this" }
        return projects.first().jsonObject
    }

    private fun JsonObject.firstProjectName(): String =
        firstProject().getValue("project_name").jsonPrimitive.content

    private fun JsonObject.windowIdFor(projectName: String): String {
        val windows = toolJson().getValue("windows").jsonArray.map { it.jsonObject }
        val window = windows.firstOrNull {
            it["project_name"]?.jsonPrimitive?.content == projectName
        }
        assertTrue(window != null) {
            "list_windows returned no window for project_name '$projectName': $this"
        }
        val selectedWindow = requireNotNull(window)
        val id = selectedWindow["window_id"] ?: selectedWindow["windowId"]
        assertTrue(id != null) { "list_windows returned a project window without a window id: $selectedWindow" }
        return requireNotNull(id).jsonPrimitive.content
    }

    private fun JsonObject.executionId(): String {
        val text = getValue("data").jsonObject
            .getValue("content").jsonArray
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            .joinToString("\n")
        val match = Regex("(?m)^execution_id:\\s*(\\S+)").find(text)
        assertTrue(match != null) { "execute_code returned no execution_id in its canonical envelope: $this" }
        return requireNotNull(match).groupValues[1]
    }

    private fun summarizeCalls(calls: List<AgentToolCall>): String = calls.joinToString(
        prefix = "Calls: ",
        limit = 30,
    ) { call -> "${call.toolName}(${call.commandText()}) result=${call.result?.isError}" }

    companion object {
        private const val DEVRIG = "/home/agent/devrig"
        private val GENERATED_COMMANDS = listOf(
            "list_projects",
            "list_windows",
            "execute_code",
            "execute_feedback",
            "take_screenshot",
            "input",
            "fetch_resource",
            "open_project",
        )
        private const val EXPECTED_HELP_FIRST_SHELL_CALLS = 23

        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                IntelliJContainerOpts(
                    consoleTitle = "devrig-cli-agent-usability",
                    project = IntelliJProject.EmptyProject,
                    aiMode = AiMode.AI_DEVRIG,
                ),
            ).waitForProjectReady()
        }

        val claude by lazy {
            ConsoleAwareAgentSession(
                delegate = DockerClaudeSession.create(session.scope),
                console = session.console,
                agentName = "claude-cli-usability",
                logDir = session.runDirInContainer,
            )
        }

        val codex by lazy {
            ConsoleAwareAgentSession(
                delegate = DockerCodexSession.create(session.scope),
                console = session.console,
                agentName = "codex-cli-usability",
                logDir = session.runDirInContainer,
            )
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
