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
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The rename experiment of the keycloak-semantic family: one cross-module rename on Keycloak, run in
 * both arms of a single agent.
 *
 * There is a second, pre-existing class named `KeycloakRenameTest` in
 * `com.jonnyzzz.mcpSteroid.integration.tests` — do not confuse the two. That class scores from the
 * agent's self-reported markers, and its prompt names the refactoring API the agent is expected to use.
 * This class instead grades a PSI post-condition captured before and after the run — alias left behind,
 * missed call sites, over-reach onto a same-named method of another type, and reference conservation —
 * backed by a scoped compile gate and a hidden reflection consumer, and its prompt deliberately does
 * NOT name the mechanism the agent must use to satisfy that consumer.
 *
 * The TeamCity config for this family runs `-PtestFilter=*KeycloakRenameRippleTest.<agent>*`, which
 * glob-matches this class's `<agent> with mcp` and `<agent> without mcp` methods for a given agent. The
 * task specification (repo, base commit, patch, target case) lives in [SemanticRippleSpec] and
 * [SemanticRippleCases]; the oracle that grades pre/post semantic state lives in [SemanticRippleOracle]
 * and [SemanticRippleOracleScripts].
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
class KeycloakRenameRippleTest {

    // The agent's own budget is 90 min (SemanticRippleSpec.agentTimeoutSeconds). A cold CI agent
    // additionally pays Docker image build (measured 34 min for the image build alone on a developer
    // machine) plus a cold Keycloak clone and Maven import. After the agent returns, grading adds the
    // post-condition query, the scoped compile gate, and the FAIL_TO_PASS verification. 90 + 34 min of
    // fixed setup plus grading overhead already exceeds 124 minutes before any headroom; 180 minutes
    // covers that with headroom, and the TeamCity cap for a two-arm build is derived from this number
    // and must stay above it.
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = runArm("claude", withMcp = true)

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = runArm("claude", withMcp = false)

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = runArm("codex", withMcp = true)

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
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

            // Nothing else installs this reactor, and `~/.m2` is shared by every container of every
            // run, so an arm otherwise begins either against nothing or against the PREVIOUS arm's
            // renamed API — under which the pristine tree it was handed does not even compile. The gate
            // run inside this call is the proof, on this machine, that the environment is sound.
            prepareAndProveGateEnvironment(session.scope, projectDir)

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
            // Before the snapshot, never after: the project's own formatter rewrites a patch file that
            // is not already in its style on the FIRST build anyone runs, and the resulting hash change
            // was charged to the agent as tampering with the oracle — flagged in build 1028521545's mcp
            // arm, whose transcript contains nothing but a Read of that file.
            verifier.normalizeFormattingBeforeSnapshot(SemanticRippleSpec.projectJdkVersion)
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

            // The IDE that ran the gold capture can be dead by the time we get here — e.g. an agent
            // that OOM-killed it with a self-verification build outside our control. That is an
            // instrument failure, not evidence the agent got the rename wrong, so it must be legible
            // as a LOST MEASUREMENT rather than as a graded (and possibly zero) result.
            val postOutput = try {
                session.mcpSteroid.mcpExecuteCode(
                    code = SemanticRippleOracleScripts.postcondition(),
                    reason = "Grade the post-agent semantic state for the semantic-ripple oracle",
                    taskId = "semantic-ripple-post",
                    timeout = 900,
                ).stdout
            } catch (e: Exception) {
                println("[RIPPLE] MEASUREMENT LOST: could not reach the IDE to take the post-condition")
                println("[RIPPLE]   reading. The grade for this run is UNKNOWN, not zero — this is an")
                println("[RIPPLE]   instrument failure, not a verdict on the agent.")
                println("[RIPPLE]   gold: ${gold.totalReferences} references, ${gold.files} files, " +
                    "${gold.decoyReferences.size} decoys")
                println("[RIPPLE]   agent time: ${result.agentDurationMs / 1000}s, " +
                    "exit code: ${result.agentResult.exitCode}")
                println("[RIPPLE]   cause: ${e::class.simpleName}: ${e.message}")
                fail(
                    "[$agentName+$modeLabel] MEASUREMENT LOST: the post-condition read failed " +
                        "(${e::class.simpleName}: ${e.message}). This is an instrument failure — the " +
                        "IDE could not be reached to grade the run — and is not a verdict on the agent."
                )
            }
            val grade = parseSemanticPostcondition(
                postOutput,
                gold,
                hiddenConsumerFiles = SemanticRippleCases.hiddenConsumerFiles(),
            )

            // The layer that covers all 445 call sites: a site the agent missed still names a method
            // that no longer exists, so it cannot compile.
            val gate = runCompileGate(session.scope, projectDir)

            val verification = verifier.verify(
                failToPass = testCase.failToPass,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                testPatch = testCase.testPatch,
                preAgentSnapshot = preAgentSnapshot,
                baseline = FullSuiteSnapshot(perClass = emptyList(), mavenExitCode = 0),
                mavenProjectSelector = SemanticRippleSpec.gradingScopeSelector,
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
            println("[RIPPLE]   consumer refs excluded from conservation: ${grade.excludedConsumerReferences}")
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
