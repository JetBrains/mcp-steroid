/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [runCliWithLastResortHandling] — [Main.kt]'s crash handler for a failure out of [runCli].
 * Extracted from `mainImpl2` so the exit-code mapping is unit-testable without booting the CLI.
 *
 * Two lanes: a [CliUserFacingException] renders as its message alone (a stack trace would bury the one
 * line the user must read), while any other throwable maps to a plain `64` with the full trace on stderr —
 * the message an agent needs to diagnose an NPE deep in the bridge must never be swallowed.
 */
class LastResortCrashHandlerTest {

    private class CapturedStream {
        val buffer = ByteArrayOutputStream()
        val stream = PrintStream(buffer, true, Charsets.UTF_8)
        fun text(): String = buffer.toString(Charsets.UTF_8)
    }

    /** Runs [block] with `System.err` captured, restoring it even on failure. */
    private fun withCapturedErr(block: () -> Unit): String {
        val originalErr = System.err
        val err = CapturedStream()
        System.setErr(err.stream)
        try {
            block()
        } finally {
            System.setErr(originalErr)
        }
        return err.text()
    }

    @Test
    fun `a successful block returns its own exit code untouched`() {
        val command = DevrigCommand.DevrigCommandInstallDevrig()

        val exit = runCliWithLastResortHandling(command) { 0 }

        assertEquals(0, exit)
    }

    @Test
    fun `CancellationException is rethrown, never swallowed into a fake unexpected error`() {
        val command = DevrigCommand.DevrigCommandInstallDevrig()

        val err = withCapturedErr {
            assertFailsWith<CancellationException> {
                runCliWithLastResortHandling(command) { throw CancellationException("scope shutting down") }
            }
        }
        // Nothing should have been reported as a crash: the exception is still in flight.
        assertEquals("", err)
    }

    @Test
    fun `a crash reports the message and trace on stderr and returns 64`() {
        val command = DevrigCommand.DevrigCommandInstallDevrig()
        var exit = -1

        val err = withCapturedErr {
            exit = runCliWithLastResortHandling(command) { error("boom deep in the bridge") }
        }

        assertEquals(64, exit)
        assertTrue(err.contains("boom deep in the bridge"), err)
        assertTrue(err.contains("\tat "), "expected an actual stack trace, got: $err")
    }

    @Test
    fun `a crash under --json still returns 64 with the trace on stderr`() {
        // stdout is reserved for the machine-readable document, so --json changes nothing about where a
        // crash is reported.
        val command = DevrigCommand.DevrigCommandInstallDevrig(json = true)
        var exit = -1

        val err = withCapturedErr {
            exit = runCliWithLastResortHandling(command) { error("boom deep in the bridge") }
        }

        assertEquals(64, exit)
        assertTrue(err.contains("boom deep in the bridge"), err)
        assertTrue(err.contains("\tat "), "expected an actual stack trace, got: $err")
    }

    @Test
    fun `a NullPointerException reaches the last-resort trace rather than being swallowed`() {
        // A handler bug (an NPE, a broken invariant) must reach the last-resort trace rather than
        // disappearing: the crash handler catches every non-cancellation throwable, reports it, returns 64.
        val command = DevrigCommand.DevrigCommandInstallDevrig()
        var exit = -1

        val err = withCapturedErr {
            exit = runCliWithLastResortHandling(command) {
                throw NullPointerException("something was null in a handler")
            }
        }

        assertEquals(64, exit)
        assertTrue(err.contains("something was null in a handler"), err)
        assertTrue(err.contains("\tat "), "expected an actual stack trace, got: $err")
    }

    @Test
    fun `a CliUserFacingException prints its message alone, with no stacktrace and no crash prose`() {
        val command = DevrigCommand.DevrigCommandBackendStart()
        var exit = -1

        val err = withCapturedErr {
            exit = runCliWithLastResortHandling(command) {
                throw CliUserFacingException("no backend matches 'idea-nope'", exit = 64)
            }
        }

        assertEquals(64, exit)
        assertEquals("no backend matches 'idea-nope'", err.trim())
        assertTrue(!err.contains("\tat "), "a user-facing error must not print a stack trace: $err")
        assertTrue(!err.contains("Unexpected error"), "a user-facing error is not a crash: $err")
    }

    @Test
    fun `a CliUserFacingException carries its own exit code`() {
        val command = DevrigCommand.DevrigCommandBackendStart()
        var exit = -1

        val err = withCapturedErr {
            exit = runCliWithLastResortHandling(command) {
                throw CliUserFacingException("no IDE is reachable", exit = 69)
            }
        }

        assertEquals(69, exit)
        assertEquals("no IDE is reachable", err.trim())
    }

    @Test
    fun `the ManagedBackend failures the CLI throws are handled as user-facing, not as crashes`() {
        // These used to be caught one-by-one inside runCli. They now travel to this handler, so the
        // message-only rendering (and exit 64) must hold for them here.
        val command = DevrigCommand.DevrigCommandBackendStart()
        val failures = listOf(
            ManagedBackendLockException("another devrig backend operation is in progress; retry shortly"),
            ManagedBackendValidationException("unknown backend id 'idea-nope'"),
        )

        for (failure in failures) {
            var exit = -1
            val err = withCapturedErr {
                exit = runCliWithLastResortHandling(command) { throw failure }
            }

            assertEquals(64, exit, "expected $failure to keep the usage exit code")
            assertEquals(failure.message, err.trim())
            assertTrue(!err.contains("\tat "), "expected no stack trace for $failure, got: $err")
        }
    }
}
