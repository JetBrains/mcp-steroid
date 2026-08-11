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
 * Infrastructure gate for the semantic-ripple track: proves a container can clone Keycloak from the
 * host bare-repo cache, import the project (156 IntelliJ modules from 152 Maven projects), and reach
 * a state where PSI queries resolve — and reports how long that takes, because the prewarm timeout in
 * [SemanticRippleSpec] is a guess until this test has run once.
 *
 * No agent, no oracle. When this fails the track is blocked on infrastructure, which is a different
 * finding from the hypothesis being wrong.
 */
class SemanticRipplePrewarmProbeTest {

    private val rolesDeclLine = Regex("""\bROLES_DECLS 17\b""")

    @Test
    @Timeout(value = 120, unit = TimeUnit.MINUTES)
    fun `keycloak opens and imports from the bare repo cache`() {
        val lifetime = CloseableStackHost()
        try {
            val startedAt = System.currentTimeMillis()
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "keycloak-prewarm-probe",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = "https://github.com/keycloak/keycloak.git",
                    repoOwnerAndName = "keycloak/keycloak",
                    baseCommit = "60c4d5e9321ff5462a772ceb896f8cb2e639e04b",
                    testPatch = "",
                    displayName = "keycloak-semantic-ripple-probe",
                    buildSystem = "maven",
                ),
                aiMode = AiMode.NONE,
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = false,
            )).waitForProjectReady(
                // Measured warm run reached readiness in 384 s. The wide bound provides headroom for
                // a genuinely cold dependency cache, which has never been measured. Bounded by the
                // method's own 120-minute @Timeout.
                timeoutMillis = 6_000_000L,
                projectJdkVersion = "21",
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                requireCleanCompile = false,
            )
            val readyMs = System.currentTimeMillis() - startedAt

            val projectDir = session.intellijDriver.getGuestProjectDir()
            val openProjects = session.mcpSteroid.mcpListProjects()
            assertTrue(openProjects.any { it.path == projectDir }) {
                "No IDE project open at $projectDir (open: ${openProjects.joinToString { it.path }})"
            }

            val probe = session.mcpSteroid.mcpExecuteCode(
                code = """
                    import com.intellij.psi.JavaPsiFacade
                    import com.intellij.psi.search.GlobalSearchScope
                    import com.intellij.psi.search.PsiShortNamesCache
                    import com.intellij.openapi.module.ModuleManager

                    println("MODULES " + readAction { ModuleManager.getInstance(project).modules.size })
                    smartReadAction(project) {
                        val scope = GlobalSearchScope.projectScope(project)
                        val decls = PsiShortNamesCache.getInstance(project).getMethodsByName("roles", scope)
                        println("ROLES_DECLS " + decls.size)
                        val jdk = JavaPsiFacade.getInstance(project)
                            .findClass("java.lang.String", GlobalSearchScope.allScope(project))
                        println("JDK_RESOLVES " + (jdk != null))
                    }
                """.trimIndent(),
                reason = "Probe whether Keycloak PSI resolves after import, for the semantic-ripple prewarm gate",
                taskId = "semantic-ripple-probe",
                timeout = 600,
            )

            println("[RIPPLE-PROBE] project ready in ${readyMs / 1000}s")
            println("[RIPPLE-PROBE] probe output:\n${probe.stdout}")

            assertTrue(probe.stdout.contains("JDK_RESOLVES true")) {
                "JDK symbols do not resolve after import; PSI counts would be wrong.\n${probe.stdout}"
            }
            assertTrue(rolesDeclLine.containsMatchIn(probe.stdout)) {
                "Expected 17 declarations named 'roles' at the pinned commit.\n${probe.stdout}"
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
