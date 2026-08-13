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
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Selects the ripple family's targets by measuring Keycloak, and is the go/no-go for every slot: a
 * kind with no qualifying candidate is reported empty rather than filled with an easier target.
 *
 * No agent, no oracle, no grading — this run only prints. Its output is transcribed into the case
 * registry as pinned constants, and `RippleCaseRegistryTest` later asserts the registry matches what
 * was transcribed.
 */
class KeycloakRippleTargetSurveyTest {

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `survey keycloak for ripple targets of every kind`() {
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "ripple-target-survey",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = SemanticRippleSpec.cloneUrl,
                    repoOwnerAndName = SemanticRippleSpec.repoOwnerAndName,
                    baseCommit = SemanticRippleSpec.baseCommit,
                    testPatch = "",
                    displayName = "keycloak-ripple-survey",
                    buildSystem = "maven",
                ),
                aiMode = AiMode.NONE,
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = false,
                // Keycloak is the largest project this harness imports (189 modules), and its
                // cold-start VFS-refresh/indexing storm outruns the plugin's 120s dialog-less
                // modality bound, failing the smart_non_modal gate before any survey work runs.
                dialoglessModalWaitMs = 600_000,
            )).waitForProjectReady(
                timeoutMillis = SemanticRippleSpec.projectReadyTimeoutMs,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                requireCleanCompile = false,
            )

            val output = session.mcpSteroid.mcpExecuteCode(
                code = RippleTargetSurveyScripts.survey(),
                reason = "Survey Keycloak for qualifying ripple targets of every transformation kind",
                taskId = "ripple-target-survey",
                timeout = 3_600,
            ).stdout

            val candidates = parseSurveyCandidates(output)
            assertTrue(candidates.isNotEmpty()) { "The survey found no candidates at all:\n$output" }

            fun report(label: String, qualified: List<SurveyCandidate>) {
                println("[SURVEY] $label — ${qualified.size} qualifying")
                qualified.sortedByDescending { it.references }.take(10).forEach {
                    println("[SURVEY]   ${it.ownerFqn}#${it.name} refs=${it.references} files=${it.files} " +
                        "modules=${it.modules} sameName=${it.sameNameDeclarations} breadth=${it.hierarchyBreadth}")
                }
            }

            for (kind in listOf("rename-type", "change-signature", "move-class")) {
                val ofKind = candidates.filter { it.kind == kind }
                report("$kind WIDE", ofKind.filter { it.qualifiesAsWide() })
                report("$kind NARROW", ofKind.filter { it.qualifiesAsNarrow() })
            }
            report("pull-up", candidates.filter { it.kind == "pull-up" && it.qualifiesForPullUp() })
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
