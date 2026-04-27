/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.testHelper.*
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import kotlinx.serialization.json.*
import java.util.*
import kotlin.time.Duration.Companion.seconds

abstract class CliIntegrationTestBase : BasePlatformTestCase() {
    val lifetime by lazy {
        CloseableStackHost().apply {
            Disposer.register(testRootDisposable, this::closeAllStacks)
        }
    }

    override fun setUp() {
        setServerPortProperties()
        super.setUp()
    }

    protected abstract fun createAiSession(): AiAgentSession

    protected open fun newAiSession(): AiAgentSession {
        val ai = createAiSession()
        ai.registerHttpMcp(resolveDockerUrl(), "intellij")
        return ai
    }

    /**
     * This test validates the discovery of tools and the use of the CLI.
     * Uses Docker to run the CLI in isolation.
     *
     * Note: This test requires ANTHROPIC_API_KEY and uses print mode (-p),
     * which runs without user interaction.
     *
     * ============================================================================
     * TEST INTEGRITY: This test verifies ACTUAL MCP tool calls, not just mentions.
     * ============================================================================
     *
     * Success criteria (ALL must be met):
     * 1. No ERROR patterns in AI's output
     * 2. AI must list tools with a "TOOL:" prefix (actual tool discovery)
     * 3. AI must call steroid_list_projects and show "PROJECTS:" output
     * 4. The PROJECTS output must contain actual project data (not an error)
     *
     * If any of these fail, the test fails. Do not weaken these assertions.
     */
    open fun testDiscoversSteroidTools(): Unit = timeoutRunBlocking(300.seconds) {
        val session = newAiSession()
        val result = session.runPrompt(
            """
            You are testing an MCP Steroid integration. You MUST use the MCP tools.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            Steps:
            1) List all MCP tools starting with "steroid_" and print each as: TOOL: <name> - <description>
            2) Call steroid_list_projects EXACTLY once and print the raw result prefixed with PROJECTS:
            Answer only 'TOOLS: <your tools list>'
            """.trimIndent(),
        )
            .assertExitCode(0) { "prompt failed" }
            .assertOutputContains(message = "TOOLS:")
            .assertOutputContains(message = "steroid_execute_code")
    }

    /**
     * This test verifies that the MCP execute_code tool can read a system property. The IDE JVM sets it.
     * This verifies the MCP server uses the same JVM and can access system properties.
     *
     * The test:
     * 1. Sets a system property with a random UUID value
     * 2. Asks AI to read it via steroid_execute_code
     * 3. Verifies AI's output contains the correct value
     */
    open fun testSystemPropertyCanBeRead(): Unit = timeoutRunBlocking(300.seconds) {
        val session = newAiSession()

        // Set a system property with a random value
        val propertyKey = "mcp.test.ai.random.value"
        val randomValue = "ai-${UUID.randomUUID()}"
        System.setProperty(propertyKey, randomValue)

        // Ask AI to read the system property using execute_code
        session.runPrompt(
            """
                You are testing MCP integration. You MUST use steroid_execute_code to run Kotlin code.
                Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
                First, call steroid_list_projects exactly once and take the first project's "name" as PROJECT_NAME.
                For steroid_execute_code, always pass project_name=${project.name}.
                IMPORTANT: "intellij" is the MCP server name, not a valid project_name value.
                Execute the following code and print the result:

                Call steroid_execute_code with this code:
                ```
                val value = System.getProperty("$propertyKey")
                println("SYSPROP_VALUE: " + value)
                ```

                After execution, extract the SYSPROP_VALUE line from the output and print it as:
                FINAL_VALUE: <the value you found>
                
                Ensure the output is plain text. Do NOT use bold, italics, or code blocks for the FINAL_VALUE line.
                Example:
                FINAL_VALUE: ai-1234-5678

                If you encounter any errors, print: ERROR: <description>
                """,
            timeoutSeconds = 240
        )
            .assertExitCode(0) { "prompt failed" }
            .assertOutputContains(
                randomValue,
                "FINAL_VALUE: $randomValue",
                message = "Output should contain the system property value '$randomValue'"
            )
    }

