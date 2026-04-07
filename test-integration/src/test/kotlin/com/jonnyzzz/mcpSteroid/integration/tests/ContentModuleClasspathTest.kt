/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Verifies that classes from IntelliJ plugin content modules are on the
 * kotlinc compile classpath. In production IDEs (2025.3+), plugins are split
 * into content modules with separate classloaders. If ideClasspath() only
 * collects JARs from main plugin descriptors, imports like
 * AnnotatedElementsSearch fail with "unresolved reference" at compile time.
 *
 * See https://github.com/jonnyzzz/mcp-steroid/issues/16
 *
 * Run with IntelliJ IDEA 2025.3 (stable):
 *   ./gradlew :test-integration:test --tests '*ContentModuleClasspathTest*'
 *
 * Run with IntelliJ IDEA 2026.1 (EAP):
 *   ./gradlew :test-integration:test --tests '*ContentModuleClasspathTest*' -Dtest.integration.ide.channel=eap
 */
class ContentModuleClasspathTest {
    companion object {
        val lifetime by lazy { CloseableStackHost(this::class.java.simpleName) }
        val session by lazy { IntelliJContainer.create(lifetime, "ide-agent", consoleTitle = "Content Module Classpath") }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `compile script importing AnnotatedElementsSearch from content module`() {
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.psi.search.searches.AnnotatedElementsSearch
                import com.intellij.psi.search.GlobalSearchScope
                import com.intellij.psi.JavaPsiFacade
                import com.intellij.psi.PsiMethod

                val count = readAction {
                    val scope = GlobalSearchScope.projectScope(project)
                    val facade = JavaPsiFacade.getInstance(project)
                    val cls = facade.findClass("java.lang.Deprecated", scope)
                        ?: return@readAction -1
                    AnnotatedElementsSearch.searchPsiMethods(cls, scope).findAll().size
                }
                if (count >= 0) {
                    println("CONTENT_MODULE_OK: found ${'$'}count @Deprecated methods")
                } else {
                    println("CONTENT_MODULE_OK: no java.lang.Deprecated on classpath")
                }
            """.trimIndent(),
            taskId = "content-module-classpath",
            reason = "Verify AnnotatedElementsSearch (intellij.java.indexing content module) compiles",
        ).assertExitCode(0)
    }
}
