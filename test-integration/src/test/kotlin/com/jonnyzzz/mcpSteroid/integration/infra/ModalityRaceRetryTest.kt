/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for the modality-race retry decision `mcpExecuteCode` makes — the check-then-require
 * race that failed roughly every second Keycloak run: the `smart_non_modal` pre-flight found no
 * modality to wait for (its bounded dialog-less wait returned in 49ms), a concurrent write-action
 * storm entered modality, and the gate observed it milliseconds later. Raising the wait budget
 * cannot close a race, so the only fix is asking again — and the whole point of these tests is that
 * asking again is confined to that one variant.
 */
class ModalityRaceRetryTest {

    /** The literal failure text observed on the Keycloak survey run. */
    private val raceFailure = "execution_id: eid_x-integration-test\n" +
        "FAILED: modal=smart_non_modal requires a non-modal IDE, but a modal dialog/progress is " +
        "present and could not be cleared. Use modal=unleashed to run anyway (no PSI guarantees). " +
        "See the screenshot + thread dump under execution 'eid_x-integration-test'."

    @Test
    fun `the transient race variant is recognized`() {
        assertTrue(isTransientModalityRace(raceFailure))
    }

    @Test
    fun `a surviving modal dialog window is NOT retried`() {
        // ScriptExecutor's DIALOG_PRESENT detail: the IDE settled into a state with a real dialog up.
        // Retrying would loop past a stuck dialog — exactly what waitForIdeWindow fails fast on.
        val dialogPresent = "FAILED: modal=smart_non_modal requires a non-modal IDE, but a modal dialog " +
            "window is showing and could not be cleared. Use modal=unleashed to run anyway."
        assertFalse(isTransientModalityRace(dialogPresent))
    }

    @Test
    fun `a dialog-less progress that outlived the bounded wait is NOT retried`() {
        // BUDGET_EXPIRED: the pre-flight DID find modality and waited its whole budget on it. That is
        // a genuinely slow IDE, and it must surface rather than be looped over silently.
        val budgetExpired = "FAILED: modal=smart_non_modal requires a non-modal IDE, but a dialog-less " +
            "modal progress (IDE freeze-protection/indexing) persisted past the bounded wait " +
            "(mcp.steroid.execution.dialogless.modal.wait.ms) — the IDE may still be settling; " +
            "retrying later can help. Use modal=unleashed to run anyway."
        assertFalse(isTransientModalityRace(budgetExpired))
    }

    @Test
    fun `the same detail on the non_modal profile is NOT retried`() {
        // The retried detail is the gate's FALLBACK branch, which `non_modal` also reaches — but that
        // profile runs no dialog sweep and no dialog-less wait, so an observed modality there is not
        // evidence of a race and may be a stuck dialog nobody ever tried to close.
        val nonModal = "FAILED: modal=non_modal requires a non-modal IDE, but a modal dialog/progress " +
            "is present and could not be cleared. Use modal=unleashed to run anyway."
        assertFalse(isTransientModalityRace(nonModal))
        assertFalse(shouldRetryModalityRace(nonModal, elapsedSinceFirstRaceMs = 0))
    }

    @Test
    fun `the race window covers the current storm, not the whole call`() {
        // A race stamps the window and later races keep it.
        val start = modalityRaceWindowStart(currentStartMs = null, isRaceAttempt = true, nowMs = 1_000)
        assertEquals(1_000L, start)
        assertEquals(1_000L, modalityRaceWindowStart(start, isRaceAttempt = true, nowMs = 5_000))

        // Any non-race attempt in between — typically an INDEXING IN PROGRESS poll, which can run for
        // minutes after the gate passes — ends the storm, so a later storm is not charged for it.
        assertNull(modalityRaceWindowStart(start, isRaceAttempt = false, nowMs = 9_000))
        assertEquals(
            400_000L,
            modalityRaceWindowStart(currentStartMs = null, isRaceAttempt = true, nowMs = 400_000),
        )
    }

    @Test
    fun `script output that merely mentions modality is not a gate failure`() {
        assertFalse(isTransientModalityRace("a modal dialog/progress is present and could not be cleared"))
        assertFalse(isTransientModalityRace("requires a non-modal IDE, but something else entirely."))
        assertFalse(isTransientModalityRace("done"))
        assertFalse(isTransientModalityRace(""))
    }

    @Test
    fun `the retry is bounded and gives up with the original failure`() {
        assertTrue(shouldRetryModalityRace(raceFailure, elapsedSinceFirstRaceMs = 0, budgetMs = 1_000))
        assertTrue(shouldRetryModalityRace(raceFailure, elapsedSinceFirstRaceMs = 999, budgetMs = 1_000))
        // At the budget the loop stops, so the caller reports the IDE's own gate error rather than a
        // wait-timeout message that would have lost it.
        assertFalse(shouldRetryModalityRace(raceFailure, elapsedSinceFirstRaceMs = 1_000, budgetMs = 1_000))
        assertFalse(shouldRetryModalityRace(raceFailure, elapsedSinceFirstRaceMs = 60_000, budgetMs = 1_000))
    }

    @Test
    fun `no budget makes a non-race failure retryable`() {
        val unrelated = "FAILED: Unresolved reference 'JavaSdk'"
        assertFalse(shouldRetryModalityRace(unrelated, elapsedSinceFirstRaceMs = 0, budgetMs = 600_000))
    }

    @Test
    fun `the default budget retries early losses and refuses to wait out a wedged IDE`() {
        assertTrue(shouldRetryModalityRace(raceFailure, elapsedSinceFirstRaceMs = 0))
        assertTrue(shouldRetryModalityRace(raceFailure, elapsedSinceFirstRaceMs = 119_000))
        assertFalse(shouldRetryModalityRace(raceFailure, elapsedSinceFirstRaceMs = 120_001))
    }
}
