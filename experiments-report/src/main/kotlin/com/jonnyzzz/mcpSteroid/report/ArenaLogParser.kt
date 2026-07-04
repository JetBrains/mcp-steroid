package com.jonnyzzz.mcpSteroid.report

/**
 * Parses agent runs out of a raw CI build log by reading the `[ARENA]` summary blocks that
 * `DpaiaScenarioBaseTest` prints once per (mode) run. This is the BASELINE source: it works on the
 * data published today (the build log) without any test/artifact change.
 *
 * Each block looks like (optionally prefixed by a CI timestamp + `[:test-experiments:test]`):
 * ```
 * [ARENA] claude+mcp — dpaia__spring__petclinic-27
 * [ARENA]   Claimed fix:    true
 * [ARENA]   Used MCP:       true
 * [ARENA]   Exit code:      0
 * [ARENA]   Agent time:     521s
 * [ARENA]   Tokens in/out:  812345/45678
 * [ARENA]   Cost:           $3.21
 * [ARENA]   Turns:          47
 * [ARENA]   Tests:          96 run, 0 fail, BUILD FAILURE
 * [ARENA]   exec_code:      1
 * [ARENA]   Summary:        …
 * ```
 * Fields are optional (a run with no token usage simply omits those lines). Duplicate blocks (CI
 * echoes both the prefixed and raw stdout streams) are left as-is here and merged by [mergeRuns].
 */
object ArenaLogParser {
    private const val MARKER = "[ARENA]"

    // Header: "<agent>+<mcp|none> — <scenario>". The separator is an em dash (U+2014).
    private val HEADER = Regex("""^(\w+)\+(mcp|none)\s+—\s+(.+)$""")
    private val SECONDS = Regex("""(\d+)\s*s""")

    fun parse(log: String): List<AgentRun> {
        val runs = mutableListOf<AgentRun>()
        var cur: Builder? = null

        fun flush() {
            cur?.let { runs += it.build() }
            cur = null
        }

        for (raw in log.lineSequence()) {
            val idx = raw.indexOf(MARKER)
            if (idx < 0) continue // interleaved non-ARENA output never terminates a block
            val body = raw.substring(idx + MARKER.length).trim()

            val header = HEADER.matchEntire(body)
            if (header != null) {
                flush()
                val (agent, modeLabel, scenario) = header.destructured
                cur = Builder(
                    scenario = scenario.trim(),
                    agent = agent,
                    mode = if (modeLabel == "mcp") McpMode.WITH else McpMode.WITHOUT,
                )
                continue
            }

            val b = cur ?: continue
            val colon = body.indexOf(':')
            if (colon < 0) {
                // A divider line (════) closes the block; anything else inside is ignored.
                if (body.startsWith("═")) flush()
                continue
            }
            b.put(body.substring(0, colon).trim(), body.substring(colon + 1).trim())
        }
        flush()
        return runs
    }

    private fun parseSeconds(value: String): Long? =
        SECONDS.find(value)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)

    private class Builder(val scenario: String, val agent: String, val mode: McpMode) {
        private var agentDurationMs: Long? = null
        private var exitCode: Int? = null
        private var claimedFix: Boolean? = null
        private var usedMcp: Boolean? = null
        private var testsRun: Int? = null
        private var testsFail: Int? = null
        private var buildSuccess: Boolean? = null
        private var inputTokens: Long? = null
        private var outputTokens: Long? = null
        private var costUsd: Double? = null
        private var numTurns: Int? = null
        private var execCodeCalls: Int? = null
        private var summary: String? = null

        fun put(key: String, value: String) {
            when (key) {
                "Claimed fix" -> claimedFix = value.toBooleanStrictOrNull()
                "Used MCP" -> usedMcp = value.toBooleanStrictOrNull()
                "Exit code" -> exitCode = value.toIntOrNull()
                "Agent time" -> agentDurationMs = parseSeconds(value)
                "Tokens in/out" -> value.split('/').let {
                    inputTokens = it.getOrNull(0)?.trim()?.toLongOrNull()
                    outputTokens = it.getOrNull(1)?.trim()?.toLongOrNull()
                }
                "Cost" -> costUsd = value.removePrefix("$").trim().toDoubleOrNull()
                "Turns" -> numTurns = value.toIntOrNull()
                "Tests" -> parseTests(value)
                "exec_code" -> execCodeCalls = value.toIntOrNull()
                "Summary" -> summary = value.ifBlank { null }
            }
        }

        // "96 run, 0 fail, BUILD FAILURE"
        private fun parseTests(value: String) {
            testsRun = Regex("""(\d+)\s*run""").find(value)?.groupValues?.get(1)?.toIntOrNull()
            testsFail = Regex("""(\d+)\s*fail""").find(value)?.groupValues?.get(1)?.toIntOrNull()
            buildSuccess = when {
                value.contains("BUILD SUCCESS") -> true
                value.contains("BUILD FAILURE") -> false
                else -> null
            }
        }

        fun build() = AgentRun(
            scenario = scenario,
            agent = agent,
            mode = mode,
            agentDurationMs = agentDurationMs,
            exitCode = exitCode,
            claimedFix = claimedFix,
            usedMcp = usedMcp,
            testsRun = testsRun,
            testsFail = testsFail,
            buildSuccess = buildSuccess,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUsd = costUsd,
            numTurns = numTurns,
            execCodeCalls = execCodeCalls,
            summary = summary,
        )
    }
}
