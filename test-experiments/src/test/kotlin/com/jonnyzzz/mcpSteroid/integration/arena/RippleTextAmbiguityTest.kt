/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The admission metric of a rename case: does a TEXT search HAVE to be wrong here?
 *
 * The family's rename cases were admitted on fan-out and on same-name DECLARATIONS, and both arms
 * still tied. These tests fix the rule that replaces that judgement with a measurement, and they fix
 * it in both directions: a candidate whose textual hits are all target references is rejected, and a
 * malformed reading throws instead of resolving to a comfortable default.
 */
class RippleTextAmbiguityTest {

    private val output = """
        SURVEY_TEXT_AMBIGUITY rename-method|org.keycloak.models.KeycloakContext|setRealm|612|496|74
        SURVEY_TEXT_AMBIGUITY rename-type|org.keycloak.validate.ValidationContext|ValidationContext|221|198|17
        SURVEY_TEXT_AMBIGUITY_END
    """.trimIndent()

    @Test
    fun `a reading parses with all three measured numbers`() {
        val readings = parseTextAmbiguity(output)
        assertEquals(2, readings.size)
        val method = readings.first()
        assertEquals("rename-method", method.kind)
        assertEquals("org.keycloak.models.KeycloakContext#setRealm", method.targetDescription)
        assertEquals(612, method.textualOccurrences)
        assertEquals(496, method.resolvedReferences)
        assertEquals(74, method.foreignSameNameCallSites)
        assertEquals(116, method.textualOverReach)
        assertTrue(method.discriminates)
        assertTrue(method.hasForeignTrap)
        // A type reading is keyed by the FQN alone, the way RenameType.targetDescription is.
        assertEquals("org.keycloak.validate.ValidationContext", readings.last().targetDescription)
    }

    @Test
    fun `a truncated reading fails loudly instead of admitting a target on partial numbers`() {
        val e = assertThrows(IllegalStateException::class.java) {
            parseTextAmbiguity(output.lines().dropLast(1).joinToString("\n"))
        }
        assertTrue(e.message!!.contains("SURVEY_TEXT_AMBIGUITY_END")) {
            "Message must name the missing terminator: ${e.message}"
        }
    }

    @Test
    fun `a malformed line throws rather than parsing a shorter tuple`() {
        val e = assertThrows(IllegalStateException::class.java) {
            parseTextAmbiguity("""
                SURVEY_TEXT_AMBIGUITY rename-method|org.keycloak.a.B|c|10|5
                SURVEY_TEXT_AMBIGUITY_END
            """.trimIndent())
        }
        assertTrue(e.message!!.contains("Malformed")) { e.message!! }
    }

    @Test
    fun `zero resolved references is an index failure, not a measurement`() {
        val e = assertThrows(IllegalStateException::class.java) {
            parseTextAmbiguity("""
                SURVEY_TEXT_AMBIGUITY rename-method|org.keycloak.a.B|c|10|0|0
                SURVEY_TEXT_AMBIGUITY_END
            """.trimIndent())
        }
        assertTrue(e.message!!.contains("0 resolved references")) { e.message!! }
    }

    @Test
    fun `more foreign call sites than textual occurrences cannot be a consistent reading`() {
        val e = assertThrows(IllegalStateException::class.java) {
            parseTextAmbiguity("""
                SURVEY_TEXT_AMBIGUITY rename-method|org.keycloak.a.B|c|10|4|11
                SURVEY_TEXT_AMBIGUITY_END
            """.trimIndent())
        }
        assertTrue(e.message!!.contains("internally inconsistent")) { e.message!! }
    }

    @Test
    fun `a candidate whose textual hits are all target references does not discriminate`() {
        // The shape the tripwire exists for: every occurrence of the name IS a reference, so
        // replacing every occurrence is correct — the arms would be comparing two spellings of the
        // same edit. Equality is rejected too, not only the strictly-smaller case.
        val equal = TextAmbiguity("rename-method", "org.keycloak.a.B", "c", 40, 40, 0)
        assertFalse(equal.discriminates)
        assertFalse(TextAmbiguity("rename-method", "org.keycloak.a.B", "c", 39, 40, 0).discriminates)
        assertTrue(TextAmbiguity("rename-method", "org.keycloak.a.B", "c", 41, 40, 1).discriminates)
    }

    @Test
    fun `the tripwire rejects a non-discriminating pin with a message that says what to do`() {
        val pin = TextAmbiguityPin.Measured(
            reading = TextAmbiguity("rename-method", "org.keycloak.a.B", "c", 40, 40, 0),
            source = "run-fixture",
        )
        val e = assertThrows(IllegalStateException::class.java) { pin.requireAdmissible("ripple__x") }
        assertTrue(e.message!!.contains("ripple__x")) { e.message!! }
        assertTrue(e.message!!.contains("40 textual occurrences")) { e.message!! }
        assertTrue(e.message!!.contains("correct by construction")) { e.message!! }
        assertTrue(e.message!!.contains("run-fixture")) { "The message must name the run: ${e.message}" }
    }

