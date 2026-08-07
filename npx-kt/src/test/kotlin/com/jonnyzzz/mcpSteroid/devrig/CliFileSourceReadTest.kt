/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A file source's two ways to read a value — standard input and a file path — must reject malformed UTF-8
 * ([CliExit.IO_ERROR], the code the file branch has answered since `Files.readString` did the decoding) and
 * an oversized payload ([CliExit.DATA_ERROR], nothing failed to be read) IDENTICALLY, through the same
 * [CliInputException]. Before this test existed, the two paths diverged: `Files.readString` (the file
 * path) threw on malformed bytes, while stdin's `decodeToString()` silently substituted U+FFFD, and
 * neither path capped how much it would read.
 *
 * Driven through `execute_code`'s declared `--code-file`, via the same public
 * [GeneratedToolInvocation.argumentsWithFileSources] entry point [CliFileSourceRuntimeTest] uses — there is no
 * need to lift the private readers to test them.
 */
class CliFileSourceReadTest {

    @TempDir
    lateinit var work: Path

    private val spec = devrigCliTools().single { it.name == "steroid_execute_code" }

    private fun commandWith(codeFile: String) = parseRunTool(
        "execute_code", "--project_name=demo", "--code-file=$codeFile", "--task_id=t", "--reason=r",
    )

    // ------------------------------- standard input -------------------------------

    @Test
    fun `malformed UTF-8 from stdin is rejected, not substituted`() {
        val stdin = ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

        val e = assertFailsWith<CliInputException> {
            commandWith("-").argumentsWithFileSources(spec, stdin)
        }

        assertEquals(CliExit.IO_ERROR, e.exit, "message was: ${e.message}")
        assertTrue(e.message!!.contains("not valid UTF-8"), "got: ${e.message}")
    }

    @Test
    fun `over-cap stdin is rejected and the limit is named`() {
        val big = ByteArray((CLI_FILE_SOURCE_MAX_BYTES + 1).toInt()) { 'a'.code.toByte() }

        val e = assertFailsWith<CliInputException> {
            commandWith("-").argumentsWithFileSources(spec, ByteArrayInputStream(big))
        }

        assertEquals(CliExit.DATA_ERROR, e.exit, "message was: ${e.message}")
        assertTrue(e.message!!.contains("10"), "message must name the cap; got: ${e.message}")
    }

    @Test
    fun `a small valid stdin value round-trips`() {
        val arguments = commandWith("-").argumentsWithFileSources(
            spec, ByteArrayInputStream("println(1)".toByteArray(Charsets.UTF_8)),
        )

        assertEquals("println(1)", arguments.getValue("code").jsonPrimitive.content)
    }

    // ------------------------------- file path -------------------------------

    @Test
    fun `malformed UTF-8 from a file is rejected, not substituted`() {
        val file = work.resolve("bad.kts")
        Files.write(file, byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

        val e = assertFailsWith<CliInputException> {
            commandWith(file.toString()).argumentsWithFileSources(spec, ByteArrayInputStream(ByteArray(0)))
        }

        assertEquals(CliExit.IO_ERROR, e.exit, "message was: ${e.message}")
        assertTrue(e.message!!.contains("not valid UTF-8"), "got: ${e.message}")
    }

    @Test
    fun `an over-cap file is rejected and the limit is named`() {
        val file = work.resolve("big.kts")
        Files.newOutputStream(file).use { out ->
            val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }
            repeat(11) { out.write(chunk) }
        }

        val e = assertFailsWith<CliInputException> {
            commandWith(file.toString()).argumentsWithFileSources(spec, ByteArrayInputStream(ByteArray(0)))
        }

        assertEquals(CliExit.DATA_ERROR, e.exit, "message was: ${e.message}")
        assertTrue(e.message!!.contains("10"), "message must name the cap; got: ${e.message}")
    }

    @Test
    fun `a small valid file value round-trips`() {
        val file = work.resolve("ok.kts")
        Files.writeString(file, "println(2)")

        val arguments = commandWith(file.toString()).argumentsWithFileSources(spec, ByteArrayInputStream(ByteArray(0)))

        assertEquals("println(2)", arguments.getValue("code").jsonPrimitive.content)
    }

    // ------------------------------- consistency between the two sources -------------------------------

    @Test
    fun `stdin and file sources report the same exit code for malformed UTF-8`() {
        val file = work.resolve("bad2.kts")
        Files.write(file, byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

        val fromStdin = assertFailsWith<CliInputException> {
            commandWith("-").argumentsWithFileSources(
                spec, ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
            )
        }
        val fromFile = assertFailsWith<CliInputException> {
            commandWith(file.toString()).argumentsWithFileSources(spec, ByteArrayInputStream(ByteArray(0)))
        }

        assertEquals(fromStdin.exit, fromFile.exit)
    }
}
