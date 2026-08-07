/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Coverage for [appendComparisonCsv] — the arena comparison CSV is what the published numbers are read
 * from, and a header/row column mismatch shifts every value one column left without failing anything.
 */
class ArenaComparisonCsvTest {

    @TempDir
    lateinit var tempDir: File

    private fun verification(
        perClass: List<SurefireClassResult>,
        failToPassTampered: Boolean = false,
        collateralTestFilesEdited: List<String> = emptyList(),
        regressions: List<String> = emptyList(),
        baselineAvailable: Boolean = true,
    ) = ArenaVerificationResult(
        perClass = perClass,
        failToPassTampered = failToPassTampered,
        collateralTestFilesEdited = collateralTestFilesEdited,
        regressions = regressions,
        baselineAvailable = baselineAvailable,
        verificationDurationMs = 1L,
    )

    private fun append(
        csv: File,
        claimedFix: Boolean = true,
        verification: ArenaVerificationResult? = null,
    ) = appendComparisonCsv(
        csvFile = csv,
        instanceId = "dpaia__example__case-1",
        passLabel = "pass-1",
        claimedFix = claimedFix,
        durationS = 12L,
        tokens = null,
        testMetrics = null,
        decoded = null,
        verification = verification,
    )

    private fun columns(csv: File): Pair<List<String>, List<String>> {
        val lines = csv.readLines().filter { it.isNotBlank() }
        return lines.first().split(",") to lines.last().split(",")
    }

    @Test
    fun `every row has exactly as many columns as the header`() {
        val csv = tempDir.resolve("arena-comparison.csv")
        append(csv, verification = verification(listOf(SurefireClassResult("A", 2, 0, 0, 0))))
        val (header, row) = columns(csv)
        assertEquals(header.size, row.size, "header=$header row=$row")
    }

    @Test
    fun `a row written without verification still matches the header width`() {
        val csv = tempDir.resolve("arena-comparison.csv")
        append(csv, verification = null)
        val (header, row) = columns(csv)
        assertEquals(header.size, row.size, "header=$header row=$row")
    }

    @Test
    fun `the objective verdict columns carry the verifier's values`() {
        val csv = tempDir.resolve("arena-comparison.csv")
        append(
            csv,
            claimedFix = false,
            verification = verification(
                perClass = listOf(SurefireClassResult("A", 2, 0, 0, 0)),
                collateralTestFilesEdited = listOf("src/test/java/A.java"),
            ),
        )
        val (header, row) = columns(csv)
        val byName = header.zip(row).toMap()

        assertEquals("true", byName["objective_success"])
        // Solved the task but withheld the marker: an under-claim, not a wrong claim.
        assertEquals("false", byName["claim_matches_reality"])
        assertEquals("false", byName["fail_to_pass_tampered"])
        assertEquals("0", byName["regression_count"])
        assertEquals("1", byName["collateral_tests_edited_count"])
    }

    @Test
    fun `a regression denies objective success in the csv too`() {
        val csv = tempDir.resolve("arena-comparison.csv")
        append(
            csv,
            claimedFix = true,
            verification = verification(
                perClass = listOf(SurefireClassResult("A", 2, 0, 0, 0)),
                regressions = listOf("com.example.OtherTest"),
            ),
        )
        val byName = columns(csv).let { (h, r) -> h.zip(r).toMap() }
        assertEquals("1.0", byName["verified_ftp_rate"])
        assertEquals("false", byName["objective_success"])
        assertEquals("1", byName["regression_count"])
    }

    @Test
    fun `an unknown regression count is blank rather than zero`() {
        val csv = tempDir.resolve("arena-comparison.csv")
        append(
            csv,
            verification = verification(
                perClass = listOf(SurefireClassResult("A", 2, 0, 0, 0)),
                baselineAvailable = false,
            ),
        )
        val byName = columns(csv).let { (h, r) -> h.zip(r).toMap() }
        assertEquals("", byName["regression_count"])
    }

    @Test
    fun `the header is written once and later runs only append rows`() {
        val csv = tempDir.resolve("arena-comparison.csv")
        append(csv, verification = verification(listOf(SurefireClassResult("A", 2, 0, 0, 0))))
        append(csv, verification = verification(listOf(SurefireClassResult("A", 2, 0, 0, 0))))
        val lines = csv.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("timestamp,"))
        val width = lines[0].split(",").size
        lines.drop(1).forEach { assertEquals(width, it.split(",").size, it) }
    }
}
