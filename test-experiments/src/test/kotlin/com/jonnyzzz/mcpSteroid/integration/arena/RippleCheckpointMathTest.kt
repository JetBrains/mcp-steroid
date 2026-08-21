/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for the arithmetic the checkpoint pilot is built on — no Docker, no agent.
 *
 * Four properties are worth a test each. The axis must be the FRACTION of the edit phase, because that
 * is the only coordinate the two arms share: this pilot's captures spent 11 and 40 tool calls writing,
 * so neither "checkpoint 3" nor "edit turn 4" names comparable amounts of work, while "30% of the way
 * through what this agent wrote" does. The phase must start at the FIRST WRITE, because everything
 * before it is the agent reading the material and not part of the trajectory being compared. A repeated
 * state must survive into the plan as a fact about the run rather than be corrected away, since "the
 * agent wrote nothing between 50% and 80% of its edit phase" is the measured shape of the mcp arm. And
 * the area under a readiness curve must stay inside the range that was actually measured: an AUC that
 * quietly extrapolates to 0 or to the end of the trajectory would be the loudest number in the report
 * and none of it measured.
 */
class RippleCheckpointMathTest {
    @Test
    fun `the grid is ten even fractions of the edit phase`() {
        assertEquals(10, RIPPLE_CHECKPOINT_FRACTIONS)
        assertEquals(listOf("mcp", "none"), RIPPLE_CHECKPOINT_ARMS)
        // The pilot's measured ground truth: mcp n=26 first write 15, none n=57 first write 17.
        assertEquals(
            listOf(15, 16, 17, 18, 19, 21, 22, 23, 24, 25),
            rippleCheckpointSteps(n = 26, firstWriteStep = 15),
        )
        assertEquals(
            listOf(17, 21, 25, 29, 33, 37, 41, 45, 49, 53),
            rippleCheckpointSteps(n = 57, firstWriteStep = 17),
        )
    }

    /**
     * The registry is what decides which capture ROUNDS exist, and it is per case for a reason: the
     * keycloak case was discarded after stage 1 and will never carry a second capture, so a global arm
     * list would demand directories nobody intends to fill.
     */
    @Test
    fun `the measured case carries both capture rounds and the discarded one only the first`() {
        assertEquals(listOf("mcp2", "none2"), RIPPLE_CHECKPOINT_ROUND2_ARMS)
        assertEquals(
            RIPPLE_CHECKPOINT_ARMS + RIPPLE_CHECKPOINT_ROUND2_ARMS,
            RIPPLE_CHECKPOINT_CASE_ARMS.getValue(RippleCheckpointCase.RESOURCE_DIR),
        )
        assertEquals(
            listOf("mcp-rmw", "none-rmw"),
            RIPPLE_CHECKPOINT_CASE_ARMS.getValue(
                RippleCases.renameMethodWide.instanceId.substringAfterLast("__")
            ),
        )
        // Round 1's tokens must never change meaning: a second capture is a NEW arm, not a redefinition
        // of an existing one, because every number in RESIDUAL-DIFFICULTY.md is keyed by `mcp`/`none`.
        // They belong to the MEASURED case, which is why the discarded keycloak one had to take
        // distinct tokens — over directories that keep the names they were committed with.
        assertEquals(listOf("mcp", "none"), RIPPLE_CHECKPOINT_ARMS)
        assertEquals(RIPPLE_CHECKPOINT_ARMS, rippleCheckpointCaseOfArm("mcp-rmw").armDirectories)
        assertTrue(RIPPLE_CHECKPOINT_CASE_ARMS.values.all { it.distinct() == it }) {
            "an arm listed twice would probe the same directory under two names: $RIPPLE_CHECKPOINT_CASE_ARMS"
        }
    }

    /**
     * The invariant the whole token scheme rests on: an arm token names exactly one case.
     *
     * A probe build forwards `arm`, `index` and `replicate` and nothing else — the three are declared
     * in another repository's TeamCity DSL — so [rippleCheckpointCaseOfArm] is the only thing that can
     * say which case a cell reads. Two cases sharing a token would not fail: the lookup would return
     * the first one and fifty probe cells would grade one case's states against another case's oracle.
     *
     * The resource directories are checked for the same reason one level down. They are the keys of
     * [RIPPLE_CHECKPOINT_CASE_ARMS], built with `associate`, which silently keeps the LAST duplicate —
     * a repeated directory would drop a case's arms out of the layout test without a word.
     */
    @Test
    fun `arm tokens and resource directories are unique across the whole registry`() {
        val arms = RippleCheckpointCases.ALL.flatMap { it.arms }
        assertEquals(arms.distinct(), arms) { "an arm token names two cases at once: $arms" }
        assertEquals(arms, RIPPLE_CHECKPOINT_ALL_ARMS)

        val dirs = RippleCheckpointCases.ALL.map { it.resourceDir }
        assertEquals(dirs.distinct(), dirs) { "two cases claim one resource directory: $dirs" }
        assertEquals(dirs.size, RIPPLE_CHECKPOINT_CASE_ARMS.size)

        RippleCheckpointCases.ALL.forEach { case ->
            assertEquals(case.arms.size, case.armDirectories.distinct().size) {
                "${case.resourceDir}: two of its arms resolve to one directory, so one capture would " +
                    "overwrite the other's states"
            }
            assertTrue(case.armDirs.keys.all { it in case.arms }) {
                "${case.resourceDir}: ${case.armDirs.keys - case.arms.toSet()} is renamed but not " +
                    "registered as an arm, so nothing addresses it"
            }
        }
    }

