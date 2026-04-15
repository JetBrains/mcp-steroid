/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [extractDecodedLogMetrics].
 *
 * Validates counting of steroid_execute_code, Read, Write, and Bash tool lines
 * from the decoded agent log format produced by ConsoleAwareAgentSession.
 */
class ExtractDecodedLogMetricsTest {

    private val extract = ::extractDecodedLogMetrics

    @Test
    fun `returns null when text has no tool lines`() {
        assertNull(extract(""))
        assertNull(extract("no tool calls here"))
        assertNull(extract("[INFO] something\n[stderr] other"))
    }

    @Test
    fun `counts steroid_execute_code with full MCP prefix`() {
        val log = ">> mcp__mcp-steroid__steroid_execute_code (Mandatory first call)"
        val metrics = extract(log)
        assertNotNull(metrics)
        assertEquals(1, metrics!!.execCodeCalls)
        assertEquals(0, metrics.readCalls)
        assertEquals(0, metrics.writeCalls)
        assertEquals(0, metrics.bashCalls)
    }

    @Test
    fun `counts steroid_execute_code with bare name`() {
        val log = ">> steroid_execute_code (some reason)"
        val metrics = extract(log)
        assertNotNull(metrics)
        assertEquals(1, metrics!!.execCodeCalls)
    }

    @Test
    fun `counts Read Write Bash tool lines`() {
        val log = """
            >> Read (/home/agent/project-home/src/main/java/Service.java)
            >> Read (/home/agent/project-home/src/test/java/ServiceTest.java)
            >> Write (/home/agent/project-home/src/main/java/NewFile.java)
            >> Bash (./mvnw test -Dtest=ServiceTest)
            >> Bash (./mvnw compile)
        """.trimIndent()
        val metrics = extract(log)
        assertNotNull(metrics)
        assertEquals(0, metrics!!.execCodeCalls)
        assertEquals(2, metrics.readCalls)
        assertEquals(1, metrics.writeCalls)
        assertEquals(2, metrics.bashCalls)
    }

    @Test
    fun `counts all tool types from real-world decoded log excerpt`() {
        val log = """
            >> ToolSearch (2)
            >> mcp__mcp-steroid__steroid_execute_code (Mandatory first call: check VCS changes)
            >> Glob (src/main/java/**/*.java)
            >> Read (/home/agent/project-home/src/test/java/ReleaseQueryEndpointsIT.java)
            >> Read (/home/agent/project-home/src/main/java/ReleaseService.java)
            >> Edit (/home/agent/project-home/src/main/java/ReleaseService.java)
            >> Write (/home/agent/project-home/src/main/java/NewDto.java)
            >> Bash (./mvnw test -Dtest=ReleaseQueryEndpointsIT)
            >> mcp__mcp-steroid__steroid_execute_code (Check compilation after edits)
            >> Bash (./mvnw test)
        """.trimIndent()
        val metrics = extract(log)
        assertNotNull(metrics)
        assertEquals(2, metrics!!.execCodeCalls)
        assertEquals(2, metrics.readCalls)
        assertEquals(1, metrics.writeCalls)
        assertEquals(2, metrics.bashCalls)
    }

    @Test
    fun `does not count Edit lines as Write`() {
        val log = """
            >> Edit (/home/agent/project-home/src/main/java/Service.java)
            >> Edit (/home/agent/project-home/src/main/java/Other.java)
        """.trimIndent()
        val metrics = extract(log)
        assertNotNull(metrics)
        assertEquals(0, metrics!!.writeCalls)
        assertEquals(0, metrics.readCalls)
    }

    @Test
    fun `ignores non-tool lines`() {
        val log = """
            [INFO] some info line
            [stderr] error output
            [INFO] Claude Code 2.1.x response
            >> Read (/some/file)
            just some text
        """.trimIndent()
        val metrics = extract(log)
        assertNotNull(metrics)
        assertEquals(1, metrics!!.readCalls)
        assertEquals(0, metrics.execCodeCalls)
    }
}
