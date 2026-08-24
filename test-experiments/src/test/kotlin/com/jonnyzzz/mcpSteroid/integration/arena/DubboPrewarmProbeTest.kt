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
 * Infrastructure gate for a SECOND host repository: can a container clone Apache Dubbo, import its
 * 119-module Maven reactor, compile it and reach a state where project PSI resolves — and in how long.
 *
 * Every acquisition result so far lives in one repository. The strongest remaining threat to the
 * finding is that it describes Keycloak's structure — many SPIs, many `META-INF/services` files —
 * rather than semantic access, and the only way to remove that threat is to measure the same thing
 * somewhere else. This test buys nothing towards the hypothesis; it answers whether the question can
 * be ASKED there at all, before any case, checklist or agent budget is designed for it.
 *
 * Dubbo was chosen against measurements rather than impressions, and the two rejections are part of
 * the record: Kill Bill's tests are TestNG and database-bound, so no hidden oracle can be a plain
 * JUnit class in one module; Apache Camel is 1 124 build files and 26 537 Java sources against
 * Keycloak's 189 and 8 263, which is not a second data point but a second infrastructure project.
 * Dubbo at the pinned commit is 119 Maven modules and 4 050 Java sources, one protobuf-generating
 * module, and no npm step anywhere in the reactor.
 *
 * The one number this test exists to produce is the wall clock to readiness. Keycloak's warm run is
 * 384 s and its cells run 25–35 minutes each; if Dubbo lands in the same range the round is affordable
 * and if it does not, that is a finding to write down rather than an obstacle to work around.
 *
 * No agent, no oracle, no API budget. When it fails the second-repository axis is blocked on
 * infrastructure, which is a different statement from the hypothesis being wrong.
 */
class DubboPrewarmProbeTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.MINUTES)
    fun `dubbo opens, imports and resolves its own symbols`() {
        val lifetime = CloseableStackHost()
        try {
            val startedAt = System.currentTimeMillis()
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "dubbo-prewarm-probe",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = "https://github.com/apache/dubbo.git",
                    repoOwnerAndName = "apache/dubbo",
                    baseCommit = DUBBO_BASE_COMMIT,
                    testPatch = "",
                    displayName = "dubbo-acquisition-probe",
                    buildSystem = "maven",
                ),
                aiMode = AiMode.NONE,
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = false,
            )).waitForProjectReady(
                // Dubbo's reactor targets Java 8 but builds under a modern JDK; 21 is what the image
                // uses for Keycloak, so using it here keeps one JDK in the family. The bound is the
                // same wide one the Keycloak probe carries, for the same reason: a cold dependency
                // cache has never been measured on this repository and guessing tight would turn an
                // infrastructure measurement into a timeout.
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
                        val facade = JavaPsiFacade.getInstance(project)
                        val jdk = facade.findClass("java.lang.String", GlobalSearchScope.allScope(project))
                        println("JDK_RESOLVES " + (jdk != null))
                        val filter = facade.findClass(
                            "org.apache.dubbo.rpc.Filter",
                            GlobalSearchScope.projectScope(project),
                        )
                        println("PROJECT_PSI_RESOLVES " + (filter != null))
                        val scope = GlobalSearchScope.projectScope(project)
                        println("INVOKE_DECLS " + PsiShortNamesCache.getInstance(project)
                            .getMethodsByName("invoke", scope).size)
                    }
                """.trimIndent(),
                reason = "Probe whether Dubbo PSI resolves after import, for the second-repository gate",
                taskId = "dubbo-acquisition-probe",
                timeout = 600,
            )

            println("[DUBBO-PROBE] project ready in ${readyMs / 1000}s")
            println("[DUBBO-PROBE] probe output:\n${probe.stdout}")

            assertTrue(probe.stdout.contains("JDK_RESOLVES true")) {
                "JDK symbols do not resolve after import; nothing measured here would mean anything.\n" +
                    probe.stdout
            }
            // The stronger of the two: a project-scope resolution of a type that exists only in this
            // repository proves the reactor was imported as source, not merely opened as a folder.
            // An IDE that indexed the JDK and nothing else looks healthy in every other signal.
            assertTrue(probe.stdout.contains("PROJECT_PSI_RESOLVES true")) {
                "Dubbo's own types do not resolve in project scope; the reactor did not import.\n" +
                    probe.stdout
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    companion object {
        /**
         * Pinned at `Fix Triple gRPC decoder handoff (#16416)`, 2026-08-21, the head of the default
         * branch when the second-repository axis was opened. Pinned before the probe runs so that the
         * numbers this test prints describe a tree that can be checked out again.
         */
        const val DUBBO_BASE_COMMIT: String = "605410407c718ea6635be3fb577ceec36f528bff"
    }
}
