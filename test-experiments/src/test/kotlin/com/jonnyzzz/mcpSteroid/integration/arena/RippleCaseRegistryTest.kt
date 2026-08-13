/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The test that keeps the whole family honest at once: every property the pilot and the second case
 * each asserted for themselves is asserted here for every case, so a fourth case inherits the
 * contract instead of copying a test file.
 */
class RippleCaseRegistryTest {

    @Test
    fun `instance ids are unique and identify the track`() {
        val ids = RippleCases.all.map { it.instanceId }
        assertEquals(ids.distinct().size, ids.size) { "Duplicate instance id in $ids" }
        assertTrue(ids.all { it.startsWith("ripple__keycloak__") }) { "$ids" }
    }

    @TestFactory
    fun `every case is well-formed`(): List<DynamicTest> = RippleCases.all.map { case ->
        DynamicTest.dynamicTest(case.instanceId) {
            val patch = case.testPatch()
            assertEquals(
                patch.lines().count { it.startsWith("diff --git ") },
                patch.lines().count { it.trim() == "--- /dev/null" },
            ) { "${case.instanceId}: the test patch must be purely additive" }
            assertFalse(patch.contains("deleted file mode")) { case.instanceId }

            val paths = extractPatchFilePaths(patch)
            assertTrue(paths.isNotEmpty()) { case.instanceId }
            assertTrue(paths.all { it.contains("/src/test/java/") }) { "${case.instanceId}: $paths" }

            assertTrue(case.compileGateModules.contains(case.declaringModuleArtifactId)) {
                "${case.instanceId}: the declaring module must be inside the compile gate"
            }
            assertTrue(case.compileGateModules.contains(case.consumerModuleArtifactId)) {
                "${case.instanceId}: the hidden consumer's module must be inside the compile gate"
            }
            assertEquals(
                case.compileGateModules.distinct().size, case.compileGateModules.size,
            ) { case.instanceId }
            assertFalse(case.compileGateArgs().contains("-am")) { case.instanceId }
            assertTrue(case.compileGateArgs().contains("test-compile")) { case.instanceId }

            assertTrue(case.expectedGoldReferences > 0) { case.instanceId }
            assertTrue(case.expectedGoldFiles > 0) { case.instanceId }
            assertTrue(case.expectedDecoyDeclarations >= 3) {
                "${case.instanceId}: without lexical ambiguity the case measures search cost only"
            }
        }
    }

    /**
     * Absorbed from the pilot's own case test. `git apply` rejects the whole patch when a header
     * promises more lines than the body holds — "corrupt patch at line N", which surfaces as a
     * container that cannot prepare the tree at all. Counting it here costs milliseconds instead of a
     * ten-minute Docker run.
     */
    @TestFactory
    fun `every hunk header counts its body exactly`(): List<DynamicTest> = RippleCases.all.map { case ->
        DynamicTest.dynamicTest(case.instanceId) {
            val lines = case.testPatch().lines()
            val headers = lines.withIndex().filter { it.value.startsWith("@@") }
            assertTrue(headers.isNotEmpty()) { "${case.instanceId}: the patch has no hunk header at all" }
            headers.forEach { (index, header) ->
                val promised = Regex("""\+\d+,(\d+)""").find(header)
                    ?.groupValues?.get(1)?.toInt()
                    ?: error("${case.instanceId}: hunk header states no line count: $header")
                val body = lines.drop(index + 1).takeWhile { it.startsWith("+") || it.startsWith(" ") }
                assertEquals(promised, body.size) {
                    "${case.instanceId}: hunk '$header' promises $promised lines but the body holds " +
                        "${body.size}; git apply would reject the whole patch as corrupt"
                }
            }
        }
    }