    /**
     * Tests that `forgetAllForTest()` truly restarts the MCP server, breaking the
     * HTTP connection, and that the agent can recover and continue on the restarted server.
     *
     * Call #2 invokes `forgetAllForTest()` which stops the Ktor server mid-request.
     * The HTTP connection carrying the response is broken, so the agent sees an error.
     * This is expected and desired — it proves the server truly restarted.
     *
     * Call #3 runs on the restarted server with a fresh session. It verifies
     * that session count is 1 (only the agent's new reconnected session).
     */
    /**
     * Tests that compilation errors from broken Kotlin code are delivered back to the agent
     * in the tool result. The code has a deliberate type mismatch (assigning Int to String).
     * The merged tool result text must contain the compiler error message.
     */
    open fun testCompilationErrorsDelivered(): Unit = timeoutRunBlocking(300.seconds) {
        val session = newAiSession()

        val result = session.runPrompt(
            """
            You are testing MCP integration. You MUST call steroid_execute_code to run Kotlin code.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            For steroid_execute_code, always pass project_name=${project.name}.

            Your task: execute BROKEN Kotlin code and report the compiler error.

            Call steroid_execute_code with EXACTLY this code (copy it verbatim, do NOT fix it):
            val x: String = 123
            println(x)

            This code is INTENTIONALLY broken — assigning Int 123 to a String variable.
            The tool call WILL fail with isError=true. That is expected and correct.
            Do NOT retry, do NOT fix the code, do NOT try alternative code.

            After the failed tool call, look at the tool response text for "type mismatch" or "error:".
            Print exactly these two lines:

            COMPILE_ERROR: <copy the line from the tool response that contains "type mismatch" or "error:">
            HAS_TYPE_ERROR: YES

            If the tool response does NOT mention "type mismatch", print:
            COMPILE_ERROR: <whatever error text you see>
            HAS_TYPE_ERROR: NO

            Output plain text only. No markdown, no bold, no code blocks.
            """.trimIndent(),
            timeoutSeconds = 240
        )
            .assertExitCode(0) { "prompt failed" }

        val combinedOutput = result.stdout + "\n" + result.stderr

        println("=== AGENT OUTPUT (testCompilationErrorsDelivered) ===")
        println(combinedOutput)
        println("=== END ===")

        // Assert on compiler-specific text that ONLY appears in the kotlinc error output,
        // not in the tool call reason or task ID. The compiler says:
        // "error: initializer type mismatch: expected 'String', actual 'Int'."
        assertTrue(
            "Output should contain compiler error with expected/actual types.\n" +
                    "If this fails, the agent cannot see the compilation error details.\n$combinedOutput",
            combinedOutput.contains("expected", ignoreCase = true)
                    && combinedOutput.contains("actual", ignoreCase = true),
        )
    }

    /**
     * Tests that compiler warnings are delivered back to the agent in the tool result.
     * The code uses an unchecked cast which produces a warning but still executes.
     */
    open fun testCompilationWarningsDelivered(): Unit = timeoutRunBlocking(300.seconds) {
        val session = newAiSession()

        val result = session.runPrompt(
            """
            You are testing MCP integration. You MUST call steroid_execute_code to run Kotlin code.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            For steroid_execute_code, always pass project_name=${project.name}.

            Your task: execute code that produces a compiler warning, then report it.

            Call steroid_execute_code with EXACTLY this code (copy it verbatim, do NOT modify it):
            val items: List<Any> = listOf("hello", "world")
            @Suppress("NOTHING_TO_SUPPRESS")
            val strings: List<String> = items as List<String>
            println("WARNING_TEST_VALUE: " + strings.joinToString(","))

            The code will compile and run. The tool response will contain BOTH:
            - A "Compiler Errors/Warnings:" section with "warning: unchecked cast" text
            - The println output "WARNING_TEST_VALUE: hello,world"

            After execution, print exactly these two lines:
            EXEC_RESULT: <the println output line from the tool response>
            COMPILER_WARNING: <copy the warning line that contains "warning:" from the tool response>

            If you do not see any warnings, print: COMPILER_WARNING: NONE

            Output plain text only. No markdown, no bold, no code blocks.
            """.trimIndent(),
            timeoutSeconds = 240
        )
            .assertExitCode(0) { "prompt failed" }

        val combinedOutput = result.stdout + "\n" + result.stderr

        println("=== AGENT OUTPUT (testCompilationWarningsDelivered) ===")
        println(combinedOutput)
        println("=== END ===")

        assertTrue(
            "Output should contain the execution result\n$combinedOutput",
            combinedOutput.contains("WARNING_TEST_VALUE") || combinedOutput.contains("hello,world"),
        )

        assertTrue(
            "Output should mention compiler warning (unchecked cast)\n$combinedOutput",
            combinedOutput.contains("unchecked cast", ignoreCase = true)
                    || combinedOutput.contains("warning:", ignoreCase = true)
                    || Regex("""COMPILER_WARNING:\s*(?!NONE)""").containsMatchIn(combinedOutput),
        )
    }