    /**
     * Every DPAIA case of the registry must be a CURATED case.
     *
     * A case outside [DpaiaCuratedCases.CASE_CONFIGS] runs on the defaults — 900 s of agent budget, a
     * 10-minute project-ready timeout and JDK 21 — which is exactly how a capture ends up timing out in
     * both arms and publishing no states at all. The keycloak case is not a DPAIA one and carries its
     * own configuration in [RippleCases], so it is excluded by the same `dpaia__` prefix the dataset
     * uses.
     */
    @Test
    fun `every dpaia case of the checkpoint registry is a curated case`() {
        val dpaiaCases = RippleCheckpointCases.ALL.filter { it.instanceId.startsWith("dpaia__") }
        assertEquals(RippleCheckpointCases.ALL.size - 1, dpaiaCases.size) {
            "only the keycloak case is not a DPAIA one: ${RippleCheckpointCases.ALL.map { it.instanceId }}"
        }
        dpaiaCases.forEach { case ->
            assertTrue(case.instanceId in DpaiaCuratedCases.CASE_CONFIGS) {
                "${case.instanceId} (${case.resourceDir}) is not curated, so it would be captured on " +
                    "the default budgets and JDK instead of its own"
            }
        }
    }

    /**
     * The whole point of the axis: the arms' edit phases differ by nearly a factor of four, so only the
     * fraction lines them up. Both plans carry the very same ten coordinates while their steps have
     * nothing to do with each other — which is what lets the report draw the two curves over one another.
     */
    @Test
    fun `both arms carry the same ten fractions over utterly different step counts`() {
        val mcp = selectCheckpoints(n = 26) { step -> if (step < 15) "pristine" else "mcp-$step" }
        val none = selectCheckpoints(n = 57) { step -> if (step < 17) "pristine" else "none-$step" }

        val fractions = listOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)
        assertEquals(fractions, mcp.checkpoints.map { it.editFraction })
        assertEquals(fractions, none.checkpoints.map { it.editFraction })
        assertEquals(21, mcp.checkpoints[5].step)
        assertEquals(37, none.checkpoints[5].step)
    }

    @Test
    fun `the edit phase starts at the first write and never reaches the final state`() {
        (2..100).forEach { n ->
            val firstWrite = maxOf(1, n / 2)
            val steps = rippleCheckpointSteps(n = n, firstWriteStep = firstWrite)

            assertEquals(RIPPLE_CHECKPOINT_FRACTIONS, steps.size) { "n=$n -> $steps" }
            assertEquals(firstWrite, steps.first()) { "n=$n -> $steps" }
            assertTrue(steps.last() < n) { "n=$n -> $steps" }
            assertEquals(steps.sorted(), steps) { "n=$n -> $steps" }
        }
    }

    /**
     * An edit phase shorter than the number of fractions cannot give every fraction a step of its own,
     * and the rounding of the last ones lands ON the final state — which is never a checkpoint, because
     * the source run's own outcome is judged separately. Pulled back to the last state before it, so the
     * plan stays ten fractions long and the repetition is visible through `sameStateAs`.
     */
    @Test
    fun `a fraction that rounds onto the final state is pulled back to the state before it`() {
        assertEquals(
            listOf(4, 4, 4, 5, 5, 5, 5, 5, 5, 5),
            rippleCheckpointSteps(n = 6, firstWriteStep = 4),
        )
    }

    @Test
    fun `a first write at or past the final state is not a trajectory to compare`() {
        assertThrows(IllegalArgumentException::class.java) {
            rippleCheckpointSteps(n = 26, firstWriteStep = 26)
        }
        assertThrows(IllegalArgumentException::class.java) {
            rippleCheckpointSteps(n = 26, firstWriteStep = 0)
        }
    }

    @Test
    fun `the first write is the earliest step whose tree differs from the pristine one`() {
        val plan = selectCheckpoints(n = 26) { step -> if (step < 15) "pristine" else "tree-$step" }

        assertEquals(26, plan.n)
        assertEquals(15, plan.firstWriteStep)
        assertEquals(RIPPLE_CHECKPOINT_FRACTIONS, plan.fractions)
        assertEquals(listOf(15, 16, 17, 18, 19, 21, 22, 23, 24, 25), plan.steps)
        assertEquals((1..10).toList(), plan.checkpoints.map { it.index })
        assertEquals("tree-15", plan.checkpoints.first().stateId)
    }

    /**
     * A capture whose work tree never changed has no edit phase at all, so there is nothing to take a
     * fraction OF. Reported rather than planned around: the caller has just paid for an Opus run and a
     * plan of ten pristine trees would look like a measurement.
     */
    @Test
    fun `a capture that never wrote anything before its final state cannot be planned`() {
        assertThrows(IllegalStateException::class.java) { selectCheckpoints(n = 26) { "pristine" } }
    }

    @Test
    fun `positions are normalized by the measured n`() {
        val plan = selectCheckpoints(n = 26) { step -> if (step < 15) "pristine" else "tree-$step" }

        assertEquals(15.0 / 26.0, plan.checkpoints.first().position, 1e-9)
        assertEquals(25.0 / 26.0, plan.checkpoints.last().position, 1e-9)
    }

    /**
     * The mcp capture's real shape, and the reason the old "move the checkpoint forward" rule had to
     * go. Ten fractions of that arm's edit phase hold six distinct trees: the agent wrote nothing
     * between its 19th and 24th tool call, which is a MEASUREMENT ("half way through its edit phase this
     * agent stopped writing for three checkpoints") and not a collision to be repaired. Every fraction
     * is kept, and the repetition is named by the earliest step holding that state so a caller can skip
     * paying for a tree the pilot has already probed.
     */
    @Test
    fun `a repeated state is kept as data and points back at the step that first held it`() {
        val plan = selectCheckpoints(n = 26) { step ->
            when {
                step < 15 -> "pristine"
                step in 19..23 -> "tree-19"
                step >= 24 -> "tree-24"
                else -> "tree-$step"
            }
        }

        assertEquals(listOf(15, 16, 17, 18, 19, 21, 22, 23, 24, 25), plan.steps)
        assertEquals(
            listOf(null, null, null, null, null, 19, 19, 19, null, 24),
            plan.checkpoints.map { it.sameStateAs },
        )
        assertEquals(6, plan.checkpoints.count { it.sameStateAs == null })
    }

    /**
     * The shell arm's shape: two separate flat stretches, each naming its own earliest step. A rule that
     * only compared a checkpoint with its predecessor would report the second pair against the first.
     */
    @Test
    fun `two separate repeated stretches each name their own earliest step`() {
        val plan = selectCheckpoints(n = 57) { step ->
            when (step) {
                in 0..16 -> "pristine"
                29, 33 -> "written-29"
                45, 49 -> "written-45"
                else -> "state-$step"
            }
        }
        val sameStateAs = plan.checkpoints.associate { it.step to it.sameStateAs }

        assertNull(sameStateAs.getValue(29))
        assertEquals(29, sameStateAs.getValue(33))
        assertNull(sameStateAs.getValue(45))
        assertEquals(45, sameStateAs.getValue(49))
    }

    @Test
    fun `readiness is the success fraction`() {
        assertEquals(0.0, checkpointReadiness(0, 5))
        assertEquals(0.4, checkpointReadiness(2, 5))
        assertEquals(1.0, checkpointReadiness(5, 5))
    }

    @Test
    fun `auc integrates only the observed range`() {
        val curve = ReadinessCurve(listOf(
            ReadinessPoint(0.1, 0.0),
            ReadinessPoint(0.2, 0.5),
            ReadinessPoint(0.5, 1.0),
        ))
        // trapezoids: (0+0.5)/2*0.1 + (0.5+1.0)/2*0.3 = 0.025 + 0.225
        assertEquals(0.25, curve.auc, 1e-9)
        assertEquals(0.1, curve.rangeFrom, 1e-9)
        assertEquals(0.5, curve.rangeTo, 1e-9)
    }

    /**
     * The edit-fraction axis the report integrates over: it starts at 0.0 — the first write — and the
     * area over a partially probed arm must cover the probed fractions only.
     */
    @Test
    fun `auc integrates the edit fraction axis from the first write`() {
        val curve = ReadinessCurve(listOf(
            ReadinessPoint(0.0, 0.0),
            ReadinessPoint(0.4, 1.0),
        ))
        assertEquals(0.2, curve.auc, 1e-9)
        assertEquals(0.0, curve.rangeFrom, 1e-9)
        assertEquals(0.4, curve.rangeTo, 1e-9)
    }

    @Test
    fun `auc refuses to extrapolate a single point`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadinessCurve(listOf(ReadinessPoint(0.1, 1.0))).auc
        }
    }
}
