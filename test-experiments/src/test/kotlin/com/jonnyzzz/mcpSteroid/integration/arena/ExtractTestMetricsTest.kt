/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DpaiaClaudeComparisonTest.extractTestMetrics].
 *
 * These tests validate parsing logic on sample Maven Surefire output strings
 * without requiring Docker or a running IDE container.
 */
class ExtractTestMetricsTest {

    private val extract = DpaiaClaudeComparisonTest.Companion::extractTestMetrics

    @Test
    fun `returns null when no test results in output`() {
        assertNull(extract("No Maven output here"))
        assertNull(extract(""))
        assertNull(extract("[INFO] BUILD SUCCESS"))
    }

    @Test
    fun `parses single summary line with build success`() {
        val output = """
            [INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
            [INFO] BUILD SUCCESS
        """.trimIndent()

        val metrics = extract(output)
        assertNotNull(metrics)
        assertEquals(25, metrics!!.testsRun)
        assertEquals(25, metrics.testsPass)
        assertEquals(0, metrics.testsFail)
        assertEquals(0, metrics.testsError)
        assertEquals(true, metrics.buildSuccess)
    }

    @Test
    fun `parses summary line with failures and build failure`() {
        val output = """
            [INFO] Tests run: 10, Failures: 3, Errors: 1, Skipped: 0
            [INFO] BUILD FAILURE
        """.trimIndent()

        val metrics = extract(output)
        assertNotNull(metrics)
        assertEquals(10, metrics!!.testsRun)
        assertEquals(6, metrics.testsPass)
        assertEquals(3, metrics.testsFail)
        assertEquals(1, metrics.testsError)
        assertEquals(false, metrics.buildSuccess)
    }

    @Test
    fun `takes the last summary line across multiple maven runs`() {
        val output = """
            [INFO] Tests run: 5, Failures: 2, Errors: 0, Skipped: 0
            [INFO] BUILD FAILURE
            [INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
            [INFO] BUILD SUCCESS
        """.trimIndent()

        val metrics = extract(output)
        assertNotNull(metrics)
        assertEquals(25, metrics!!.testsRun)
        assertEquals(25, metrics.testsPass)
        assertEquals(0, metrics.testsFail)
        assertEquals(true, metrics.buildSuccess)
    }

    @Test
    fun `parses per-class lines and takes last (total summary)`() {
        // Maven Surefire outputs per-class lines followed by a total summary
        val output = """
            [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.733 s -- in com.example.FooTest
            [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.144 s -- in com.example.BarTest
            [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
            [INFO] BUILD SUCCESS
        """.trimIndent()

        val metrics = extract(output)
        assertNotNull(metrics)
        // Last match is the summary line (12 total), not a per-class line
        assertEquals(12, metrics!!.testsRun)
        assertEquals(12, metrics.testsPass)
        assertEquals(true, metrics.buildSuccess)
    }

    @Test
    fun `handles output embedded in ndjson-like content`() {
        // The raw agent stdout is NDJSON; Maven output appears inside JSON strings
        val output = """
            {"type":"user","content":"[INFO] Tests run: 45, Failures: 5, Errors: 0, Skipped: 0\nBUILD FAILURE"}
        """.trimIndent()

        val metrics = extract(output)
        assertNotNull(metrics)
        assertEquals(45, metrics!!.testsRun)
        assertEquals(40, metrics.testsPass)
        assertEquals(5, metrics.testsFail)
        assertEquals(0, metrics.testsError)
        assertEquals(false, metrics.buildSuccess)
    }

    @Test
    fun `returns null build success when no build line present`() {
        val output = "[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0"

        val metrics = extract(output)
        assertNotNull(metrics)
        assertEquals(10, metrics!!.testsRun)
        assertNull(metrics.buildSuccess)
    }

    @Test
    fun `calculates pass count correctly from failures and errors`() {
        val output = "[INFO] Tests run: 20, Failures: 3, Errors: 2, Skipped: 5\n[INFO] BUILD FAILURE"

        val metrics = extract(output)
        assertNotNull(metrics)
        // testsPass = testsRun - failures - errors = 20 - 3 - 2 = 15
        assertEquals(20, metrics!!.testsRun)
        assertEquals(15, metrics.testsPass)
        assertEquals(3, metrics.testsFail)
        assertEquals(2, metrics.testsError)
    }
}