    /**
     * Runs the Serena project's self-evaluation prompt against the MCP Steroid tools.
     *
     * The prompt body is reproduced verbatim from Serena and stored locally at
     * `ij-plugin/src/integrationTest/resources/serena/self-eval-prompt.md`. It asks the
     * agent to compare a tool augmentation layer against its built-ins on a real codebase
     * and produce a structured delta report. Attribution and refresh instructions live in
     * the resource file's header.
     *
     * The prompt targets Serena by name, but the methodology is tool-agnostic. This test
     * wraps the verbatim prompt in a short preamble that (a) redirects the evaluation
     * subject to MCP Steroid's `steroid_*` tools and (b) overrides the "write report to
     * `serena-evaluation.md`" instruction so the agent prints the report to stdout between
     * recognizable markers. The wrapped execution costs real API tokens and minutes of
     * wall-clock time, so the test is part of the Docker-only `:ij-plugin:integrationTest`
     * suite and never runs as part of default `./gradlew test`.
     */
    open fun testSerenaSelfEvalPrompt(): Unit = timeoutRunBlocking(1800.seconds) {
        val session = newAiSession()

        val serenaPromptBody = loadSerenaSelfEvalPromptBody()

        val preamble = """
            You are running a tool-vs-built-ins evaluation inside MCP Steroid's integration test harness.

            The evaluation prompt below is reproduced VERBATIM from the Serena project
            (https://github.com/oraios/serena). It targets Serena by name, but the methodology is
            tool-agnostic. For this run, substitute "Serena" -> "MCP Steroid" and "Serena's tools" ->
            "MCP Steroid's steroid_* MCP tools" everywhere it appears. Evaluate what MCP Steroid's
            steroid_* tools add on top of your built-ins.

            Use only the MCP server named "intellij" for MCP tool calls. Do not call list_mcp_resources.
            When you invoke steroid_execute_code, pass project_name=${project.name}.

            OVERRIDE: do NOT write a file. Instead print the full evaluation report to stdout,
            framed between these markers:

            EVAL_REPORT_START
            <report body>
            EVAL_REPORT_END

            The report body MUST include these literal section headings on their own lines
            (one per section): "### 1.", "### 2.", "### 3.", "### 4.", "### 5.", "### 6.",
            "### 7.", "### 8.", "### 9.". Each of those nine sections MUST end with a line
            starting with "**Verdict:**".

            If the test fixture codebase is too small for some tasks, report "no suitable candidate"
            for that task as the prompt allows, but still emit every section heading with at least
            a one-line note and the required Verdict line.

            ----- BEGIN VERBATIM SERENA EVALUATION PROMPT -----
            $serenaPromptBody
            ----- END VERBATIM SERENA EVALUATION PROMPT -----
            """.trimIndent()

        val result = session.runPrompt(preamble, timeoutSeconds = 1500)
            .assertExitCode(0) { "self-eval prompt run" }

        val combinedOutput = result.stdout + "\n" + result.stderr

        println("=== AGENT OUTPUT (testSerenaSelfEvalPrompt) ===")
        println(combinedOutput)
        println("=== END ===")

        assertTrue(
            "report must be framed by EVAL_REPORT_START / EVAL_REPORT_END markers\n$combinedOutput",
            combinedOutput.contains("EVAL_REPORT_START") && combinedOutput.contains("EVAL_REPORT_END"),
        )

        // Use substringAfterLast / substringBeforeLast: the preamble we handed the
        // agent contains an example of the marker format, so the first pair of
        // markers in the output is usually the preamble echo, not the actual
        // report. The agent's real report is the LAST marker pair.
        val reportBody = combinedOutput
            .substringAfterLast("EVAL_REPORT_START")
            .substringBeforeLast("EVAL_REPORT_END")

        for (sectionNumber in 1..9) {
            assertTrue(
                "report must contain section heading '### $sectionNumber.'\n$reportBody",
                Regex("""###\s*$sectionNumber\.""").containsMatchIn(reportBody),
            )
        }

        // Agents format the verdict tag several equivalent ways across runs:
        //   **Verdict:** ...
        //   **Verdict**: ...
        //   **Verdict (3.1):** ...   ← sub-verdicts within section 3
        //   **Verdict (3.14–3.16):** ...
        // Accept any `**Verdict…**` or `**Verdict…:` pattern so the assertion measures
        // "did the agent produce 9 recognizable verdict lines" rather than punctuating
        // exactly the way my prompt preamble showed.
        val verdictCount = Regex("""\*\*Verdict\b[^*\n]{0,50}(?:\*\*:?|:\*\*)""")
            .findAll(reportBody).count()
        assertTrue(
            "report must contain at least 9 verdict lines, found $verdictCount\n$reportBody",
            verdictCount >= 9,
        )
    }

