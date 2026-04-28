/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Runs the upstream Serena self-evaluation prompt against an MCP-enabled agent on
 * a real Docker IDE container. The prompt body lives at
 * `test-experiments/src/test/resources/serena/self-eval-prompt.md` (verbatim from
 * https://github.com/oraios/serena, MIT-licensed; do not edit the body).
 *
 * Lives in `:test-experiments` because the run takes 10–25 minutes per agent — too
 * heavy for the `:ij-plugin:integrationTest` smoke surface that the
 * `ciIntegrationTests` chain exercises on every push. Per CLAUDE.md, run
 * `:test-experiments` cases one at a time.
 *
 * Each `@Test` spins up a fresh `IntelliJContainer` and runs the Serena prompt
 * through the corresponding `session.aiAgents.<name>` session. The MCP server
 * inside the container is reachable via the standard arena-style Http connection.
 *
 * Acceptance is intentionally minimal: the agent must produce the bracketed
 * `EVAL_REPORT_START` / `EVAL_REPORT_END` report, with all nine
 * `### N.` section headings and at least nine `**Verdict…**` lines. The
 * qualitative content is the real artifact, captured in stdout.
 */
class SerenaSelfEvalTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = runSelfEval(agentName = "claude")

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = runSelfEval(agentName = "codex")

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `gemini with mcp`() = runSelfEval(agentName = "gemini")

    private fun runSelfEval(agentName: String) = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime = lifetime,
            consoleTitle = "serena-$agentName",
            project = IntelliJProject.TestProject,
            aiMode = AiMode.AI_MCP,
        ).waitForProjectReady()

        val agent: AiAgentSession = when (agentName) {
            "claude" -> session.aiAgents.claude
            "codex" -> session.aiAgents.codex
            "gemini" -> session.aiAgents.gemini
            else -> error("Unknown agent: $agentName")
        }

        val serenaPromptBody = loadSerenaSelfEvalPromptBody()

        val preamble = """
            You are running a tool-vs-built-ins evaluation inside MCP Steroid's integration test harness.

            The evaluation prompt below is reproduced VERBATIM from the Serena project
            (https://github.com/oraios/serena). It targets Serena by name, but the methodology is
            tool-agnostic. For this run, substitute "Serena" -> "MCP Steroid" and "Serena's tools" ->
            "MCP Steroid's steroid_* MCP tools" everywhere it appears. Evaluate what MCP Steroid's
            steroid_* tools add on top of your built-ins.

            Use only the MCP server named "intellij" for MCP tool calls. Do not call list_mcp_resources.

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

        val result = agent.runPrompt(preamble, timeoutSeconds = 1500).awaitForProcessFinish()
        check(result.exitCode == 0) {
            "Serena self-eval prompt run for $agentName exited with code ${result.exitCode}"
        }

        val combinedOutput = result.stdout + "\n" + result.stderr

        println("=== AGENT OUTPUT (SerenaSelfEvalTest:$agentName) ===")
        println(combinedOutput)
        println("=== END ===")

        assertTrue(
            combinedOutput.contains("EVAL_REPORT_START") && combinedOutput.contains("EVAL_REPORT_END"),
            "report must be framed by EVAL_REPORT_START / EVAL_REPORT_END markers\n$combinedOutput",
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
                Regex("""###\s*$sectionNumber\.""").containsMatchIn(reportBody),
                "report must contain section heading '### $sectionNumber.'\n$reportBody",
            )
        }

        // Agents format the verdict tag several equivalent ways across runs:
        //   **Verdict:** ...
        //   **Verdict**: ...
        //   **Verdict (3.1):** ...   ← sub-verdicts within section 3
        //   **Verdict (3.14–3.16):** ...
        // Accept any `**Verdict…**` or `**Verdict…:` pattern so the assertion measures
        // "did the agent produce 9 recognizable verdict lines" rather than punctuating
        // exactly the way the prompt preamble showed.
        val verdictCount = Regex("""\*\*Verdict\b[^*\n]{0,50}(?:\*\*:?|:\*\*)""")
            .findAll(reportBody).count()
        assertTrue(
            verdictCount >= 9,
            "report must contain at least 9 verdict lines, found $verdictCount\n$reportBody",
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
}
