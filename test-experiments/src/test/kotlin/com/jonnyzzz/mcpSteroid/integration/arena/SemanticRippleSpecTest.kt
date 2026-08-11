/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRippleSpecTest {

    @Test
    fun `base commit is a full pinned sha`() {
        assertTrue(SemanticRippleSpec.baseCommit.matches(Regex("[0-9a-f]{40}"))) {
            "Base commit must be a full 40-character SHA, was '${SemanticRippleSpec.baseCommit}'"
        }
    }

    @Test
    fun `new name differs from old name and is not the already-taken realmRoles`() {
        assertNotEquals(SemanticRippleSpec.oldName, SemanticRippleSpec.newName)
        assertNotEquals("realmRoles", SemanticRippleSpec.newName) {
            "realmRoles is already declared 5 times in Keycloak and must not be the rename target name"
        }
    }

    @Test
    fun `expected counts are the measured ones`() {
        assertEquals(445, SemanticRippleSpec.expectedGoldReferences)
        assertEquals(79, SemanticRippleSpec.expectedGoldFiles)
        assertEquals(16, SemanticRippleSpec.expectedDecoyDeclarations)
    }

    @Test
    fun `compile gate covers the declaring module and every reference module`() {
        assertEquals(7, SemanticRippleSpec.compileGateModules.size)
        assertTrue(SemanticRippleSpec.compileGateModules.contains("keycloak-admin-client-core"))
        assertEquals(
            SemanticRippleSpec.compileGateModules.distinct().size,
            SemanticRippleSpec.compileGateModules.size,
        ) { "compileGateModules must not repeat a module" }
    }

    @Test
    fun `maven invocations never use also-make`() {
        assertFalse(SemanticRippleSpec.compileGateArgs().contains("-am")) {
            "-am walks the upstream graph and OOM-kills the container"
        }
        assertTrue(SemanticRippleSpec.compileGateArgs().contains("test-compile")) {
            "The gate must compile test sources — the 445 references live in test modules"
        }
    }
}
