/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The probe brief is the shell brief plus ONE paragraph, and these tests are what says so.
 *
 * A probe measures how far a state carries an agent that knows nothing about that state. Any extra
 * word — which arm produced it, how many calls it took, what was already tried — turns the measured
 * quantity from "readiness of the state" into "readiness of the state plus a hint", and no report can
 * separate the two afterwards. The first test pins the composition, the second pins the silence.
 */
class RippleCheckpointProbePromptTest {
    private val case = RippleCases.renameMethodWide

    @Test
    fun `probe prompt is the shell prompt plus one continuation paragraph`() {
        val probe = buildCheckpointProbePrompt(case, "/work/keycloak")
        val shell = buildRipplePrompt(case, "/work/keycloak", withMcp = false)
        assertEquals(CHECKPOINT_CONTINUATION_PARAGRAPH + "\n\n" + shell, probe)
        assertEquals(1, Regex(Regex.escape(CHECKPOINT_CONTINUATION_PARAGRAPH)).findAll(probe).count())
    }

    @Test
    fun `probe prompt leaks neither the checkpoint nor the source arm`() {
        val probe = buildCheckpointProbePrompt(case, "/work/keycloak").lowercase()
        listOf("checkpoint", "step 1", "steps", "% of", "mcp", "steroid", "intellij", "opus",
               "previous agent", "another agent", "trajectory")
            .forEach { leak -> assertFalse(probe.contains(leak)) { "probe prompt leaks '$leak'" } }
    }
}
