/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.resolveContainerAgentBaseUrl
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * CHEAP preflight for the arena's agent models — verifies each configured model is RECOGNIZED
 * and REACHABLE without paying for a full arena run (no project clone, no test patch, no IDE
 * container, no grader).
 *
 * Rationale: before committing to a 96-run DPAIA arena benchmark on a freshly bumped model
 * (e.g. switching `codex.model` / `claude.model` to a new release), we want a ~1-2 min signal
 * that the model id is actually accepted by the CLI and answers — not 96 runs discovering the
 * same misconfiguration 96 times.
 *
 * Lightest available container: [DockerCodexSession.create] / [DockerClaudeSession.create] build
 * ONLY the bare `codex-cli` / `claude-cli` Docker images (`test-helper/src/main/docker/{codex,claude}-cli/`
 * — Debian + Node + the agent CLI, no IntelliJ, no devrig, no project) and start ONE container each.
 * This is the same session type the arena runs use ([DpaiaScenarioBaseTest.runAgent]) — configured with
 * the SAME model resolution ([DockerCodexSession.DEFAULT_MODEL] / [DockerClaudeSession.DEFAULT_MODEL],
 * overridable via `-Dcodex.model` / `-Dclaude.model`, exactly like the real arena runs) — so a green
 * preflight here is evidence the real runs will reach the same model.
 *
 * No skip logic: per CLAUDE.md, the purpose of this test is to FAIL LOUDLY when a model is
 * unrecognized, unreachable, or the API key/gateway is misconfigured. A missing API key is a real
 * misconfiguration on this environment and must fail the test, not skip it (see
 * [DockerCodexSession.Companion] / [DockerClaudeSession.Companion] — neither opts into
 * `skipTestWhenKeyMissing`).
 */
class AgentModelPreflightTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `codex model preflight`() {
        val model = System.getProperty("codex.model", DockerCodexSession.DEFAULT_MODEL)
        val baseUrlConfigured = resolveContainerAgentBaseUrl("OPENAI_BASE_URL", "OPENAI_API_BASE") != null
        val apiKeyResolved = DockerCodexSession.isApiKeyAvailable()

        println(
            "[PREFLIGHT] Codex — model=$model  gateway_configured=$baseUrlConfigured  " +
                "api_key_resolved=$apiKeyResolved"
        )

        val lifetime = CloseableStackHost()
        try {
            val session = DockerCodexSession.create(lifetime)

            val cliVersion = readCliVersion { session.runInContainer(listOf("--version"), timeoutSeconds = 60) }
            println("[PREFLIGHT] Codex CLI version: $cliVersion")

            val result = session.runPrompt(
                prompt = "Reply with exactly one word: READY",
                timeoutSeconds = 180,
            ).awaitForProcessFinish()

            val combined = "${result.rawStdout}\n${result.stderr}"

            assertFalse(UNKNOWN_MODEL_PATTERN.containsMatchIn(combined)) {
                "Codex model '$model' is unrecognized by the CLI (\"Model metadata for ... not found\"). " +
                    "This means the model id is not present in the Codex CLI's own model registry — a hard " +
                    "misconfiguration, not a transient error. Output:\n${combined.take(4000)}"
            }

            assertTrue(result.stdout.contains("READY")) {
                "Codex model '$model' did not answer READY — the model is unreachable or misconfigured " +
                    "(check API key / gateway settings printed above). Output:\n${combined.take(4000)}"
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `claude model preflight`() {
        val model = System.getProperty("claude.model", DockerClaudeSession.DEFAULT_MODEL)
        val baseUrlConfigured = resolveContainerAgentBaseUrl("ANTHROPIC_BASE_URL") != null
        val apiKeyResolved = DockerClaudeSession.isApiKeyAvailable()

        println(
            "[PREFLIGHT] Claude — model=$model  gateway_configured=$baseUrlConfigured  " +
                "api_key_resolved=$apiKeyResolved"
        )

        val lifetime = CloseableStackHost()
        try {
            val session = DockerClaudeSession.create(lifetime)

            val cliVersion = readCliVersion { session.runInContainer(listOf("--version"), timeoutSeconds = 60) }
            println("[PREFLIGHT] Claude CLI version: $cliVersion")

            val result = session.runPrompt(
                prompt = "Reply with exactly one word: READY",
                timeoutSeconds = 180,
            ).awaitForProcessFinish()

            val combined = "${result.rawStdout}\n${result.stderr}"

            assertFalse(UNKNOWN_CLAUDE_MODEL_PATTERN.containsMatchIn(combined)) {
                "Claude model '$model' is unrecognized/unreachable by the CLI (unknown/not-found model " +
                    "error). Output:\n${combined.take(4000)}"
            }

            assertTrue(result.stdout.contains("READY")) {
                "Claude model '$model' did not answer READY — the model is unreachable or misconfigured " +
                    "(check API key / gateway settings printed above). Output:\n${combined.take(4000)}"
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    /**
     * Reads a CLI's own version string via [runVersionCommand] (e.g. `codex --version`). Purely
     * informational for the TC log — a failure here must NOT fail the test (the real pass/fail
     * signal is the prompt round-trip below), so it is logged and reported as "unknown" instead.
     */
    private fun readCliVersion(runVersionCommand: () -> StartedProcess): String {
        return try {
            val result = runVersionCommand().awaitForProcessFinish()
            (result.stdout.trim().ifBlank { result.stderr.trim() }).ifBlank { "(empty)" }
        } catch (e: Exception) {
            System.err.println("[PREFLIGHT] Could not read CLI version: ${e.message}")
            "unknown (${e.message})"
        }
    }

    companion object {
        /** Codex CLI's own "unrecognized model id" error — a hard registry miss, not a transient error. */
        private val UNKNOWN_MODEL_PATTERN = Regex("Model metadata for.*not found", RegexOption.IGNORE_CASE)

        /** Analogous "unknown model" / "model not found" shapes reported by the Claude Code CLI / Anthropic API. */
        private val UNKNOWN_CLAUDE_MODEL_PATTERN = Regex(
            "unknown model|model not found|not a valid model|invalid model|model_not_found",
            RegexOption.IGNORE_CASE,
        )
    }
}
