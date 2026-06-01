package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import java.io.File


class HostMappingsInfo(
    val volumes: List<ContainerVolume>,
    val envOverride: Map<String, String>,
    val containerSetup: (ContainerDriver) -> Unit,
) {
    fun applyToContainer(container: ContainerDriver, lifetime: CloseableStack) {
        containerSetup(container)
    }
}

/**
 * Host → container mappings, driven directly by [IntelliJContainerOpts].
 *
 * Only the **Docker socket** is supported. The former host-credential forwarding — SSH agent,
 * `~/.netrc`, `~/.m2/settings.xml`, JetBrains tokens, the private-packages auth cache, and JB_SPACE
 * env credentials — was removed as **insecure** (it copied host secrets into the container wholesale).
 * Reintroduce any of it later behind a proper, opt-in, least-privilege mechanism, as its own dedicated
 * function (mirroring [dockerSocketMapping]).
 */
fun setupHostMappings(opts: IntelliJContainerOpts): HostMappingsInfo =
    dockerSocketMapping(mount = opts.mountDockerSocket)

/**
 * Mount the host Docker socket (`/var/run/docker.sock`) so Testcontainers-based tests can start sibling
 * containers on the host daemon. No-op when [mount] is false.
 */
private fun dockerSocketMapping(mount: Boolean): HostMappingsInfo {
    if (!mount) return HostMappingsInfo(emptyList(), emptyMap(), {})

    val dockerSocketFile = File("/var/run/docker.sock")
    require(dockerSocketFile.exists()) {
        "mountDockerSocket=true but Docker socket not found at ${dockerSocketFile.absolutePath}. " +
            "Ensure Docker is running on the host."
    }
    println("[IDE-AGENT] Docker socket mount enabled: ${dockerSocketFile.absolutePath}")

    return HostMappingsInfo(
        volumes = listOf(ContainerVolume(dockerSocketFile, "/var/run/docker.sock", "rw")),
        // Testcontainers creates containers on the HOST daemon via the mounted socket; their ports live on
        // the host network. host.docker.internal (added via --add-host in docker-container-start.kt)
        // bridges back from this container, so Testcontainers connects to the host gateway, not itself.
        envOverride = mapOf("TESTCONTAINERS_HOST_OVERRIDE" to "host.docker.internal"),
        containerSetup = ::fixDockerSocketPermissions,
    )
}

/**
 * The host socket's GID won't match the container's `docker` group GID (created by `groupadd` without a
 * fixed GID), so `chmod 666` makes it usable by the agent user.
 */
private fun fixDockerSocketPermissions(container: ContainerDriver) {
    val result = container.startProcessInContainer {
        this
            .user("0:0")
            .args(
                "bash", "-lc",
                """
                set -euo pipefail
                if [ ! -S "/var/run/docker.sock" ]; then
                  echo "Mounted Docker socket is missing: /var/run/docker.sock" >&2
                  exit 1
                fi
                chmod 666 /var/run/docker.sock
                """.trimIndent()
            )
            .timeoutSeconds(10)
            .description("Fix docker socket permissions for TestContainers")
            .quietly()
    }.awaitForProcessFinish()
    require(result.exitCode == 0) {
        "Failed to fix docker socket permissions: ${result.stderr}"
    }
}
