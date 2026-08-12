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
    fun `every module is selected by artifactId, not by a bare name Maven reads as a path`() {
        assertEquals(SemanticRippleSpec.compileGateModules.size, SemanticRippleSpec.compileGateSelectors.size)
        SemanticRippleSpec.compileGateSelectors.forEach { selector ->
            assertTrue(selector.startsWith(":")) {
                "'$selector' has no colon, so Maven looks for a directory of that name and answers " +
                    "'Could not find the selected project in the reactor'"
            }
        }
        assertTrue(SemanticRippleSpec.compileGateSelectors.contains(":keycloak-admin-client-core"))
    }

    @Test
    fun `the gate activates the profile that puts the arquillian testsuite in the reactor`() {
        val args = SemanticRippleSpec.compileGateArgs()
        assertTrue(args.contains("-P") && args.contains(SemanticRippleSpec.reactorProfile)) {
            "integration-arquillian-tests-base is behind the '${SemanticRippleSpec.reactorProfile}' " +
                "profile and is absent from the default reactor: $args"
        }
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
