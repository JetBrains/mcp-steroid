/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DevrigCliHelpTest {
    @TempDir
    lateinit var testHome: Path

    @Test
    fun `root help is generated from the command tree`() {
        val result = runHelp("--help")

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue(result.stdout.contains("Usage: devrig"), result.stdout)
        assertTrue(result.stdout.contains("Commands:"), result.stdout)
        for (command in listOf("backend", "install", "mcp", "project")) {
            assertTrue(result.stdout.contains(command), "missing $command in:\n${result.stdout}")
        }
        assertTrue(!result.stdout.contains("mpc"), result.stdout)
        assertTrue(result.stdout.contains("--json"), result.stdout)
    }

    @Test
    fun `backend help describes backend commands only`() {
        val result = runHelp("backend", "--help")

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue(result.stdout.contains("Usage: devrig backend"), result.stdout)
        assertTrue(result.stdout.contains("Commands:"), result.stdout)
        for (command in listOf("download", "provision", "start", "stop")) {
            assertTrue(result.stdout.contains(command), "missing $command in:\n${result.stdout}")
        }
        assertTrue(!result.stdout.contains("install claude"), result.stdout)
    }

    @Test
    fun `install help exposes each target as a real subcommand`() {
        val result = runHelp("install", "--help")

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue(result.stdout.contains("Usage: devrig install"), result.stdout)
        assertTrue(result.stdout.contains("Commands:"), result.stdout)
        for (target in listOf("claude", "codex", "gemini", "config", "devrig", "plugin")) {
            assertTrue(result.stdout.contains(target), "missing $target in:\n${result.stdout}")
        }
    }

    @Test
    fun `nested action help is specific and complete`() {
        val result = runHelp("backend", "download", "--help")

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue(result.stdout.contains("Usage: devrig backend download"), result.stdout)
        assertTrue(result.stdout.contains("<id>"), result.stdout)
        assertTrue(result.stdout.contains("--version"), result.stdout)
        assertTrue(result.stdout.contains("--json"), result.stdout)
    }

    private fun runHelp(vararg args: String): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val lifetime = CloseableStackHost()
        val originalErr = System.err
        return try {
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            val exitCode = runBlocking {
                val services = DevrigServices(
                    lifetime = lifetime,
                    homePaths = HomePaths(testHome).also { it.mkdirsAll() },
                    mcpStdin = ByteArrayInputStream(ByteArray(0)),
                    mcpStdout = PrintStream(stdout, true, Charsets.UTF_8),
                )
                parseDevrigCommand(args.toList().toTypedArray()).execute(services)
            }
            CliResult(
                exitCode = exitCode,
                stdout = stdout.toString(Charsets.UTF_8).replace("\r\n", "\n"),
                stderr = stderr.toString(Charsets.UTF_8).replace("\r\n", "\n"),
            )
        } finally {
            System.setErr(originalErr)
            lifetime.closeAllStacks()
        }
    }

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
