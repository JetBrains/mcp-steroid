/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What an arm actually SENDS, read from the harness's own code path.
 *
 * Every assertion here goes through [RippleScenarioBaseTest.promptFor] — the single function
 * `runArm` passes to `ArenaTestRunner.runTest` as its `promptBuilder` — rather than through
 * [buildRipplePrompt] directly. That distinction is the whole defect this class exists for: the
 * prompt-purity contract used to assert about a builder output that `runTest` replaced with a
 * dpaia-shaped brief, so a prompt could be pure, reviewed, and never sent.
 */
class SemanticRipplePromptWiringTest {

    /** The harness itself, for a case. Its `@Test` methods are not discovered from here. */
    private fun harness(rippleCase: RippleCase): RippleScenarioBaseTest =
        object : RippleScenarioBaseTest() {
            override val case: RippleCase get() = rippleCase
        }

    private val projectDir = "/work/keycloak"

    private fun sent(case: RippleCase, withMcp: Boolean) = harness(case).promptFor(projectDir, withMcp)

    @Test
    fun `the harness sends the ripple prompt, not a dpaia brief built around the task section`() {
        RippleCases.all.forEach { case ->
            listOf(true, false).forEach { withMcp ->
                assertEquals(buildRipplePrompt(case, projectDir, withMcp), sent(case, withMcp)) {
                    "${case.instanceId}: the string the harness sends must be the ripple prompt itself"
                }
            }
        }
    }

    @Test
    fun `the sent prompt keeps the only marker the harness reads as a claimed fix`() {
        // `ArenaTestRunner.evaluate` detects this exact string and nothing else; `runTest` refuses to
        // send a prompt without it.
        RippleCases.all.forEach { case ->
            listOf(true, false).forEach { withMcp ->
                assertTrue(sent(case, withMcp).contains(ARENA_FIX_APPLIED_MARKER)) { case.instanceId }
            }
        }
    }

    @Test
    fun `the sent prompt never orders the reactor-wide test run the dpaia brief demands`() {
        RippleCases.all.forEach { case ->
            listOf(true, false).forEach { withMcp ->
                val prompt = sent(case, withMcp)
                assertFalse(prompt.contains("full test suite")) {
                    "${case.instanceId}: Keycloak's reactor cannot be built to completion, so an " +
                        "instruction to run it sends the agent somewhere it can only fail:\n$prompt"
                }
                assertTrue(prompt.contains("does not fit this task's time budget")) { case.instanceId }
                assertTrue(prompt.contains("one at a time")) { case.instanceId }
            }
        }
    }

    @Test
    fun `the environment facts the dpaia brief used to supply survive in the sent prompt`() {
        // Purity is not the only requirement: a prompt that leaves the agent hunting for a JDK
        // measures JDK hunting. These four are what the wrapper was silently providing.
        val mcp = sent(RippleCases.renameMethodWide, withMcp = true)
        val shell = sent(RippleCases.renameMethodWide, withMcp = false)
        listOf(mcp, shell).forEach { prompt ->
            assertTrue(prompt.contains("./mvnw")) { prompt }
            assertTrue(prompt.contains("/usr/lib/jvm/temurin-${SemanticRippleSpec.projectJdkVersion}-jdk-")) { prompt }
            assertTrue(prompt.contains("-pl <module>")) { prompt }
            assertFalse(prompt.contains("-am")) { prompt }
            assertTrue(prompt.contains("ARENA_SUMMARY:")) {
                "The run summary is collected from this marker and would otherwise be lost:\n$prompt"
            }
        }
        assertTrue(mcp.contains("Recommended JAVA_HOME")) {
            "The mcp arm resolves its JDK from the first tool call's output:\n$mcp"
        }
    }

    @Test
    fun `the sent prompt no longer opens on the dpaia brief's false premise`() {
        val prompt = sent(RippleCases.renameMethodWide, withMcp = true)
        assertTrue(prompt.startsWith("You are working on a large multi-module Java project")) { prompt }
        assertFalse(prompt.contains("Java Spring project")) {
            "Keycloak is not a Spring project; the dpaia brief said it was:\n$prompt"
        }
    }

    @Test
    fun `the sent prompt does not hand over the hidden consumer`() {
        // The dpaia brief printed FAIL_TO_PASS and the whole test patch. For this family the patch IS
        // the oracle: it names the destination and asserts the old name is gone.
        RippleCases.all.forEach { case ->
            val prompt = sent(case, withMcp = true)
            assertFalse(prompt.contains(case.hiddenConsumerFqn)) { case.instanceId }
            assertFalse(prompt.contains("diff --git")) { case.instanceId }
        }
    }

    @Test
    fun `the dpaia brief this family used to run under really did contradict its own design`() {
        // Kept as evidence rather than as prose: this is the string the measured rounds sent.
        val legacy = legacyDpaiaWrappedRipplePrompt(RippleCases.renameMethodWide, projectDir, true)
        assertTrue(legacy.contains("Run the full test suite ONCE as the LAST step")) { legacy }
        assertTrue(legacy.contains("Java Spring project")) { legacy }
        assertTrue(legacy.contains(RippleCases.renameMethodWide.hiddenConsumerFqn)) {
            "The wrapper printed the hidden consumer to the agent"
        }
        assertFalse(legacy.contains("does not fit this task's time budget")) {
            "…while dropping every environment paragraph the ripple prompt states"
        }
    }
}
