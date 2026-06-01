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

    linkIdeArchive(contextDir, ideArchive)

    val imageId = buildDockerImage(
        logPrefix = "IDE",
        dockerfilePath = File(contextDir, "Dockerfile"),
        timeoutSeconds = 900,
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
        timeoutSeconds = 900,
        buildArgs = mapOf("BASE_IMAGE" to resolvedBaseImageId.imageId),
    )

    return imageId
}

fun buildSharedBaseImage(): ImageDriver {
    val baseContext = prepareContext("docker-ide-base", "ide-base")

    val rawImageId = buildDockerImage(
        logPrefix = "IDE",
        dockerfilePath = File(baseContext, "Dockerfile"),
        timeoutSeconds = 900,
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
