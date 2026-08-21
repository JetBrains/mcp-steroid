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
    /**
     * The message of a transport-level abort of the agent's API connection, as
     * [extractApiTransportError] defines it — null for every run that reached its own end, however
     * badly.
     *
     * A NAMED value rather than a flag: the reader that acts on it (the checkpoint probe) has to print
     * WHAT went wrong, because the operator's next move is to re-queue the cell and a cell re-queued
     * without a stated cause is indistinguishable from a flaky one.
     */
    val apiTransportError: String? = null,
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
    val decodedLogName = agentLogName(agentName)
    val rawOutput = resolveAgentRawOutput(runDir, agentName = agentName, fallbackStdout = fallbackStdout)
    return ArenaRunMetrics(
        tokenUsage = extractTokenUsage(rawOutput),
        testMetrics = extractTestMetrics(rawOutput),
        decodedLogMetrics = findDecodedLogFile(runDir, agentName = decodedLogName)
            ?.let { extractDecodedLogMetrics(it.readText()) },
        toolCallStats = extractToolCallStats(rawOutput),
        endContextTokens = extractEndContextTokens(rawOutput),
        apiTransportError = extractApiTransportError(rawOutput),
    )
}

/**
 * The agent's own UNFILTERED output for [agentName], which is the only text any of these parsers may
 * be pointed at.
 *
 * Extracted from [collectRunMetrics] so that a caller wanting one more fact out of the same run — the
 * final response, say — cannot reach for a DIFFERENT source and get a different answer. Reading the
 * captured process stdout instead is the specific mistake this function exists to prevent: that stream
 * is console-filtered, and the filter removes exactly the terminal `result` event that carries both the
 * usage totals and the agent's final message. A parser fed the filtered stream does not fail loudly; it
 * reports "no result", which reads as an agent that produced nothing.
 *
 * [fallbackStdout] is still honoured, because a run that died before the driver persisted its
 * transcript has nothing else — and a partial answer beats none when the alternative is discarding a
 * paid run.
 */
fun resolveAgentRawOutput(runDir: File, agentName: String, fallbackStdout: String): String =
    findRawNdjsonFile(runDir, agentName = agentLogName(agentName))?.readText()
        ?: unprefixConsoleNdjson(fallbackStdout)

/**
 * Strip the console decoration the interactive session driver puts in front of each event line, so
 * the fallback text parses as NDJSON again.
 *
 * The driver does not swallow the agent's events — it re-emits them for a human reader as
 * `[IDE OUT] {"type":"result",...}`. Every parser in this file walks the text line by line and calls
 * `Json.parseToJsonElement` on each one, and a line that starts with `[` is silently skipped. The
 * effect is indistinguishable from an agent that never spoke: the terminal `result` event, which
 * carries both the usage totals and the final message, disappears without a single error being
 * logged. That is what made eight paid research runs report "no final message" while their notes sat
 * in plain sight in the build log.
 *
 * Cutting at the first brace rather than matching a fixed prefix keeps this indifferent to which
 * decoration a driver uses. A line whose brace does not begin valid JSON is skipped exactly as before,
 * so the worst case of a wrong cut is the behaviour we already had.
 */
fun unprefixConsoleNdjson(text: String): String = text.lineSequence().joinToString("\n") { line ->
    val brace = line.indexOf('{')
    if (brace > 0 && line.trimStart().startsWith("[")) line.substring(brace) else line
}

/** Maps a driver's agent name onto the prefix its persisted logs are written under. */
fun agentLogName(agentName: String): String = when (agentName) {
    "claude" -> "claude-code"
    "codex" -> "codex"
    "gemini" -> "gemini"
    else -> agentName
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
