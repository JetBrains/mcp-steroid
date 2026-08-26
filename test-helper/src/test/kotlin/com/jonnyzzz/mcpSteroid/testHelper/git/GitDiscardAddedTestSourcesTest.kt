/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pins what [GitDriver.discardAddedTestSources] selects, against REAL `git ls-files --others`.
 *
 * A host-side test for a container helper, on the same grounds as [GitApplyPatchFlagsTest]: the
 * container part of that method is one `rm`, and everything that can be wrong about it is which paths
 * git reports and which of them the filter keeps. Both behave identically on the host.
 *
 * The distinction under test is the one the rule rests on (amendment 3 of the acquisition case
 * admission protocol): a test the solver **added** is discarded because nothing grades it and a
 * module-wide `testCompile` will fail on it, while a shipped test the solver **modified** survives,
 * because breaking one is evidence about the solver's change.
 */
class GitDiscardAddedTestSourcesTest {

    @Test
    fun `selects test sources the working tree added, in every source root layout`() {
        val repo = repoWith(
            "services/src/test/java/org/keycloak/ShippedTest.java" to "class ShippedTest {}\n",
            "services/src/main/java/org/keycloak/Shipped.java" to "class Shipped {}\n",
        )
        // What a solving agent leaves behind: an implementation, a scratch test beside the shipped
        // ones, a scratch test in a root-level source root, and an edit to a test that already existed.
        repo.write("services/src/main/java/org/keycloak/NewExecutor.java", "class NewExecutor {}\n")
        repo.write("services/src/test/java/org/keycloak/NewExecutorTest.java", "class NewExecutorTest {}\n")
        repo.write("src/test/java/RootLayoutTest.java", "class RootLayoutTest {}\n")
        repo.write("services/src/test/java/org/keycloak/ShippedTest.java", "class ShippedTest { /* edited */ }\n")

        val selected = untrackedPaths(repo).filter { GitDriver.isTestSourcePath(it) }

        assertEquals(
            listOf(
                "services/src/test/java/org/keycloak/NewExecutorTest.java",
                "src/test/java/RootLayoutTest.java",
            ),
            selected.sorted(),
        )
    }

    @Test
    fun `an edited shipped test is not selected, because breaking one is evidence`() {
        val repo = repoWith("services/src/test/java/org/keycloak/ShippedTest.java" to "class ShippedTest {}\n")
        repo.write("services/src/test/java/org/keycloak/ShippedTest.java", "this does not compile\n")

        assertTrue(
            untrackedPaths(repo).isEmpty(),
            "a modified tracked file is not an untracked one, so nothing may be discarded",
        )
    }

    @Test
    fun `an added main source is never selected`() {
        val repo = repoWith("pom.xml" to "<project/>\n")
        repo.write("services/src/main/java/org/keycloak/NewExecutor.java", "class NewExecutor {}\n")

        assertEquals(emptyList<String>(), untrackedPaths(repo).filter { GitDriver.isTestSourcePath(it) })
    }

    @Test
    fun `a file outside every source root is scaffolding, and a resource line is not`() {
        // The distinction the column rests on: `META-INF/services/...` under src/main/resources IS the
        // change on several of these cases, while a root-level report about the change is not.
        assertTrue(GitDriver.isSourcePath("services/src/main/resources/META-INF/services/x.Factory"))
        assertTrue(GitDriver.isSourcePath("services/src/test/java/A.java"))
        assertTrue(GitDriver.isSourcePath("src/main/java/A.java"))
        assertFalse(GitDriver.isSourcePath("FINAL_REPORT.md"))
        assertFalse(GitDriver.isSourcePath("apply_patch.py"))
        assertFalse(GitDriver.isSourcePath("update-client-profiles.sh"))
        assertFalse(GitDriver.isSourcePath("services/OIDCLoginProtocol_patch.txt"))
    }

    @Test
    fun `the path rule matches both source-root layouts and nothing that merely looks like one`() {
        assertTrue(GitDriver.isTestSourcePath("src/test/java/A.java"))
        assertTrue(GitDriver.isTestSourcePath("services/src/test/java/A.java"))
        assertTrue(GitDriver.isTestSourcePath("a/b/c/src/test/resources/x.json"))
        assertFalse(GitDriver.isTestSourcePath("src/main/java/A.java"))
        assertFalse(GitDriver.isTestSourcePath("services/src/testFixtures/java/A.java"))
        // `testsuite/` is a real Keycloak directory and is NOT a test source root of the module the
        // solver is graded in; a rule that swallowed it would delete tracked project structure.
        assertFalse(GitDriver.isTestSourcePath("testsuite/integration/pom.xml"))
    }

    @TempDir
    lateinit var tempDir: File

    /** Real `git ls-files --others --exclude-standard`, the call the production method makes. */
    private fun untrackedPaths(repo: File): List<String> {
        val process = ProcessBuilder("git", "-C", repo.absolutePath, "ls-files", "--others", "--exclude-standard")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ls-files failed:\n$output" }
        return output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    private fun File.write(path: String, content: String) {
        resolve(path).also { it.parentFile.mkdirs() }.writeText(content)
    }

    private fun repoWith(vararg files: Pair<String, String>): File {
        val repo = tempDir.resolve("repo").also { it.mkdirs() }
        run(repo, "git", "init", "-q", ".")
        files.forEach { (name, content) -> repo.write(name, content) }
        run(repo, "git", "add", "-A")
        run(repo, "git", "-c", "user.email=arena@test", "-c", "user.name=arena", "commit", "-q", "-m", "base")
        return repo
    }

    private fun run(dir: File, vararg command: String) {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "${command.joinToString(" ")} failed with $exitCode:\n$output" }
    }
}
