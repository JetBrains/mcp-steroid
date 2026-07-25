/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Golden-path check for the arena verification oracle: deploy service-125 (dataset test patch +
 * local overlay), apply the dataset's REFERENCE fix, then require the verifier to see every
 * Docker-free FAIL_TO_PASS class green. IT classes are reported (and asserted once the
 * Testcontainers oracle works) — their raw failure output is the diagnostic for the Docker fix.
 */
class ArenaVerificationSmokeTest {

    private val instanceId = "dpaia__feature__service-125"

    /** Docker-free classes that MUST pass on the reference solution regardless of the Docker oracle. */
    private val dockerFreeClasses = setOf(
        "com.sivalabs.ft.features.domain.ReleaseStatusTransitionValidatorTest",
        "com.sivalabs.ft.features.config.ReleaseApiSecuritySliceTest",
    )

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `reference solution passes verification`() {
        val testCase = DpaiaDatasetLoader.findById(DpaiaScenarioBaseTest.dataset, instanceId)
        val caseConfig = DpaiaCuratedCases.CASE_CONFIGS.getValue(instanceId)
        val overlay = checkNotNull(javaClass.classLoader.getResourceAsStream(caseConfig.overlayTestPatch!!))
            .readBytes().decodeToString()
        val effective = testCase.copy(
            testPatch = testCase.testPatch + "\n" + overlay,
            failToPass = testCase.failToPass + caseConfig.overlayFailToPass,
        )

        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "arena-verify-smoke",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = effective.cloneUrl,
                    repoOwnerAndName = effective.repo.removeSuffix(".git"),
                    baseCommit = effective.baseCommit,
                    testPatch = effective.testPatch,
                    displayName = effective.instanceId,
                    buildSystem = effective.buildSystem,
                ),
                aiMode = AiMode.NONE,
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = true,
            )).waitForProjectReady(
                timeoutMillis = caseConfig.projectReadyTimeoutMs,
                projectJdkVersion = caseConfig.projectJdkVersion,
                buildSystem = BuildSystem.MAVEN,
                compileProject = false,
            )
            val projectDir = session.intellijDriver.getGuestProjectDir()

            // Apply the dataset's reference fix — the state every FAIL_TO_PASS class must pass in.
            GitDriver(session.scope).applyPatch(projectDir, effective.patch)

            val verifier = ArenaVerifier(session.scope, projectDir)
            val snapshot = verifier.snapshotTestFiles(effective.testPatch)
            val result = verifier.verify(
                failToPass = effective.failToPass,
                projectJdkVersion = caseConfig.projectJdkVersion,
                testPatch = effective.testPatch,
                preAgentSnapshot = snapshot,
            )

            println("[SMOKE] tampered=${result.testsTampered} rate=${result.failToPassRate}")
            result.perClass.forEach { println("[SMOKE]   ${it.className}: run=${it.testsRun} fail=${it.failures} err=${it.errors} passed=${it.passed}") }

            assertTrue(!result.testsTampered, "Reference patch must not count as tampering (it touches no test files)")
            val dockerFreeFailures = result.perClass.filter { it.className in dockerFreeClasses && !it.passed }
            assertTrue(dockerFreeFailures.isEmpty()) {
                "Docker-free oracle classes failed on the reference solution: $dockerFreeFailures"
            }
            // Tightened by the Docker-oracle task once Testcontainers works in the arena container:
            // assertTrue(result.failToPassRate == 1.0) { "All FAIL_TO_PASS must pass on the reference solution" }
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
