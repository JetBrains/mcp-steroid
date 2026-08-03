/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createTempFile

data class ImageDriver(
    val imageId: String,
    val logPrefix: String,
    /**
     * Combined stdout+stderr of the `docker build` that produced this image (empty for images
     * obtained without a build, e.g. `docker commit`). [buildDockerImage] forces
     * `--progress=plain`, so tests can assert on BuildKit step markers (`#<id> CACHED`)
     * instead of flaky wall-clock timings.
     */
    val buildOutput: String = "",
) {
    val imageIdToLog get() = imageId.take(10)

    val imageSha256 get() = "sha256:$imageId"

    override fun toString(): String {
        return "ImageDriver(imageId='$imageIdToLog', logPrefix='$logPrefix')"
    }

    fun newRunProcessRequest() = RunProcessRequest()
        .logPrefix(logPrefix)
}

/**
 * Create a Docker image snapshot from a running container.
 *
 * @param imageTag optional target tag (e.g. "mcp-steroid-indexed:latest")
 * @return [ImageDriver] referencing the committed image ID
 */
fun ContainerDriver.commitContainerToImage(imageTag: String? = null): ImageDriver {
    val command = buildList {
        add("docker")
        add("commit")
        add(containerId)
        if (imageTag != null) {
            add(imageTag)
        }
    }

    val result = newRunOnHost()
        .command(command)
        .description(
            if (imageTag != null) "Commit container $containerIdForLog as $imageTag"
            else "Commit container $containerIdForLog",
        )
        .timeoutSeconds(120L)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to commit container $containerIdForLog: $stderr" }

    val rawId = result.stdout.trim()
    require(rawId.isNotBlank()) { "docker commit returned empty image ID for $containerIdForLog" }
    val normalizedId = rawId.removePrefix("sha256:").trim()

    println("[$logPrefix] Snapshot image created: $rawId${if (imageTag != null) " ($imageTag)" else ""}")
    return ImageDriver(imageId = normalizedId, logPrefix = logPrefix)
}

/**
 * CACHE_BUST drives the "daily refresh boundary" in the agent-CLI Dockerfiles (ide-base):
 * layers below it (the `npm install -g` agent-CLI steps) rebuild whenever the token changes.
 * Pinned ONCE per JVM — not re-read on every [buildDockerImage] call — so a test run that
 * crosses midnight keeps a single token for its whole lifetime; otherwise the date flip
 * invalidates every already-built image mid-run and the incremental-build guarantees
 * (and image reuse between tests) silently break. A fresh run still picks up the new date,
 * preserving the once-per-day agent-CLI refresh.
 */
private val cacheBustToken: String = DateTimeFormatter.ISO_DATE.format(LocalDateTime.now())

/**
 * Build a Docker image and return its content-addressable image ID (sha256:...).
 *
 * @param buildArgs Extra `--build-arg KEY=VALUE` entries (e.g. `BASE_IMAGE` for derived images)
 * @return The image ID in `sha256:<hex>` format
 */
fun buildDockerImage(
    logPrefix: String,
    dockerfilePath: File,
    timeoutSeconds: Long,
    buildArgs: Map<String, String> = emptyMap(),
    quietly: Boolean = false,
): ImageDriver {
    require(dockerfilePath.exists() && dockerfilePath.isFile) {
        "File does not exist: $dockerfilePath"
    }

    val iidFile = createTempFile("docker-iid", ".txt").toFile()
    try {
        val command = buildList {
            add("docker")
            add("build")
            add("--iidfile")
            add(iidFile.absolutePath)
            // Plain BuildKit progress renders one deterministic `#<id> ...` line per step regardless
            // of TTY, so [ImageDriver.buildOutput] is machine-parseable (incremental-build tests
            // assert on the `#<id> CACHED` markers).
            add("--progress=plain")

            for ((k, v) in buildArgs + ("CACHE_BUST" to cacheBustToken)) {
                add("--build-arg")
                add("$k=$v")
            }

            add(".")
        }

        val buildResult = RunProcessRequest()
            .logPrefix(logPrefix)
            .command(command)
            .description("Build Docker image $dockerfilePath")
            .workingDir(dockerfilePath.parentFile)
            .timeoutSeconds(timeoutSeconds)
            .quietly(quietly)
            .startProcess()
            .assertExitCode(0) { "Failed to build Docker image.\n$stderr" }

        val imageId = iidFile.readText().trim()
        require(imageId.startsWith("sha256:")) {
            "Unexpected image ID format from --iidfile: $imageId"
        }

        println("[$logPrefix] Docker image built $imageId")
        return ImageDriver(
            imageId = imageId.removePrefix("sha256:").trim(),
            logPrefix = logPrefix,
            // BuildKit writes progress to stderr; keep stdout too for legacy-builder output.
            buildOutput = buildResult.stdout + "\n" + buildResult.stderr,
        )
    } finally {
        iidFile.delete()
    }
}


/**
 * Tag an existing Docker image with a new name.
 *
 * @param tag Target tag (e.g. `mcp-steroid-ide-base-test:latest`)
 */
fun ImageDriver.tagDockerImage(tag: String) : ImageDriver {
    newRunProcessRequest()
        .command("docker", "tag", imageSha256, tag)
        .description("Tag Docker image $imageIdToLog as $tag")
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to tag Docker image $imageIdToLog as $tag: $stderr" }

    println("Tagged image $imageId → $tag")
    return copy(imageId = tag)
}
