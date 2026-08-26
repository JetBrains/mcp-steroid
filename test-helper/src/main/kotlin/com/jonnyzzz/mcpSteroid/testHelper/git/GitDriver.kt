/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.git

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ExecContainerProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

/**
 * Reusable Git operations for Docker containers.
 * Works with any [com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver] instance.
 */
//TODO: We need to cache repositories on the host machine as bare checkout to make deployment faster
class GitDriver(
    private val driver: ContainerDriver,
) {
    /**
     * Clone a git repository into [targetDir] inside the container.
     * Creates the parent directory if needed.
     *
     * @param repoUrl repository URL (https, ssh, or file)
     * @param targetDir guest path for the cloned repository
     * @param shallow use `--depth 1` for a shallow clone (default true)
     * @param timeoutSeconds timeout for the clone operation
     */
    fun clone(
        repoUrl: String,
        targetDir: String,
        shallow: Boolean = true,
        timeoutSeconds: Long = 300,
    ) {
        // Ensure parent directory exists
        val parent = targetDir.substringBeforeLast("/")
        driver.mkdirs(parent)

        val args = mutableListOf("git", "clone")
        if (shallow) {
            args += listOf("--depth", "1")
        }
        args += listOf(repoUrl, targetDir)

        println("[GIT] Cloning $repoUrl into $targetDir (shallow=$shallow)...")
        driver.startProcessInContainer {
            this
                .args(args)
                .timeoutSeconds(timeoutSeconds)
                .description("git clone $repoUrl into $targetDir")
        }.assertExitCode(0) { "git clone $repoUrl failed" }
    }

    /**
     * Checkout a specific commit or ref in a repository.
     */
    fun checkout(repoDir: String, ref: String) {
        println("[GIT] Checking out $ref in $repoDir...")
        driver.startProcessInContainer {
            this
                .args("git", "-C", repoDir, "checkout", ref)
                .timeoutSeconds(30)
                .description("git checkout $ref in $repoDir")
        }.assertExitCode(0) { "git checkout $ref failed" }
    }

    /**
     * Try to clone a repository from a host-side bare cache mounted inside the container.
     *
     * Checks whether `{cacheGuestPath}/{ownerAndRepo}.git` exists in the container.
     * If it does, clones from it using a fast `file://` local clone.
     * If it does not, returns false so the caller can fall back to a remote clone.
     *
     * @param ownerAndRepo repo identifier without `.git`, e.g. `"dpaia/feature-service"`
     * @param targetDir guest path for the cloned repository
     * @param cacheGuestPath guest path where the host repo cache is mounted (default `/repo-cache`)
     * @return true if cloned from cache; false if the bare repo was not found in the cache
     */
    fun cloneFromCachedBare(
        ownerAndRepo: String,
        targetDir: String,
        cacheGuestPath: String = "/repo-cache",
    ): Boolean {
        val bareGuestPath = "$cacheGuestPath/$ownerAndRepo.git"

        val check = driver.startProcessInContainer {
            this
                .args("test", "-d", bareGuestPath)
                .timeoutSeconds(5)
                .quietly()
                .description("test -d $bareGuestPath")
        }.awaitForProcessFinish()
        if (check.exitCode != 0) {
            println("[GIT] No cached bare repo at $bareGuestPath, will clone from remote")
            return false
        }

        val parent = targetDir.substringBeforeLast("/")
        driver.mkdirs(parent)

        // Bypass git's "dubious ownership" check by seeding the safe.directory
        // whitelist in the container's ~/.gitconfig BEFORE the clone. Observed
        // on TC CI:
        //   fatal: detected dubious ownership in repository at
        //     '/repo-cache/dpaia/feature-service.git'
        //   git config --global --add safe.directory /repo-cache/dpaia/…
        //
        // Root cause: the /repo-cache bind mount is owned by the host TC-agent
        // user (uid e.g. 999) while git inside the container runs as `agent`
        // (uid 1000). Linux bind mounts don't do UID remapping, so git refuses
        // without an explicit safe.directory entry.
        //
        // `-c safe.directory=<x>` on the clone command itself was tried first
        // (both `*` wildcard and the specific path, belt-and-suspenders) and
        // did NOT work on the TC agents' git version — the in-memory config
        // set by `-c` apparently isn't honored for the owner check on some
        // git builds. Running `git config --global --add` in a separate exec
        // persists the setting to ~/.gitconfig inside the ephemeral container,
        // which the subsequent clone picks up reliably. The container's home
        // is wiped when it's torn down so this doesn't leak anywhere.
        println("[GIT] Registering safe.directory=$bareGuestPath in container ~/.gitconfig")
        driver.startProcessInContainer {
            this
                .args("git", "config", "--global", "--add", "safe.directory", bareGuestPath)
                .timeoutSeconds(5)
                .quietly()
                .description("git config safe.directory for $bareGuestPath")
        }.awaitForProcessFinish().assertExitCode(0, "git config safe.directory")

        println("[GIT] Cloning from bare cache: $bareGuestPath -> $targetDir ...")
        driver.startProcessInContainer {
            this
                .args("git", "clone", "file://$bareGuestPath", targetDir)
                .timeoutSeconds(120)
                .description("git clone from bare cache $bareGuestPath")
        }.awaitForProcessFinish().assertExitCode(0, "git clone from bare cache $bareGuestPath")

        return true
    }

    /**
     * Apply a patch to a repository using `git apply`.
     *
     * @param repoDir guest path of the repository
     * @param patchContent the patch text (unified diff format)
     */
    fun applyPatch(repoDir: String, patchContent: String) {
        if (patchContent.isBlank()) {
            println("[GIT] No patch to apply")
            return
        }

        val patchPath = "/tmp/_tmp_patch_${System.currentTimeMillis()}.diff"
        println("[GIT] Applying patch to $repoDir...")
        // Ensure patch ends with a trailing newline — git apply requires it,
        // but some dataset entries omit the final newline causing "corrupt patch" errors.
        val normalizedPatch = if (patchContent.endsWith("\n")) patchContent else patchContent + "\n"
        driver.writeFileInContainer(patchPath, normalizedPatch, executable = false)

        driver.startProcessInContainer {
            this
                .args(listOf("git", "-C", repoDir) + APPLY_PATCH_ARGS + patchPath)
                .timeoutSeconds(30)
                .description("git apply patch in $repoDir")
        }.awaitForProcessFinish().assertExitCode(0, "git apply patch")
    }

    companion object {
        /**
         * The `git apply` arguments [applyPatch] uses, between `git -C <repo>` and the patch path.
         *
         * Deliberately minimal: git's header counts must stay authoritative. `--recount` recomputes
         * hunk counts from the hunk body and counts a bare empty line as context, so it swallows the
         * blank lines that separate a patch's file sections into the preceding hunk — fatal on a
         * `new file` hunk (`new file <path> depends on old contents`) and a context inflation that
         * fails to apply on a modified-file hunk. A malformed header is repaired where it is read
         * (`repairTrimmedUnifiedDiff`), not worked around here.
         *
         * Exercised against real patch shapes by
         * [com.jonnyzzz.mcpSteroid.testHelper.git.GitApplyPatchFlagsTest], so a flag added here is
         * checked against the patches it has to apply instead of being assumed harmless.
         */
        val APPLY_PATCH_ARGS: List<String> = listOf("apply", "--allow-empty")

        /**
         * Whether a repository-relative path lives in a test source root.
         *
         * Matches `src/test/...` at the repository root and `<any>/src/test/...` below it, which is
         * the Maven and Gradle convention both. Public and pure so [discardAddedTestSources]'s
         * selection can be exercised without a container.
         */
        fun isTestSourcePath(repoRelativePath: String): Boolean =
            repoRelativePath.startsWith("src/test/") || "/src/test/" in repoRelativePath

        /**
         * Whether a repository-relative path lives in ANY source root, main or test.
         *
         * Used to tell a file that is part of the change from a file that is prose about the change.
         * Resources count: a `META-INF/services` line is the change on several of these cases.
         */
        fun isSourcePath(repoRelativePath: String): Boolean =
            repoRelativePath.startsWith("src/") || "/src/" in repoRelativePath
    }

    /**
     * Deletes every file the working tree has ADDED under a `src/test/` directory, and returns the
     * repository-relative paths it removed.
     *
     * Added, never modified. A tracked test the agent edited is evidence about the agent's change and
     * has to keep its consequences; a test the agent invented is not part of any change under
     * evaluation, and a grading build that compiles the whole module will happily fail on it.
     *
     * The selection is `git ls-files --others --exclude-standard` filtered in code rather than by a
     * pathspec, because the two shapes that have to match — `src/test/...` at the repository root and
     * `<module>/src/test/...` — are one substring and two pathspecs, and a pathspec that silently
     * matches neither would look exactly like a tree with nothing to discard.
     *
     * @param repoDir guest path of the repository
     */
    fun discardAddedTestSources(repoDir: String): List<String> {
        val listed = driver.startProcessInContainer {
            this
                .args("git", "-C", repoDir, "ls-files", "--others", "--exclude-standard")
                .timeoutSeconds(30)
                .quietly()
                .description("git ls-files --others in $repoDir")
        }.awaitForProcessFinish()
        listed.assertExitCode(0, "git ls-files --others")

        val added = listed.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { isTestSourcePath(it) }
            .toList()
        if (added.isEmpty()) return emptyList()

        driver.startProcessInContainer {
            this
                .args(listOf("rm", "-f", "--") + added.map { "$repoDir/$it" })
                .timeoutSeconds(30)
                .description("discard ${added.size} agent-added test source(s) in $repoDir")
        }.awaitForProcessFinish().assertExitCode(0, "discard agent-added test sources")
        return added
    }

    /**
     * Counts the files the working tree ADDED outside every source root, and returns their paths.
     *
     * A blocked agent has an escape hatch this experiment created on purpose: reads are charged and
     * edits are free, so an agent that hits the interaction wall can no longer investigate but can
     * still write. One cell answered that by producing twenty-eight files — `apply_patch.py`,
     * `fix.pl`, `REQUIRED_EDITS.md`, `FINAL_REPORT.md`, `DEPLOY_CHECKLIST.md` and a dozen more
     * documents ABOUT the change — and none of the change. The oracle scored it zero, correctly, and
     * nothing in the published row said why.
     *
     * Recorded rather than forbidden. Free edits exist so a note is not priced in keystrokes, and that
     * reason has not changed; what was missing is a column that makes the behaviour visible in the
     * table instead of only in a transcript somebody happens to read.
     *
     * @param repoDir guest path of the repository
     */
    fun addedNonSourceFiles(repoDir: String): List<String> {
        val listed = driver.startProcessInContainer {
            this
                .args("git", "-C", repoDir, "ls-files", "--others", "--exclude-standard")
                .timeoutSeconds(30)
                .quietly()
                .description("git ls-files --others in $repoDir")
        }.awaitForProcessFinish()
        listed.assertExitCode(0, "git ls-files --others")
        return listed.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isSourcePath(it) }
            .toList()
    }

    /**
     * Generate a diff of changes in a repository.
     *
     * @param repoDir guest path of the repository
     * @return the diff output as a string in unified diff format, suitable for applyPatch()
     */
    fun diff(repoDir: String): String {
        println("[GIT] Generating diff for $repoDir...")
        val result = driver.startProcessInContainer {
            this
                .args("git", "-C", repoDir, "diff")
                .timeoutSeconds(30)
                .quietly()
                .description("git diff in $repoDir")
        }.awaitForProcessFinish()

        result.assertExitCode(0, "git diff")
        return result.stdout
    }

}
