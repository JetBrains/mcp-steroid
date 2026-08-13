/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenameTypeWideCaseTest {

    @Test
    fun `base commit is the pinned family commit`() {
        assertEquals(SemanticRippleSpec.baseCommit, RenameTypeWideSpec.baseCommit) {
            "Every case of the family measures the same tree; a per-case commit would make the cases " +
                "incomparable with each other"
        }
    }

    @Test
    fun `destination name differs from the old one`() {
        assertNotEquals(RenameTypeWideSpec.oldName, RenameTypeWideSpec.newName)
    }

    @Test
    fun `expected counts are the surveyed ones`() {
        assertEquals(198, RenameTypeWideSpec.expectedGoldReferences)
        assertEquals(41, RenameTypeWideSpec.expectedGoldFiles)
        assertEquals(3, RenameTypeWideSpec.expectedDecoyDeclarations)
    }

    @Test
    fun `the target qualifies as wide under the family thresholds`() {
        assertTrue(RenameTypeWideSpec.expectedGoldReferences >= 100)
        assertTrue(RenameTypeWideSpec.expectedGoldFiles >= 20)
        assertTrue(RenameTypeWideSpec.compileGateModules.size >= 4) {
            "Wide means references in at least 3 modules, plus the declaring module"
        }
        assertTrue(RenameTypeWideSpec.expectedDecoyDeclarations >= 3) {
            "Without lexical ambiguity this case measures search cost, not disambiguation"
        }
    }

    @Test
    fun `compile gate names each module once and never asks for also-make`() {
        assertEquals(
            RenameTypeWideSpec.compileGateModules.distinct().size,
            RenameTypeWideSpec.compileGateModules.size,
        )
        assertFalse(RenameTypeWideSpec.compileGateArgs().contains("-am"))
        assertTrue(RenameTypeWideSpec.compileGateArgs().contains("test-compile"))
    }

    @Test
    fun `test patch is purely additive`() {
        val patch = RenameTypeWideCases.testPatch()
        val fileSections = patch.lines().count { it.startsWith("diff --git ") }
        val newFileMarkers = patch.lines().count { it.trim() == "--- /dev/null" }
        assertEquals(fileSections, newFileMarkers) {
            "Every file section must create a new file, or the patch could collide with a file the " +
                "agent must edit"
        }
        assertFalse(patch.contains("deleted file mode"))
    }

    @Test
    fun `hidden consumer lives in test sources of a compile-gate module`() {
        val paths = extractPatchFilePaths(RenameTypeWideCases.testPatch())
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it.contains("/src/test/java/") }) { "$paths" }
    }

    @Test
    fun `prompt names the target and leaks neither the mechanism nor the answer`() {
        val prompts = listOf(
            buildRenameTypeWidePrompt("/work/keycloak", withMcp = true),
            buildRenameTypeWidePrompt("/work/keycloak", withMcp = false),
        )
        prompts.forEach { p ->
            assertTrue(p.contains(RenameTypeWideSpec.targetClassFqn)) { p }
            assertTrue(p.contains(RenameTypeWideSpec.newName)) { p }
            assertTrue(p.contains("ARENA_FIX_APPLIED: yes")) { p }
            assertTrue(p.contains("other types")) {
                "The prompt must forbid renaming same-named types elsewhere:\n$p"
            }
            listOf("steroid_", "mcp-steroid://", "find usages", "findUsages", "Find Usages",
                   "PSI", "IntelliJ", "refactor", "Refactor", "IDE").forEach { token ->
                assertFalse(p.contains(token)) { "Prompt leaks the mechanism via '$token':\n$p" }
            }
            assertFalse(p.contains(".java")) { "The prompt must not enumerate affected files:\n$p" }
            assertFalse(p.contains("${RenameTypeWideSpec.expectedGoldReferences}")) {
                "Stating the reference count tells the agent when to stop searching:\n$p"
            }
            assertFalse(p.contains("-am")) { p }
        }
    }

    @Test
    fun `case pins the commit and names the consumer as FAIL_TO_PASS`() {
        val case = RenameTypeWideCases.case()
        assertEquals(RenameTypeWideSpec.baseCommit, case.baseCommit)
        assertEquals("maven", case.buildSystem)
        assertTrue(case.isMaven)
        assertEquals(listOf(RenameTypeWideCases.hiddenConsumerFqn), case.failToPass)
        assertTrue(case.passToPass.isEmpty())
        assertEquals("ripple__keycloak__rename-type-wide", case.instanceId)
    }
}
