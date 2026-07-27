/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppendComparisonCsvTest {

    @TempDir
    lateinit var tempDir: File

    private val csvFile: File get() = File(tempDir, "arena-comparison.csv")

    private fun append(
        instanceId: String = "dpaia__feature__service-125",
        mode: String = "mcp",
        execDescriptionVariant: String = "full",
        execDescriptionChars: Int = 29667,
        verification: ArenaVerificationResult? = null,
    ) = appendComparisonCsv(
        csvFile = csvFile,
        instanceId = instanceId,
        passLabel = "pass1",
        mode = mode,
        execDescriptionVariant = execDescriptionVariant,
        execDescriptionChars = execDescriptionChars,
        claimedFix = true,
        durationS = 42L,
        tokens = null,
        testMetrics = null,
        decoded = null,
        verification = verification,
    )

    @Test
    fun `creates the file with a header and a matching-width row`() {
        append(
            verification = ArenaVerificationResult(
                perClass = listOf(SurefireClassResult("A", 5, 0, 0, 0), SurefireClassResult("B", 0, 0, 0, 0)),
                testsTampered = false,
                verificationDurationMs = 7L,
            ),
        )

        val lines = csvFile.readLines()
        assertEquals(2, lines.size)
        assertEquals(lines[0].split(",").size, lines[1].split(",").size, "Row must align with the header")

        val row = lines[0].split(",").zip(lines[1].split(",")).toMap()
        assertEquals("mcp", row["mode"])
        assertEquals("full", row["exec_description_variant"])
        assertEquals("29667", row["exec_description_chars"])
        assertEquals("1", row["verified_ftp_passed"])
        assertEquals("2", row["verified_ftp_total"])
        assertEquals("0.5000", row["verified_ftp_rate"])
        assertEquals("false", row["tests_tampered"])
    }

    @Test
    fun `the two execute-code description arms stay distinguishable in one file`() {
        append(mode = "mcp")
        append(mode = "mcp-slim", execDescriptionVariant = "slim", execDescriptionChars = 15005)

        val lines = csvFile.readLines()
        val header = lines[0].split(",")
        val rows = lines.drop(1).map { header.zip(it.split(",")).toMap() }

        assertEquals(listOf("mcp", "mcp-slim"), rows.map { it["mode"] })
        assertEquals(listOf("full", "slim"), rows.map { it["exec_description_variant"] })
        assertEquals(listOf("29667", "15005"), rows.map { it["exec_description_chars"] })
    }

    @Test
    fun `keeps appending under an unchanged header`() {
        append(instanceId = "case-a")
        append(instanceId = "case-b")

        val lines = csvFile.readLines()
        assertEquals(3, lines.size)
        assertEquals(emptyList<File>(), tempDir.listFiles()!!.filter { it.name.contains("legacy") })
    }

    @Test
    fun `rotates a file whose header predates the current column set`() {
        val staleHeader = "timestamp,instance_id,pass_label,with_mcp,agent_claimed_fix,duration_s"
        csvFile.writeText("$staleHeader\n2026-07-25T00:00:00Z,old-case,pass0,true,true,10\n")

        append()

        val legacy = tempDir.listFiles()!!.single { it.name.startsWith("arena-comparison-legacy-") }
        assertEquals(staleHeader, legacy.readLines()[0])
        assertTrue(legacy.readText().contains("old-case"), "Old rows must survive the rotation")

        val lines = csvFile.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("verified_ftp_rate"), "Fresh file must carry the current header")
    }

    @Test
    fun `rotation picks a free name when one legacy file already exists`() {
        val staleHeader = "timestamp,instance_id"
        csvFile.writeText("$staleHeader\nrow-1\n")
        val first = checkNotNull(rotateComparisonCsvOnHeaderDrift(csvFile)) { "First rotation must happen" }

        csvFile.writeText("$staleHeader\nrow-2\n")
        // Force the same mtime so both rotations compete for the same target name.
        csvFile.setLastModified(first.lastModified())
        val second = checkNotNull(rotateComparisonCsvOnHeaderDrift(csvFile)) { "Second rotation must happen" }

        assertTrue(second.name != first.name, "Must not overwrite ${first.name}")
        assertTrue(first.readText().contains("row-1"))
        assertTrue(second.readText().contains("row-2"))
    }

    @Test
    fun `nothing to rotate for a missing file`() {
        assertNull(rotateComparisonCsvOnHeaderDrift(csvFile))
    }

    @Test
    fun `an empty file gets the header rather than a rotation`() {
        csvFile.writeText("")

        append()

        assertEquals(emptyList<File>(), tempDir.listFiles()!!.filter { it.name.contains("legacy") })
        assertEquals(2, csvFile.readLines().size)
    }
}
