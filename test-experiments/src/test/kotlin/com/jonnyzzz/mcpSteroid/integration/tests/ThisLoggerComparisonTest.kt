/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Comparison test: MCP Steroid IDE API (PSI/FilenameIndex) vs. plain bash grep
 * for finding ALL usages of `thisLogger()` in the thislogger-project fixture.
 *
 * Fixture project ground truth:
 * - 55 files that call `thisLogger()` (one call per file)
 * - 1 file (Logging.kt) that defines the `thisLogger()` extension function
 * - Total call sites: 55
 *
 * Agent A: [MCP] uses IntelliJ IDEA via MCP Steroid — expected to use
 *   `steroid_execute_code` with `FilenameIndex.getVirtualFilesByName()` or
 *   `ReferencesSearch.search()` to locate all call sites (O(1) IDE-indexed lookup).
 *
 * Agent B: [NONE] has no MCP Steroid registered — uses bash grep/find to scan
 *   the project files (O(n) filesystem scan).
 *
 * Both agents are prompted with the same task and must report:
 * - TOTAL_COUNT: <number of files/usages found>
 * - DISCOVERY_METHOD: <brief description of how they found the usages>
 *
 * Run:
 * ```
 * ./gradlew :test-experiments:test --tests '*ThisLoggerComparisonTest*'
 * ```
 *
 * Run a single mode:
 * ```
 * ./gradlew :test-experiments:test --tests '*ThisLoggerComparisonTest.mcp*'
 * ./gradlew :test-experiments:test --tests '*ThisLoggerComparisonTest.none*'
 * ```
 */
class ThisLoggerComparisonTest {

    // ── Expected ground truth ───────────────────────────────────────────────
    // 55 files each have: private val logger = thisLogger()
    // Logging.kt defines the function but is not a call site.
    // ReferencesSearch.search() or FilenameIndex will yield 55 call sites.
    private val expectedCallSiteFiles = 55

    // ── Shared companion ────────────────────────────────────────────────────
    companion object {
        private val results = CopyOnWriteArrayList<RunRecord>()

        @JvmStatic
        @AfterAll
        fun reportComparison() {
            if (results.isEmpty()) return
            println()
            println("=".repeat(70))
            println("  thisLogger() COMPARISON REPORT")
            println("=".repeat(70))
            println()
            for (r in results) {
                val badge = if (r.withMcp) "[MCP ]" else "[NONE]"
                val timeStr = "${r.durationMs / 1000}s"
                val accurate = if (r.countAccurate) "✓" else "✗"
                println("$badge  time=$timeStr  count_accurate=$accurate  " +
                        "method=${r.discoveryMethod ?: "(not reported)"}")
                if (r.discoveryMethod != null) {
                    println("       method detail: ${r.discoveryMethod}")
                }
            }
            println()

            val mcp = results.firstOrNull { it.withMcp }
            val none = results.firstOrNull { !it.withMcp }
            if (mcp != null && none != null) {
                val delta = mcp.durationMs - none.durationMs
                val faster = if (delta < 0) "MCP by ${-delta / 1000}s" else "NONE by ${delta / 1000}s"
                println("  Faster: $faster")
                println("  MCP used IDE API: ${mcp.usedIdeApi}")
                println("  NONE used grep:   ${none.usedGrep}")
            }
            println("=".repeat(70))
            println()
        }
    }

    data class RunRecord(
        val withMcp: Boolean,
        val durationMs: Long,
        val countAccurate: Boolean,
        val discoveryMethod: String?,
        val usedIdeApi: Boolean,
        val usedGrep: Boolean,
    )

    // ── Test methods ────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `mcp agent finds thisLogger usages`() {
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(
                lifetime,
                consoleTitle = "thislogger-mcp",
                project = IntelliJProject.ThisLoggerProject,
            ).waitForProjectReady()

            val result = runAgent(session, withMcp = true)
            results.add(result)

            session.console.writeInfo("MCP result: count_accurate=${result.countAccurate}, " +
                    "method=${result.discoveryMethod}")

