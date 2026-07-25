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
        containerSetup = { container ->
            fixDockerSocketPermissions(container)
            writeDockerJavaApiVersionProperties(container)
        },
    )
}

/**
 * Pin the docker-java client API version so Testcontainers can talk to Docker Engine 29+.
 *
 * Testcontainers (through at least 2.0.1) falls back to Docker API version 1.32 when none is configured
 * (DockerClientProviderStrategy.getClientForConfig), and Engine 29+ rejects API versions below its
 * minimum (1.44) with HTTP 400 — an empty /info payload on Docker Desktop — so NO client strategy
 * resolves and every Testcontainers test dies with "Could not find a valid Docker environment".
 * See https://github.com/testcontainers/testcontainers-java/issues/11212.
 *
 * The pin must go through `~/.docker-java.properties` (read by docker-java's
 * DefaultDockerClientConfig from the test JVM's user.home), NOT through a container env var: docker-java
 * only honors the literal env key "api.version", and Maven Surefire rebuilds its forked-JVM environment
 * dropping env names that are not valid shell identifiers — the dotted key never reaches the test JVM.
 * 1.44 is Engine 29's minimum and below every current daemon's maximum. Maintenance assumption: the 1.44
 * pin assumes the host Docker Engine is >= 25 (API max >= 1.44); bump the pin if engines' minimum API
 * ever exceeds 1.44.
 */
private fun writeDockerJavaApiVersionProperties(container: ContainerDriver) {
    val result = container.startProcessInContainer {
        this
            .args("bash", "-c", "printf 'api.version=1.44\\n' > \"${'$'}HOME/.docker-java.properties\"")
            .timeoutSeconds(10)
            .description("Pin docker-java API version for Testcontainers")
            .quietly()
    }.awaitForProcessFinish()
    require(result.exitCode == 0) {
        "Failed to write ~/.docker-java.properties: ${result.stderr}"
    }
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
