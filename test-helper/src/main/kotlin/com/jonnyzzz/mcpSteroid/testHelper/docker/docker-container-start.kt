/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.time.Duration
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach


@Suppress("DATA_CLASS_COPY_VISIBILITY_WILL_BE_CHANGED_WARNING", "DataClassPrivateConstructor")
data class StartContainerRequest private constructor(
    val image: String? = null,
    val logPrefix: String? = null,
    val extraEnvVars: Map<String, String> = emptyMap(),
    val volumes: List<ContainerVolume> = emptyList(),
    val ports: List<ContainerPort> = emptyList(),
    val entryPoint: List<String> = emptyList(),
    val autoRemove: Boolean = true,
    val quietly: Boolean = false,
    val timeout: Duration = Duration.ofMinutes(5),
) {
    companion object {
        operator fun invoke(): StartContainerRequest = StartContainerRequest()
    }

    fun logPrefix(logPrefix: String) = copy(logPrefix = logPrefix)
    fun image(image: String) = copy(image = image)
    fun image(image: ImageDriver) = copy(image = image.imageId, logPrefix = image.logPrefix)
    fun extraEnvVars(extraEnvVars: Map<String, String>) = copy(extraEnvVars = extraEnvVars)
    fun volumes(volumes: List<ContainerVolume>) = copy(volumes = volumes)
    fun volumes(vararg volumes: ContainerVolume) = volumes(volumes.asList())
    fun ports(ports: List<ContainerPort>) = copy(ports = ports)
    fun ports(vararg ports: ContainerPort) = ports(ports.asList())
    fun entryPoint(args: List<String>) = copy(entryPoint = args)
    fun entryPoint(vararg args: String) = entryPoint(args.toList())
    fun autoRemove(autoRemove: Boolean) = copy(autoRemove = autoRemove)
    fun timeout(timeout: Duration) = copy(timeout = timeout)
    fun quietly() = copy(quietly = true)
}

fun startDockerContainerAndForget(
    request: StartContainerRequest,
): ContainerDriver {
    val imageId = request.image ?: error("No image name")
    val logPrefix = request.logPrefix ?: error("No log prefix")

    val command = buildList {
        add("docker")
        add("run")
        add("-d")
        if (request.autoRemove) add("--rm")
        add("--add-host=host.docker.internal:host-gateway")

        // Run the container process as the host caller's uid:gid. This is the
        // standard `docker run --user $(id -u):$(id -g)` pattern — any file
        // the container writes to a bind-mounted host dir (e.g. /mcp-run-dir)
        // comes back out owned by the same user that started the test, so:
        //   * the host can clean up run-dir artifacts without root
        //   * git / package managers inside the container don't trip the
        //     "dubious ownership" / EACCES checks against bind-mounted
        //     volumes (repo-cache, m2, etc. are owned by this uid from the
        //     outside)
        // On Docker Desktop (macOS) the virtiofs VM remapped uids anyway so
        // the feature is visible only on Linux CI agents; setting it
        // unconditionally is safe because the Dockerfiles don't require
        // root at runtime.
        val uid = System.getProperty("test.integration.container.uid") ?: userToUid()
        val gid = System.getProperty("test.integration.container.gid") ?: userToGid()
        if (uid != null && gid != null) {
            add("--user")
            add("$uid:$gid")
        }

        request.extraEnvVars.forEach { (key, value) ->
            add("-e")
            add("$key=$value")
        }

        request.volumes.forEach { v ->
            add("-v")
            add("${v.host.absolutePath}:${v.guest}:${v.mode}")
        }

        request.ports.forEach { p ->
            add("-p")
            add("0:${p.containerPort}")
        }

        add(imageId)

        // Add container command if specified
        addAll(request.entryPoint)
    }

    val result = RunProcessRequest()
        .command(command)
        .logPrefix(logPrefix)
        .description("Start container from $imageId with ${request.entryPoint}")
        .withTimeout(request.timeout)
        .quietly(request.quietly)
        .startProcess()
        .assertExitCode(0) {
            "Failed to start Docker container: $stderr"
        }

    val containerId = result.stdout.trim()
    if (containerId.isEmpty()) {
        throw IllegalStateException("Failed to start Docker container: ${result.stderr}")
    }

    println("[$logPrefix] Container started: $containerId")
    return ContainerDriver(
        logPrefix = logPrefix,
        containerId = containerId,
        startRequest = request,
    )
}

/** Host uid as a string, or null if it can't be determined (non-POSIX JVM). */
private fun userToUid(): String? = try {
    ProcessBuilder("id", "-u").redirectErrorStream(true).start().let { p ->
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.isNotEmpty() && out.all { it.isDigit() }) out else null
    }
} catch (e: Exception) {
    null
}

/** Host gid as a string, or null if it can't be determined. */
private fun userToGid(): String? = try {
    ProcessBuilder("id", "-g").redirectErrorStream(true).start().let { p ->
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.isNotEmpty() && out.all { it.isDigit() }) out else null
    }
} catch (e: Exception) {
    null
}