    /**
     * Absorbed from the pilot's "the consumer imports the types it names", generalised: a consumer
     * whose file does not sit where its FQN says cannot be found by the FAIL_TO_PASS run, and a
     * consumer in the wrong package cannot see the type it pins without an import it does not have.
     */
    @TestFactory
    fun `the hidden consumer sits where its fully-qualified name says`(): List<DynamicTest> =
        RippleCases.all.map { case ->
            DynamicTest.dynamicTest(case.instanceId) {
                val patch = case.testPatch()
                val expectedSuffix = "/" + case.hiddenConsumerFqn.replace('.', '/') + ".java"
                assertTrue(extractPatchFilePaths(patch).any { it.endsWith(expectedSuffix) }) {
                    "${case.instanceId}: no patched file matches $expectedSuffix"
                }
                assertTrue(patch.contains("+package ${case.hiddenConsumerFqn.substringBeforeLast('.')};")) {
                    "${case.instanceId}: the consumer declares a package other than its FQN's"
                }
                // JUnit 4 in the server-side modules, JUnit 5 under `tests/`: which one a consumer
                // must use is a property of ITS module, so the family only requires that it is a
                // test at all. A consumer with no test annotation compiles and runs nothing, and a
                // FAIL_TO_PASS class that runs nothing reads as a failed fix.
                assertTrue(
                    patch.contains("+import org.junit.Test;") ||
                        patch.contains("+import org.junit.jupiter.api.Test;")
                ) { "${case.instanceId}: the consumer imports no JUnit test annotation" }

                // Absorbed from the pilot's "the consumer imports the types it names". A consumer
                // outside the target type's package cannot see it without an import, and a consumer
                // that does not compile fails the gate for every run whatever the agent did — which
                // is not a grade, it is a broken instrument.
                val consumerPackage = case.hiddenConsumerFqn.substringBeforeLast('.')
                val targetPackage = case.target.targetTypeFqn.substringBeforeLast('.')
                if (consumerPackage != targetPackage) {
                    assertTrue(patch.contains("+import ${case.target.targetTypeFqn};")) {
                        "${case.instanceId}: the consumer sits in $consumerPackage and names a type " +
                            "in $targetPackage, so it must import ${case.target.targetTypeFqn}"
                    }
                }
            }
        }

    /**
     * The deleted per-case tests each asserted that the destination differed from the origin in the
     * terms of their own kind. `destinationDescription != targetDescription` does not reproduce that
     * — the two strings have structurally different shapes and can never be equal — so each kind
     * states its own real check here.
     */
    @TestFactory
    fun `every kind's destination really differs from its origin`(): List<DynamicTest> =
        RippleCases.all.map { case ->
            DynamicTest.dynamicTest(case.instanceId) {
                when (val target = case.target) {
                    is RenameMethod -> assertNotEquals(target.oldName, target.newName) {
                        "A rename to the same name is not a rename"
                    }
                    is RenameType -> {
                        assertNotEquals(target.oldSimpleName, target.newSimpleName)
                        assertNotEquals(target.oldFqn, target.newFqn) {
                            "The renamed type must keep its package but change its simple name"
                        }
                    }
                    is ChangeSignature -> {
                        assertNotEquals(target.oldArity, target.newArity)
                        assertEquals(target.oldArity + 1, target.newArity) {
                            "This kind adds exactly one parameter"
                        }
                        assertTrue(target.addedParameterType.isNotBlank()) { case.instanceId }
                        assertTrue(target.addedParameterName.isNotBlank()) { case.instanceId }
                    }
                    is MoveClass -> {
                        assertNotEquals(target.oldFqn, target.newFqn) {
                            "A move to the same fully-qualified name is not a move"
                        }
                        assertNotEquals(target.oldFqn.substringBeforeLast('.'), target.newPackage) {
                            "A move to the same package is not a move"
                        }
                        assertEquals(target.simpleName, target.newFqn.substringAfterLast('.')) {
                            "This kind keeps the simple name and changes only the package"
                        }
                    }
                }
            }
        }

    @TestFactory
    fun `every case pins the commit and names its consumer as FAIL_TO_PASS`(): List<DynamicTest> =
        RippleCases.all.map { case ->
            DynamicTest.dynamicTest(case.instanceId) {
                val dpaia = case.dpaiaCase()
                assertEquals(SemanticRippleSpec.baseCommit, dpaia.baseCommit)
                assertEquals("maven", dpaia.buildSystem)
                assertTrue(dpaia.isMaven)
                assertEquals(listOf(case.hiddenConsumerFqn), dpaia.failToPass)
                assertTrue(dpaia.passToPass.isEmpty()) {
                    "${case.instanceId}: regression evidence here is the compile gate, not a " +
                        "PASS_TO_PASS list"
                }
                assertEquals(case.instanceId, dpaia.instanceId)
            }
        }

    @TestFactory
    fun `every case selects its modules by artifactId and enables the testsuite profile`():
        List<DynamicTest> = RippleCases.all.map { case ->
        DynamicTest.dynamicTest(case.instanceId) {
            assertEquals(case.compileGateModules.size, case.compileGateSelectors().size)
            case.compileGateSelectors().forEach { selector ->
                assertTrue(selector.startsWith(":")) {
                    "'$selector' has no colon, so Maven looks for a directory of that name and " +
                        "answers 'Could not find the selected project in the reactor'"
                }
            }
            val args = case.compileGateArgs()
            assertTrue(args.contains("-P") && args.contains(SemanticRippleSpec.reactorProfile)) {
                "${case.instanceId}: modules behind the '${SemanticRippleSpec.reactorProfile}' " +
                    "profile are absent from the default reactor: $args"
            }
            assertTrue(case.gradingScopeSelector().split(",").all { it.startsWith(":") }) {
                "${case.instanceId}: ${case.gradingScopeSelector()}"
            }
        }
    }

