/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the preflight has to be able to say about a recording, decided here rather than in a container.
 *
 * The preflight is the ONLY check that runs before an Opus capture is paid for, so what it does not
 * examine is what the pilot discovers at capture time. Its first version asserted a counter and ONE
 * non-empty patch — both of which a hook that snapshots a single step would also satisfy, which is
 * exactly the instrument the pilot has just stopped using.
 */
class RippleCheckpointPreflightTest {
    @Test
    fun `a recording with a snapshot per step and visible writes has nothing to report`() {
        val problems = preflightProblems(
            steps = 3,
            trees = mapOf(0 to "empty", 1 to "written", 2 to "written", 3 to "written"),
        )
        assertTrue(problems.isEmpty()) { problems.toString() }
    }

    /**
     * The gap case. `RippleCheckpointRecorder.plan` refuses to select checkpoints when a step has no
     * snapshot, so a missing tag turns into a failure AFTER the Opus run — unless the preflight sees
     * the same shape first.
     */
    @Test
    fun `a step without a snapshot is reported by the step number`() {
        val problems = preflightProblems(
            steps = 3,
            trees = mapOf(0 to "empty", 1 to "written", 3 to "written"),
        )
        assertEquals(1, problems.size) { problems.toString() }
        assertTrue(problems.single().contains("[2]")) { problems.toString() }
    }

    /**
     * The silent-instrument case: the hook runs, the counter advances, every tag exists — and every
     * snapshot holds the same tree, because the shadow repository never saw the agent's writes. A
     * capture like that yields five checkpoints that are all step 0, and it looks perfectly healthy in
     * the log.
     */
    @Test
    fun `snapshots that never change are reported even when every tag exists`() {
        val problems = preflightProblems(
            steps = 3,
            trees = mapOf(0 to "empty", 1 to "empty", 2 to "empty", 3 to "empty"),
        )
        assertEquals(1, problems.size) { problems.toString() }
        assertTrue(problems.single().contains("never changed")) { problems.toString() }
    }

    @Test
    fun `the pristine snapshot must exist for a patch to be a diff against anything`() {
        val problems = preflightProblems(steps = 1, trees = mapOf(1 to "written"))
        assertTrue(problems.any { it.contains("step-0") }) { problems.toString() }
    }
}
