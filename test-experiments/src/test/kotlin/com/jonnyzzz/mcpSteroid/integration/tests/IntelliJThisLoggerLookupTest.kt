/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Monorepo-scale symbol lookup smoke test for MCP Steroid on the IntelliJ Ultimate checkout.
 *
 * This intentionally mirrors the DPAIA container flow: deploy a real multi-module project,
 * wait for IDEA indexing, then perform an indexed lookup through `steroid_execute_code`.
 * The lookup is syntactic call-site search by design; full Kotlin reference resolution is
 * intentionally avoided because it triggers severe FIR resolver logs on this monorepo.
 * The smaller [ThisLoggerComparisonTest] checks agent behavior on a toy project; this test
 * verifies that the IDE-side lookup works against the real IntelliJ monorepo.
 */
class IntelliJThisLoggerLookupTest {
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `mcp finds thisLogger references in IntelliJ monorepo`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime = lifetime,
            dockerFileBase = "ide-agent",
            consoleTitle = "intellij-thislogger-lookup",
            project = IntelliJProject.IntelliJMasterProject,
        ).waitForProjectReady(
            timeoutMillis = System.getProperty("test.integration.intellij.project.ready.timeout.ms")
                ?.toLongOrNull()
                ?: 5_400_000L,
            pollIntervalMillis = 5_000L,
            requireIndexingComplete = true,
            performPostSetup = false,
        )

        val logLineCountBeforeLookup = session.intellijDriver.readLogs().size
        val result = session.mcpSteroid.mcpExecuteCode(
            taskId = "intellij-thislogger-lookup",
            reason = "Find thisLogger references in the IntelliJ monorepo",
            timeout = 600,
            code = """
                import com.intellij.openapi.application.smartReadAction
                import com.intellij.openapi.fileEditor.FileDocumentManager
                import com.intellij.platform.backend.observation.Observation
                import com.intellij.psi.PsiManager
                import com.intellij.psi.impl.cache.CacheManager
                import com.intellij.psi.search.FilenameIndex
                import com.intellij.psi.search.GlobalSearchScope
                import com.intellij.psi.search.UsageSearchContext
                import com.intellij.psi.util.PsiTreeUtil
                import kotlinx.coroutines.withTimeout
                import org.jetbrains.kotlin.psi.KtCallExpression
                import org.jetbrains.kotlin.psi.KtFile
                import org.jetbrains.kotlin.psi.KtNamedFunction

                println("WAITING_FOR_CONFIGURATION")
                val configurationChanged = withTimeout(10 * 60 * 1000L) {
                    Observation.awaitConfiguration(project) { message ->
                        println("CONFIGURATION_ACTIVITY=${'$'}message")
                    }
                }
                println("CONFIGURATION_COMPLETE=${'$'}configurationChanged")

                val (loggerPath, hits) = smartReadAction(project) {
                    val scope = GlobalSearchScope.projectScope(project)
                    val loggerFile = FilenameIndex.getVirtualFilesByName("logger.kt", scope)
                        .firstOrNull { it.path.endsWith("/com/intellij/openapi/diagnostic/logger.kt") }
                        ?: error("Could not find com.intellij.openapi.diagnostic.logger.kt")

                    val psiFile = PsiManager.getInstance(project).findFile(loggerFile)
                        ?: error("Could not resolve PSI for ${'$'}{loggerFile.path}")
                    val target = PsiTreeUtil.findChildrenOfType(psiFile, KtNamedFunction::class.java)
                        .firstOrNull { it.name == "thisLogger" && it.valueParameters.isEmpty() }
                        ?: error("Could not find zero-argument thisLogger function in ${'$'}{loggerFile.path}")

                    val candidateFiles = CacheManager.getInstance(project)
                        .getVirtualFilesWithWord(target.name!!, UsageSearchContext.IN_CODE, scope, true)

                    val foundHits = buildList {
                        for (virtualFile in candidateFiles) {
                            val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
                            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                            for (call in PsiTreeUtil.findChildrenOfType(ktFile, KtCallExpression::class.java)) {
                                if (call.calleeExpression?.text == target.name) {
                                    val line = document?.getLineNumber(call.textOffset)?.plus(1) ?: -1
                                    add("${'$'}{virtualFile.path}:${'$'}line")
                                }
                            }
                        }
                    }.filterNot { it.contains("/com/intellij/openapi/diagnostic/logger.kt:") }
                        .distinct()
                        .sorted()
                    loggerFile.path to foundHits
                }
                val files = hits.map { it.substringBeforeLast(":") }.distinct()
                println("THISLOGGER_LOOKUP_STRATEGY=INDEXED_WORD_PLUS_KOTLIN_PSI")
                println("LOGGER_FILE=${'$'}loggerPath")
                println("THISLOGGER_REFERENCE_COUNT=${'$'}{hits.size}")
                println("THISLOGGER_FILE_COUNT=${'$'}{files.size}")
                println("THISLOGGER_SAMPLE=${'$'}{hits.take(20).joinToString("|")}")
                check(hits.size >= 500) { "Expected monorepo-scale thisLogger references, got ${'$'}{hits.size}" }
                check(files.size >= 300) { "Expected thisLogger references across many files, got ${'$'}{files.size}" }
                check(hits.any { it.contains("/platform/") || it.contains("/community/platform/") }) {
                    "Expected at least one platform reference in sample of ${'$'}{hits.size} references"
                }
            """.trimIndent(),
        ).assertExitCode(0) { "thisLogger lookup failed:\n$stdout\n$stderr" }

        val referenceCount = markerInt(result.stdout, "THISLOGGER_REFERENCE_COUNT")
        val fileCount = markerInt(result.stdout, "THISLOGGER_FILE_COUNT")
        val lookupLogs = session.intellijDriver.readLogs().drop(logLineCountBeforeLookup)

        assertTrue(
            result.stdout.lineSequence().any { it == "THISLOGGER_LOOKUP_STRATEGY=INDEXED_WORD_PLUS_KOTLIN_PSI" },
            "Expected thisLogger lookup to use indexed word plus Kotlin PSI strategy:\n${result.stdout}",
        )
        assertTrue(referenceCount >= 500, "Expected at least 500 thisLogger references, got $referenceCount")
        assertTrue(fileCount >= 300, "Expected at least 300 files with thisLogger references, got $fileCount")
        assertNoKotlinFirSevereLogs(lookupLogs)
    }

    private fun markerInt(output: String, marker: String): Int =
        output.lineSequence()
            .firstOrNull { it.startsWith("$marker=") }
            ?.substringAfter("=")
            ?.trim()
            ?.toIntOrNull()
            ?: error("Missing integer marker $marker in output:\n$output")

    private fun assertNoKotlinFirSevereLogs(logLines: List<String>) {
        // Exact failure signature from IU-261.23567.138 when ReferencesSearch forced Kotlin FIR reference resolution.
        val firFailures = logLines.filter { line ->
            line.contains("KaFirReferenceResolver") ||
                    line.contains("Expected FirResolvedContractDescription") ||
                    line.contains("FirLazyContractDescriptionImpl")
        }

        assertTrue(
            firFailures.isEmpty(),
            "thisLogger lookup should not emit Kotlin FIR severe logs:\n${firFailures.take(20).joinToString("\n")}",
        )
    }
}
