/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRipplePromptContractTest {

    private val mcpPrompt = buildSemanticRipplePrompt("/work/keycloak", withMcp = true)
    private val nonePrompt = buildSemanticRipplePrompt("/work/keycloak", withMcp = false)
    private val both = listOf(mcpPrompt, nonePrompt)

    @Test
    fun `both arms name the declaration exactly`() {
        both.forEach { p ->
            assertTrue(p.contains("org.keycloak.admin.client.resource.RealmResource")) { p }
            assertTrue(p.contains("roles()")) { p }
            assertTrue(p.contains("realmLevelRoles")) { p }
        }
    }

    @Test
    fun `both arms forbid renaming same-named methods of other types`() {
        both.forEach { p ->
            assertTrue(p.contains("other types")) {
                "The prompt must state that same-named methods on other types keep their name:\n$p"
            }
        }
    }

    @Test
    fun `both arms carry the harness success marker`() {
        both.forEach { p -> assertTrue(p.contains("ARENA_FIX_APPLIED: yes")) { p } }
    }

    @Test
    fun `neither arm reveals the mechanism`() {
        val forbidden = listOf(
            "steroid_", "mcp-steroid://", "find usages", "findUsages", "Find Usages",
            "PSI", "IntelliJ", "refactor", "Refactor", "IDE",
        )
        both.forEach { p ->
            forbidden.forEach { token ->
                assertFalse(p.contains(token)) {
                    "Prompt leaks the mechanism via '$token'; the agent must discover it:\n$p"
                }
            }
        }
    }

    @Test
    fun `neither arm hands over the answer`() {
        both.forEach { p ->
            assertFalse(p.contains("ClientResource")) {
                "Naming a decoy tells the agent which matches to skip:\n$p"
            }
            assertFalse(p.contains("445")) {
                "Stating the reference count tells the agent when to stop searching:\n$p"
            }
            assertFalse(p.contains(".java")) {
                "The prompt must not enumerate affected files:\n$p"
            }
        }
    }

    @Test
    fun `both arms state the behaviour-preservation requirement`() {
        both.forEach { p ->
            assertTrue(p.contains("behaviour") || p.contains("behavior")) { p }
            assertTrue(p.contains("HTTP")) {
                "The prompt must say the HTTP contract stays unchanged:\n$p"
            }
        }
    }

    @Test
    fun `no maven invocation suggests also-make`() {
        both.forEach { p -> assertFalse(p.contains("-am")) { p } }
    }
}
