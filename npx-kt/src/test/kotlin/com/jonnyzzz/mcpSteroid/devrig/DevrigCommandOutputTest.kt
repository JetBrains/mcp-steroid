/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * Pins how [DevrigCliInvocation.execute] routes its output. CLI convention:
 *  - Help / version go to **stdout** (so `--help | less`, `--version | awk` work).
 *  - Human error variants go to **stderr**; requested JSON errors use one envelope on stdout.
 *
 * Mixing these up has bitten plenty of CLIs in the past — we lock the routing in.
 *
 * The test temporarily replaces `System.out` / `System.err` with byte buffers,
 * runs the unit, then restores the originals. JUnit's `@AfterEach` guarantees
 * restoration even on assertion failure so a single failing case doesn't poison
 * unrelated tests in the suite.
 */
class DevrigCommandOutputTest {

    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream
    private lateinit var outBuf: ByteArrayOutputStream
    private lateinit var errBuf: ByteArrayOutputStream
    private lateinit var homePaths: HomePaths

    @TempDir
    lateinit var testHome: Path

    @BeforeEach
    fun captureStreams() {
        homePaths = HomePaths(testHome).also { it.mkdirsAll() }
        originalOut = System.out
        originalErr = System.err
        outBuf = ByteArrayOutputStream()
        errBuf = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuf, true, Charsets.UTF_8))
        System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))
    }

    @AfterEach
    fun restoreStreams() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    private fun stdout(): String = outBuf.toString(Charsets.UTF_8).replace("\r\n", "\n")
    private fun stderr(): String = errBuf.toString(Charsets.UTF_8).replace("\r\n", "\n")
    private fun runCliForTest(vararg rawArgs: String): Int {
        val lifetime = CloseableStackHost()
        return try {
            runBlocking {
                val services = DevrigServices(
                    lifetime = lifetime,
                    homePaths = homePaths,
                    mcpStdin = ByteArrayInputStream(ByteArray(0)),
                    mcpStdout = PrintStream(outBuf, true, Charsets.UTF_8),
                )
                val command = parseDevrigCommand(arrayOf(*rawArgs))
                runCliWithLastResortHandling(command, services.mcpStdout) { command.execute(services) }
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    // ------------------------------- Help ----------------------------------

    @Test
    fun `Bare devrig writes root help to stdout and exits successfully`() {
        val exit = runCliForTest()

        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for bare devrig; got: ${stderr()}")
        val out = stdout()
        assertTrue(out.contains("Usage: devrig"), "bare devrig must explain the command tree; got:\n$out")
        assertTrue(out.contains("Commands:"), "bare devrig must list its commands; got:\n$out")
    }

    @Test
    fun `Help writes the usage banner to stdout, nothing to stderr`() {
        val exit = runCliForTest("--help")
        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for --help; got: ${stderr()}")
        val out = stdout()
        assertTrue(out.contains("Usage:"), "help output should mention 'Usage:'; got:\n$out")
        assertTrue(out.contains("mcp"), "help should advertise the canonical mcp subcommand; got:\n$out")
        assertFalse(out.contains("mpc"), "help must NOT advertise the hidden mpc alias; got:\n$out")
        assertTrue(out.contains("--version"), "help should advertise --version; got:\n$out")
        assertTrue(out.contains("--help"), "help should advertise --help itself; got:\n$out")
        assertTrue(out.contains("Commands:"), "help should be generated from the Clikt tree; got:\n$out")
        assertTrue(out.contains("backend"), "help should advertise backend management; got:\n$out")
        assertTrue(out.contains("install"), "help should advertise installation; got:\n$out")
    }

    @Test
    fun `Help output is line-terminated`() {
        // `command --help | tail -n1` should not see a partial line; the launcher
        // must finish its banner with a newline so shells / piped consumers
        // behave predictably.
        runCliForTest("--help")
        assertTrue(stdout().endsWith("\n"), "help output must end with a newline; got: '${stdout().takeLast(20)}'")
    }

    // --------------------------- Install config -----------------------------

    @Test
    fun `Install config writes the manual MCP configuration to stdout, nothing to stderr`() {
        // Pasteable output contract: the stdio mcpServers JSON goes to stdout (so
        // `devrig install config | pbcopy` works); stderr stays clean.
        val exit = runCliForTest("install", "config")
        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for install config; got: ${stderr()}")
        val out = stdout()
        assertTrue(out.contains("\"mcpServers\""), "config must print the mcpServers JSON snippet; got:\n$out")
        assertTrue(out.contains("mcp-steroid"), "config must use the canonical server name; got:\n$out")
        assertTrue(out.contains("mcp add"), "config must list the per-agent add commands; got:\n$out")
    }

    @Test
    fun `a lifecycle verb's --help prints its generated command help exactly once`() {
        val args = arrayOf("backend", "--help")
        val exit = runCliForTest(*args)

        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for --help; got: ${stderr()}")
        val out = stdout()
        assertTrue(out.contains("Usage: devrig backend"), out)
        assertEquals(1, Regex("(?m)^Usage:").findAll(out).count(), "help must be rendered exactly once: $out")
    }

    @Test
    fun `a generated tool command's --help prints that command's own help to stdout`() {
        val args = arrayOf("execute_code", "--help")
        val exit = runCliForTest(*args)

        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for --help; got: ${stderr()}")
        val out = stdout()
        for (token in listOf("execute_code", "--code", "--code-file", "--task_id", "--reason")) {
            assertTrue(out.contains(token), "execute_code --help must name '$token'; got:\n$out")
        }
        assertTrue(out.endsWith("\n"), "help output must end with a newline; got: '${out.takeLast(20)}'")
    }

    // ------------------------------ Version --------------------------------

    @Test
    fun `Version writes getDevrigVersion value to stdout`() {
        val exit = runCliForTest("--version")
        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for --version; got: ${stderr()}")
        val expectedVersion = DevrigVersionMetadata.getDevrigVersion()
        assertEquals("$expectedVersion\n", stdout(),
            "stdout must be exactly the version + newline for `--version`")
    }

    @Test
    fun `Version output is a single line`() {
        // Some monitoring scripts grep `--version | head -1`. Pinning single-line
        // output prevents an accidental multi-line banner sneaking in.
        runCliForTest("--version")
        val lines = stdout().trimEnd().lines()
        assertEquals(1, lines.size, "version must be a single line; got: ${stdout()}")
    }

    // ------------------------------ Unknown --------------------------------

    @Test
    fun `Unknown writes an error and the usage banner, both to stderr`() {
        val exit = runCliForTest("--no-such", "thing")
        assertEquals(64, exit)
        assertEquals("", stdout(), "stdout must stay clean for unknown-arg errors; got: ${stdout()}")
        val err = stderr()
        assertTrue(err.contains("Error:"), "stderr should announce the bad input; got:\n$err")
        assertTrue(err.contains("Usage:"), "stderr should include parser usage for orientation; got:\n$err")
    }

    @Test
    fun `Unknown with multiple tokens joins them with a single space`() {
        runCliForTest("a", "b", "c")
        val err = stderr()
        assertTrue(err.contains("a"), "stderr should identify the offending input; got:\n$err")
    }

    @Test
    fun `Unknown with a single token still produces a coherent error`() {
        runCliForTest("--what")
        val err = stderr()
        assertTrue(err.contains("--what"), "got: $err")
    }

    // -------------------- generated tool commands: usage exit code ----------------------

    @Test
    fun `an unknown flag on a generated tool command exits 64 with stdout clean`() {
        val args = arrayOf("list_windows", "--bogus")
        val exit = runCliForTest(*args)

        assertEquals(CliExit.USAGE, exit)
        assertEquals("", stdout(), "stdout must stay clean for usage errors; got: ${stdout()}")
        assertTrue(stderr().contains("--bogus"), "got:\n${stderr()}")
    }

    @Test
    fun `a usage error the schema binding derives after finalization also exits 64`() {
        // Both --code and its file source: a rule Clikt's grammar cannot express, so SchemaCliBinding
        // raises it one step later (from run(), not from finalization). It must still exit 64, not 1.
        val args =
            arrayOf("execute_code", "--project_name=key", "--code=x", "--code-file=f.kts", "--task_id=t", "--reason=r")
        val exit = runCliForTest(*args)

        assertEquals(CliExit.USAGE, exit)
        assertEquals("", stdout(), "stdout must stay clean for usage errors; got: ${stdout()}")
        assertTrue(stderr().contains("--code-file"), "got:\n${stderr()}")
    }

    @Test
    fun `Hidden mpc alias is never suggested by parser errors`() {
        val exit = runCliForTest("mpx")

        assertEquals(64, exit)
        assertFalse(stderr().contains("mpc"), "hidden alias leaked into error output:\n${stderr()}")
    }

    @Test
    fun `Bare json request fails instead of returning human help`() {
        val exit = runCliForTest("--json")

        assertEquals(64, exit)
        assertEquals("", stderr(), "requested JSON errors must keep stderr clean")
        val envelope = Json.parseToJsonElement(stdout()).jsonObject
        assertEquals("devrig", envelope.getValue("command").jsonPrimitive.content)
        assertEquals(true, envelope.getValue("isError").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `Invalid backend id remains a usage error without a stack trace`() {
        val exit = runCliForTest("backend", "download", "bogus")

        assertEquals(64, exit)
        assertEquals("", stdout())
        assertTrue(stderr().contains("devrig backend download"), stderr())
        assertFalse(stderr().contains("Exception"), stderr())
    }
}
