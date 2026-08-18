/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The only sentence a probe learns about the state it inherits.
 *
 * Blindness is the experiment: the probe must not know which arm produced the state, how far along
 * the trajectory it is, or what was tried. Anything beyond "this is mid-attempt, continue" would let
 * the probe's own competence be confused with a hint from the harness.
 */
const val CHECKPOINT_CONTINUATION_PARAGRAPH: String =
    "You are given an intermediate state of an ongoing attempt to solve this task. Some " +
        "investigation and/or modifications may already have been performed. Continue from the " +
        "current repository state and complete the original task."

/**
 * The brief a checkpoint probe is sent: the continuation paragraph, then the unchanged shell brief.
 *
 * `withMcp = false` in BOTH arms' probes on purpose — a probe measures what the STATE is worth, so
 * giving the probe of the mcp capture the tools back would measure the state and the tools together.
 * The paragraph goes first because it is the frame for everything after it: a probe that reads the
 * task as a fresh one and only later learns the tree is already half-edited has to re-decide what it
 * just planned.
 */
fun buildCheckpointProbePrompt(case: RippleCase, projectDir: String): String =
    CHECKPOINT_CONTINUATION_PARAGRAPH + "\n\n" + buildRipplePrompt(case, projectDir, withMcp = false)
