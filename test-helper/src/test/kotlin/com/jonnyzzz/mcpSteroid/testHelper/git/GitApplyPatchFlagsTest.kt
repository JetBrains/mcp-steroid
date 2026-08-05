/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Runs the REAL `git apply` with the exact argument list [GitDriver.APPLY_PATCH_ARGS] passes to git,
 * over patch shapes taken from the dpaia.dev arena dataset.
 *
 * Why a host-side test for a container helper: [GitDriver.applyPatch] only chooses git's argv — the
 * behaviour under test belongs to git itself, and git behaves identically on the host. Sharing the
 * argv constant with production means a flag added there is exercised here, so the flag set cannot
 * drift away from the patches it has to apply. [com.jonnyzzz.mcpSteroid.testHelper.EscapeShellArgsTest]
 * sets the same precedent by driving a real `bash`.
 *
 * The regression this pins (jonnyzzz/mcp-steroid#447 follow-up): `--recount` recomputes each hunk's
 * line counts from the hunk body instead of trusting the `@@ -a,b +c,d @@` header, and git counts a
 * bare empty line as CONTEXT. Dataset patches separate their file sections with blank lines, so
 * `--recount` swallows those separators into the preceding hunk. On a `new file` hunk that yields
 * `error: new file <path> depends on old contents` (exit 128); on a modified-file hunk it inflates the
 * context and yields `patch does not apply` (exit 1). Measured against the live dataset, `--recount`
 * fatally rejected 41 of 150 test patches that git accepts without it.
 *
 * Both fixtures are already in the shape [repairTrimmedUnifiedDiff]'s consumers hand to git — that is,
 * with correct header counts — so the only variable left is the flag set.
 */
class GitApplyPatchFlagsTest {

    /**
     * dpaia__train__ticket-31's shape: a `new file` section whose hunk is followed by
     * `\ No newline at end of file` and the dataset's blank section separators.
     */
    private val newFileFollowedByBlankSeparators = """
        diff --git a/Added.java b/Added.java
        new file mode 100644
        index 0000000000..cea62b1b7d
        --- /dev/null
        +++ b/Added.java
        @@ -0,0 +1,3 @@
        +class Added {
        +    // body
        +}
        \ No newline at end of file



        diff --git a/Existing.java b/Existing.java
        index 0497ae6c53..95aa6e7104 100644
        --- a/Existing.java
        +++ b/Existing.java
        @@ -1,3 +1,4 @@
         class Existing {
        +    // inserted
             // tail
         }
    """.trimIndent() + "\n"

    /**
     * dpaia__jhipster__sample__app-3 / dpaia__spring__petclinic-36's shape: a modified-file hunk whose
     * header counts are authoritative, followed by the same blank separators.
     */
    private val modifiedFileFollowedByBlankSeparators = """
        diff --git a/First.java b/First.java
        index 1111111111..2222222222 100644
        --- a/First.java
        +++ b/First.java
        @@ -1,4 +1,5 @@
         class First {
             // keep
        +    // inserted
             // tail
         }


        diff --git a/Second.java b/Second.java
        index 3333333333..4444444444 100644
        --- a/Second.java
        +++ b/Second.java
        @@ -1,3 +1,4 @@
         class Second {
        +    // inserted
             // tail
         }
    """.trimIndent() + "\n"

    @Test
    fun `a new-file section followed by blank separators applies`() {
        val repo = repoWith(
            "Existing.java" to "class Existing {\n    // tail\n}\n",
        )

        assertEquals("", repo.applyPatch(newFileFollowedByBlankSeparators))
        assertEquals("class Added {\n    // body\n}", repo.resolve("Added.java").readText())
    }

    @Test
    fun `modified-file sections separated by blank lines all apply`() {
        val repo = repoWith(
            "First.java" to "class First {\n    // keep\n    // tail\n}\n",
            "Second.java" to "class Second {\n    // tail\n}\n",
        )

        assertEquals("", repo.applyPatch(modifiedFileFollowedByBlankSeparators))
        assertEquals(
            "class First {\n    // keep\n    // inserted\n    // tail\n}\n",
            repo.resolve("First.java").readText(),
        )
        assertEquals(
            "class Second {\n    // inserted\n    // tail\n}\n",
            repo.resolve("Second.java").readText(),
        )
    }

    @TempDir
    lateinit var tempDir: File

    private fun repoWith(vararg files: Pair<String, String>): File {
        val repo = tempDir.resolve("repo").also { it.mkdirs() }
        run(repo, "git", "init", "-q", ".")
        files.forEach { (name, content) ->
            repo.resolve(name).also { it.parentFile.mkdirs() }.writeText(content)
        }
        run(repo, "git", "add", "-A")
        run(repo, "git", "-c", "user.email=arena@test", "-c", "user.name=arena", "commit", "-q", "-m", "base")
        return repo
    }

    /** Applies [patch] with production's flag set; returns git's stderr, empty when git succeeded. */
    private fun File.applyPatch(patch: String): String {
        val patchFile = tempDir.resolve("patch.diff").also { it.writeText(patch) }
        val process = ProcessBuilder(
            listOf("git", "-C", absolutePath) + GitDriver.APPLY_PATCH_ARGS + patchFile.absolutePath,
        ).redirectErrorStream(false).start()
        val stderr = process.errorStream.bufferedReader().readText()
        process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        // "warning: … trailing whitespace." is git being chatty about the patch text, not a failure.
        val errors = stderr.lineSequence()
            .filterNot { it.isBlank() || it.contains("trailing whitespace") || it.startsWith("warning:") }
            .joinToString("\n")
        return if (exitCode == 0) "" else "exit=$exitCode\n$errors"
    }

    private fun run(dir: File, vararg command: String) {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "${command.joinToString(" ")} failed with $exitCode:\n$output" }
    }
}
