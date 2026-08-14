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
    fun `evaluated pull-up destinations parse, including the ones below the threshold`() {
        val supers = parsePullUpSuperTypes("""
            SURVEY_PULLUP_SUPER org.keycloak.models.UserModel|31
            SURVEY_PULLUP_SUPER org.keycloak.a.Narrow|2
            SURVEY_CANDIDATE pull-up|org.keycloak.a.Deep|handle|140|33|6|3|12
            SURVEY_END
        """.trimIndent())
        assertEquals(2, supers.size)
        assertEquals(31, supers.first().breadth)
        // The sub-threshold destination must survive parsing: it is the only near-miss evidence there
        // is, since the script emits no candidate for it.
        assertEquals(2, supers.last().breadth)
        assertTrue(supers.last().breadth < MIN_PULL_UP_BREADTH)
    }

    @Test
    fun `the pull-up breadth threshold is the one the script gates on`() {
        // The script interpolates MIN_PULL_UP_BREADTH into its supertype pre-gate. If the two ever
        // diverged, the survey would silently omit candidates that qualify.
        assertTrue(RippleTargetSurveyScripts.pullUp().contains("breadth >= $MIN_PULL_UP_BREADTH"))
    }

    @Test
    fun `the cheap-kinds script does not carry the pull-up query`() {
        // Sharing one script is what killed the IDE mid-run; the split is the fix and must stay.
        assertFalse(RippleTargetSurveyScripts.survey().contains("pull-up"))
        assertFalse(RippleTargetSurveyScripts.survey().contains("ClassInheritorsSearch"))
    }

    @Test
    fun `decoy verifications parse the measured count the pins must equal`() {
        val verified = parseDecoyVerifications("""
            DECOY_VERIFY org.keycloak.authorization.model.Resource#getId|1022|1018
            DECOY_EXCLUDED org.keycloak.models.cache.infinispan.authorization.ResourceAdapter#getId()
            DECOY_VERIFY org.keycloak.userprofile.Attributes#contains|20|18
            DECOY_VERIFY_END
        """.trimIndent())
        assertEquals(2, verified.size)
        assertEquals("org.keycloak.authorization.model.Resource#getId", verified.first().targetDescription)
        assertEquals(1022, verified.first().sameSimpleName)
        assertEquals(1018, verified.first().decoys)
        assertEquals(18, verified.last().decoys)
    }

    @Test
    fun `a truncated decoy verification fails loudly rather than confirming a pin`() {
        val e = assertThrows(IllegalStateException::class.java) {
            parseDecoyVerifications("DECOY_VERIFY org.keycloak.userprofile.Attributes#contains|20|18")
        }
        assertTrue(e.message!!.contains("DECOY_VERIFY_END")) { "Message must name the terminator: ${e.message}" }
    }

    @Test
    fun `the cheap-kinds script surveys the rename-method kind`() {
        // The pilot predates the survey, so its kind was never surveyed and its target was never
        // measured against the family's own criteria — which is how an ill-posed rename got pinned.
        val script = RippleTargetSurveyScripts.survey()
        assertTrue(script.contains("\"rename-method\"")) { script }
        assertTrue(script.contains("SURVEY_STRING_LITERAL_NAMES")) {
            "A rename-method candidate must be measured against string literals naming it: $script"
        }
        assertTrue(script.contains("jakarta.ws.rs")) {
            "A JAX-RS resource method is addressable by name and must not be offered as a " +
                "rename-method candidate: $script"
        }
    }

    @Test
    fun `the script prints its extra evidence from the same fan-out floor the verdict uses`() {
        assertTrue(RippleTargetSurveyScripts.survey().contains("refs.size >= $MIN_WIDE_REFERENCES")) {
            "A gate below the wide floor would withhold the module list and the literal count from " +
                "candidates that qualify"
        }
    }

    @Test
    fun `the modules a compile gate would need are parsed off the survey output`() {
        val modules = parseCandidateModules("""
            SURVEY_MODULE_NAMES org.keycloak.a.Wide|handle|keycloak-services, keycloak-model-jpa
            SURVEY_CANDIDATE rename-method|org.keycloak.a.Wide|handle|312|41|5|4|0
            SURVEY_END
        """.trimIndent())
        assertEquals(1, modules.size)
        assertEquals(listOf("keycloak-services", "keycloak-model-jpa"), modules.single().modules)
        assertEquals("handle", modules.single().name)
    }

    @Test
    fun `the string-literal read-back is parsed, and a non-zero count is what disqualifies a target`() {
        val literals = parseLiteralNameOccurrences("""
            SURVEY_STRING_LITERAL_NAMES org.keycloak.admin.client.resource.RealmResource|roles|2
            SURVEY_STRING_LITERAL_NAMES org.keycloak.a.Wide|handle|0
            SURVEY_END
        """.trimIndent())
        assertEquals(2, literals.size)
        assertEquals(2, literals.first().occurrences)
        assertEquals(0, literals.last().occurrences)
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