            check(result.countAccurate) {
                "MCP agent reported incorrect thisLogger() count.\n" +
                        "Expected: $expectedCallSiteFiles call-site files\n" +
                        "Got count_accurate=false (see TOTAL_COUNT in agent output)"
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `none agent finds thisLogger usages`() {
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(
                lifetime,
                consoleTitle = "thislogger-none",
                project = IntelliJProject.ThisLoggerProject,
                mcpConnectionMode = McpConnectionMode.None,
            ).waitForProjectReady()

            val result = runAgent(session, withMcp = false)
            results.add(result)

            session.console.writeInfo("NONE result: count_accurate=${result.countAccurate}, " +
                    "method=${result.discoveryMethod}")

            check(result.countAccurate) {
                "NONE agent reported incorrect thisLogger() count.\n" +
                        "Expected: $expectedCallSiteFiles call-site files\n" +
                        "Got count_accurate=false (see TOTAL_COUNT in agent output)"
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    // ── Prompt + evaluation ─────────────────────────────────────────────────

    private fun runAgent(session: IntelliJContainer, withMcp: Boolean): RunRecord {
        val console = session.console
        val agent = session.aiAgents.claude
        val label = if (withMcp) "MCP" else "NONE"

        console.writeStep(1, "[$label] Building thisLogger search prompt")

        val prompt = buildString {
            appendLine("# Task: Count all usages of `thisLogger()` in the project")
            appendLine()
            appendLine("Find all files in this Kotlin project that CALL `thisLogger()`.")
            appendLine("Do NOT count the file that defines/declares the function.")
            appendLine()
            if (withMcp) {
                appendLine("Use `steroid_execute_code` to search the project via IntelliJ APIs.")
                appendLine("Read `mcp-steroid://skill/coding-with-intellij-vfs` for the preferred pattern.")
                appendLine()
                appendLine("Recommended approach inside steroid_execute_code:")
                appendLine("  import com.intellij.psi.search.FilenameIndex")
                appendLine("  import com.intellij.psi.search.GlobalSearchScope")
                appendLine("  // search for all .kt files and grep for thisLogger() call pattern")
                appendLine("  // OR use ReferencesSearch on the thisLogger function's PsiElement")
                appendLine()
                appendLine("Do NOT use the Glob tool or Bash find/grep — use IntelliJ IDE APIs.")
            } else {
                appendLine("Use bash tools (grep, find, Glob) to search the project files.")
                appendLine("The project is located in the current working directory.")
            }
            appendLine()
            appendLine("A 'call site' is any line with: `thisLogger()` used as a value/property initializer.")
            appendLine("Lines that define the function itself are NOT call sites.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines in your final response:")
            appendLine("TOTAL_COUNT: <number of files that call thisLogger()>")
            appendLine("DISCOVERY_METHOD: <one-line description: grep / FilenameIndex / ReferencesSearch / etc.>")
            appendLine("COUNT_ACCURATE: yes")
        }

        console.writeStep(2, "[$label] Running agent prompt")
        val startMs = System.currentTimeMillis()
        val result = agent.runPrompt(prompt, timeoutSeconds = 900).awaitForProcessFinish()
        val durationMs = System.currentTimeMillis() - startMs

        console.writeStep(3, "[$label] Evaluating agent output (${durationMs / 1000}s)")

        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        // Extract markers
        val totalCountStr = findMarkerValue(output, "TOTAL_COUNT", "Total count")
        val discoveryMethod = findMarkerValue(output, "DISCOVERY_METHOD", "Discovery method")
        val countAccurateStr = findMarkerValue(output, "COUNT_ACCURATE", "Count accurate")

        val totalCount = totalCountStr?.trim()?.toIntOrNull()
        // Accept if within ±2 of expected (some agents may count the declaration file too)
        val countAccurate = totalCount != null && totalCount in (expectedCallSiteFiles - 2)..(expectedCallSiteFiles + 2)
        val countAccurateMarker = countAccurateStr?.lowercase()?.contains("yes") == true

        val usedIdeApi = withMcp && (
                combined.contains("FilenameIndex", ignoreCase = false) ||
                combined.contains("ReferencesSearch", ignoreCase = false) ||
                combined.contains("steroid_execute_code", ignoreCase = true)
        )
        val usedGrep = !withMcp && (
                combined.contains("grep", ignoreCase = true) ||
                combined.contains("find", ignoreCase = true) ||
                combined.contains("Glob", ignoreCase = false)
        )

        println("[$label] exit=${result.exitCode} time=${durationMs / 1000}s " +
                "TOTAL_COUNT=$totalCountStr accurate=$countAccurate " +
                "method=$discoveryMethod")

        console.writeInfo("[$label] TOTAL_COUNT=$totalCountStr (expected ~$expectedCallSiteFiles)")
        console.writeInfo("[$label] COUNT_ACCURATE=$countAccurateMarker | computed=$countAccurate")
        console.writeInfo("[$label] DISCOVERY_METHOD=$discoveryMethod")

        return RunRecord(
            withMcp = withMcp,
            durationMs = durationMs,
            countAccurate = countAccurate,
            discoveryMethod = discoveryMethod,
            usedIdeApi = usedIdeApi,
            usedGrep = usedGrep,
        )
    }
}
