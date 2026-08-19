/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import java.io.File

/** The metric groups every arena run reports, whatever produced the run. */
data class ArenaRunMetrics(
    val tokenUsage: TokenUsage?,
    val testMetrics: TestMetrics?,
    val decodedLogMetrics: DecodedLogMetrics?,
    /**
     * Raw-NDJSON tool statistics. Only [ToolCallStats.toolErrorCount] is reported anywhere, because
     * its call counts are a SECOND count of the same calls [decodedLogMetrics] already counted, and
     * mixing the two sources in one table would let the same run report two different totals.
     */
    val toolCallStats: ToolCallStats? = null,
    /**
     * The agent's context size at the END of the run, as [extractEndContextTokens] defines it.
     *
     * Its own field rather than something derived from [tokenUsage]: the terminal `result` event reports
     * cumulative traffic, so no arithmetic over [TokenUsage] can produce this quantity. It exists
     * because the checkpoint pilot's representativeness band is measured this way — see [admitCapture].
     */
    val endContextTokens: Long? = null,
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
        toolCallStats = extractToolCallStats(rawOutput),
        endContextTokens = extractEndContextTokens(rawOutput),
    )
}

/**
 * Write the per-run JSON summary and append the comparison CSV row.
 *
 * [runDir] is the per-run directory that `TeamCityArtifactPostProcess.buildPublishTree` rsyncs into
 * `publish/bundle/`, i.e. the only place a file written from a test ends up as a published TeamCity
 * artifact. The primary copy still goes to `IdeTestFolders.testOutputDir` — that is where local runs
 * and the existing tooling look for it — but a summary that only exists there is unreachable from a
 * CI build, which is why the six-case round's dollar figures had to be scraped back out of raw agent
 * NDJSON echoed into the build log. Writing the same bytes twice is the cheapest fix available from
 * this side; the alternative is a TeamCity DSL release in another repository.
 */
fun writeArenaRunSummary(
    instanceId: String,
    agentName: String,
    modeLabel: String,
    record: DpaiaScenarioBaseTest.RunRecord,
    runDir: File? = null,
) {
    val summaryJson = buildRunSummaryJson(record).toString()
    val summaryName = "dpaia-arena-run-$instanceId-$agentName-$modeLabel.json"
    val summaryFile = IdeTestFolders.testOutputDir.resolve(summaryName)
    summaryFile.parentFile.mkdirs()
    summaryFile.writeText(summaryJson)
    println("[ARENA] Run summary written to: ${summaryFile.absolutePath}")

    if (runDir != null) {
        val published = runDir.resolve(summaryName)
        published.parentFile?.mkdirs()
        published.writeText(summaryJson)
        println("[ARENA] Run summary also written to the published run dir: ${published.absolutePath}")
    }

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
