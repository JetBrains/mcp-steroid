/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.AgentProgressOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess

/**
 * Result from running an AI agent process.
 * Contains both filtered (human-readable) output and raw (NDJSON) output.
 */
class AiProcessResult(
    override val exitCode: Int?,
    override val stdout: String,
    override val stderr: String,
    /** Raw unfiltered stdout (NDJSON) before output filter was applied */
    val rawStdout: String,
) : ProcessResult {
    override fun toString(): String =
        "AiProcessResult(exitCode=$exitCode, stdout=${stdout.take(500)}, stderr=${stderr.take(500)})"
}

abstract class AIContainerBase(
    private val session: ContainerDriver,
    private val apiKey: String,
    private val debug: Boolean = false,
    private val workdirInContainer: String,
    override val displayName: String
) : AiAgentSession {
//     = this.ComCompanion.displayName


}

abstract class AIAgentCompanion<T : Any>(val dockerFileBase: String) {
    abstract val displayName: String
    abstract val outputFilter: AgentProgressOutputFilter

    /**
     * Returns the API key for this agent, or `null` if it cannot be found.
     * Each subclass checks env vars and well-known key files.
     */
    protected abstract fun readApiKey(): String?

    /** Human-readable description of where the key can come from (for error/skip messages). */
    protected abstract val apiKeyHint: String

    private fun requireApiKey(): String {
        val key = readApiKey()

        // Reject unresolved TeamCity credential references (%credentialsJSON:...%)
        // which look non-blank but are not actual API keys.
        if (key != null && !key.startsWith("%")) {
            return key
        }

        val message = if (key != null) {
            "$displayName API key is an unresolved TeamCity reference ($apiKeyHint)"
        } else {
            "$displayName API key not found ($apiKeyHint)"
        }

        // On TeamCity, skip the test instead of failing the build.
        // TestAbortedException works for JUnit 5 (DockerProgressTests in test-helper:test).
        // For JUnit 4 (CliIntegrationTests in ij-plugin:integrationTest), the Vintage engine
        // on JUnit Platform also recognizes it. The only case where it doesn't work is
        // pure useJUnit() without the Platform — but ciIntegrationTests runs both tasks
        // independently, so each uses its own test framework.
        if (System.getenv("TEAMCITY_VERSION") != null) {
            throw org.opentest4j.TestAbortedException(message)
        }

        error(message)
    }

    fun create(lifetime: CloseableStack): T {
        val dockerfilePath = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-helper/src/main/docker/$dockerFileBase/Dockerfile")
            .toFile()
        require(dockerfilePath.isFile) { "Docker file $dockerfilePath must exist" }

        val imageId = buildDockerImage(
            logPrefix = dockerFileBase.uppercase(),
            dockerfilePath,
            timeoutSeconds = 600,
        )

        val session = startDockerContainerAndDispose(lifetime,
            StartContainerRequest()
                .image(imageId)
        )

        return create(session)
    }

    fun create(session: ContainerDriver): T {
        println("[DOCKER-${dockerFileBase.uppercase()}] Session created in container")
        val apiKey = requireApiKey()
        return createImpl(session, apiKey)
    }

    fun StartedProcess.toAiStartedProcess(): AiStartedProcess {
        return object: AiStartedProcess, StartedProcess by this@toAiStartedProcess {
            override val outputFilter: AgentProgressOutputFilter
                get() = this@AIAgentCompanion.outputFilter

            override fun awaitForProcessFinish(): AiProcessResult {
                val rawResult = this@toAiStartedProcess.awaitForProcessFinish()

                return AiProcessResult(
                    exitCode = rawResult.exitCode ?: error("Process ${this@toAiStartedProcess} finished with exit code ${rawResult.exitCode}"),
                    stdout = this.outputFilter.filterText(rawResult.stdout),
                    stderr = rawResult.stderr,
                    rawStdout = rawResult.stdout,
                )
            }

            override fun toString(): String {
                return "$displayName-${this@toAiStartedProcess}"
            }
        }
    }


    protected abstract fun createImpl(session: ContainerDriver, apiKey: String): T
}
