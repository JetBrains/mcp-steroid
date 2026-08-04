/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.testFramework.common.timeoutRunBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the polling contract of [awaitDialoglessModality] with fake probes.
 *
 * Real EDT modality cannot be exercised host-side: test JVMs run headless
 * (`isModalEdt` short-circuits to false) and `LaterInvocator.enterModal`
 * deadlocks the executor (see the note in ScriptExecutorTest). The real-world
 * trigger — the platform's dialog-less SuvorovProgress modality during EAP
 * cold-start VFS refresh — is validated by the PluginRuntimeCompatibilityTest
 * pycharm-eap leg on TC.
 */
class DialoglessModalityWaitTest {

    @Test
    fun `not modal at all returns immediately`(): Unit = timeoutRunBlocking(10.seconds) {
        val outcome = awaitDialoglessModality(
            isModalEdt = { false },
            hasModalDialogWindow = { error("must not probe dialogs when not modal") },
            budget = 1.seconds,
            poll = 1.milliseconds,
        )
        assertEquals(DialoglessModalityWait.CLEARED_IMMEDIATELY, outcome)
    }

    @Test
    fun `dialog-less modality that clears within budget is waited out`(): Unit = timeoutRunBlocking(10.seconds) {
        val probes = AtomicInteger(0)
        val outcome = awaitDialoglessModality(
            // modal on the first three probes, cleared afterwards
            isModalEdt = { probes.incrementAndGet() <= 3 },
            hasModalDialogWindow = { false },
            budget = 5.seconds,
            poll = 1.milliseconds,
        )
        assertEquals(DialoglessModalityWait.CLEARED, outcome)
    }

    @Test
    fun `a modal dialog window fails fast instead of waiting`(): Unit = timeoutRunBlocking(10.seconds) {
        val outcome = awaitDialoglessModality(
            isModalEdt = { true },
            hasModalDialogWindow = { true },
            budget = 5.seconds,
            poll = 1.milliseconds,
        )
        assertEquals(DialoglessModalityWait.DIALOG_PRESENT, outcome)
    }

    @Test
    fun `persistent dialog-less modality expires the budget`(): Unit = timeoutRunBlocking(10.seconds) {
        val outcome = awaitDialoglessModality(
            isModalEdt = { true },
            hasModalDialogWindow = { false },
            budget = 20.milliseconds,
            poll = 1.milliseconds,
        )
        assertEquals(DialoglessModalityWait.BUDGET_EXPIRED, outcome)
    }

    @Test
    fun `clearance during the final poll wins over an expired deadline`(): Unit = timeoutRunBlocking(10.seconds) {
        val probes = AtomicInteger(0)
        val outcome = awaitDialoglessModality(
            // modal on the first probe only; the poll delay (20ms) outlives the budget (5ms),
            // so the deadline expires DURING the final poll — clearance must still win.
            isModalEdt = { probes.incrementAndGet() <= 1 },
            hasModalDialogWindow = { false },
            budget = 5.milliseconds,
            poll = 20.milliseconds,
        )
        assertEquals(DialoglessModalityWait.CLEARED, outcome)
    }

    @Test
    fun `dialog appearing mid-wait stops the wait`(): Unit = timeoutRunBlocking(10.seconds) {
        val probes = AtomicInteger(0)
        val outcome = awaitDialoglessModality(
            isModalEdt = { true },
            // no dialog on the first two probes, then one shows up
            hasModalDialogWindow = { probes.incrementAndGet() > 2 },
            budget = 5.seconds,
            poll = 1.milliseconds,
        )
        assertEquals(DialoglessModalityWait.DIALOG_PRESENT, outcome)
    }
}