    @Test
    fun `a discriminating pin is admissible`() {
        TextAmbiguityPin.Measured(
            reading = TextAmbiguity("rename-method", "org.keycloak.a.B", "c", 61, 40, 12),
            source = "run-fixture",
        ).requireAdmissible("ripple__x")
    }

    @Test
    fun `an unmeasured pin must state why, and is not silently a pass`() {
        assertThrows(IllegalArgumentException::class.java) { TextAmbiguityPin.Unmeasured("  ") }
        val pin = TextAmbiguityPin.Unmeasured("no survey run yet")
        // It does not throw — a missing measurement is a hole to fill, not a case to void — but it
        // is a distinct value that reporting and the registry can both see.
        pin.requireAdmissible("ripple__x")
        assertTrue(pin.reason.isNotBlank())
    }

    @Test
    fun `a measured pin must name the run it came from`() {
        assertThrows(IllegalArgumentException::class.java) {
            TextAmbiguityPin.Measured(TextAmbiguity("rename-method", "a.B", "c", 2, 1, 1), source = "")
        }
    }

    @Test
    fun `every rename case in the registry carries a measured reading of its own target`() {
        for (case in RippleCases.all) {
            if (case.target.needsTextAmbiguityPin()) {
                // Every rename case has been measured by the text-ambiguity survey phase
                // (run-20260816-185913-ripple-target-survey), so a hole here is now a regression:
                // the whole point of the metric is that no rename case is admitted on argument.
                val pin = case.textAmbiguity
                assertTrue(pin is TextAmbiguityPin.Measured) {
                    "${case.instanceId} renames a name and must carry a MEASURED text-ambiguity " +
                        "pin; found $pin"
                }
                pin as TextAmbiguityPin.Measured
                assertEquals(case.target.targetDescription, pin.reading.targetDescription) {
                    "${case.instanceId} pins a reading of a different target"
                }
                assertEquals(case.expectedGoldReferences, pin.reading.resolvedReferences) {
                    "${case.instanceId}'s reading must resolve the same references the gold pin does"
                }
                assertTrue(pin.reading.hasForeignTrap) {
                    "${case.instanceId} has no foreign same-name call site, so a textual rename " +
                        "has nothing to break here — retarget it (see TODO.md)"
                }
            } else {
                assertEquals(TextAmbiguityPin.NotApplicable, case.textAmbiguity) {
                    "${case.instanceId} changes no name, so the metric does not apply to it"
                }
            }
        }
    }

    @Test
    fun `only the rename kinds need the metric`() {
        assertTrue(RippleCases.renameMethodWideTarget.needsTextAmbiguityPin())
        assertTrue(RippleCases.renameTypeWideTarget.needsTextAmbiguityPin())
        assertFalse(RippleCases.changeSignatureWideTarget.needsTextAmbiguityPin())
        assertFalse(RippleCases.moveClassWideTarget.needsTextAmbiguityPin())
    }

    @Test
    fun `a case built on a non-discriminating measurement cannot be constructed at all`() {
        val e = assertThrows(IllegalStateException::class.java) {
            RippleCases.renameTypeNarrow.copy(
                textAmbiguity = TextAmbiguityPin.Measured(
                    reading = TextAmbiguity(
                        "rename-type", "org.keycloak.tests.utils.KeyUtils", "KeyUtils", 12, 12, 0,
                    ),
                    source = "run-fixture",
                ),
            )
        }
        assertTrue(e.message!!.contains("ripple__keycloak__rename-type-narrow")) { e.message!! }
    }

    @Test
    fun `the script measures every rename case and terminates its own output`() {
        val script = RippleTargetSurveyScripts.textAmbiguity(RippleCases.all.filter {
            it.target.needsTextAmbiguityPin()
        })
        assertTrue(script.contains("SURVEY_TEXT_AMBIGUITY_END"))
        assertTrue(script.contains("UsageSearchContext.IN_CODE")) {
            "The textual count must come from the code word index, not from strings"
        }
        for (case in RippleCases.all.filter { it.target.needsTextAmbiguityPin() }) {
            val owner = when (val t = case.target) {
                is RenameMethod -> t.targetClassFqn
                is RenameType -> t.oldFqn
                else -> error("unreachable")
            }
            assertTrue(script.contains(owner)) { "${case.instanceId} is not measured by the script" }
        }
        // The override family is excluded for the rename-method reading: a correct rename must move
        // those declarations, so their call sites are gold sites and not traps for a text tool.
        assertTrue(script.contains("overrideFamily"))
    }

    @Test
    fun `a script over no rename case fails instead of printing an empty measurement`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            RippleTargetSurveyScripts.textAmbiguity(listOf(RippleCases.moveClassWide))
        }
        assertTrue(e.message!!.contains("No rename case")) { e.message!! }
    }
}
