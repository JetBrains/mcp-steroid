/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRippleCasesTest {

    @Test
    fun `test patch resource is present and non-empty`() {
        assertTrue(SemanticRippleCases.testPatch().isNotBlank())
    }

    @Test
    fun `test patch is purely additive`() {
        val patch = SemanticRippleCases.testPatch()
        val fileSections = patch.lines().count { it.startsWith("diff --git ") }
        val newFileMarkers = patch.lines().count { it.trim() == "--- /dev/null" }
        assertEquals(fileSections, newFileMarkers) {
            "Every file section must create a new file. A patch that modifies an existing file could " +
                "collide with one of the ${SemanticRippleSpec.expectedGoldFiles} files the agent must edit."
        }
        assertFalse(patch.contains("deleted file mode")) { "The patch must not delete anything" }
    }

    @Test
    fun `hidden consumer lives under tests-base test sources`() {
        val paths = extractPatchFilePaths(SemanticRippleCases.testPatch())
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it.startsWith("tests/base/src/test/java/") }) {
            "The consumer belongs in keycloak-tests-base's test sources, not any other module: $paths"
        }
    }

    @Test
    fun `pilot case pins the commit and names the consumer as FAIL_TO_PASS`() {
        val case = SemanticRippleCases.pilotCase()
        assertEquals(SemanticRippleSpec.baseCommit, case.baseCommit)
        assertEquals("maven", case.buildSystem)
        assertTrue(case.isMaven)
        assertEquals(listOf(SemanticRippleCases.hiddenConsumerFqn), case.failToPass)
        assertTrue(case.passToPass.isEmpty()) {
            "Regression evidence here is the compile gate, not a PASS_TO_PASS list"
        }
    }

    @Test
    fun `pilot case problem statement is the purity-checked prompt task section`() {
        val case = SemanticRippleCases.pilotCase()
        assertTrue(case.problemStatement.contains(SemanticRippleSpec.newName))
        assertFalse(case.problemStatement.contains("ClientResource")) {
            "The statement must not name a decoy"
        }
    }

    @Test
    fun `instance id is stable and identifies the track`() {
        assertEquals("ripple__keycloak__realm-roles-rename", SemanticRippleCases.pilotCase().instanceId)
    }
}
