/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The contract of [UnderstandingCases], asserted in seconds and without a container.
 *
 * Nothing else in the pipeline reads the oracle patch until the moment its verdict is final: it is
 * applied AFTER the downstream agent has finished, so a patch that adds the wrong path — or that names
 * the very class the agent was supposed to invent — surfaces as an unexplained grading failure hours
 * into a cell that then has to be thrown away. This is the only place that catches it beforehand.
 */
class UnderstandingCaseRegistryTest {

    private val cases: List<UnderstandingCase>
        get() = UnderstandingCases.ALL.also {
            assertTrue(it.isNotEmpty()) { "the registry is empty, so every assertion below is vacuous" }
        }

    @Test
    fun `the oracle patch is a unified diff that adds exactly one file`() = cases.forEach { case ->
        val patch = case.oracleTestPatch()
        val lines = patch.lines()
        assertEquals(1, lines.count { it.startsWith("diff --git ") }) { "${case.instanceId}: not one file" }
        assertEquals(1, lines.count { it.trim() == "--- /dev/null" }) { "${case.instanceId}: not purely additive" }
        assertFalse(patch.contains("deleted file mode")) { "${case.instanceId}: the oracle deletes something" }
        assertEquals(1, extractPatchFilePaths(patch).size) { "${case.instanceId}: ${extractPatchFilePaths(patch)}" }

        // A hunk header that disagrees with its body is what `git apply` reports as "corrupt patch",
        // long after the tree it should have been applied to is gone.
        val hunks = lines.filter { it.startsWith("@@ ") }
        assertEquals(1, hunks.size) { "${case.instanceId}: $hunks" }
        val declared = Regex("""^@@ -0,0 \+1,(\d+) @@$""").find(hunks.single())?.groupValues?.get(1)?.toInt()
        assertTrue(declared != null) { "${case.instanceId}: '${hunks.single()}' does not add a whole file" }
        val body = lines.dropWhile { !it.startsWith("@@ ") }.drop(1).dropLastWhile { it.isEmpty() }
        assertTrue(body.all { it.startsWith("+") }) { "${case.instanceId}: a body line is not an addition" }
        assertEquals(declared ?: 0, body.size) { "${case.instanceId}: the hunk header lies about its length" }
    }

    @Test
    fun `the added file is the FAIL_TO_PASS class in the graded module's test sources`() = cases.forEach { case ->
        val path = extractPatchFilePaths(case.oracleTestPatch()).single()
        val fqn = case.failToPass.single()
        assertTrue(path.endsWith("/" + fqn.replace('.', '/') + ".java")) { "${case.instanceId}: $path is not $fqn" }
        assertTrue(path.contains("/src/test/java/")) { "${case.instanceId}: $path is not a test source" }

        // The Maven selector is an artifactId and the patch path is a directory, so they can only be
        // cross-checked by their tail: `services` under `:keycloak-services`, as `model/infinispan`
        // would be under `:keycloak-model-infinispan`. A mismatch means the grading run compiles a
        // module that does not contain the oracle and reports it as simply absent.
        val moduleDirectory = path.substringBefore("/src/test/java/")
        assertTrue(case.gradingScopeSelector.removePrefix(":").endsWith(moduleDirectory.substringAfterLast('/'))) {
            "${case.instanceId}: ${case.gradingScopeSelector} does not grade $moduleDirectory"
        }
    }

    @Test
    fun `the oracle never names the class the agent has to invent`() = cases.forEach { case ->
        val simpleName = case.failToPass.single().substringAfterLast('.')
        val subject = simpleName.removeSuffix("ContractTest")
        assertNotEquals(simpleName, subject) { "${case.instanceId}: an oracle is named <Subject>ContractTest" }

        // Its own class name necessarily contains the subject; every OTHER occurrence would be the
        // oracle compiling against a type the task never asked for by name — and would leak the answer
        // outright if the file were ever seen.
        val remainder = case.oracleTestPatch().replace(simpleName, "")
        assertFalse(remainder.contains(subject)) { "${case.instanceId}: the oracle names '$subject'" }
    }

    @Test
    fun `the problem statement names nothing the solution must touch`() = cases.forEach { case ->
        val forbidden = buildList<String> {
            addAll(listOf("OIDCLoginProtocolFactory", "initBuiltIns", "META-INF", "ProtocolMapper", "mappers/"))
            case.precedentPaths.forEach { add(it.substringAfterLast('/').removeSuffix(".java")) }
            case.goldRolePaths.values.flatten().forEach {
                add(it)
                add(it.substringAfterLast('/'))
            }
        }.filter { it.isNotBlank() }
        forbidden.forEach { token ->
            assertFalse(case.problemStatement.contains(token, ignoreCase = true)) {
                "${case.instanceId}: the statement hands over '$token', so the case measures no research"
            }
        }
    }

    @Test
    fun `every recorded leakage token is really in the statement`() = cases.forEach { case ->
        assertTrue(case.statementLeakageTokens.isNotEmpty()) { "${case.instanceId}: no leakage audit" }
        case.statementLeakageTokens.keys.forEach { token ->
            assertTrue(case.problemStatement.contains(token, ignoreCase = true)) {
                "${case.instanceId}: '$token' was measured but the statement does not contain it"
            }
        }
    }

    @Test
    fun `the gold change set is recorded by role`() = cases.forEach { case ->
        assertTrue(case.goldRolePaths.size >= 3) {
            "${case.instanceId}: ${case.goldRolePaths.keys} — a feature graded on ONE role is not this experiment"
        }
        case.goldRolePaths.forEach { (role, paths) ->
            assertTrue(paths.isNotEmpty()) { "${case.instanceId}: the role '$role' names no path" }
        }
        assertTrue(case.precedentPaths.isNotEmpty()) { "${case.instanceId}: no precedent to imitate" }
    }
}
