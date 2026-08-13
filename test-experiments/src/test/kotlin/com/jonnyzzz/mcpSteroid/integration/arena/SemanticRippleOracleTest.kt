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
        val e = assertThrows(IllegalStateException::class.java) { g.checkPilotTripwires() }
        assertTrue(e.message!!.contains("445")) { "Message should state the expected count: ${e.message}" }
    }

    @Test
    fun `tripwires reject a pre-existing new name`() {
        val taken = goldOutput.replace("GOLD_NEWNAME_DECLS 0", "GOLD_NEWNAME_DECLS 5")
        val e = assertThrows(IllegalStateException::class.java) { parseSemanticGold(taken).checkPilotTripwires() }
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

    /**
     * The hidden consumer names the new method before it exists, so its reference resolves only after
     * the rename and cannot be in the gold set. Left in, it reads as one invented reference — which is
     * exactly how a complete, correct rename scored 446 against a gold of 445 in both arms of build
     * 1028521545.
     */
    private val postWithConsumer = perfectPost
        .replace(
            "POST_DECOY org.keycloak.admin.client.resource.ClientResource|343",
            "POST_SITE tests/base/src/test/java/org/keycloak/tests/admin/Contract.java|Contract#newName|1\n" +
                "POST_DECOY org.keycloak.admin.client.resource.ClientResource|343",
        )
        .replace("POST_TOTAL_NEW_REFS 4", "POST_TOTAL_NEW_REFS 5")

    private val consumerFiles =
        setOf("tests/base/src/test/java/org/keycloak/tests/admin/Contract.java")

    @Test
    fun `the consumer's reference to the OLD name is excluded from the gold set too`() {
        // It reaches the old method by reflection as well, so once its imports resolve it lands in the
        // gold capture and inflates the pinned repository counts by exactly the overlay's own size.
        val withConsumer = goldOutput.replace(
            "GOLD_SITE b/B.java|B#three|1",
            "GOLD_SITE b/B.java|B#three|1\n" +
                "GOLD_SITE tests/base/src/test/java/org/keycloak/tests/admin/Contract.java|Contract#old|1",
        )
        assertEquals(5, parseSemanticGold(withConsumer).totalReferences)
        val g = parseSemanticGold(withConsumer, hiddenConsumerFiles = consumerFiles)
        assertEquals(4, g.totalReferences) { "The repository's own count must not move with our overlay" }
        assertEquals(2, g.files)
    }

    @Test
    fun `a hidden-consumer reference is excluded from conservation and precision`() {
        val r = parseSemanticPostcondition(postWithConsumer, gold(), hiddenConsumerFiles = consumerFiles)
        assertTrue(r.p4Conserved) {
            "The consumer's own reference must not count as an invented one: ${r.excludedConsumerReferences}"
        }
        assertEquals(1, r.excludedConsumerReferences)
        assertEquals(1.0, r.precision)
        assertEquals(1.0, r.recall)
        assertTrue(r.allPassed)
    }

    @Test
    fun `without the exclusion the same perfect rename fails conservation`() {
        val r = parseSemanticPostcondition(postWithConsumer, gold())
        assertFalse(r.p4Conserved) {
            "This is the defect the exclusion fixes; if it passes here the test proves nothing"
        }
        assertEquals(0, r.excludedConsumerReferences)
    }

    @Test
    fun `the exclusion is matched by suffix, so absolute container paths are covered`() {
        val absolute = postWithConsumer.replace(
            "POST_SITE tests/base/",
            "POST_SITE /home/agent/project-home/tests/base/",
        )
        val r = parseSemanticPostcondition(absolute, gold(), hiddenConsumerFiles = consumerFiles)
        assertEquals(1, r.excludedConsumerReferences)
        assertTrue(r.p4Conserved)
    }

    @Test
    fun `a real missed site still fails even with a consumer reference present`() {
        val missing = postWithConsumer
            .lines().filterNot { it.startsWith("POST_SITE b/B.java") }.joinToString("\n")
            .replace("POST_TOTAL_NEW_REFS 5", "POST_TOTAL_NEW_REFS 4")
        val r = parseSemanticPostcondition(missing, gold(), hiddenConsumerFiles = consumerFiles)
        assertFalse(r.p2AllSitesConverted)
        assertEquals(listOf(GoldSite("b/B.java", "B#three", 1)), r.missedSites)
        assertEquals(0.75, r.recall)
    }

    @Test
    fun `change-signature arity is a separate predicate and can fail on its own`() {
        val post = """
            POST_NEWNAME_DECLARED true
            POST_OLDNAME_ON_TARGET 0
            POST_SITE a/A.java|A#one|2
            POST_SITE a/A.java|A#two|1
            POST_SITE b/B.java|B#three|1
            POST_DECOY org.keycloak.admin.client.resource.ClientResource|343
            POST_DECOY org.keycloak.admin.client.resource.UserResource|401
            POST_TOTAL_NEW_REFS 4
            POST_ARITY_EXPECTED 2
            POST_ARITY_MATCHING 3
            POST_END
        """.trimIndent()
        val r = parseSemanticPostcondition(
            post, gold(), emptySet(),
            extraPredicates = mapOf("P5_ARITY" to parseArityPredicate(post, expectedArity = 2)),
        )
        assertTrue(r.p2AllSitesConverted) { "Every gold site is present; only the arity is wrong" }
        assertFalse(r.extraPredicates.getValue("P5_ARITY")) {
            "3 of 4 call sites carry the new arity, so the signature change is incomplete"
        }
        assertFalse(r.allPassed)
    }

    @Test
    fun `arity predicate passes when every call site carries the new arity`() {
        val post = """
            POST_NEWNAME_DECLARED true
            POST_OLDNAME_ON_TARGET 0
            POST_SITE a/A.java|A#one|2
            POST_SITE a/A.java|A#two|1
            POST_SITE b/B.java|B#three|1
            POST_DECOY org.keycloak.admin.client.resource.ClientResource|343
            POST_DECOY org.keycloak.admin.client.resource.UserResource|401
            POST_TOTAL_NEW_REFS 4
            POST_ARITY_EXPECTED 2
            POST_ARITY_MATCHING 4
            POST_END
        """.trimIndent()
        assertTrue(parseArityPredicate(post, expectedArity = 2))
    }

    @Test
    fun `the arity predicate refuses to grade a script that measured another arity`() {
        val post = """
            POST_TOTAL_NEW_REFS 4
            POST_ARITY_EXPECTED 2
            POST_ARITY_MATCHING 4
            POST_END
        """.trimIndent()
        val e = assertThrows(IllegalStateException::class.java) {
            parseArityPredicate(post, expectedArity = 3)
        }
        assertTrue(e.message!!.contains("POST_ARITY_EXPECTED") || e.message!!.contains("arity 2")) {
            "The message must say the script and the case registry disagree: ${e.message}"
        }
    }

    // --- Fix round 1: the change-signature decoy reading ---

    /**
     * A change-signature case keys its decoys by declaration and VALUES them by arity, so a no-arg
     * getter's gold value is 0. Under a `postDecoys[key] ?: 0` lookup, a decoy whose declaration the
     * agent reshaped — its old key gone, a new key in its place — read as unchanged, and P3 was inert
     * for the case with the family's strongest lexical ambiguity. Over-reach is a comparison of key
     * SETS, and this is the test that says so.
     */
    private val arityGold = """
        GOLD_TARGET org.keycloak.authorization.model.Resource|getId/0|getId/1
        GOLD_SITE a/A.java|A#one|2
        GOLD_SITE b/B.java|B#three|1
        GOLD_DECOY org.keycloak.other.Thing#getId()|0
        GOLD_DECOY org.keycloak.other.Widget#getId()|0
        GOLD_NEWNAME_DECLS 0
        GOLD_END
    """.trimIndent()

    @Test
    fun `a zero-arity decoy that vanishes fails P3 instead of reading as unchanged`() {
        val post = """
            POST_NEWNAME_DECLARED true
            POST_OLDNAME_ON_TARGET 0
            POST_SITE a/A.java|A#one|2
            POST_SITE b/B.java|B#three|1
            POST_DECOY org.keycloak.other.Thing#getId(boolean)|1
            POST_DECOY org.keycloak.other.Widget#getId()|0
            POST_TOTAL_NEW_REFS 3
            POST_ARITY_EXPECTED 1
            POST_ARITY_MATCHING 3
            POST_END
        """.trimIndent()
        val r = parseSemanticPostcondition(post, parseSemanticGold(arityGold))
        assertFalse(r.p3DecoysUnchanged) {
            "Thing#getId() was given the new parameter; both the retired key and the new one are " +
                "over-reach"
        }
        assertEquals(
            listOf("org.keycloak.other.Thing#getId()", "org.keycloak.other.Thing#getId(boolean)"),
            r.overReachedDecoys,
        )
        assertFalse(r.allPassed)
    }

    @Test
    fun `a solution that updates every implementer passes P3, because implementers are not decoys`() {
        // The capture and post-condition scripts exclude the target's own override family by
        // hierarchy, so `ResourceAdapter#getId` appears in NEITHER reading. Java requires those
        // declarations to take the new parameter and the prompt orders it, so a correct solution
        // moves them — and must not be charged for it.
        val post = """
            POST_NEWNAME_DECLARED true
            POST_OLDNAME_ON_TARGET 0
            POST_SITE a/A.java|A#one|2
            POST_SITE b/B.java|B#three|1
            POST_DECOY org.keycloak.other.Thing#getId()|0
            POST_DECOY org.keycloak.other.Widget#getId()|0
            POST_TOTAL_NEW_REFS 3
            POST_ARITY_EXPECTED 1
            POST_ARITY_MATCHING 3
            POST_END
        """.trimIndent()
        val r = parseSemanticPostcondition(
            post, parseSemanticGold(arityGold),
            extraPredicates = mapOf("P5_ARITY" to parseArityPredicate(post, expectedArity = 1)),
        )
        assertTrue(r.p3DecoysUnchanged) { "${r.overReachedDecoys}" }
        assertTrue(r.allPassed)
    }

    @Test
    fun `an implementer left inside the decoy set would fail a correct solution`() {
        // The negative control for the exclusion: if the scripts DID emit the implementer as a decoy,
        // the correct solution above would be graded as over-reach. If this test ever passes P3, the
        // exclusion has stopped mattering and the one above proves nothing.
        val goldWithImplementer = arityGold.replace(
            "GOLD_DECOY org.keycloak.other.Widget#getId()|0",
            "GOLD_DECOY org.keycloak.other.Widget#getId()|0\n" +
                "GOLD_DECOY org.keycloak.models.cache.infinispan.authorization.ResourceAdapter#getId()|0",
        )
        val post = """
            POST_NEWNAME_DECLARED true
            POST_OLDNAME_ON_TARGET 0
            POST_SITE a/A.java|A#one|2
            POST_SITE b/B.java|B#three|1
            POST_DECOY org.keycloak.other.Thing#getId()|0
            POST_DECOY org.keycloak.other.Widget#getId()|0
            POST_DECOY org.keycloak.models.cache.infinispan.authorization.ResourceAdapter#getId(boolean)|1
            POST_TOTAL_NEW_REFS 3
            POST_ARITY_EXPECTED 1
            POST_ARITY_MATCHING 3
            POST_END
        """.trimIndent()
        val r = parseSemanticPostcondition(post, parseSemanticGold(goldWithImplementer))
        assertFalse(r.p3DecoysUnchanged) {
            "Without the hierarchy exclusion, a correct solution is charged with over-reach"
        }
    }

    @Test
    fun `a same-named declaration that appears from nowhere is over-reach`() {
        val post = """
            POST_NEWNAME_DECLARED true
            POST_OLDNAME_ON_TARGET 0
            POST_SITE a/A.java|A#one|2
            POST_SITE b/B.java|B#three|1
            POST_DECOY org.keycloak.other.Thing#getId()|0
            POST_DECOY org.keycloak.other.Widget#getId()|0
            POST_DECOY org.keycloak.other.Widget#getId(boolean)|1
            POST_TOTAL_NEW_REFS 3
            POST_END
        """.trimIndent()
        val r = parseSemanticPostcondition(post, parseSemanticGold(arityGold))
        assertFalse(r.p3DecoysUnchanged)
        assertEquals(listOf("org.keycloak.other.Widget#getId(boolean)"), r.overReachedDecoys) {
            "An overload added to an unrelated same-named declaration is over-reach"
        }
    }

    // --- Move class ---

    @Test
    fun `a move is complete only when the new FQN resolves and the old one does not`() {
        fun post(new: Boolean, old: Boolean) = """
            POST_NEWNAME_DECLARED $new
            POST_OLDNAME_ON_TARGET ${if (old) 1 else 0}
            POST_NEW_FQN org.keycloak.b.Moved
            POST_OLD_FQN org.keycloak.a.Moved
            POST_NEW_FQN_RESOLVES $new
            POST_OLD_FQN_RESOLVES $old
            POST_TOTAL_NEW_REFS 4
            POST_END
        """.trimIndent()

        assertTrue(parseFqnMovePredicate(post(new = true, old = false)))
        assertFalse(parseFqnMovePredicate(post(new = true, old = true))) {
            "A class left behind at the old FQN is a copy, not a move"
        }
        assertFalse(parseFqnMovePredicate(post(new = false, old = true)))
    }
}
