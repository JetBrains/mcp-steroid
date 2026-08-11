/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRippleOracleTest {

    /** Shape emitted by the capture script: three sites holding 4 references in total. */
    private val goldOutput = """
        GOLD_TARGET org.keycloak.admin.client.resource.RealmResource|roles|realmLevelRoles
        GOLD_SITE a/A.java|A#one|2
        GOLD_SITE a/A.java|A#two|1
        GOLD_SITE b/B.java|B#three|1
        GOLD_DECOY org.keycloak.admin.client.resource.ClientResource|343
        GOLD_DECOY org.keycloak.admin.client.resource.UserResource|401
        GOLD_NEWNAME_DECLS 0
        GOLD_END
    """.trimIndent()

    private fun gold() = parseSemanticGold(goldOutput)

    @Test
    fun `gold parses sites, totals and decoys`() {
        val g = gold()
        assertEquals("org.keycloak.admin.client.resource.RealmResource", g.targetFqn)
        assertEquals("realmLevelRoles", g.newName)
        assertEquals(3, g.sites.size)
        assertEquals(4, g.totalReferences)
        assertEquals(2, g.files)
        assertEquals(343, g.decoyReferences.getValue("org.keycloak.admin.client.resource.ClientResource"))
        assertEquals(0, g.newNameDeclarations)
    }

    @Test
    fun `truncated gold output fails loudly instead of parsing as empty`() {
        val truncated = goldOutput.lines().dropLast(1).joinToString("\n")
        val e = assertThrows(IllegalStateException::class.java) { parseSemanticGold(truncated) }
        assertTrue(e.message!!.contains("GOLD_END")) { "Message should name the missing terminator: ${e.message}" }
    }

    private val perfectPost = """
        POST_NEWNAME_DECLARED true
        POST_OLDNAME_ON_TARGET 0
        POST_SITE a/A.java|A#one|2
        POST_SITE a/A.java|A#two|1
        POST_SITE b/B.java|B#three|1
        POST_DECOY org.keycloak.admin.client.resource.ClientResource|343
        POST_DECOY org.keycloak.admin.client.resource.UserResource|401
        POST_TOTAL_NEW_REFS 4
        POST_END
    """.trimIndent()

    @Test
    fun `a complete rename passes every post-condition`() {
        val r = parseSemanticPostcondition(perfectPost, gold())
        assertTrue(r.p1NoAliasAndNewNameDeclared)
        assertTrue(r.p2AllSitesConverted)
        assertTrue(r.p3DecoysUnchanged)
        assertTrue(r.p4Conserved)
        assertEquals(1.0, r.recall)
        assertEquals(1.0, r.precision)
        assertEquals(1.0, r.f1)
        assertTrue(r.allPassed)
        assertTrue(r.missedSites.isEmpty())
        assertTrue(r.overReachedDecoys.isEmpty())
    }

    @Test
    fun `a compatibility alias fails P1 even when every site converted`() {
        val aliased = perfectPost.replace("POST_OLDNAME_ON_TARGET 0", "POST_OLDNAME_ON_TARGET 1")
        val r = parseSemanticPostcondition(aliased, gold())
        assertFalse(r.p1NoAliasAndNewNameDeclared)
        assertTrue(r.p2AllSitesConverted) { "Sites are converted; only the alias is wrong" }
        assertFalse(r.allPassed)
    }

    @Test
    fun `a partially converted site fails P2 and lowers recall`() {
        val partial = perfectPost
            .replace("POST_SITE a/A.java|A#one|2", "POST_SITE a/A.java|A#one|1")
            .replace("POST_TOTAL_NEW_REFS 4", "POST_TOTAL_NEW_REFS 3")
        val r = parseSemanticPostcondition(partial, gold())
        assertFalse(r.p2AllSitesConverted) {
            "A site that held 2 references and now holds 1 is a partial failure, not a success"
        }
        assertEquals(0.75, r.recall)
        assertEquals(listOf(GoldSite("a/A.java", "A#one", 2)), r.missedSites)
    }

    @Test
    fun `a missing site is reported and fails P2`() {
        val missing = perfectPost
            .lines().filterNot { it.startsWith("POST_SITE b/B.java") }.joinToString("\n")
            .replace("POST_TOTAL_NEW_REFS 4", "POST_TOTAL_NEW_REFS 3")
        val r = parseSemanticPostcondition(missing, gold())
        assertFalse(r.p2AllSitesConverted)
        assertEquals(listOf(GoldSite("b/B.java", "B#three", 1)), r.missedSites)
    }

    @Test
    fun `renaming a decoy fails P3 and names the decoy`() {
        val overReach = perfectPost
            .replace("POST_DECOY org.keycloak.admin.client.resource.ClientResource|343",
                     "POST_DECOY org.keycloak.admin.client.resource.ClientResource|340")
        val r = parseSemanticPostcondition(overReach, gold())
        assertFalse(r.p3DecoysUnchanged)
        assertEquals(listOf("org.keycloak.admin.client.resource.ClientResource"), r.overReachedDecoys)
        assertFalse(r.allPassed)
    }

    @Test
    fun `inventing references beyond the gold set fails P4 and lowers precision`() {
        val invented = perfectPost
            .replace("POST_SITE b/B.java|B#three|1", "POST_SITE b/B.java|B#three|1\nPOST_SITE c/C.java|C#four|2")
            .replace("POST_TOTAL_NEW_REFS 4", "POST_TOTAL_NEW_REFS 6")
        val r = parseSemanticPostcondition(invented, gold())
        assertFalse(r.p4Conserved)
        assertEquals(1.0, r.recall) { "Every gold site is still converted" }
        assertEquals(4.0 / 6.0, r.precision)
    }

    @Test
    fun `an empty gold set is rejected by the tripwires instead of scoring perfectly`() {
        val empty = """
            GOLD_TARGET org.keycloak.admin.client.resource.RealmResource|roles|realmLevelRoles
            GOLD_NEWNAME_DECLS 0
            GOLD_END
        """.trimIndent()
        val g = parseSemanticGold(empty)
        assertEquals(0, g.totalReferences)
        val e = assertThrows(IllegalStateException::class.java) { g.checkTripwires() }
        assertTrue(e.message!!.contains("445")) { "Message should state the expected count: ${e.message}" }
    }

    @Test
    fun `tripwires reject a pre-existing new name`() {
        val taken = goldOutput.replace("GOLD_NEWNAME_DECLS 0", "GOLD_NEWNAME_DECLS 5")
        val e = assertThrows(IllegalStateException::class.java) { parseSemanticGold(taken).checkTripwires() }
        assertTrue(e.message!!.contains(SemanticRippleSpec.newName))
    }

    // --- Fix round 1 ---

    @Test
    fun `a duplicated GOLD_SITE key fails loudly instead of being double-counted`() {
        val duplicated = goldOutput.replace(
            "GOLD_SITE a/A.java|A#one|2",
            "GOLD_SITE a/A.java|A#one|2\nGOLD_SITE a/A.java|A#one|5",
        )
        val e = assertThrows(IllegalStateException::class.java) { parseSemanticGold(duplicated) }
        assertTrue(e.message!!.contains("a/A.java")) { "Message should name the duplicated key: ${e.message}" }
    }

    @Test
    fun `a duplicated POST_DECOY key fails loudly instead of keeping only the last line`() {
        val duplicated = perfectPost.replace(
            "POST_DECOY org.keycloak.admin.client.resource.ClientResource|343",
            "POST_DECOY org.keycloak.admin.client.resource.ClientResource|343\n" +
                "POST_DECOY org.keycloak.admin.client.resource.ClientResource|999",
        )
        val e = assertThrows(IllegalStateException::class.java) { parseSemanticPostcondition(duplicated, gold()) }
        assertTrue(e.message!!.contains("ClientResource")) {
            "Message should name the duplicated decoy owner: ${e.message}"
        }
    }

    @Test
    fun `POST_SITE references exceeding the declared total fail loudly`() {
        val inflatedSites = perfectPost
            .replace("POST_SITE a/A.java|A#one|2", "POST_SITE a/A.java|A#one|20")
        val e = assertThrows(IllegalStateException::class.java) {
            parseSemanticPostcondition(inflatedSites, gold())
        }
        assertTrue(e.message!!.contains("POST_TOTAL_NEW_REFS")) {
            "Message should name the exceeded declared total: ${e.message}"
        }
    }

    @Test
    fun `a missing post-condition field names itself instead of throwing NoSuchElementException`() {
        val missingField = perfectPost.lines()
            .filterNot { it.startsWith("POST_TOTAL_NEW_REFS") }
            .joinToString("\n")
        val e = assertThrows(IllegalStateException::class.java) {
            parseSemanticPostcondition(missingField, gold())
        }
        assertTrue(e.message!!.contains("POST_TOTAL_NEW_REFS")) {
            "Message should name the missing field: ${e.message}"
        }
    }

    @Test
    fun `a decoy whose count increased is also reported as over-reach`() {
        val increased = perfectPost.replace(
            "POST_DECOY org.keycloak.admin.client.resource.ClientResource|343",
            "POST_DECOY org.keycloak.admin.client.resource.ClientResource|400",
        )
        val r = parseSemanticPostcondition(increased, gold())
        assertFalse(r.p3DecoysUnchanged)
        assertEquals(listOf("org.keycloak.admin.client.resource.ClientResource"), r.overReachedDecoys)
        assertFalse(r.allPassed)
    }
}
