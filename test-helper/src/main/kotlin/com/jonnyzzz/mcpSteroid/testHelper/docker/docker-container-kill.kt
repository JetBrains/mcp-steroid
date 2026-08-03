/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess

fun ContainerDriver.killContainer() {
    val killResult = newRunOnHost()
        .command("docker", "kill", containerId)
        .description("kill container $containerIdForLog")
        .timeoutSeconds(10)
        .quietly()
        .startProcess()
        .awaitForProcessFinish()

    if (killResult.exitCode == 0 && startRequest.autoRemove) {
        // A `--rm` container is removed by the daemon once the kill lands.
        log("Container $containerIdForLog removed")
        return
    }

    // Either `docker kill` failed (container already exited and auto-removed, or a starved daemon
    // did not deliver the signal within the timeout) or the container is not `--rm` and survives
    // the kill. `docker rm -f` kills and removes in one idempotent call. The outcome must be
    // reported honestly: an earlier unconditional "Container removed" log hid real leaks
    // (issue #412: one TC build logged 119 "Container started" but at most 93 removals).
    val rmResult = newRunOnHost()
        .command("docker", "rm", "-f", containerId)
        .description("force-remove container $containerIdForLog")
        .timeoutSeconds(30)
        .quietly()
        .startProcess()
        .awaitForProcessFinish()

    when {
        rmResult.exitCode == 0 ->
            log("Container $containerIdForLog removed (docker rm -f; docker kill exit code ${killResult.exitCode})")
        rmResult.stderr.contains("No such container") ->
            log("Container $containerIdForLog already removed (docker kill exit code ${killResult.exitCode})")
        else -> {
            log("Failed to remove container $containerIdForLog — leak candidate")
            System.err.println(
                "[$logPrefix] Failed to remove container $containerIdForLog: " +
                        "docker kill exit code ${killResult.exitCode} (${killResult.stderr.trim()}), " +
                        "docker rm -f exit code ${rmResult.exitCode} (${rmResult.stderr.trim()}) — " +
                        "DockerReaper and the test-run sweep remain responsible for it"
            )
        }
    }
}
