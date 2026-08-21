/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The coordinates of one research cell, resolved from the build's properties.
 *
 * Every field is validated here, before a container exists, because the cells of this experiment differ
 * ONLY by these numbers: a budget that arrived as null and silently defaulted would publish a note
 * under a budget it never ran with, and nothing later in the pipeline could notice.
 */
data class UnderstandingResearchCoordinates(
    val caseId: String,
    val arm: String,
    val budget: Int,
    val noteLimitChars: Int,
    val replicate: Int,
) {
    val noteId: String get() = understandingNoteId(arm, budget, noteLimitChars, replicate)
}

/**
 * Reads and checks the five research coordinates.
 *
 * [allowedBudgets] and [allowedNoteLimits] are the pre-registered grid. A value outside it is refused
 * rather than run: the curve this experiment publishes is `budget -> downstream success`, and a point
 * measured at a budget nobody registered cannot be placed on it.
 */
fun understandingResearchCoordinates(
    caseId: String?,
    arm: String?,
    budget: String?,
    noteLimit: String?,
    replicate: String?,
    allowedBudgets: Set<Int> = UNDERSTANDING_BUDGETS,
    allowedNoteLimits: Set<Int> = UNDERSTANDING_NOTE_LIMITS,
): UnderstandingResearchCoordinates {
    val resolvedCase = caseId?.trim().orEmpty()
    check(resolvedCase.isNotEmpty()) {
        "no case given. Pass -D$UNDERSTANDING_CASE_PROPERTY=<instanceId>"
    }
    val resolvedArm = arm?.trim().orEmpty()
    check(resolvedArm == "mcp" || resolvedArm == "none") {
        "the research arm must be `mcp` or `none`, got '$resolvedArm' — pass -D$UNDERSTANDING_ARM_PROPERTY"
    }
    val resolvedBudget = budget?.trim()?.toIntOrNull()
    check(resolvedBudget != null && resolvedBudget in allowedBudgets) {
        "the research budget must be one of $allowedBudgets, got '${budget.orEmpty()}' — pass " +
            "-D$UNDERSTANDING_BUDGET_PROPERTY"
    }
    val resolvedLimit = noteLimit?.trim()?.toIntOrNull()
    check(resolvedLimit != null && resolvedLimit in allowedNoteLimits) {
        "the note limit must be one of $allowedNoteLimits, got '${noteLimit.orEmpty()}' — pass " +
            "-D$UNDERSTANDING_NOTE_LIMIT_PROPERTY"
    }
    val resolvedReplicate = replicate?.trim()?.toIntOrNull()
    check(resolvedReplicate != null && resolvedReplicate >= 1) {
        "the replicate must be a positive number, got '${replicate.orEmpty()}' — pass " +
            "-D$UNDERSTANDING_REPLICATE_PROPERTY"
    }
    return UnderstandingResearchCoordinates(
        caseId = resolvedCase,
        arm = resolvedArm,
        budget = resolvedBudget,
        noteLimitChars = resolvedLimit,
        replicate = resolvedReplicate,
    )
}

/**
 * The pre-registered research budgets, in environment interactions.
 *
 * Three points and not two, because the claim under test is about the SHAPE of the acquisition curve —
 * "semantic access buys the same understanding earlier" is a statement about 5 and 10, and 20 is what
 * says whether the shell arm merely catches up later or never catches up at all. The pilot may still
 * run 5 and 10 first and add 20 only if the first two separate; the constant lists what a cell may be
 * queued with, not what must be spent.
 */
val UNDERSTANDING_BUDGETS: Set<Int> = setOf(5, 10, 20)

/**
 * The pre-registered note limits, in characters.
 *
 * 1 000 asks whether the agent can pick the single most valuable thing it learned; 5 000 is enough for
 * an actionable model of the change.
 *
 * The middle points are no longer hypothetical. 5 000 saturated: every note from either arm, at either
 * budget, carried the weak agent to 5/5, so that length cannot separate anything. 1 000 separated
 * sharply. The interesting region is therefore between them, and it is measured rather than argued —
 * which is why 2 000 and 3 000 are registered as first-class cells.
 */
val UNDERSTANDING_NOTE_LIMITS: Set<Int> = setOf(1_000, 2_000, 3_000, 5_000)

/**
 * One research cell of the repository-understanding experiment.
 *
 * A test class of its own, holding one `@Test`, for the same reason the checkpoint pilot's probe does:
 * a build queues exactly one cell, and the class must not inherit graded scenario methods that would
 * spend four more agent runs per build.
 */
class UnderstandingResearchTest {

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun research() {
        val coordinates = understandingResearchCoordinates(
            caseId = System.getProperty(UNDERSTANDING_CASE_PROPERTY),
            arm = System.getProperty(UNDERSTANDING_ARM_PROPERTY),
            budget = System.getProperty(UNDERSTANDING_BUDGET_PROPERTY),
            noteLimit = System.getProperty(UNDERSTANDING_NOTE_LIMIT_PROPERTY),
            replicate = System.getProperty(UNDERSTANDING_REPLICATE_PROPERTY),
        )
        runUnderstandingResearch(
            case = UnderstandingCases.of(coordinates.caseId),
            arm = coordinates.arm,
            budget = coordinates.budget,
            noteLimitChars = coordinates.noteLimitChars,
            replicate = coordinates.replicate,
        )
    }
}

/**
 * One downstream cell: the weak agent, the pristine tree, and either a note or nothing.
 *
 * The model is defaulted here rather than trusted to every build configuration, exactly as the
 * checkpoint probe defaults it: an explicit value is left alone and judged by the assertion inside the
 * run, which is what makes a wrong model loud instead of expensive. Restored afterwards because the
 * Gradle test JVM is shared and a research cell in the same JVM must not inherit a haiku.
 */
class UnderstandingDownstreamTest {

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun downstream() {
        val caseId = System.getProperty(UNDERSTANDING_CASE_PROPERTY)?.trim().orEmpty()
        check(caseId.isNotEmpty()) { "no case given. Pass -D$UNDERSTANDING_CASE_PROPERTY=<instanceId>" }
        val condition = understandingConditionOf(System.getProperty(UNDERSTANDING_CONDITION_PROPERTY))
        val replicate = System.getProperty(UNDERSTANDING_REPLICATE_PROPERTY)?.trim()?.toIntOrNull()
        check(replicate != null && replicate >= 1) {
            "the replicate must be a positive number — pass -D$UNDERSTANDING_REPLICATE_PROPERTY"
        }

        val previousModel = System.getProperty(RippleCheckpointProbeTest.CLAUDE_MODEL_PROPERTY)
        if (previousModel == null) {
            System.setProperty(
                RippleCheckpointProbeTest.CLAUDE_MODEL_PROPERTY,
                RippleCheckpointProbeTest.PROBE_MODEL,
            )
        }
        try {
            runUnderstandingDownstream(
                case = UnderstandingCases.of(caseId),
                condition = condition,
                replicate = replicate,
            )
        } finally {
            if (previousModel == null) {
                System.clearProperty(RippleCheckpointProbeTest.CLAUDE_MODEL_PROPERTY)
            }
        }
    }
}
