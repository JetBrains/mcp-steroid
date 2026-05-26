/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.io.File

fun ContainerDriver.mkdirs(guestPath: String): ProcessResult {
    return startProcessInContainer {
        this
            .args("mkdir", "-p", guestPath)
            .description("Create directory $guestPath in the container")
            .quietly()
    }.awaitForProcessFinish()
}

fun ContainerDriver.copyFromContainer(containerPath: String, localPath: File) {
    localPath.parentFile?.mkdirs()
    newRunOnHost()
        .command("docker", "cp", "$containerId:$containerPath", localPath.absolutePath)
        .description("Copy container:$containerPath to ${localPath.name}")
        .timeoutSeconds(300L)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to copy from container: $containerPath to $localPath: $stderr" }
}

fun ContainerDriver.copyToContainer(localPath: File, containerPath: String) {
    require(localPath.exists()) { "Local path does not exist: $localPath" }
    newRunOnHost()
        .command("docker", "cp", localPath.absolutePath, "$containerId:$containerPath")
        .description("Copy ${localPath.name} to container:$containerPath")
        .timeoutSeconds(120L)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to copy to container: $localPath: $stderr" }
}

fun ContainerDriver.writeFileInContainer(
    containerPath: String,
    content: String,
    executable: Boolean = false,
) {
    val parentDir = containerPath.substringBeforeLast('/')
    if (parentDir.isNotEmpty()) {
        mkdirs(parentDir).assertExitCode(0) { "Failed to create directory in container $parentDir: $stderr" }
    }

    startProcessInContainer {
        this
            .args("bash", "-c", "cat > ${shellQuote(containerPath)} << 'FILE_EOF'\n$content\nFILE_EOF")
            .description("Write content to $containerPath")
            .timeoutSeconds(5)
            .quietly()
    }.assertExitCode(0) { "Failed to write content in container to $containerPath: $content: $stderr" }

    if (executable) {
        startProcessInContainer {
            this
                .args("chmod", "+x", containerPath)
                .description("chmod +x $containerPath")
                .timeoutSeconds(5)
                .quietly()
        }.assertExitCode(0) { "Failed to chmod the created file in container to $containerPath: $stderr" }
    }
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
