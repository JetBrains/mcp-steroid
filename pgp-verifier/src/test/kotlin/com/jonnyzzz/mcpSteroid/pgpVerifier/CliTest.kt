package com.jonnyzzz.mcpSteroid.pgpVerifier

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    @Test
    fun `CLI exit codes`() {
        val good = invokeCli(
            fixture("good-archive.bin").absolutePath,
            fixture("good-archive.bin.sig").absolutePath,
            fixture("test-pubkey.asc").absolutePath,
        )
        assertEquals(0, good.exitCode)
        assertEquals("", good.stdout)
        assertEquals("", good.stderr)

        val badArchive = invokeCli(
            fixture("bad-archive.bin").absolutePath,
            fixture("good-archive.bin.sig").absolutePath,
            fixture("test-pubkey.asc").absolutePath,
        )
        assertEquals(3, badArchive.exitCode)
        assertEquals("", badArchive.stdout)
        assertTrue(badArchive.stderr.contains("[pgp-verifier]"), badArchive.stderr)
        assertTrue(badArchive.stderr.contains("verification FAILED"), badArchive.stderr)

        val wrongArgCount = invokeCli("only-one-arg")
        assertEquals(2, wrongArgCount.exitCode)
        assertEquals("", wrongArgCount.stdout)
        assertTrue(wrongArgCount.stderr.contains("Usage: pgp-verifier <archive> <signature> <public-key>"), wrongArgCount.stderr)
    }

    private fun invokeCli(vararg args: String): CliResult {
        val launcher = System.getProperty("pgpVerifier.test.launcher")
            ?.let(::File)
            ?.takeIf { it.isFile }
        if (launcher != null) {
            val process = ProcessBuilder(listOf(launcher.absolutePath) + args)
                .directory(File(System.getProperty("user.dir")))
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            return CliResult(exitCode, stdout, stderr)
        }

        val stderrBytes = ByteArrayOutputStream()
        val exitCode = PrintStream(stderrBytes, true, StandardCharsets.UTF_8).use { stderr ->
            runCli(args.toList().toTypedArray(), stderr)
        }
        return CliResult(exitCode, "", stderrBytes.toString(StandardCharsets.UTF_8))
    }

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
