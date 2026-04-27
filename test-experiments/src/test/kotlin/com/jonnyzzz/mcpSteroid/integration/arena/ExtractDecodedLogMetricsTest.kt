/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
            >> Grep (pattern)
        """.trimIndent()
        val metrics = extract(log)
        assertNotNull(metrics)
        assertEquals(2, metrics!!.execCodeCalls)
        assertEquals(2, metrics.readCalls)
        assertEquals(1, metrics.writeCalls)
        assertEquals(1, metrics.editCalls)
        assertEquals(2, metrics.bashCalls)
        assertEquals(1, metrics.globCalls)
        assertEquals(1, metrics.grepCalls)
    }

    @Test
    fun `counts Edit lines separately from Write`() {
        val log = """
            >> Edit (/home/agent/project-home/src/main/java/Service.java)
            >> Edit (/home/agent/project-home/src/main/java/Other.java)
        """.trimIndent()
        val metrics = extract(log)
        assertNotNull(metrics)
        assertEquals(0, metrics!!.writeCalls)
        assertEquals(0, metrics.readCalls)
        assertEquals(2, metrics.editCalls)
    }

    @Test
    fun `counts Glob and Grep tool lines`() {
        val log = """
            >> Glob (src/**/*.java)
            >> Glob (src/**/*.kt)
            >> Grep (TODO)
        """.trimIndent()
        val metrics = extract(log)
        assertNotNull(metrics)
        assertEquals(2, metrics!!.globCalls)
        assertEquals(1, metrics.grepCalls)
        assertEquals(0, metrics.readCalls)
        assertEquals(0, metrics.bashCalls)
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

    @Test
    fun `extracts decoded bash commands`() {
        val log = """
            >> Bash (JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-arm64 ./gradlew test)
            Some build output
            >> Bash (./mvnw test)
        """.trimIndent()

        assertEquals(
            listOf(
                "JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-arm64 ./gradlew test",
                "./mvnw test",
            ),
            extractDecodedBashCommands(log),
        )
    }

    @Test
    fun `microshop gradle bash commands use configured jdk without wildcard`() {
        val log = """
            Recommended JAVA_HOME: /usr/lib/jvm/temurin-24-jdk-arm64
            >> Bash (JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-arm64 ./gradlew :microservices:product-service:test --rerun-tasks --no-daemon --console=plain)
            BUILD SUCCESSFUL in 11s
            >> Bash (JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-arm64 /home/agent/project-home/gradlew test --no-daemon --console=plain)
            BUILD SUCCESSFUL in 9s
        """.trimIndent()

        val bashCommands = extractDecodedBashCommands(log)
        assertEquals(2, bashCommands.size)
        assertTrue(bashCommands.all { it.contains("JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-arm64") })
        assertFalse(bashCommands.any { it.contains("temurin-21") || it.contains("JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-*") })
        assertEquals(
            emptyList<String>(),
            findDecodedGradleCommandsWithUnexpectedJavaHome(log, expectedJavaHomePrefix = "/usr/lib/jvm/temurin-24-jdk-"),
        )
    }

    @Test
    fun `detects gradle bash commands with lower jdk or wildcard java home`() {
        val log = """
            >> Bash (JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-arm64 ./gradlew test --no-daemon --console=plain)
            >> Bash (JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-* ./gradlew test --no-daemon --console=plain)
            >> Bash (/home/agent/project-home/gradlew test --no-daemon --console=plain)
            >> Bash (JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-arm64 ./gradlew test --no-daemon --console=plain)
        """.trimIndent()

        val unsafe = findDecodedGradleCommandsWithUnexpectedJavaHome(
            decodedLogText = log,
            expectedJavaHomePrefix = "/usr/lib/jvm/temurin-24-jdk-",
        )

        assertEquals(3, unsafe.size)
        assertTrue(unsafe[0].contains("temurin-21"))
        assertTrue(unsafe[1].contains("temurin-24-jdk-*"))
        assertTrue(unsafe[2].contains("/home/agent/project-home/gradlew"))
    }
}