    private fun loadSerenaSelfEvalPromptBody(): String {
        val resourcePath = "/serena/self-eval-prompt.md"
        val raw = requireNotNull(javaClass.getResource(resourcePath)) {
            "Missing test resource $resourcePath"
        }.readText()

        // The resource file starts with an attribution header followed by a '---' line.
        // Strip the header so we hand the model the verbatim upstream prompt body only.
        val separator = "\n---\n"
        val idx = raw.indexOf(separator)
        require(idx >= 0) { "Expected attribution header and '---' separator in $resourcePath" }
        return raw.substring(idx + separator.length).trimEnd()
    }

    /**
     * Runs the apply-patch recipe evaluator prompt. This is an autoresearch probe,
     * not a pass/fail behaviour test: the agent is asked to exercise the current
     * `mcp-steroid://ide/apply-patch` recipe end-to-end and produce a candid
     * review under ~700 words. The review is framed between
     * `APPLY_PATCH_REVIEW_START` / `APPLY_PATCH_REVIEW_END` markers so the loop
     * harness can extract it.
     *
     * The assertion surface is deliberately minimal — we only require that the
     * agent (a) produced the bracketed review, (b) cited the recipe, and (c) ran
     * at least one `steroid_execute_code` call. The qualitative content is the
     * real artifact, captured from the test run's stdout.
     */
    open fun testApplyPatchRecipeEvaluation(): Unit = timeoutRunBlocking(1800.seconds) {
        val session = newAiSession()
        val evaluatorPrompt = requireNotNull(javaClass.getResource("/apply-patch/evaluator-prompt.md")) {
            "Missing test resource /apply-patch/evaluator-prompt.md"
        }.readText()

        val preamble = """
            You are participating in an autoresearch evaluation of MCP Steroid's
            `mcp-steroid://ide/apply-patch` recipe. The MCP server is already registered
            under the name "intellij" — use it for every tool call. Do not call
            list_mcp_resources.

            When you invoke steroid_execute_code, pass project_name=${project.name}.
            First, call steroid_list_projects once so you have the project name and layout.

            Below is the evaluator task description. Execute it faithfully and produce the
            review between the APPLY_PATCH_REVIEW_START / APPLY_PATCH_REVIEW_END markers
            exactly as specified.

            ----- BEGIN EVALUATOR TASK -----
            $evaluatorPrompt
            ----- END EVALUATOR TASK -----
            """.trimIndent()

        val result = session.runPrompt(preamble, timeoutSeconds = 1500)
            .assertExitCode(0) { "apply-patch evaluator prompt run" }

        val combinedOutput = result.stdout + "\n" + result.stderr

        println("=== AGENT OUTPUT (testApplyPatchRecipeEvaluation) ===")
        println(combinedOutput)
        println("=== END ===")

        assertTrue(
            "review must be framed by APPLY_PATCH_REVIEW_START / APPLY_PATCH_REVIEW_END markers\n$combinedOutput",
            combinedOutput.contains("APPLY_PATCH_REVIEW_START") &&
                combinedOutput.contains("APPLY_PATCH_REVIEW_END"),
        )

        // Use substringAfterLast / substringBeforeLast — the preamble contains an
        // example marker pair that agents echo back in their thinking phase.
        val review = combinedOutput
            .substringAfterLast("APPLY_PATCH_REVIEW_START")
            .substringBeforeLast("APPLY_PATCH_REVIEW_END")

        assertTrue(
            "review must reference the apply-patch recipe path\n$review",
            review.contains("apply-patch") || review.contains("mcp-steroid://ide/apply-patch"),
        )
        assertTrue(
            "review must be non-trivial (>= 300 chars of content)\n$review",
            review.trim().length >= 300,
        )
    }

