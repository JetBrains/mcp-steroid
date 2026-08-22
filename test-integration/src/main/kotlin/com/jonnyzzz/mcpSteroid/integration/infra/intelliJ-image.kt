/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.tagDockerImage
import java.io.File
import java.nio.file.Files.createLink
import kotlin.io.path.exists

/**
 * Builds the IDE Docker image for [dockerFileBase] and returns the [DockerDriver]
 * scoped to its build context together with the image ID (sha256:...).
 *
 * The build-context directory name is derived internally from a SHA-256 hash of the
 * [ideArchive] path (the download path is deterministic), so the context path is STABLE
 * across runs. BuildKit keys its local-context snapshot by that path, so a constant path
 * lets it reuse the snapshot instead of re-transferring the ~1.5GB IDE archive on every
 * build. Parallel :test-integration runs are forbidden (root CLAUDE.md), so a shared
 * context dir is race-free.
 *
 * The derived image is built with `--build-arg BASE_IMAGE=<sha256>` so it
 * references the exact base image built in this JVM run, preventing collisions
 * when multiple test processes build the base image concurrently.
 */
fun buildIdeImage(dockerFileBase: String, ideArchive: File): ImageDriver {
    val resolvedBaseImageId = buildSharedBaseImage()
    // Context dir keyed by a SHA-256 hash of the archive PATH (constant across runs) so the
    // path stays stable and BuildKit reuses its local-context snapshot rather than
    // re-transferring the ~1.5GB IDE archive each build (see KDoc above).
    val contextKey = sha256Hex(ideArchive.absolutePath).take(16)
    val contextDir = prepareContext("docker-$dockerFileBase-$contextKey", "ide-base", dockerFileBase)

    // The ide-agent Dockerfile pre-bakes the test project's Gradle caches (wrapper dist +
    // dependencies) by running the REAL project build during the image build — stage the
    // project into the context for its COPY. Staged for every IDE flavor because the context
    // prep is shared; non-IDEA Dockerfiles simply never reference it (a few KB of context).
    IdeTestFolders.copyDockerFiles("test-project", File(contextDir, "test-project"))

    linkIdeArchive(contextDir, ideArchive)

    val imageId = buildDockerImage(
        logPrefix = "IDE",
        dockerfilePath = File(contextDir, "Dockerfile"),
        // 1800s, not 900: the once-per-day uncached build now includes the Gradle pre-bake
        // (wrapper distribution + kotlin-gradle-plugin + dependency downloads + a compile),
        // which on slow agent egress can add several minutes on top of the IDE extraction.
        timeoutSeconds = 1800,
        buildArgs = mapOf("BASE_IMAGE" to resolvedBaseImageId.imageId),
    )
    return imageId
}

fun buildDevrigImage(dockerFileBase: String, imageName: String) : ImageDriver {
    val resolvedBaseImageId = buildSharedBaseImage()
    // Derive a per-build context dir from the full image name.
    // Since imageName already carries a unique suffix (e.g. "ide-agent-test-a1b2c3d4"),
    // this guarantees each concurrent build gets its own isolated directory.
    val contextDir = prepareContext("docker-$imageName", "ide-base", dockerFileBase)

    val imageId = buildDockerImage(
        logPrefix = "devrig",
        dockerfilePath = File(contextDir, "Dockerfile"),
        timeoutSeconds = IDE_BASE_IMAGE_BUILD_TIMEOUT_SECONDS,
        buildArgs = mapOf("BASE_IMAGE" to resolvedBaseImageId.imageId),
    )

    return imageId
}

/**
 * How long the shared IDE base image may take to build.
 *
 * Sized for a COLD agent, and the number is the one the derived image already uses — keeping them
 * different was the whole defect. This layer installs six Temurin JDKs: 817 MB of archives, over a
 * minute of which is one 168 MB package. A warm agent skips all of it in seconds, so the value is
 * invisible there; a fresh TeamCity agent needs more than the fifteen minutes this used to allow, and
 * when it runs out `docker build` is killed mid-`apt-get` and the run reports
 * `Failed to build Docker image / Terminated by timeout` — which reads as a broken Dockerfile rather
 * than as a machine that started from nothing.
 *
 * That misreading has now cost two rounds of paid runs: twenty-five downstream cells of the
 * understanding-note experiment through the agent image (see `AGENT_IMAGE_BUILD_TIMEOUT_SECONDS`,
 * raised for exactly this), and an acquisition trajectory through THIS one, which spent nineteen
 * minutes downloading JDKs and never reached the research agent at all. Raising it cannot mask a
 * genuinely broken build: that still fails immediately with apt's or the compiler's own error.
 */
const val IDE_BASE_IMAGE_BUILD_TIMEOUT_SECONDS: Long = 1_800

fun buildSharedBaseImage(): ImageDriver {
    val baseContext = prepareContext("docker-ide-base", "ide-base")

    val rawImageId = buildDockerImage(
        logPrefix = "IDE",
        dockerfilePath = File(baseContext, "Dockerfile"),
        timeoutSeconds = IDE_BASE_IMAGE_BUILD_TIMEOUT_SECONDS,
    )
    //TODO: can be a problem if multiple builds run in parallel
    return rawImageId.tagDockerImage("mcp-steroid-base")
}

private fun prepareContext(contextName: String, vararg dockerContexts: String): File {
    val contextDir = File(IdeTestFolders.testOutputDir, contextName)
    contextDir.deleteRecursively()
    contextDir.mkdirs()
    println("[IDE-AGENT] Build context: $contextDir")
    dockerContexts.forEach {
        IdeTestFolders.copyDockerFiles(it, contextDir)
    }

    val topLevelFiles = contextDir.listFiles()
        ?.sortedBy { it.name }
        ?.joinToString("") { "\n - ${it.name}" + if (it.isDirectory) "/" else "" }
        ?: ""
    println("[IDE] Prepared context:$topLevelFiles")
    return contextDir
}

private fun linkIdeArchive(contextDir: File, ideArchive: File) {
    // Hard-link large IDE archive to avoid copying ~1GB file.
    // Falls back to copy if hard link fails (e.g. cross-filesystem).
    val ideDest = File(contextDir, "ide.tar.gz").toPath()
    if (ideDest.exists()) return

    try {
        createLink(ideDest, ideArchive.toPath())
    } catch (_: Exception) {
        println("[IDE-AGENT] Hard link failed, copying IDE archive...")
        copyRecursively(ideArchive, ideDest.toFile())
    }
}

private fun sha256Hex(input: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
