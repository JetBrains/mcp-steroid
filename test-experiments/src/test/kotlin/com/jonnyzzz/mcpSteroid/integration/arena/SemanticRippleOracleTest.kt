/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
        assertTrue(e.message!!.contains("${RippleCases.renameMethodWide.expectedGoldReferences}")) {
            "Message should state the expected count: ${e.message}"
        }
    }

    /**
     * The defect the pilot was retargeted for. `path(RealmResource.class, "roles")` in two
     * `AdminEventPaths` files names the method as a STRING: no compiler error, no reference-search
     * hit, and an arm graded P1–P4 true at recall 1.0 and precision 1.0 over runtime breakage. The
     * capture now reports every file holding such a literal and the tripwire refuses to grade.
     */
    @Test
    fun `a name spelled by a string literal in the repository aborts the case before it is graded`() {
        val withLiteral = goldOutput.replace(
            "GOLD_NEWNAME_DECLS 0",
            "GOLD_STRING_LITERAL_NAME /work/keycloak/tests/utils/src/main/java/org/keycloak/tests/utils/admin/AdminEventPaths.java|1\n" +
                "GOLD_NEWNAME_DECLS 0",
        )
        val g = parseSemanticGold(withLiteral)
        assertEquals(1, g.literalNameSites.size)
        val e = assertThrows(IllegalStateException::class.java) { g.checkPilotTripwires() }
        assertTrue(e.message!!.contains("string literal")) { "${e.message}" }
        assertTrue(e.message!!.contains("AdminEventPaths")) { "${e.message}" }
    }

    @Test
    fun `the hidden consumer's own reflective naming is not held against the case`() {
        // The consumer calls getMethod("<old name>") on purpose — that is how it proves the old name
        // is gone. Charging it would abort every arm of every rename-method case.
        val consumer = "tests/base/src/test/java/org/keycloak/tests/admin/RenameContractTest.java"
        val withLiteral = goldOutput.replace(
            "GOLD_NEWNAME_DECLS 0",
            "GOLD_STRING_LITERAL_NAME /work/keycloak/$consumer|2\nGOLD_NEWNAME_DECLS 0",
        )
        val g = parseSemanticGold(withLiteral, hiddenConsumerFiles = setOf(consumer))
        assertTrue(g.literalNameSites.isEmpty()) { "${g.literalNameSites}" }
    }

    @Test
    fun `a capture that reports no literal at all leaves the tripwire silent`() {
        // Every kind shares one tripwire, and only rename-method measures literals today. An absent
        // reading must not read as a violation, or the other five cases would stop grading.
        assertTrue(gold().literalNameSites.isEmpty())
    }

    /**
     * A failed predicate must name the keys behind it. Build 1031008889 printed `missed sites: 1` and
     * nothing else, and with the round stopped there was no way to tell a residual key artifact from
     * a genuine miss — the ambiguity that has now cost three separate readings.
     */
    @Test
    fun `a failed P2 prints the missed site keys, not just how many there were`() {
        val postWithOneSiteLeftBehind = """
            POST_NEWNAME_DECLARED true
            POST_OLDNAME_ON_TARGET 0
            POST_SITE a/A.java|A#one|2
            POST_SITE a/A.java|A#two|1
            POST_DECOY org.keycloak.admin.client.resource.ClientResource|343
            POST_DECOY org.keycloak.admin.client.resource.UserResource|401
            POST_TOTAL_NEW_REFS 3
            POST_END
        """.trimIndent()
        val grade = parseSemanticPostcondition(postWithOneSiteLeftBehind, gold())
        assertFalse(grade.p2AllSitesConverted)
        val lines = rippleFailedPredicateDetail(grade)
        assertTrue(lines.any { it.contains("b/B.java|B#three|1") }) { "$lines" }
    }

    @Test
    fun `the printed key list is capped and says how many it withheld`() {
        val many = (1..40).map { GoldSite("a/F$it.java", "a.F$it#m", 1) }
        val grade = SemanticPostconditionResult(
            p1NoAliasAndNewNameDeclared = true,
            p2AllSitesConverted = false,
            p3DecoysUnchanged = false,
            p4Conserved = false,
            recall = 0.0, precision = 0.0, f1 = 0.0,
            missedSites = many,
            overReachedDecoys = (1..40).map { "a.D$it#m()" },
        )
        val lines = rippleFailedPredicateDetail(grade, limit = 5)
        assertTrue(lines.any { it.contains("... 35 more not printed") }) { "$lines" }
        assertEquals(2, lines.count { it.contains("more not printed") }) { "$lines" }
        assertTrue(lines.any { it.contains("a.D1#m()") }) { "$lines" }
    }

    @Test
    fun `nothing is printed when both predicates hold`() {
        val clean = SemanticPostconditionResult(
            p1NoAliasAndNewNameDeclared = true,
            p2AllSitesConverted = true,
            p3DecoysUnchanged = true,
            p4Conserved = true,
            recall = 1.0, precision = 1.0, f1 = 1.0,
            missedSites = emptyList(),
            overReachedDecoys = emptyList(),
        )
        assertTrue(rippleFailedPredicateDetail(clean).isEmpty())
    }

    /**
     * A failed `P7_RECEIVER` must arrive with its owners, for the reason the missed-site keys do: a
     * bare `false` cannot be told from an oracle artifact, and the first failure of the series is the
     * one that has to be diagnosable from the build log alone.
     */
    @Test
    fun `the rename predicates print the owners and the declarations behind them`() {
        val post = """
            POST_RECEIVER_CHECKED 61
            POST_RECEIVER_FOREIGN 2
            POST_RECEIVER_FOREIGN_SITE org.keycloak.other.Session#setRealm
            POST_RECEIVER_UNQUALIFIED 3
            POST_RECEIVER_UNRESOLVED 0
            POST_SHIM_DECLS 1
            POST_SHIM_DECL org.keycloak.models.KeycloakContext#setRealm
            POST_END
        """.trimIndent()
        val lines = rippleStructuralPredicateDetail(post)
        val counts = lines.single { it.contains("receivers:") }
        assertTrue(counts.contains("61 checked"), counts)
        assertTrue(counts.contains("2 foreign"), counts)
        assertTrue(counts.contains("3 anonymous or local"), counts)
        assertTrue(lines.any { it.contains("org.keycloak.other.Session#setRealm") }) { "$lines" }
        assertTrue(lines.any { it.contains("org.keycloak.models.KeycloakContext#setRealm") }) { "$lines" }
    }

    /**
     * The counts are printed even when both predicates hold — a P7 that passed over three checked
     * references is a different fact from one that passed over sixty — while a kind that emits no
     * receiver reading at all (a move, a signature change) prints nothing.
     */
    @Test
    fun `a clean rename reading prints its counts and a non-rename kind prints nothing`() {
        val clean = """
            POST_RECEIVER_CHECKED 3
            POST_RECEIVER_FOREIGN 0
            POST_RECEIVER_UNQUALIFIED 0
            POST_RECEIVER_UNRESOLVED 0
            POST_SHIM_DECLS 0
            POST_END
        """.trimIndent()
        val lines = rippleStructuralPredicateDetail(clean)
        assertEquals(1, lines.size) { "$lines" }
        assertTrue(lines.single().contains("3 checked"), lines.single())
        assertTrue(rippleStructuralPredicateDetail(perfectMovePost).isEmpty())
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

    // --- Fix round 2: conservation across a move, and the type-level gold key ---

    private val moveTarget = RippleCases.moveClassNarrowTarget

    private val moveOldDir = "/work/keycloak/model/infinispan/src/main/java/org/keycloak/models/cache/infinispan"

    /**
     * The move-class NARROW case, modelled on its measurement: the 9 gold references over 3 files and
     * the 10-against-9 post total are measured, the import-versus-usage SPLIT inside them (2 imports, 7
     * usages) is a reconstruction — the pre-fix instrument could not tell an import from any other
     * file-level reference, so no measurement of that split exists. It is chosen to be consistent with
     * the case: `RealmCacheSession` sits in the moved class's OWN package, so at the base commit it names
     * the class with no import, and it is the file that must gain one.
     */
    private val moveGold = """
        GOLD_TARGET ${moveTarget.oldFqn}|${moveTarget.simpleName}|${moveTarget.newPackage}
        GOLD_SITE $moveOldDir/RealmCacheSession.java|org.keycloak.models.cache.infinispan.RealmCacheSession#getClientAdapter|4
        GOLD_SITE $moveOldDir/stream/ClientListPredicate.java|<import>|1
        GOLD_SITE $moveOldDir/stream/ClientListPredicate.java|org.keycloak.models.cache.infinispan.stream.ClientListPredicate#test|2
        GOLD_SITE $moveOldDir/events/ClientUpdatedEvent.java|<import>|1
        GOLD_SITE $moveOldDir/events/ClientUpdatedEvent.java|org.keycloak.models.cache.infinispan.events.ClientUpdatedEvent#getClient|1
        GOLD_DECOY org.keycloak.models.cache.infinispan.entities.ClientAdapter|3
        GOLD_DECOY org.keycloak.storage.client.ClientAdapter|7
        GOLD_DECOY org.keycloak.models.map.client.ClientAdapter|1
        GOLD_NEWNAME_DECLS 0
        GOLD_END
    """.trimIndent()

    /**
     * A PERFECT move of the same target, as both arms of the smoke round performed it: recall 1.0000,
     * P1/P2/P3 true, the new FQN resolving and the old one not, compile gate PASS, hidden consumer green
     * — and 10 post references against a gold of 9, at precision 0.9000, because `RealmCacheSession` sat
     * in the moved class's own package and named it with NO import, and after the move it must add one.
     * The single extra reference is that newly required import statement.
     */
    private val perfectMovePost = """
        POST_NEWNAME_DECLARED true
        POST_OLDNAME_ON_TARGET 0
        POST_NEW_FQN ${moveTarget.newFqn}
        POST_OLD_FQN ${moveTarget.oldFqn}
        POST_NEW_FQN_RESOLVES true
        POST_OLD_FQN_RESOLVES false
        POST_SITE $moveOldDir/RealmCacheSession.java|<import>|1
        POST_SITE $moveOldDir/RealmCacheSession.java|org.keycloak.models.cache.infinispan.RealmCacheSession#getClientAdapter|4
        POST_SITE $moveOldDir/stream/ClientListPredicate.java|<import>|1
        POST_SITE $moveOldDir/stream/ClientListPredicate.java|org.keycloak.models.cache.infinispan.stream.ClientListPredicate#test|2
        POST_SITE $moveOldDir/events/ClientUpdatedEvent.java|<import>|1
        POST_SITE $moveOldDir/events/ClientUpdatedEvent.java|org.keycloak.models.cache.infinispan.events.ClientUpdatedEvent#getClient|1
        POST_DECOY org.keycloak.models.cache.infinispan.entities.ClientAdapter|3
        POST_DECOY org.keycloak.storage.client.ClientAdapter|7
        POST_DECOY org.keycloak.models.map.client.ClientAdapter|1
        POST_TOTAL_NEW_REFS 10
        POST_END
    """.trimIndent()

    @Test
    fun `a perfect move conserves references once imports are excluded from both readings`() {
        val gold = parseSemanticGold(moveGold)
        assertEquals(9, gold.totalReferences) { "The raw reading is what the pinned tripwire counts" }
        assertEquals(3, gold.files) { "And the pinned file count is the raw reading too" }
        assertEquals(2, gold.importReferences)
        assertEquals(7, gold.countedReferences) { "The graded reading is usages only" }

        val r = parseSemanticPostcondition(
            perfectMovePost, gold,
            extraPredicates = moveTarget.extraPredicates(perfectMovePost),
            expectedPostKey = moveTarget::expectedPostKey,
        )
        assertTrue(r.p4Conserved) {
            "A correct move must add an import wherever the class was named without one; 10 post " +
                "references against a gold of 9 is that newly required import, not an invented usage"
        }
        assertEquals(3, r.excludedImportReferences)
        assertEquals(1.0, r.recall)
        assertEquals(1.0, r.precision)
        assertEquals(1.0, r.f1)
        assertTrue(r.p2AllSitesConverted) { "missed: ${r.missedSites}" }
        assertTrue(r.extraPredicates.getValue("P1_MOVED"))
        assertTrue(r.allPassed) {
            "This is the run the smoke round scored SUCCESS: false on P4 alone: ${r.missedSites}"
        }
    }

    @Test
    fun `without the import exclusion the same perfect move fails conservation at 9 of 10`() {
        // The negative control, in the numbers the round printed for move-class narrow: recall 1.0000,
        // precision 0.9000, P4 the only failing predicate. The oracle as it was is reproduced by
        // relabelling every import site to the file bucket it used to land in, on BOTH readings — which
        // is exactly what an oracle that cannot see imports does.
        val gold = parseSemanticGold(moveGold.replace("|<import>|", "|<file>|"))
        assertEquals(9, gold.countedReferences)
        val r = parseSemanticPostcondition(perfectMovePost.replace("|<import>|", "|<file>|"), gold)
        assertFalse(r.p4Conserved) {
            "If this passes, the test above proves nothing: conservation was never the failing predicate"
        }
        assertEquals(1.0, r.recall) { "Nothing was missed — only the total grew" }
        assertEquals(0.9, r.precision, 1e-9)
        assertFalse(r.allPassed)
    }

    @Test
    fun `an invented usage still fails conservation with the import exclusion in place`() {
        // The exclusion must not become a hole: a reference the agent created where it did not belong is
        // not an import, so it still shows.
        val invented = perfectMovePost
            .replace(
                "POST_TOTAL_NEW_REFS 10",
                "POST_SITE $moveOldDir/Invented.java|org.keycloak.models.cache.infinispan.Invented#x|3\n" +
                    "POST_TOTAL_NEW_REFS 13",
            )
        val r = parseSemanticPostcondition(
            invented, parseSemanticGold(moveGold),
            expectedPostKey = moveTarget::expectedPostKey,
        )
        assertFalse(r.p4Conserved)
        assertEquals(7.0 / 10.0, r.precision, 1e-9)
        assertEquals(1.0, r.recall) { "Every gold usage is still converted" }
    }

    @Test
    fun `the move's legitimate import growth is reported, never asserted`() {
        val r = parseSemanticPostcondition(
            perfectMovePost, parseSemanticGold(moveGold),
            expectedPostKey = moveTarget::expectedPostKey,
            importCountIsInvariant = moveTarget.importCountIsInvariant,
        )
        assertEquals(1, r.importReferenceDelta) {
            "One import more than the gold held: the file that named the class from its own old package"
        }
        assertNull(r.p6ImportCountUnchanged) {
            "A move MUST add imports, so asserting the count would fail every correct run"
        }
        assertTrue(r.allPassed)
    }

    /**
     * The hole the import exclusion would leave open if nothing consumed the delta: an agent that sprays
     * `import <new name>;` into files that never use it pays no precision and no conservation — the
     * references are excluded — and an unused import compiles, so the scoped gate cannot see it either.
     * Before the exclusion those references inflated the post total and broke P4. P6 is what replaces
     * that accidental coverage, for the kinds whose correct runs cannot move the count.
     */
    private val postWithSprayedImports = perfectPost
        .replace(
            "POST_TOTAL_NEW_REFS 4",
            "POST_SITE x/X.java|<import>|1\n" +
                "POST_SITE y/Y.java|<import>|1\n" +
                "POST_SITE z/Z.java|<import>|1\n" +
                "POST_TOTAL_NEW_REFS 7",
        )

    @Test
    fun `spraying unnecessary imports is over-reach where the import count cannot legitimately move`() {
        val r = parseSemanticPostcondition(
            postWithSprayedImports, gold(),
            importCountIsInvariant = RippleCases.renameMethodWideTarget.importCountIsInvariant,
        )
        // The point of the test: everything else still reads as a perfect rename, which is exactly why
        // the delta had to be consumed by something.
        assertTrue(r.p4Conserved)
        assertEquals(1.0, r.precision)
        assertEquals(1.0, r.recall)
        assertEquals(3, r.importReferenceDelta)
        assertEquals(false, r.p6ImportCountUnchanged) {
            "Only an import static can reference a method, and a rename neither creates nor destroys one"
        }
        assertFalse(r.allPassed) { "A sprayed import must cost the run its SUCCESS" }
    }

    @Test
    fun `a static import present in both readings leaves P6 true`() {
        val goldWithStaticImport = goldOutput.replace(
            "GOLD_SITE b/B.java|B#three|1",
            "GOLD_SITE b/B.java|B#three|1\nGOLD_SITE b/B.java|<import>|1",
        )
        val postWithStaticImport = perfectPost.replace(
            "POST_SITE b/B.java|B#three|1",
            "POST_SITE b/B.java|B#three|1\nPOST_SITE b/B.java|<import>|1",
        ).replace("POST_TOTAL_NEW_REFS 4", "POST_TOTAL_NEW_REFS 5")
        val g = parseSemanticGold(goldWithStaticImport)
        assertEquals(5, g.totalReferences)
        assertEquals(4, g.countedReferences) { "The static import cancels between the two readings" }
        val r = parseSemanticPostcondition(
            postWithStaticImport, g,
            importCountIsInvariant = RippleCases.renameMethodWideTarget.importCountIsInvariant,
        )
        assertEquals(0, r.importReferenceDelta)
        assertEquals(true, r.p6ImportCountUnchanged)
        assertEquals(1.0, r.recall)
        assertEquals(1.0, r.precision)
        assertTrue(r.allPassed)
    }

    private val renameTypeTarget = RippleCases.renameTypeWideTarget

    private val renameTypeDir = "/work/keycloak/server-spi/src/main/java/org/keycloak/validate"

    /**
     * The rename-type WIDE case, modelled on its measurement: the 198 gold references and the FOUR keys
     * that name the target's own file and the target itself are measured — those four are what went
     * missing when the gold key was compared unmapped, giving both arms exactly 194 of 198. The
     * DISTRIBUTION of the other 194 over two files is modelled, not measured: the case spans 41 files,
     * and reproducing all of them would add no coverage to a key comparison. One of the four sits in a
     * nested class, so the mapping is exercised on `Old.Nested#member` as well as on `Old` and
     * `Old#member`.
     */
    private val renameTypeGold = """
        GOLD_TARGET ${renameTypeTarget.oldFqn}|ValidationContext|ValidationRunContext
        GOLD_SITE $renameTypeDir/ValidationContext.java|${renameTypeTarget.oldFqn}|1
        GOLD_SITE $renameTypeDir/ValidationContext.java|${renameTypeTarget.oldFqn}#getEvent|1
        GOLD_SITE $renameTypeDir/ValidationContext.java|${renameTypeTarget.oldFqn}#getAttributes|1
        GOLD_SITE $renameTypeDir/ValidationContext.java|${renameTypeTarget.oldFqn}.Builder#build|1
        GOLD_SITE $renameTypeDir/Validators.java|org.keycloak.validate.Validators#validate|190
        GOLD_SITE $renameTypeDir/ValidatorConfig.java|org.keycloak.validate.ValidatorConfig#of|4
        GOLD_DECOY org.keycloak.services.validation.ValidationContext|11
        GOLD_DECOY org.keycloak.userprofile.ValidationContext|2
        GOLD_DECOY org.keycloak.authorization.policy.evaluation.ValidationContext|5
        GOLD_NEWNAME_DECLS 0
        GOLD_END
    """.trimIndent()

    /**
     * A PERFECT rename of the same target: every one of the 198 references converted, the four
     * self-references now living in `ValidationRunContext.java` under the renamed type — which is both
     * halves of the gold key changing at once.
     */
    private val perfectRenameTypePost = """
        POST_NEWNAME_DECLARED true
        POST_OLDNAME_ON_TARGET 0
        POST_SITE $renameTypeDir/ValidationRunContext.java|${renameTypeTarget.newFqn}|1
        POST_SITE $renameTypeDir/ValidationRunContext.java|${renameTypeTarget.newFqn}#getEvent|1
        POST_SITE $renameTypeDir/ValidationRunContext.java|${renameTypeTarget.newFqn}#getAttributes|1
        POST_SITE $renameTypeDir/ValidationRunContext.java|${renameTypeTarget.newFqn}.Builder#build|1
        POST_SITE $renameTypeDir/Validators.java|org.keycloak.validate.Validators#validate|190
        POST_SITE $renameTypeDir/ValidatorConfig.java|org.keycloak.validate.ValidatorConfig#of|4
        POST_DECOY org.keycloak.services.validation.ValidationContext|11
        POST_DECOY org.keycloak.userprofile.ValidationContext|2
        POST_DECOY org.keycloak.authorization.policy.evaluation.ValidationContext|5
        POST_TOTAL_NEW_REFS 198
        POST_END
    """.trimIndent()

    @Test
    fun `a perfect type rename scores recall 1 once the gold key follows the rename`() {
        val gold = parseSemanticGold(renameTypeGold)
        assertEquals(198, gold.totalReferences)
        val r = parseSemanticPostcondition(
            perfectRenameTypePost, gold,
            expectedPostKey = renameTypeTarget::expectedPostKey,
        )
        assertEquals(1.0, r.recall) {
            "The four self-references moved with the type; they are converted, not missed: ${r.missedSites}"
        }
        assertEquals(1.0, r.precision)
        assertTrue(r.p2AllSitesConverted) { "missed: ${r.missedSites}" }
        assertTrue(r.p4Conserved)
        assertTrue(r.allPassed)
    }

    @Test
    fun `without the mapping the same perfect rename scores 194 of 198 in both arms`() {
        // The negative control, in the numbers the round printed: recall AND precision both exactly
        // 0.9798, P2 false, no over-reach — identical in both arms because it was the oracle's key, not
        // the agents' work.
        val r = parseSemanticPostcondition(perfectRenameTypePost, parseSemanticGold(renameTypeGold))
        assertEquals(194.0 / 198.0, r.recall, 1e-9)
        assertEquals(194.0 / 198.0, r.precision, 1e-9)
        assertEquals(0.9798, r.recall, 1e-4) { "The printed figure was 0.9798" }
        assertFalse(r.p2AllSitesConverted)
        assertEquals(4, r.missedSites.size) { "The four gold keys inside the renamed file: ${r.missedSites}" }
        assertTrue(r.overReachedDecoys.isEmpty()) { "No decoy moved; only the key was wrong" }
    }

    @Test
    fun `a type rename that really misses a site still fails P2 under the mapping`() {
        val missing = perfectRenameTypePost
            .replace(
                "POST_SITE $renameTypeDir/ValidatorConfig.java|org.keycloak.validate.ValidatorConfig#of|4",
                "POST_SITE $renameTypeDir/ValidatorConfig.java|org.keycloak.validate.ValidatorConfig#of|1",
            )
            .replace("POST_TOTAL_NEW_REFS 198", "POST_TOTAL_NEW_REFS 195")
        val r = parseSemanticPostcondition(
            missing, parseSemanticGold(renameTypeGold),
            expectedPostKey = renameTypeTarget::expectedPostKey,
        )
        assertFalse(r.p2AllSitesConverted) { "The mapping must not make missed sites unreachable" }
        assertEquals(195.0 / 198.0, r.recall, 1e-9)
    }

    // --- Fix round 2: the run block prints what the round cost ---

    @Test
    fun `the run block prints tokens and cost beside the agent time`() {
        // The separating case's two arms, in the figures the smoke round measured.
        val withIde = rippleAgentCostLines(
            agentDurationMs = 1_800_000L,
            tokens = TokenUsage(
                inputTokens = 1_200L, outputTokens = 34_000L, cacheReadTokens = 4_100_000L,
                cacheCreationTokens = 120_000L, costUsd = 1.21, numTurns = 61,
            ),
        )
        assertEquals("[RIPPLE]   agent time:      1800s", withIde.first())
        assertTrue(withIde.any { it.contains("cost:") && it.contains("$1.2100") }) { "$withIde" }
        assertTrue(withIde.any { it.contains("tokens in/out:") && it.contains("1200/34000") }) { "$withIde" }
        assertTrue(withIde.any { it.contains("turns:") && it.contains("61") }) { "$withIde" }

        val withoutIde = rippleAgentCostLines(
            agentDurationMs = 5_400_000L,
            tokens = TokenUsage(
                inputTokens = 900_000L, outputTokens = 410_000L, cacheReadTokens = 260_000_000L,
                cacheCreationTokens = 3_000_000L, costUsd = 86.84, numTurns = 720,
            ),
        )
        assertTrue(withoutIde.any { it.contains("$86.8400") }) {
            "The 86.84-against-1.21 separation is the headline and must be readable from the log: $withoutIde"
        }
    }

    @Test
    fun `a run with no usage event says UNAVAILABLE rather than printing a free run`() {
        val lines = rippleAgentCostLines(agentDurationMs = 60_000L, tokens = null)
        assertEquals("[RIPPLE]   agent time:      60s", lines.first())
        assertTrue(lines.any { it.contains("UNAVAILABLE") }) { "$lines" }
        assertFalse(lines.any { it.contains("$0") }) { "A missing figure is unknown, not zero: $lines" }
    }

    @Test
    fun `an agent CLI that reports no dollar figure says so instead of inventing one`() {
        // Codex reports per-turn token usage and no cost at all.
        val lines = rippleAgentCostLines(
            agentDurationMs = 120_000L,
            tokens = TokenUsage(inputTokens = 10L, outputTokens = 20L, numTurns = 3),
        )
        assertTrue(lines.any { it.startsWith("[RIPPLE]   cost:") && it.contains("not reported") }) {
            "$lines"
        }
        assertFalse(lines.any { it.contains("$0.0000") }) { "$lines" }
    }
}
