/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [runCliWithLastResortHandling] — [Main.kt]'s crash handler for an unhandled failure out of
 * [runCli]. This is the coverage gap that let a real bug through review: the first version of the
 * `--json` arm reported a one-line envelope and logged nothing anywhere, silently dropping the trace
 * an agent needs to diagnose an NPE deep in the bridge.
 */
class LastResortCrashHandlerTest {

    private class CapturedStream {
        val buffer = ByteArrayOutputStream()
        val stream = PrintStream(buffer, true, Charsets.UTF_8)
        fun text(): String = buffer.toString(Charsets.UTF_8)
    }

    private val parseJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `a successful block returns its own exit code untouched`() {
        val out = CapturedStream()
        val command = DevrigCommand.DevrigCommandInstallDevrig()

        val exit = runCliWithLastResortHandling(command, out.stream) { CliExit.OK }

        assertEquals(CliExit.OK, exit)
        assertEquals("", out.text())
    }

    @Test
    fun `CancellationException is rethrown, never swallowed into a fake unexpected error`() {
        val out = CapturedStream()
        val command = DevrigCommand.DevrigCommandInstallDevrig()

        assertFailsWith<CancellationException> {
            runCliWithLastResortHandling(command, out.stream) { throw CancellationException("scope shutting down") }
        }
        // Nothing should have been reported as a crash: the exception is still in flight.
        assertEquals("", out.text())
    }

    @Test
    fun `a crash under --json emits one envelope on stdout and the trace on stderr`() {
        val out = CapturedStream()
        val command = DevrigCommand.DevrigCommandInstallDevrig(json = true)
        val originalErr = System.err
        val err = CapturedStream()
        System.setErr(err.stream)
        try {
            val exit = runCliWithLastResortHandling(command, out.stream) { error("boom deep in the bridge") }

            assertEquals(CliExit.SOFTWARE, exit)
            val envelope = parseJson.parseToJsonElement(out.text()).jsonObject
            assertEquals(true, envelope.getValue("isError").jsonPrimitive.boolean)
            val content = envelope.getValue("data").jsonObject.getValue("content")
            assertTrue(content.toString().contains("boom deep in the bridge"), "expected the message in the envelope: $content")

            // The trace must reach stderr even though stdout stays a single clean document: an agent
            // debugging a crash has nowhere else to look for it.
            assertTrue(err.text().contains("boom deep in the bridge"), err.text())
            assertTrue(err.text().contains("\tat "), "expected an actual stack trace, got: ${err.text()}")
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `a NullPointerException propagated from the tool dispatcher reaches the last-resort trace`() {
        // A5: the generated-tool dispatcher no longer maps a non-IOException fault to UNAVAILABLE; it lets
        // the throwable propagate, and THIS handler is where it lands. A handler bug (an NPE, a broken
        // invariant) must reach the last-resort trace rather than being reported to the user as an
        // unreachable IDE with the stack trace discarded. The dispatcher's half is pinned by
        // CliErrorEnvelopeTest's propagation test; this pins that the crash handler catches what it hands up.
        val out = CapturedStream()
        val command = DevrigCommand.RunTool(
            toolName = "steroid_list_windows",
            commandName = "list_windows",
            arguments = kotlinx.serialization.json.JsonObject(emptyMap()),
            json = false,
        )
        val originalErr = System.err
        val err = CapturedStream()
        System.setErr(err.stream)
        try {
            val exit = runCliWithLastResortHandling(command, out.stream) {
                throw NullPointerException("windows was null in a handler")
            }

            assertEquals(CliExit.SOFTWARE, exit)
            assertEquals("", out.text(), "stdout must stay clean outside --json")
            assertTrue(err.text().contains("windows was null in a handler"), err.text())
            assertTrue(err.text().contains("\tat "), "expected an actual stack trace, got: ${err.text()}")
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `a crash without --json logs the message and trace to stderr and returns USAGE`() {
        val out = CapturedStream()
        val command = DevrigCommand.DevrigCommandInstallDevrig(json = false)
        val originalErr = System.err
        val err = CapturedStream()
        System.setErr(err.stream)
        try {
            val exit = runCliWithLastResortHandling(command, out.stream) { error("boom deep in the bridge") }

            assertEquals(CliExit.SOFTWARE, exit)
            assertEquals("", out.text(), "stdout must stay clean outside --json")
            assertTrue(err.text().contains("boom deep in the bridge"), err.text())
            assertTrue(err.text().contains("\tat "), "expected an actual stack trace, got: ${err.text()}")
        } finally {
            System.setErr(originalErr)
        }
    }
}
