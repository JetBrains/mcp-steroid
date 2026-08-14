/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule against the shape that actually got through.
 *
 * The first case is the real line, verbatim, from both copies of `AdminEventPaths` at
 * `SemanticRippleSpec.baseCommit`. It is what a rename of `RealmResource.roles()` breaks at runtime
 * — `RESTEASY003645: No public @Path annotated method for ...RealmResource.roles` — while the
 * compiler, the reference search and every predicate of the oracle report a perfect rename.
 */
class RippleNameEscapeRuleTest {

    private val adminEventPathsLine =
        """        URI uri = UriBuilder.fromUri("").path(RealmResource.class, "roles").build();"""

    @Test
    fun `the JAX-RS class-and-name pair is found where the compiler and a reference search see nothing`() {
        val found = RippleNameEscapeRule.lookupsNaming(
            "roles", "tests/utils/.../AdminEventPaths.java", adminEventPathsLine,
        )
        assertEquals(1, found.size) { "$found" }
        assertEquals(LiteralNameLookupKind.CLASS_AND_NAME_PAIR, found.single().kind)
        assertEquals(1, found.single().line)
    }

    @Test
    fun `the pair is recognised whatever call carries it`() {
        // `path` is how the pilot's premise failed; `fromMethod` resolves a method name the same way,
        // and a rule written around one call name would miss the other.
        listOf(
            """URI u = UriBuilder.fromMethod(RealmResource.class, "roles").build();""",
            """Method m = ReflectionUtil.find(org.keycloak.admin.client.resource.RealmResource.class, "roles");""",
        ).forEach { line ->
            val found = RippleNameEscapeRule.lookupsNaming("roles", "X.java", line)
            assertEquals(LiteralNameLookupKind.CLASS_AND_NAME_PAIR, found.single().kind) { line }
        }
    }

    @Test
    fun `reflective method lookups by literal are found`() {
        listOf(
            """Method m = RealmResource.class.getMethod("roles");""",
            """Method m = type.getDeclaredMethod("roles", String.class);""",
        ).forEach { line ->
            val found = RippleNameEscapeRule.lookupsNaming("roles", "X.java", line)
            assertEquals(LiteralNameLookupKind.REFLECTIVE_METHOD_LOOKUP, found.single().kind) { line }
        }
    }

    @Test
    fun `a bare literal of the name is reported as the weakest finding rather than ignored`() {
        val found = RippleNameEscapeRule.lookupsNaming(
            "roles", "X.java", """    private static final String KEY = "roles";""",
        )
        assertEquals(LiteralNameLookupKind.BARE_STRING_LITERAL, found.single().kind)
    }

    @Test
    fun `another declaration's name is not a finding`() {
        val clean = RippleNameEscapeRule.lookupsNaming(
            "roles", "X.java",
            """URI uri = UriBuilder.fromUri("").path(RealmResource.class, "users").build();""",
        )
        assertTrue(clean.isEmpty()) { "$clean" }
    }

    @Test
    fun `a name that merely occurs inside a longer token is not a finding`() {
        // Without exact quoting the rule would disqualify every candidate whose name is a prefix of
        // some other string, and a rule that rejects everything is not applied for long.
        val text = """
            URI uri = UriBuilder.fromUri("").path(RealmResource.class, "rolesById").build();
            String s = "the roles of a user";
            return getRoles();
        """.trimIndent()
        assertTrue(RippleNameEscapeRule.lookupsNaming("roles", "X.java", text).isEmpty()) {
            RippleNameEscapeRule.lookupsNaming("roles", "X.java", text).toString()
        }
    }

    @Test
    fun `every reported finding carries the line it was found on`() {
        val text = "int a = 1;\n$adminEventPathsLine\nint b = 2;"
        val found = RippleNameEscapeRule.lookupsNaming("roles", "AdminEventPaths.java", text)
        assertEquals(2, found.single().line)
        assertTrue(found.single().text.startsWith("URI uri")) { found.single().text }
    }

    @Test
    fun `the offline confirmation commands pin the base commit and cover all three shapes`() {
        val commands = RippleNameEscapeRule.gitGrepCommands("roles")
        assertEquals(3, commands.size)
        assertTrue(commands.all { it.contains(SemanticRippleSpec.baseCommit) }) { "$commands" }
        assertTrue(commands.any { it.contains(".class") }) { "$commands" }
        assertTrue(commands.any { it.contains("get(Declared)?Method") }) { "$commands" }
        assertTrue(commands.all { it.contains("roles") }) { "$commands" }
    }
}
