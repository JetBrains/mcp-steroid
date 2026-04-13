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
 * The build context directory is derived from [imageName], so parallel calls
 * with different image names (which include a unique suffix from the caller)
 * each get their own isolated context directory — no races.
 *
 * The derived image is built with `--build-arg BASE_IMAGE=<sha256>` so it
 * references the exact base image built in this JVM run, preventing collisions
 * when multiple test processes build the base image concurrently.
 */
fun buildIdeImage(dockerFileBase: String, imageName: String, ideArchive: File): ImageDriver {
    val resolvedBaseImageId = buildSharedBaseImage()
    // Derive a per-build context dir from the full image name.
    // Since imageName already carries a unique suffix (e.g. "ide-agent-test-a1b2c3d4"),
    // this guarantees each concurrent build gets its own isolated directory.
    val contextDir = prepareContext("docker-$imageName", "ide-base", dockerFileBase)

    linkIdeArchive(contextDir, ideArchive)

    val imageId = buildDockerImage(
        logPrefix = "IDE",
        dockerfilePath = File(contextDir, "Dockerfile"),
        timeoutSeconds = 900,
        buildArgs = mapOf("BASE_IMAGE" to resolvedBaseImageId.imageId),
    )
    return imageId
}

private fun buildSharedBaseImage(): ImageDriver {
    val baseContext = prepareContext("docker-ide-base", "ide-base")

    val rawImageId = buildDockerImage(
        logPrefix = "IDE",
        dockerfilePath = File(baseContext, "Dockerfile"),
        timeoutSeconds = 900,
    )
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
        ideArchive.copyTo(File(contextDir, "ide.tar.gz"), overwrite = true)
    }
}
