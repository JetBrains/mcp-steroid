/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The case the solution-readiness pilot records and probes, named once.
 *
 * Three cases were tried for this one slot, and the two that were dropped say what the slot requires.
 *
 * `ripple__keycloak__rename-method-wide` was dropped because its solution is ATOMIC. A readiness curve
 * can only rise if the intermediate states differ, and in the mcp capture of build 1034656372 the work
 * tree went from untouched straight to all 111 files renamed at step 11 — the scheduled step-11 and
 * step-17 patches came out byte-identical. `V` over that trajectory is a step function.
 *
 * `dpaia__spring__boot__microshop-18` was dropped because it is not SOLVABLE often enough to measure.
 * Its recorded history is one success in six runs (`docs/dpaia-arena-results.md`), every failure the
 * same exploration loop, and it carries `dockerOracleWorks = false`, so its Testcontainers oracle never
 * runs in the arena container and the prompt lets an agent claim success on a compile alone. Probes
 * over that case would return zero everywhere, and a flat zero cannot be told apart from "readiness
 * does not grow".
 *
 * `dpaia__feature__service-125` is what remains, and it is chosen for how UNDETERMINED its solution
 * path is. The reference solution is a set of independent deliverables — a release status transition
 * validator (DRAFT→PLANNED→IN_PROGRESS→COMPLETED), five new query endpoints, filtering/pagination on
 * the list endpoint and a database migration, 44 KB over 4 files — which an agent can land in any
 * order, so two states at the same trajectory position are genuinely different amounts of the solution
 * rather than the same edit seen twice. Its history is non-degenerate: five recorded runs, one 900 s
 * timeout and four passes (638 s, 444 s, 570 s, 403 s), which is a success rate strictly between 0 and
 * 1 — the only range in which `V` carries information. And it is the one curated case with
 * `dockerOracleWorks = true`: the 25-test `ReleaseStatusTransitionValidatorTest` really executes in the
 * container, so the grade cannot be earned by compiling.
 *
 * The grading is what makes any of them usable as a probe target at all: [ArenaVerifier.verify] needs
 * the current tree and the pre-agent snapshot, and nothing from the session that produced the tree. So
 * a probe restarted from a recorded state is graded exactly as the capture was.
 */
object RippleCheckpointCase {
    /** The dpaia dataset id; [DpaiaCuratedCases.CASE_CONFIGS] carries its timeouts and JDK. */
    const val INSTANCE_ID: String = "dpaia__feature__service-125"

    /**
     * The directory under `src/test/resources/ripple-checkpoints` the committed states live in.
     *
     * Short instead of the full instance id: the path appears in every probe build's configuration and
     * in the README an operator follows when copying patches out of a capture artifact.
     */
    const val RESOURCE_DIR: String = "feature-service-125"
}
