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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Proves the two GRADING layers work on the unmodified patched tree, with no agent involved.
 *
 * Its whole reason to exist is that build 1028521545 spent two full agent runs to discover that
 * neither layer could execute: the compile gate selected its modules with colon-less `-pl` tokens,
 * which Maven reads as directory paths, and the FAIL_TO_PASS grading ran the whole reactor, where
 * `keycloak-quarkus-dist` cannot resolve a `:zip` artifact that only the `distribution` profile
 * builds. Both are properties of Keycloak's build, not of any agent, and both are visible in one
 * agentless container run.
 *
 * This is the positive control for a rename task, so the two layers are expected to disagree. Nothing
 * has been renamed here, and an untouched tree is self-consistent, so the gate must PASS: it detects a
 * PARTIAL rename, never the absence of one. The consumer, on the other hand, asks by reflection for a
 * method that does not exist yet, so it must RUN and FAIL. A gate that fails here, or a consumer that
 * passes, means the layer is measuring something other than the task.
 */
class SemanticRippleGradingProbeTest {

    @Test
    @Timeout(value = 150, unit = TimeUnit.MINUTES)
    fun `the compile gate and the scoped grading run both execute on the patched tree`() {
        val testCase = SemanticRippleCases.pilotCase()
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "ripple-grading-probe",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = SemanticRippleSpec.cloneUrl,
                    repoOwnerAndName = SemanticRippleSpec.repoOwnerAndName,
                    baseCommit = testCase.baseCommit,
                    testPatch = testCase.testPatch,
                    displayName = testCase.instanceId,
                    buildSystem = testCase.buildSystem,
                ),
                aiMode = AiMode.NONE,
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = false,
            )).waitForProjectReady(
                timeoutMillis = SemanticRippleSpec.projectReadyTimeoutMs,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                requireCleanCompile = false,
            )
            val projectDir = session.intellijDriver.getGuestProjectDir()

            // Installs the reactor and requires the gate to pass on the untouched tree — the same call
            // every real arm makes, so this probe exercises the production path rather than a copy.
            prepareAndProveGateEnvironment(session.scope, projectDir)

            val gate = runCompileGate(session.scope, projectDir)
            println("[RIPPLE-PROBE] compile gate exit=${gate.exitCode}\n${gate.tail}")
            assertFalse(gate.tail.contains("Could not find the selected project in the reactor")) {
                "The gate's -pl selectors do not resolve, so it graded nothing:\n${gate.tail}"
            }
            assertTrue(gate.passed) {
                "The gate FAILED on an untouched tree, so it is not a rename gate — it would report a " +
                    "missed call site for every run whatever the agent did:\n${gate.tail}"
            }

            val verifier = ArenaVerifier(session.scope, projectDir, testCase.buildSystem)
            verifier.normalizeFormattingBeforeSnapshot(SemanticRippleSpec.projectJdkVersion)
            val verification = verifier.verify(
                failToPass = testCase.failToPass,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                testPatch = testCase.testPatch,
                preAgentSnapshot = verifier.snapshotTestFiles(testCase.testPatch),
                mavenProjectSelector = SemanticRippleSpec.gradingScopeSelector,
            )

            // The consumer must be REACHED and fail on its own terms. `verify` throws rather than
            // returning when the reactor stopped somewhere else, so arriving here at all is half the
            // result; the other half is that nothing was charged to a nonexistent agent.
            assertEquals(1, verification.classesTotal)
            assertEquals(0, verification.classesPassed) {
                "The consumer passed without the rename — it is not pinning the new name at all"
            }
            assertTrue(verification.perClass.single().testsRun > 0) {
                "The consumer produced no surefire report, so it did not run at all — a zero here is a " +
                    "harness fault dressed up as a failed fix: ${verification.perClass}"
            }
            assertFalse(verification.failToPassTampered) {
                "No agent ran, so a tamper flag here means the pre-agent snapshot is taken after " +
                    "something already rewrote the file — the formatter normalization is not working"
            }
            assertTrue(verification.regressions.isEmpty()) { "${verification.regressions}" }
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
