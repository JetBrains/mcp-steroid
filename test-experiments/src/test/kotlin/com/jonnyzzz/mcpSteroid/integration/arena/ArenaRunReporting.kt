/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import java.io.File

/** The three metric groups every arena run reports, whatever produced the run. */
data class ArenaRunMetrics(
    val tokenUsage: TokenUsage?,
    val testMetrics: TestMetrics?,
    val decodedLogMetrics: DecodedLogMetrics?,
)

/**
 * Extract run metrics from the agent's own logs.
 *
 * Shared by every arena track on purpose: the DPAIA cases and the semantic-ripple track are compared
 * against each other, so a divergence in how their tokens, turns and tool calls are counted would
 * silently invalidate that comparison.
 *
 * Prefers the persisted UNFILTERED transcript — the captured process stdout is console-filtered and
 * drops the usage and result events these metrics parse — and falls back to [fallbackStdout].
 */
fun collectRunMetrics(runDir: File, agentName: String, fallbackStdout: String): ArenaRunMetrics {
    val decodedLogName = when (agentName) {
        "claude" -> "claude-code"
        "codex" -> "codex"
        "gemini" -> "gemini"
        else -> agentName
    }
    val rawOutput = findRawNdjsonFile(runDir, agentName = decodedLogName)?.readText() ?: fallbackStdout
    return ArenaRunMetrics(
        tokenUsage = extractTokenUsage(rawOutput),
        testMetrics = extractTestMetrics(rawOutput),
        decodedLogMetrics = findDecodedLogFile(runDir, agentName = decodedLogName)
            ?.let { extractDecodedLogMetrics(it.readText()) },
    )
}

/** Write the per-run JSON summary and append the comparison CSV row. */
fun writeArenaRunSummary(
    instanceId: String,
    agentName: String,
    modeLabel: String,
    record: DpaiaScenarioBaseTest.RunRecord,
) {
    val summaryFile = IdeTestFolders.testOutputDir
        .resolve("dpaia-arena-run-$instanceId-$agentName-$modeLabel.json")
    summaryFile.parentFile.mkdirs()
    summaryFile.writeText(buildRunSummaryJson(record).toString())
    println("[ARENA] Run summary written to: ${summaryFile.absolutePath}")

    val csvFile = IdeTestFolders.testOutputDir.resolve("arena-comparison.csv")
    appendComparisonCsv(
        csvFile = csvFile,
        instanceId = instanceId,
        passLabel = System.getProperty("arena.pass.label", ""),
        claimedFix = record.claimedFix,
        durationS = record.agentDurationMs / 1000,
        tokens = record.tokenUsage,
        testMetrics = record.testMetrics,
        decoded = record.decodedLogMetrics,
        verification = record.verification,
    )
    println("[ARENA] Comparison CSV appended to: ${csvFile.absolutePath}")
}
