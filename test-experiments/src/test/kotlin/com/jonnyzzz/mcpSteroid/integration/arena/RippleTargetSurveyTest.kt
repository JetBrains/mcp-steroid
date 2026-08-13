/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RippleTargetSurveyTest {

    private val output = """
        SURVEY_CANDIDATE rename-type|org.keycloak.a.Wide|Wide|312|41|5|4|0
        SURVEY_CANDIDATE rename-type|org.keycloak.a.Narrow|Narrow|8|2|1|5|0
        SURVEY_CANDIDATE pull-up|org.keycloak.a.Deep|handle|140|33|6|3|12
        SURVEY_CANDIDATE rename-type|org.keycloak.a.Unqualified|Unqualified|312|41|5|1|0
        SURVEY_END
    """.trimIndent()

    @Test
    fun `candidates parse with every measured field`() {
        val candidates = parseSurveyCandidates(output)
        assertEquals(4, candidates.size)
        val wide = candidates.first()
        assertEquals("rename-type", wide.kind)
        assertEquals("org.keycloak.a.Wide", wide.ownerFqn)
        assertEquals(312, wide.references)
        assertEquals(41, wide.files)
        assertEquals(5, wide.modules)
        assertEquals(4, wide.sameNameDeclarations)
        assertEquals(0, wide.hierarchyBreadth)
    }

    @Test
    fun `truncated output fails loudly instead of parsing as a short list`() {
        val truncated = output.lines().dropLast(1).joinToString("\n")
        val e = assertThrows(IllegalStateException::class.java) { parseSurveyCandidates(truncated) }
        assertTrue(e.message!!.contains("SURVEY_END")) { "Message must name the missing terminator: ${e.message}" }
    }

    @Test
    fun `wide requires fan-out AND lexical ambiguity`() {
        val byName = parseSurveyCandidates(output).associateBy { it.name }
        assertTrue(byName.getValue("Wide").qualifiesAsWide())
        assertFalse(byName.getValue("Unqualified").qualifiesAsWide()) {
            "A candidate with only 1 same-name declaration is not lexically ambiguous and must not qualify"
        }
        assertFalse(byName.getValue("Narrow").qualifiesAsWide())
    }

    @Test
    fun `narrow requires a small fan-out and still requires ambiguity`() {
        val byName = parseSurveyCandidates(output).associateBy { it.name }
        assertTrue(byName.getValue("Narrow").qualifiesAsNarrow())
        assertFalse(byName.getValue("Wide").qualifiesAsNarrow())
    }

    @Test
    fun `pull-up requires hierarchy breadth on top of a wide fan-out`() {
        val byName = parseSurveyCandidates(output).associateBy { it.name }
        assertTrue(byName.getValue("handle").qualifiesForPullUp())
        assertFalse(byName.getValue("Wide").qualifiesForPullUp()) {
            "A wide candidate with breadth 0 exercises no hierarchy and must not qualify"
        }
    }
}
