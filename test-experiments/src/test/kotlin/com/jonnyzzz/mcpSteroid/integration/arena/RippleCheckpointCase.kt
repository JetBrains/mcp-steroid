/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The case the solution-readiness pilot records and probes, named once.
 *
 * It is a DPAIA case and not a semantic-ripple one, and the reason is the shape of its SOLUTION. A
 * readiness curve needs a task whose solution is built up gradually: `V(a_i/n)` can only rise from
 * something to something else if the intermediate states differ. The pilot's first case,
 * `ripple__keycloak__rename-method-wide`, does not have that shape — in the mcp capture of build
 * 1034656372 the work tree went from untouched straight to all 111 files renamed at step 11, and the
 * scheduled step-11 and step-17 patches came out byte-identical. `microshop-18` migrates 23 files
 * across 3 modules from `RestTemplate` to `WebClient` and is graded by 8 FAIL_TO_PASS test classes, so
 * a state can be genuinely half-finished and the grade says how half.
 *
 * The grading is what makes it usable as a probe target at all: [ArenaVerifier.verify] needs the
 * current tree and the pre-agent snapshot, and nothing from the session that produced the tree. So a
 * probe restarted from a recorded state is graded exactly as the capture was.
 */
object RippleCheckpointCase {
    /** The dpaia dataset id; [DpaiaCuratedCases.CASE_CONFIGS] carries its timeouts and JDK. */
    const val INSTANCE_ID: String = "dpaia__spring__boot__microshop-18"

    /**
     * The directory under `src/test/resources/ripple-checkpoints` the committed states live in.
     *
     * Short instead of the full instance id: the path appears in every probe build's configuration and
     * in the README an operator follows when copying patches out of a capture artifact.
     */
    const val RESOURCE_DIR: String = "microshop-18"
}
