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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The semantic-ripple pilot: one cross-module rename on Keycloak, run in both arms.
 *
 * A sibling of [DpaiaScenarioBaseTest] rather than a subclass — that class loads its case from the
 * dpaia dataset and takes a whole-suite regression baseline, and neither applies here. Regression
 * evidence is the scoped compile gate instead, which for a rename is a complete invariant: a missed
 * call site is a compile error by construction.
 *
 * Reporting goes through [collectRunMetrics] and [writeArenaRunSummary], the same code the DPAIA
 * cases use, so the two tracks' numbers stay comparable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticRippleKeycloakRolesTest {

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = runArm("claude", withMcp = true)

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = runArm("claude", withMcp = false)

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = runArm("codex", withMcp = true)

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = runArm("codex", withMcp = false)

    private fun runArm(agentName: String, withMcp: Boolean) {
        val testCase = SemanticRippleCases.pilotCase()
        val modeLabel = if (withMcp) "mcp" else "none"
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "ripple-roles-$modeLabel",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = SemanticRippleSpec.cloneUrl,
                    repoOwnerAndName = SemanticRippleSpec.repoOwnerAndName,
                    baseCommit = testCase.baseCommit,
                    testPatch = testCase.testPatch,
                    displayName = testCase.instanceId,
                    buildSystem = testCase.buildSystem,
                ),
                aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE,
                mcpConnectionMode = if (withMcp) null else McpConnectionMode.None,
                mountDockerSocket = false,
            )).waitForProjectReady(
                timeoutMillis = SemanticRippleSpec.projectReadyTimeoutMs,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                // The patched tree deliberately does not compile: the hidden consumer calls a method
                // that does not exist yet. The prewarm build is a warm-up, never an assertion.
                requireCleanCompile = false,
            )

            val projectDir = session.intellijDriver.getGuestProjectDir()

            // Gold BEFORE the agent. The IDE runs in both arms — withMcp only controls whether the
            // AGENT may reach it — so the shell arm is measured without being given any access.
            val goldOutput = session.mcpSteroid.mcpExecuteCode(
                code = SemanticRippleOracleScripts.capture(),
                reason = "Capture the pre-agent resolved reference set for the semantic-ripple oracle",
                taskId = "semantic-ripple-gold",
                timeout = 900,
            ).stdout
            val gold = parseSemanticGold(goldOutput)
            gold.checkTripwires()
            println("[RIPPLE] gold: ${gold.totalReferences} references, ${gold.files} files, " +
                "${gold.decoyReferences.size} decoys")

            val verifier = ArenaVerifier(session.scope, projectDir, testCase.buildSystem)
            val preAgentSnapshot = verifier.snapshotTestFiles(testCase.testPatch)

            val runner = ArenaTestRunner(container = session.scope, projectGuestDir = projectDir)
            val result = runner.runTest(
                testCase = testCase,
                agent = when (agentName) {
                    "claude" -> session.aiAgents.claude
                    "codex" -> session.aiAgents.codex
                    else -> error("Unknown agent: $agentName")
                },
                withMcp = withMcp,
                timeoutSeconds = SemanticRippleSpec.agentTimeoutSeconds,
                predeployedProjectDir = projectDir,
                logDir = session.runDirInContainer,
            )

            val postOutput = session.mcpSteroid.mcpExecuteCode(
                code = SemanticRippleOracleScripts.postcondition(),
                reason = "Grade the post-agent semantic state for the semantic-ripple oracle",
                taskId = "semantic-ripple-post",
                timeout = 900,
            ).stdout
            val grade = parseSemanticPostcondition(postOutput, gold)

            // The layer that covers all 445 call sites: a site the agent missed still names a method
            // that no longer exists, so it cannot compile.
            val gate = runCompileGate(session.scope, projectDir)

            val verification = verifier.verify(
                failToPass = testCase.failToPass,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                testPatch = testCase.testPatch,
                preAgentSnapshot = preAgentSnapshot,
                baseline = FullSuiteSnapshot(perClass = emptyList(), mavenExitCode = 0),
            )

            val metrics = collectRunMetrics(
                runDir = session.runDirInContainer,
                agentName = agentName,
                fallbackStdout = result.agentResult.stdout,
            )
            val record = DpaiaScenarioBaseTest.RunRecord(
                instanceId = testCase.instanceId,
                agentName = agentName,
                withMcp = withMcp,
                agentDurationMs = result.agentDurationMs,
                prewarmMs = 0L,
                exitCode = result.agentResult.exitCode,
                claimedFix = result.evaluation.agentClaimedFix,
                usedMcpSteroid = result.evaluation.usedMcpSteroid,
                summary = result.evaluation.agentSummary,
                tokenUsage = metrics.tokenUsage,
                testMetrics = metrics.testMetrics,
                decodedLogMetrics = metrics.decodedLogMetrics,
                verification = verification,
                runDirPath = session.runDirInContainer.absolutePath,
            )
            writeArenaRunSummary(testCase.instanceId, agentName, modeLabel, record)

            println("[RIPPLE] ════════════════════════════════════════")
            println("[RIPPLE] $agentName+$modeLabel — ${testCase.instanceId}")
            println("[RIPPLE]   P1 no alias:     ${grade.p1NoAliasAndNewNameDeclared}")
            println("[RIPPLE]   P2 all sites:    ${grade.p2AllSitesConverted}")
            println("[RIPPLE]   P3 decoys kept:  ${grade.p3DecoysUnchanged}")
            println("[RIPPLE]   P4 conserved:    ${grade.p4Conserved}")
            println("[RIPPLE]   recall:          ${"%.4f".format(grade.recall)}")
            println("[RIPPLE]   precision:       ${"%.4f".format(grade.precision)}")
            println("[RIPPLE]   f1:              ${"%.4f".format(grade.f1)}")
            println("[RIPPLE]   missed sites:    ${grade.missedSites.size}")
            println("[RIPPLE]   over-reached:    ${grade.overReachedDecoys}")
            println("[RIPPLE]   compile gate:    ${if (gate.passed) "PASS" else "FAIL (exit ${gate.exitCode})"}")
            println("[RIPPLE]   verified FTP:    ${verification.classesPassed}/${verification.classesTotal}")
            println("[RIPPLE]   agent time:      ${record.agentDurationMs / 1000}s")
            val success = gate.passed && verification.objectiveSuccess && grade.allPassed
            println("[RIPPLE]   SUCCESS:         $success")
            println("[RIPPLE] ════════════════════════════════════════")
            if (!gate.passed) {
                println("[RIPPLE] compile gate tail:\n${gate.tail}")
            }

            // The run is a measurement, not a pass/fail on the agent's competence: a shell arm scoring
            // 0.0 recall is the expected positive-control outcome, not a broken test. Only an invalid
            // MEASUREMENT fails the test.
            assertTrue(!verification.failToPassTampered) {
                "[$agentName+$modeLabel] the agent modified the FAIL_TO_PASS file, so the grade measures " +
                    "tests it rewrote. Run invalid."
            }
            if (withMcp) {
                assertTrue(result.evaluation.usedMcpSteroid) {
                    "[$agentName+mcp] never called steroid_execute_code, so this is not an mcp-arm run"
                }
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
