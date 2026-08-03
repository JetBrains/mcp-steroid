/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
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
    val init: Boolean = false,
    val quietly: Boolean = false,
    val timeout: Duration = Duration.ofMinutes(5),
    val reaperRegistration: Boolean = true,
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
    fun enableInit() = copy(init = true)
    fun timeout(timeout: Duration) = copy(timeout = timeout)
    fun quietly() = copy(quietly = true)

    /**
     * Opt out of the up-front [DockerReaper] name registration in [startDockerContainerAndForget].
     * Only the reaper's own container uses this — registering the reaper with itself would make it
     * kill itself mid-cleanup once the socket closes.
     */
    fun withoutReaperRegistration() = copy(reaperRegistration = false)
}

/**
 * The hostname a container uses to reach a service on the Docker host. Added as a host alias to every
 * container via `--add-host` below; reused wherever a host endpoint is rewritten for in-container use
 * (e.g. the agent LLM-gateway base URL — see AgentEndpoint).
 */
const val DOCKER_HOST_ALIAS = "host.docker.internal"

private val containerNameCounter = AtomicInteger()

/**
 * Run-scoped, collision-free container name. The random suffix keeps names unique across the
 * sequential Gradle test JVMs that share one [DockerReaper.testRunId] on a TC build (each JVM's
 * counter restarts at 1).
 */
private fun newTestContainerName(): String {
    val randomSuffix = ThreadLocalRandom.current().nextLong().toULong().toString(16)
    return "mcpsteroid-${DockerReaper.testRunId}-${containerNameCounter.incrementAndGet()}-$randomSuffix"
}

fun startDockerContainerAndForget(
    request: StartContainerRequest,
): ContainerDriver {
    val imageId = request.image ?: error("No image name")
    val logPrefix = request.logPrefix ?: error("No log prefix")

    // The name is generated and registered with DockerReaper BEFORE `docker run` so cleanup is
    // idempotent: `docker rm -f <name>` works whether or not the daemon ever created the container.
    // A timeout-killed `docker run` client returns no container ID, so ID-based-only cleanup can
    // never reach the daemon-side straggler (issue #412: one TC build logged 119 "Container
    // started" but at most 93 removals — the leaked full-IDE containers starved the daemon into
    // 5-minute `docker run` timeouts for every subsequent test).
    val containerName = newTestContainerName()
    if (request.reaperRegistration) {
        DockerReaper.registerContainerName(containerName)
    }

    val command = buildList {
        add("docker")
        add("run")
        add("-d")
        add("--name")
        add(containerName)
        // The per-test-run label lets DockerReaper.sweepTestRunContainers() reap a build's strays
        // without knowing their IDs or names.
        add("--label")
        add("${DockerReaper.TEST_RUN_LABEL}=${DockerReaper.testRunId}")
        if (request.autoRemove) add("--rm")
        if (request.init) add("--init")
        add("--add-host=$DOCKER_HOST_ALIAS:host-gateway")

        // NOTE: we deliberately do NOT pass --user $(id -u):$(id -g). Tried
        // that as "standard docker-run hygiene" but the ide-agent image and
        // agent-CLI images bake a user `agent` with uid 1000 and pre-populate
        // /home/agent/.fluxbox, /home/agent/.m2, etc. owned by that uid.
        // Forcing a different host uid (TC agent's uid 999) via --user made
        // every `mkdir -p /home/agent/.fluxbox` fail with EACCES inside the
        // container.
        //
        // Bind-mount write correctness is handled separately:
        //   * host runDir gets setWritable(…, ownerOnly=false) before being
        //     mounted at /mcp-run-dir (see intelliJ-factory.kt) so the
        //     container's uid-1000 user can write there regardless of who
        //     owns the dir on the host
        //   * git's dubious-ownership check on the read-only /repo-cache
        //     mount is suppressed via a `git config --global --add
        //     safe.directory <path>` call in GitDriver.cloneFromCachedBare()
        //
        // Re-introduce --user only when the image contract is rewritten to
        // support arbitrary runtime uids (e.g. by chmodding $HOME entries
        // in the Dockerfile to 0777 or using numeric USER).

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
            add(p.dockerPublishSpec())
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
        .awaitForProcessFinish()

    val containerId = result.stdout.trim()
    if (result.exitCode != 0 || containerId.isEmpty()) {
        // The daemon may materialize (or keep running) the container even though the client call
        // failed or was killed by timeout — remove it by the pre-generated name right away. If it
        // appears only later, the reaper registration above and the test-run label sweep collect it.
        forceRemoveContainerAfterFailedStart(logPrefix, containerName)
        throw IllegalStateException(
            "Failed to start Docker container (exit code ${result.exitCode}): ${result.stderr}"
        )
    }

    println("[$logPrefix] Container started: $containerId")
    return ContainerDriver(
        logPrefix = logPrefix,
        containerId = containerId,
        containerName = containerName,
        startRequest = request,
    )
}

private fun forceRemoveContainerAfterFailedStart(logPrefix: String, containerName: String) {
    val result = RunProcessRequest()
        .command("docker", "rm", "-f", containerName)
        .logPrefix(logPrefix)
        .description("Remove container $containerName after failed docker run")
        .timeoutSeconds(30)
        .quietly()
        .startProcess()
        .awaitForProcessFinish()

    when {
        result.exitCode == 0 ->
            println("[$logPrefix] Removed straggler container $containerName left by the failed docker run")
        result.stderr.contains("No such container") ->
            println("[$logPrefix] No straggler container $containerName after the failed docker run")
        else ->
            System.err.println(
                "[$logPrefix] Could not remove container $containerName after the failed docker run " +
                        "(exit code ${result.exitCode}): ${result.stderr.trim()} — " +
                        "DockerReaper and the test-run sweep remain responsible for it"
            )
    }
}
