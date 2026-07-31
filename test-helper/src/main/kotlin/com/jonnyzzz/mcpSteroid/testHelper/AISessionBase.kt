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
import java.io.File

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

    override fun registerDevrigMcp(installDir: File, mcpName: String) {
        registerStdioMcp(session.installDevrigMcp(installDir), mcpName)
    }
}

abstract class AIAgentCompanion<T : Any>(val dockerFileBase: String) {
    abstract val displayName: String
    abstract val outputFilter: AgentProgressOutputFilter

    /**
     * Returns the API key for this agent, or `null` if it cannot be found.
     * Each subclass checks env vars and well-known key files.
     */
    protected abstract fun readApiKey(): String?

    /**
     * `true` when [readApiKey] returns a real key (not `null`, not an unresolved
     * `%credentialsJSON:…%` TeamCity reference). NOTE: this treats the unresolved-TC-ref
     * case the same as a missing key, so it must NOT gate `UsefulTestCase.shouldRunTest()` —
     * that would silently swallow a real TC credentials misconfiguration. Use
     * [skipTestBecauseApiKeyMissing] for the JUnit 3 skip hook instead.
     */
    fun isApiKeyAvailable(): Boolean {
        val key = readApiKey() ?: return false
        return !key.startsWith("%")
    }

    /**
     * The predicate JUnit 3 / `BasePlatformTestCase` tests must consult from
     * `UsefulTestCase.shouldRunTest()`: `true` only when this companion opts into
     * [skipTestWhenKeyMissing] AND [readApiKey] found nothing.
     *
     * Why `shouldRunTest()` and not the [requireApiKey] assumption: the JUnit 3↔4 bridge
     * (`JUnit38ClassRunner.OldTestClassAdaptingListener.addError`, still true in JUnit 4.13.2)
     * routes EVERY `Throwable` — including `AssumptionViolatedException` — to
     * `fireTestFailure`, so an assumption thrown inside a `BasePlatformTestCase` test body
     * is reported as a FAILURE on CI. `UsefulTestCase.runBare()` checking `shouldRunTest()`
     * is the platform's only not-fail hook on that path (the JUnit 3 runner then reports
     * the test as passed-without-running; JUnit 4/5 callers keep getting the real
     * `AssumptionViolatedException` from [requireApiKey], reported as ignored).
     *
     * Deliberately `false` for an unresolved `%credentialsJSON:…%` reference: that test
     * must still run and fail hard in [requireApiKey] with `IllegalStateException` —
     * a TeamCity credentials misconfiguration must stay visible.
     */
    fun skipTestBecauseApiKeyMissing(): Boolean =
        skipTestWhenKeyMissing && readApiKey() == null

    /** Human-readable description of where the key can come from (for error/skip messages). */
    protected abstract val apiKeyHint: String

    /**
     * Documented exception to the CLAUDE.md "no test-level skips" rule. When `true`
     * and [readApiKey] returns `null`, [requireApiKey] reports the test as **ignored**
     * (via JUnit's [org.junit.AssumptionViolatedException], honored by JUnit 4/5
     * runners). JUnit 3 / `BasePlatformTestCase` tests do NOT get the skip from the
     * assumption — the JUnit 3↔4 bridge turns it into a failure — and must additionally
     * gate `shouldRunTest()` on [skipTestBecauseApiKeyMissing] (see its kdoc).
     * Default `false` preserves fail-fast for agents whose keys are configured on CI;
     * only Gemini opts in because the TeamCity server has no Gemini token and there is no
     * plan to add one.
     *
     * The unresolved-TC-reference branch (`%credentialsJSON:…%`) still throws
     * regardless of this flag — that case is a real TC misconfiguration that
     * must stay visible.
     */
    protected open val skipTestWhenKeyMissing: Boolean = false

    private fun requireApiKey(): String {
        val key = readApiKey()

        // Reject unresolved TeamCity credential references (%credentialsJSON:...%)
        // which look non-blank but are not actual API keys.
        if (key != null && !key.startsWith("%")) {
            return key
        }

        val isUnresolvedTcRef = key != null
        val message = if (isUnresolvedTcRef) {
            "$displayName API key is an unresolved TeamCity reference ($apiKeyHint)"
        } else {
            "$displayName API key not found ($apiKeyHint)"
        }

        if (skipTestWhenKeyMissing && !isUnresolvedTcRef) {
            // Opt-in skip — see [skipTestWhenKeyMissing] kdoc. Recognised by
            // JUnit 4/5 runners, which report the result as ignored rather than
            // failed. JUnit 3 / BasePlatformTestCase never reaches this branch
            // when wired correctly: shouldRunTest() consults
            // skipTestBecauseApiKeyMissing() first (the JUnit 3 bridge would
            // report this assumption as a failure).
            throw org.junit.AssumptionViolatedException(message)
        }

        // Fail-fast default. Earlier revisions threw TestAbortedException /
        // Assume.assumeTrue unconditionally; that masked a real TC credentials
        // misconfiguration as "0 failed, 20 ignored" for weeks. The
        // unresolved-TC-ref branch keeps that lesson — don't loosen it.
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