    /**
     * Scoped to the wide cases only — [RippleCases.renameTypeNarrow] and
     * [RippleCases.changeSignatureNarrow] exist specifically to violate "wide" while holding
     * everything else about their twin constant, so asserting this over [RippleCases.all] would fail
     * the very cases built to be the exception.
     */
    private val wideCases = listOf(RippleCases.renameMethodWide, RippleCases.renameTypeWide, RippleCases.changeSignatureWide)

    @TestFactory
    fun `every wide case's target is wide and its destination differs from its origin`(): List<DynamicTest> =
        wideCases.map { case ->
            DynamicTest.dynamicTest(case.instanceId) {
                assertTrue(case.expectedGoldReferences >= 100) { case.instanceId }
                assertTrue(case.expectedGoldFiles >= 20) { case.instanceId }
                assertTrue(case.compileGateModules.size >= 4) {
                    "${case.instanceId}: wide means references in at least 3 modules, plus the " +
                        "declaring module"
                }
                assertTrue(case.target.behaviourPreservationEvidence.isNotBlank()) {
                    "${case.instanceId}: a case whose behaviour preservation is not written down " +
                        "cannot be reviewed"
                }
            }
        }

    @TestFactory
    fun `every case's behaviour preservation is written down`(): List<DynamicTest> =
        RippleCases.all.map { case ->
            DynamicTest.dynamicTest(case.instanceId) {
                assertTrue(case.target.behaviourPreservationEvidence.isNotBlank()) {
                    "${case.instanceId}: a case whose behaviour preservation is not written down " +
                        "cannot be reviewed"
                }
            }
        }

    @Test
    fun `each narrow case is the fan-out ablation of its wide twin, ambiguity held constant`() {
        val pairs = listOf(
            RippleCases.renameTypeWide to RippleCases.renameTypeNarrow,
            RippleCases.changeSignatureWide to RippleCases.changeSignatureNarrow,
        )
        pairs.forEach { (wide, narrow) ->
            assertTrue(wide.expectedGoldReferences >= 100) { wide.instanceId }
            assertTrue(narrow.expectedGoldReferences in 5..20) { narrow.instanceId }
            assertTrue(narrow.expectedGoldFiles <= 3) { narrow.instanceId }
            assertTrue(narrow.expectedDecoyDeclarations >= 3) {
                "${narrow.instanceId}: a narrow case without ambiguity varies two axes at once and " +
                    "cannot isolate fan-out"
            }
            assertEquals(wide.target.kindId, narrow.target.kindId)
        }
    }

    @TestFactory
    fun `every prompt names its target and leaks neither mechanism nor answer`(): List<DynamicTest> =
        RippleCases.all.map { case ->
            DynamicTest.dynamicTest(case.instanceId) {
                listOf(true, false).forEach { withMcp ->
                    val p = buildRipplePrompt(case, "/work/keycloak", withMcp)
                    // The benchmark does not test guessing the starting point: both the declaration
                    // and its destination are stated exactly, and only the ripple is left to find.
                    assertTrue(p.contains(case.target.targetDescription.substringBefore('#'))) { p }
                    assertTrue(p.contains(case.target.destinationDescription)) { p }
                    assertTrue(p.contains("ARENA_FIX_APPLIED: yes")) { p }
                    assertTrue(p.contains("other types") || p.contains("other packages")) {
                        "${case.instanceId}: the prompt must forbid touching same-named declarations elsewhere"
                    }
                    listOf("steroid_", "mcp-steroid://", "find usages", "findUsages", "Find Usages",
                           "PSI", "IntelliJ", "refactor", "Refactor", "IDE").forEach { token ->
                        assertFalse(p.contains(token)) { "${case.instanceId} leaks '$token':\n$p" }
                    }
                    assertFalse(p.contains(".java")) { "${case.instanceId} enumerates files:\n$p" }
                    assertFalse(p.contains("${case.expectedGoldReferences}")) {
                        "${case.instanceId} states the reference count:\n$p"
                    }
                    assertFalse(p.contains("-am")) { case.instanceId }
                }
            }
        }

