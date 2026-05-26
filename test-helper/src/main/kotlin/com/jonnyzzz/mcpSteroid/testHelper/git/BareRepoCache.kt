/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.git

import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Host-side bare git repository cache.
 *
 * Maintains bare clones of remote repositories under a configurable [cacheDir].
 * Each repo is stored at `{cacheDir}/{owner}/{repo}.git`.
 *
 * Freshness contract (mirrors IDE download caching):
 * - If the bare repo was cloned or updated today (per `last-update` file), it is returned as-is.
 * - Otherwise `git remote update --prune` is run to pull new commits from the remote.
 *
 * Thread-safe: a per-repo lock prevents duplicate concurrent clones/updates.
 *
 * Intended use:
 * 1. Call [ensureRepo] at test setup to warm the cache on the host.
 * 2. Mount the cache root as a read-only Docker volume at `/repo-cache`.
 * 3. Use [GitDriver.cloneFromCachedBare] inside the container for fast local clones.
 */
object BareRepoCache {

    private val locks = HashMap<String, Any>()
    private val globalLock = Any()

    private const val LAST_UPDATE_FILENAME = "last-update"

    /**
     * Ensure a bare clone of [repoUrl] exists and is fresh in [cacheDir].
     * Returns the host [java.io.File] pointing to the bare repo directory.
     *
     * @param repoUrl HTTPS clone URL, e.g. `https://github.com/dpaia/feature-service.git`
     * @param cacheDir root cache directory on the host (created if missing)
     * @param timeoutSeconds git clone/update timeout (default 5 minutes)
     */
    fun ensureRepo(
        repoUrl: String,
        cacheDir: File,
        timeoutSeconds: Long = 300,
    ): File {
        val ownerAndRepo = repoUrl
            .removePrefix("https://github.com/")
            .trimEnd('/')
            .removeSuffix(".git")

        val lock = synchronized(globalLock) { locks.getOrPut(ownerAndRepo) { Any() } }

        return synchronized(lock) {
            val barePath = File(cacheDir, "$ownerAndRepo.git")
            val lastUpdateFile = File(barePath, LAST_UPDATE_FILENAME)
            val today = LocalDate.now().toString()

            when {
                barePath.isDirectory -> {
                    val lastUpdate = lastUpdateFile.takeIf { it.exists() }?.readText()?.trim()
                    if (lastUpdate == today) {
                        println("[BareRepoCache] $ownerAndRepo is fresh ($today), skipping update")
                    } else {
                        println("[BareRepoCache] Updating bare repo: $ownerAndRepo (last update: ${lastUpdate ?: "unknown"})...")
                        runGit(listOf("remote", "update", "--prune"), workDir = barePath, timeoutSeconds = timeoutSeconds)
                        lastUpdateFile.writeText(today)
                    }
                }

                else -> {
                    println("[BareRepoCache] Cloning bare repo: $repoUrl -> $barePath ...")
                    barePath.parentFile.mkdirs()
                    runGit(listOf("clone", "--bare", repoUrl, barePath.absolutePath), workDir = null, timeoutSeconds = timeoutSeconds)
                    lastUpdateFile.writeText(today)
                }
            }

            barePath
        }
    }

    /**
     * Warm the cache for all known DPAIA repos.
     *
     * Clones or updates all repos from the dpaia GitHub organisation so subsequent
     * container runs can clone from the local bare cache instead of hitting GitHub.
     */
    fun warmDpaiaRepos(cacheDir: File) {
        val dpaiaRepos = listOf(
            "https://github.com/dpaia/feature-service.git",
            "https://github.com/dpaia/spring-petclinic.git",
            "https://github.com/dpaia/spring-boot-microshop.git",
            "https://github.com/dpaia/spring-petclinic-rest.git",
            "https://github.com/dpaia/train-ticket.git",
            "https://github.com/dpaia/jhipster-sample-app.git",
            "https://github.com/dpaia/piggymetrics.git",
            "https://github.com/dpaia/spring-petclinic-microservices.git",
            "https://github.com/dpaia/empty-maven-springboot3.git",
            "https://github.com/dpaia/Stirling-PDF.git",
            "https://github.com/dpaia/spring-llm-chat.git",
        )

        println("[BareRepoCache] Warming ${dpaiaRepos.size} DPAIA repos in $cacheDir ...")
        for (repoUrl in dpaiaRepos) {
            try {
                ensureRepo(repoUrl, cacheDir)
            } catch (e: Exception) {
                println("[BareRepoCache] WARNING: Failed to cache $repoUrl: ${e.message}")
            }
        }
        println("[BareRepoCache] DPAIA repo warm-up complete")
    }

    /**
     * Warm the cache for large remote-git project repos used by integration tests.
     *
     * These are not DPAIA arena repos but large external repos cloned by test scenarios
     * (e.g. [KeycloakArchitectureTest]). Caching them avoids multi-GB GitHub clones on
     * every test run.
     */
    fun warmLargeProjectRepos(cacheDir: File) {
        val largeRepos = listOf(
            "https://github.com/BroadleafCommerce/BroadleafCommerce.git",
            "https://github.com/keycloak/keycloak.git",
            "https://github.com/killbill/killbill.git",
            "https://github.com/thingsboard/thingsboard.git",
        )

        println("[BareRepoCache] Warming ${largeRepos.size} large project repo(s) in $cacheDir ...")
        for (repoUrl in largeRepos) {
            try {
                ensureRepo(repoUrl, cacheDir)
            } catch (e: Exception) {
                println("[BareRepoCache] WARNING: Failed to cache $repoUrl: ${e.message}")
            }
        }
        println("[BareRepoCache] Large project repo warm-up complete")
    }

    private fun runGit(args: List<String>, workDir: File?, timeoutSeconds: Long) {
        val cmd = listOf("git") + args
        val pb = ProcessBuilder(cmd).inheritIO()
        if (workDir != null) pb.directory(workDir)
        val process = pb.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        require(finished) { "git ${args.first()} timed out after ${timeoutSeconds}s" }

        val exitCode = process.exitValue()
        require(exitCode == 0) {
            "git ${args.first()} failed with exit code $exitCode in ${workDir?.absolutePath ?: "."}"
        }
    }
}
