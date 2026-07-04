/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess

data class ContainerPort(
    val containerPort: Int,
    /**
     * When set, this entry publishes the inclusive container-port range [containerPort]..[endPort] in a
     * single `docker run -p` flag (each container port gets a random host port), instead of one flag per
     * port. Used for devrig's JDWP debug range (23900-23999).
     */
    val endPort: Int? = null,
) {
    /** The `docker run -p <spec>` value: a random host port per container port. */
    fun dockerPublishSpec(): String = if (endPort == null) "0:$containerPort" else "$containerPort-$endPort"
}

/**
 * Query the host port mapped to a container port.
 * Docker output format: "0.0.0.0:52134" or "[::]:52134"
 *
 * Retries up to 5 times with 500 ms delay — on Windows Docker Desktop the
 * port mapping may not be immediately queryable right after container start.
 */
fun ContainerDriver.mapGuestPortToHostPort(containerPort: ContainerPort): Int {
    var lastResult: ProcessResult? = null
    repeat(5) { attempt ->
        if (attempt > 0) Thread.sleep(500)

        val result = newRunOnHost()
            .command("docker", "port", containerId, "${containerPort.containerPort}/tcp")
            .description("Query host port for $containerPort")
            .timeoutSeconds(5)
            .quietly()
            .startProcess()
            .awaitForProcessFinish()

        lastResult = result
        if (result.exitCode == 0) {
            val port = parseMappedPortOutput(result.stdout)
            if (port != null) return port
        }
    }

    error("Failed to map container port $containerPort for $containerIdForLog after 5 attempts. $lastResult")
}

internal fun parseMappedPortOutput(stdout: String): Int? {
    return stdout
        .lineSequence()
        .map { it.trim() }
        .firstNotNullOfOrNull { line ->
            line.takeIf { it.isNotBlank() }?.substringAfterLast(':')?.toIntOrNull()
        }
}


/**
 * Query bridge-network container IP (for example 172.17.x.x).
 * Returns null when inspect output does not contain an address.
 */
fun ContainerDriver.queryContainerIp(): String? {
    val result = newRunOnHost()
        .command(
            "docker",
            "inspect",
            "-f",
            "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}",
            containerId
        )
        .description("Query container IP for $containerIdForLog")
        .timeoutSeconds(5)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to query container IP for $containerId: $stderr" }

    return result.stdout
        .trim()
        .split(Regex("\\s+"))
        .firstOrNull { it.isNotBlank() }
}
