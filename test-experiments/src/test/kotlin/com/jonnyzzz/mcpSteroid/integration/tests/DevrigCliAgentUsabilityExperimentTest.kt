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
 *  1. a task-first route that must discover the canonical project lister, its compatibility alias,
 *     and code execution without being told their command names;
 *  2. a help-first route that must read root and command help, observe every missing execute-code value,
 *     and recover to a successful action.
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

            Treat the command syntax as unknown. Discover from the launcher itself which command is the
            canonical way to list open IDE projects and which command is its backwards-compatible alias.

            Run the canonical project-list action and its alias as two separate shell commands with
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
            PROJECT_ALIAS: <the compatibility alias>
            ALIAS_EQUIVALENT: yes
            EXECUTION_MARKER: $sentinel
        """.trimIndent()

        val result = agent.runPrompt(prompt, timeoutSeconds = 10 * 60L).awaitForProcessFinish()
        val calls = decodeAgentToolCalls(result.rawStdout)
        assertCliOnly(calls)
        val shellCalls = calls.filter { it.isNativeShellCall() }

        val canonicalIndex = shellCalls.indexOfFirst {
            it.invokes("list_projects") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val aliasIndex = shellCalls.indexOfFirst {
            it.invokes("project") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val executeIndex = shellCalls.indexOfFirst {
            it.invokes("execute_code") && it.hasFlag("--json") && !it.hasHelpFlag() &&
                it.succeeded() && sentinel in it.resultText()
        }
        assertOrdered(
            listOf(canonicalIndex, aliasIndex, executeIndex),
            "canonical list -> alias list -> execute_code action",
            shellCalls,
        )

        val rootHelpIndex = shellCalls.indexOfFirst { it.invokesRootHelp() && it.succeeded() }
        if (rootHelpIndex >= 0) {
            val rootHelp = shellCalls[rootHelpIndex].resultText()
            assertTrue("devrig list_projects" in rootHelp) { "Root help did not advertise list_projects:\n$rootHelp" }
            assertTrue("alias: project" in rootHelp) { "Root help did not advertise project as the alias:\n$rootHelp" }
        }
        val executeHelpIndex = shellCalls.indexOfFirst {
            it.invokes("execute_code") && it.hasHelpFlag() && it.succeeded()
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
            "project alias must return exactly the canonical list_projects data",
        )
        val projectName = canonicalEnvelope.firstProjectName()
        assertTrue(projectName in shellCalls[executeIndex].commandText()) {
            "execute_code did not use project_name '$projectName'. ${summarizeCalls(shellCalls)}"
        }
        shellCalls[executeIndex].successfulEnvelope("execute_code")

        val finalResponse = decodeAgentFinalResponse(result.rawStdout).orEmpty()
        assertTrue("CANONICAL_PROJECT_COMMAND: list_projects" in finalResponse) { finalResponse }
        assertTrue("PROJECT_ALIAS: project" in finalResponse) { finalResponse }
        assertTrue("ALIAS_EQUIVALENT: yes" in finalResponse) { finalResponse }
        assertTrue("EXECUTION_MARKER: $sentinel" in finalResponse) { finalResponse }

        assertAgentExit(agentName, result.exitCode) {
            result.assertExitCode(0) { "$agentName task-first CLI experiment failed" }
        }
    }

    private fun helpFirstExperiment(agentName: String, agent: AiAgentSession) {
        val sentinel = "DEVRIG_HELP_FIRST_${agentName.uppercase()}_OK"
        val prompt = """
            # Task: audit the complete devrig help-to-action route

            The packaged launcher is `$DEVRIG`. Use only your native shell tool. Do not call MCP tools
            directly, inspect repository source code, or inspect tests. Treat all command syntax as unknown.

            Perform these steps as separate shell commands, in this exact order:

            1. Read the launcher root help.
            2. From root help, identify the canonical command that lists open IDE projects. Read that
               command's own help.
            3. Run that project-list command with raw machine-readable output. Do not pipe or redirect it.
            4. From root help, identify the command that executes Kotlin code in an IDE. Read that command's
               own help.
            5. Deliberately run the code-execution command once with only its machine-readable-output flag
               and no action parameters. This failure is expected: do not add `|| true`, redirect it, or hide
               its exit status. Read the structured command-scoped error and verify that it explains how to
               obtain or choose every missing value.
            6. Recover using only the earlier help/error plus the real project routing key from step 3. Run
               the code-execution action with machine-readable output and this Kotlin script:

                   println("$sentinel")

            Supply all audit parameters that help requires. Do not combine steps with `&&`, `;`, pipelines,
            redirections, variables, aliases, functions, or other shell wrappers around the launcher.

            Finish with exactly these marker lines:
            HELP_ROUTE: root -> project-list help/action -> code-execution help/error/action
            MISSING_PARAMETER_HELP: complete
            EXECUTION_MARKER: $sentinel
        """.trimIndent()

        val result = agent.runPrompt(prompt, timeoutSeconds = 10 * 60L).awaitForProcessFinish()
        val calls = decodeAgentToolCalls(result.rawStdout)
        assertCliOnly(calls)
        val shellCalls = calls.filter { it.isNativeShellCall() }

        val rootHelpIndex = shellCalls.indexOfFirst { it.invokesRootHelp() && it.succeeded() }
        val listHelpIndex = shellCalls.indexOfFirst {
            it.invokes("list_projects") && it.hasHelpFlag() && it.succeeded()
        }
        val listActionIndex = shellCalls.indexOfFirst {
            it.invokes("list_projects") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val executeHelpIndex = shellCalls.indexOfFirst {
            it.invokes("execute_code") && it.hasHelpFlag() && it.succeeded()
        }
        val missingExecuteIndex = shellCalls.indexOfFirst {
            it.invokes("execute_code") && !it.hasHelpFlag() && it.hasFlag("--json") && it.failed()
        }
        val executeActionIndex = shellCalls.indexOfFirst {
            it.invokes("execute_code") && it.hasFlag("--json") && !it.hasHelpFlag() &&
                it.succeeded() && sentinel in it.resultText()
        }
        assertOrdered(
            listOf(
                rootHelpIndex,
                listHelpIndex,
                listActionIndex,
                executeHelpIndex,
                missingExecuteIndex,
                executeActionIndex,
            ),
            "root help -> list_projects help/action -> execute_code help/error/action",
            shellCalls,
        )

        val listEnvelope = shellCalls[listActionIndex].successfulEnvelope("list_projects")
        val projectName = listEnvelope.firstProjectName()
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

        val executeCall = shellCalls[executeActionIndex]
        assertTrue(projectName in executeCall.commandText()) {
            "Recovered execute_code did not use project_name '$projectName'. ${summarizeCalls(shellCalls)}"
        }
        executeCall.successfulEnvelope("execute_code")

        val finalResponse = decodeAgentFinalResponse(result.rawStdout).orEmpty()
        assertTrue("HELP_ROUTE: root -> project-list help/action -> code-execution help/error/action" in finalResponse) {
            finalResponse
        }
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

    private fun AgentToolCall.invokesRootHelp(): Boolean =
        invokes("--help") || invokes("-h") || invokes("help")

    private fun AgentToolCall.invokes(subcommand: String): Boolean {
        val normalized = commandText()
            .replace("\"$DEVRIG\"", DEVRIG)
            .replace("'$DEVRIG'", DEVRIG)
        return Regex("\\Q$DEVRIG\\E\\s+\\Q$subcommand\\E(?:\\s|$)").containsMatchIn(normalized)
    }

    private fun AgentToolCall.hasFlag(flag: String): Boolean =
        Regex("(?:^|\\s)\\Q$flag\\E(?:=|\\s|$)").containsMatchIn(commandText())

    private fun AgentToolCall.hasHelpFlag(): Boolean = hasFlag("--help") || hasFlag("-h")

    private fun AgentToolCall.succeeded(): Boolean = result?.isError == false

    private fun AgentToolCall.failed(): Boolean = result?.isError == true

    private fun AgentToolCall.resultText(): String = result?.text.orEmpty()

    private fun AgentToolCall.successfulEnvelope(expectedCommand: String): JsonObject {
        assertTrue(succeeded()) { "$expectedCommand did not succeed: ${resultText()}" }
        return jsonEnvelope(expectedCommand, expectedError = false)
    }

    private fun AgentToolCall.jsonEnvelope(expectedCommand: String, expectedError: Boolean): JsonObject {
        val text = resultText()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        assertTrue(start in 0 until end) { "$expectedCommand did not emit a JSON envelope:\n$text" }
        val envelope = Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        assertEquals(expectedCommand, envelope.getValue("command").jsonPrimitive.content, envelope.toString())
        assertEquals(expectedError.toString(), envelope.getValue("isError").jsonPrimitive.content, envelope.toString())
        return envelope
    }

    private fun JsonObject.firstProjectName(): String {
        val projects = getValue("data").jsonObject
            .getValue("content").jsonArray
            .single().jsonObject
            .getValue("json").jsonObject
            .getValue("projects").jsonArray
        assertTrue(projects.isNotEmpty()) { "list_projects returned no project in the preinstalled IDE: $this" }
        return projects.first().jsonObject.getValue("project_name").jsonPrimitive.content
    }

    private fun summarizeCalls(calls: List<AgentToolCall>): String = calls.joinToString(
        prefix = "Calls: ",
        limit = 30,
    ) { call -> "${call.toolName}(${call.commandText()}) result=${call.result?.isError}" }

    companion object {
        private const val DEVRIG = "/home/agent/devrig"

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