    @TestFactory
    fun `every problem statement is the purity-checked prompt task section`(): List<DynamicTest> =
        RippleCases.all.map { case ->
            DynamicTest.dynamicTest(case.instanceId) {
                val statement = case.problemStatement()
                assertTrue(statement.contains(case.target.destinationDescription)) { statement }
                assertFalse(statement.contains("ClientResource")) {
                    "${case.instanceId}: the statement must not name a decoy"
                }
            }
        }

    @Test
    fun `every case shares the family base commit`() {
        assertTrue(RippleCases.all.all { it.dpaiaCase().baseCommit == SemanticRippleSpec.baseCommit })
    }

    @Test
    fun `the base commit is a full pinned sha`() {
        assertTrue(SemanticRippleSpec.baseCommit.matches(Regex("[0-9a-f]{40}"))) {
            "Base commit must be a full 40-character SHA, was '${SemanticRippleSpec.baseCommit}'"
        }
    }

    @Test
    fun `the pilot's measured numbers and its destination name are unchanged`() {
        val pilot = RippleCases.renameMethodWide
        assertEquals(445, pilot.expectedGoldReferences)
        assertEquals(79, pilot.expectedGoldFiles)
        assertEquals(16, pilot.expectedDecoyDeclarations)
        assertEquals(7, pilot.compileGateModules.size)
        assertTrue(pilot.compileGateSelectors().contains(":keycloak-admin-client-core"))
        assertEquals("realmLevelRoles", RippleCases.renameMethodWideTarget.newName)
        assertFalse(RippleCases.renameMethodWideTarget.newName == "realmRoles") {
            "realmRoles is already declared 5 times in Keycloak and must not be the destination name"
        }
    }

    @Test
    fun `the rename-type case's measured numbers are unchanged`() {
        val case = RippleCases.renameTypeWide
        assertEquals(198, case.expectedGoldReferences)
        assertEquals(41, case.expectedGoldFiles)
        assertEquals(3, case.expectedDecoyDeclarations)
        assertEquals("ValidationRunContext", RippleCases.renameTypeWideTarget.newSimpleName)
        assertEquals(
            "org.keycloak.validate.ValidationRunContext", RippleCases.renameTypeWideTarget.newFqn,
        ) { "A rename-type case must keep the type in its own package" }
    }

    @Test
    fun `the change-signature case's measured numbers are unchanged`() {
        val case = RippleCases.changeSignatureWide
        assertEquals(104, case.expectedGoldReferences)
        assertEquals(49, case.expectedGoldFiles)
        // 1021 same-named declarations at the base commit, minus the three implementers of the
        // target, which a correct solution must change and which are therefore not decoys.
        assertEquals(1018, case.expectedDecoyDeclarations)
        assertEquals(1, RippleCases.changeSignatureWideTarget.newArity)
        assertEquals(0, RippleCases.changeSignatureWideTarget.oldArity)
        assertEquals(9, case.compileGateModules.size)
    }

    /**
     * Every case gets its own post-condition text, built with ITS OWN target arity where the kind is
     * `ChangeSignature` — [parseArityPredicate] throws on a mismatch between the script's measured
     * arity and the case's expected one, so a single shared string (as the pilot's version of this
     * test used) throws for [RippleCases.changeSignatureNarrow], whose arity differs from
     * [RippleCases.changeSignatureWide]'s.
     */
    @Test
    fun `only change-signature and move-class kinds contribute an extra predicate`() {
        RippleCases.all.forEach { case ->
            when (val target = case.target) {
                is ChangeSignature -> {
                    val arityPost = """
                        POST_TOTAL_NEW_REFS 2
                        POST_ARITY_EXPECTED ${target.newArity}
                        POST_ARITY_MATCHING 2
                        POST_END
                    """.trimIndent()
                    val predicates = target.extraPredicates(arityPost)
                    assertEquals(setOf("P5_ARITY"), predicates.keys) {
                        "${case.instanceId}: the change-signature kind must contribute P5_ARITY"
                    }
                }
                is MoveClass -> {
                    val movePost = """
                        POST_NEW_FQN_RESOLVES true
                        POST_OLD_FQN_RESOLVES false
                        POST_END
                    """.trimIndent()
                    val predicates = target.extraPredicates(movePost)
                    assertEquals(setOf("P1_MOVED"), predicates.keys) {
                        "${case.instanceId}: the move-class kind must contribute P1_MOVED"
                    }
                }
                is RenameMethod, is RenameType -> {
                    val predicates = target.extraPredicates("POST_END")
                    assertTrue(predicates.isEmpty()) {
                        "${case.instanceId}: P1 to P4 are the family contract; a kind adds its own only " +
                            "when it needs one"
                    }
                }
            }
        }
    }
}