    open fun testExecSessionReset(): Unit = timeoutRunBlocking(360.seconds) {
        val session = newAiSession()

        val result = session.runPrompt(
            """
            You are testing MCP integration. You MUST call steroid_execute_code exactly three times, in order.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            Reason: cli session reset test, and distinct task_id values.
            First, call steroid_list_projects exactly once and take the first project's "name" as PROJECT_NAME.
            For each steroid_execute_code call (#1, #2, #3), pass project_name=PROJECT_NAME.
            IMPORTANT: "intellij" is the MCP server name, not a valid project_name value.

            Call #1 code:
            ```
            println("EXEC1_OK")
            ```

            Call #2 code:
            ```
            ${SteroidsMcpServer::class.java.name}.getInstance().forgetAllForTest()
            println("SESSIONS_FORGOTTEN: OK")
            ```
            IMPORTANT: Call #2 restarts the MCP server. The connection WILL break and the call WILL fail.
            This is expected and correct. Do NOT retry call #2. Just note it failed and move on.

            Call #3 code:
            ```
            val count = ${SteroidsMcpServer::class.java.name}.getInstance().getServer().sessionManager.getSessionCount()
            println("EXEC2_OK: sessions=" + count)
            ```

            After each call, print a result line:
            RESULT1: <line from call #1 output containing EXEC1_OK>
            RESULT2: RESET_CONNECTION_BROKEN (if call #2 failed, which is expected) or RESET_OK (if it somehow succeeded)
            RESULT3: <line from call #3 output containing EXEC2_OK>

            Output must be plain text only. Do NOT use Markdown, lists, or code blocks.
            """.trimIndent(),
            timeoutSeconds = 300
        )
            .assertExitCode(0) { "prompt failed" }

        val combinedOutput = buildString {
            appendLine(result.stdout)
            appendLine(result.stderr)
        }

        assertTrue(
            "exec #1 should run before restart\n$combinedOutput",
            Regex("""RESULT1:\s*.*EXEC1_OK""").containsMatchIn(combinedOutput),
        )
        assertTrue(
            "exec #2 must report connection broken (server restart kills the HTTP connection)\n$combinedOutput",
            Regex("""RESULT2:\s*RESET_CONNECTION_BROKEN""").containsMatchIn(combinedOutput),
        )
        assertTrue(
            "exec #3 should run on the restarted server\n$combinedOutput",
            Regex("""RESULT3:\s*.*EXEC2_OK""").containsMatchIn(combinedOutput),
        )
        assertTrue(
            "restarted server should have exactly 1 fresh session (old sessions were closed, this is a new one)\n$combinedOutput",
            Regex("""RESULT3:\s*.*sessions=1""").containsMatchIn(combinedOutput),
        )
    }
}
