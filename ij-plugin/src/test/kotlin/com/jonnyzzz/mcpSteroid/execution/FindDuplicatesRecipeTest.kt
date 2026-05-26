/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.setSystemPropertyForTest
import com.jonnyzzz.mcpSteroid.testExecParams
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/**
 * In-process verification that the `mcp-steroid://ide/find-duplicates` recipe
 * extracts clone clusters via the typed `DuplicateProblemDescriptor.getTextClone()`
 * getter — no `setAccessible(true)`, no `getDeclaredField`.
 *
 * Backs [issue #33](https://github.com/jonnyzzz/mcp-steroid/issues/33). Cheap counterpart
 * to the heavy `FindDuplicatesPromptTest` (Docker + Claude); this one runs entirely
 * inside the test JVM and verifies that:
 *  - the recipe compiles against the IDE classpath provided by `ScriptClassLoaderFactory`,
 *  - the bundled `DuplicatedCode` inspection actually fires on a Kotlin fixture with
 *    two byte-identical methods,
 *  - the typed cast `filterIsInstance<DuplicateProblemDescriptor>()` yields a non-empty
 *    list of clusters whose `textClone.main` and `textClone.duplicates` resolve to a
 *    `VirtualFile` + `TextRange` from public Kotlin properties only.
 */
class FindDuplicatesRecipeTest : BasePlatformTestCase() {

    private lateinit var fixturePath: String

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()

        // Two byte-identical method bodies — DuplicatedCode must flag them.
        // If you change one body, change both (the inspection wants tokens to match).
        val fixtureSource = """
            package test.duplicates

            class DemoDuplicates {
                fun calculateInvoiceTotal(items: List<Pair<Int, Double>>): Double {
                    var total = 0.0
                    var count = 0
                    for ((quantity, price) in items) {
                        val line = quantity * price
                        total += line
                        count += quantity
                        println("[invoice] quantity=${'$'}quantity price=${'$'}price line=${'$'}line total=${'$'}total count=${'$'}count")
                    }
                    println("[invoice] done — total=${'$'}total count=${'$'}count")
                    return total
                }

                fun calculateOrderTotal(items: List<Pair<Int, Double>>): Double {
                    var total = 0.0
                    var count = 0
                    for ((quantity, price) in items) {
                        val line = quantity * price
                        total += line
                        count += quantity
                        println("[invoice] quantity=${'$'}quantity price=${'$'}price line=${'$'}line total=${'$'}total count=${'$'}count")
                    }
                    println("[invoice] done — total=${'$'}total count=${'$'}count")
                    return total
                }
            }
        """.trimIndent()

        val basePath = project.basePath ?: error("Project base path is not available")
        val srcVf = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(Paths.get(basePath, "src").toString())
        }
        PsiTestUtil.addSourceRoot(module, srcVf)

        val file = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val filePath = Paths.get(basePath, "src/test/duplicates/DemoDuplicates.kt")
            val parent = VfsUtil.createDirectories(filePath.parent.toString())
            val name = filePath.fileName.toString()
            val child = parent.findChild(name) ?: parent.createChildData(this, name)
            VfsUtil.saveText(child, fixtureSource)
            child
        }
        fixturePath = file.path
    }

    private fun textOf(result: ToolCallResult): String =
        result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

    fun testTypedDuplicateProblemDescriptorAccessFindsCluster(): Unit = timeoutRunBlocking(120.seconds) {
        val manager = project.service<ExecutionManager>()

        // The recipe under test — copied verbatim from `mcp-steroid://ide/find-duplicates`,
        // minus the println summary loop. Direct typed imports, no reflection.
        val code = $$"""
            import com.intellij.codeInspection.InspectionEngine
            import com.intellij.codeInspection.ProblemDescriptor
            import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
            import com.intellij.openapi.progress.EmptyProgressIndicator
            import com.intellij.psi.search.FilenameIndex
            import com.intellij.psi.search.GlobalSearchScope
            import com.intellij.util.PairProcessor
            import com.jetbrains.clones.DuplicateInspection
            import com.jetbrains.clones.DuplicateProblemDescriptor
            import com.jetbrains.clones.structures.TextClone
            import com.jetbrains.clones.structures.TextFragment

            data class CloneRange(val path: String, val startLine: Int, val endLine: Int)
            data class CloneCluster(val main: CloneRange, val duplicates: List<CloneRange>)

            fun TextFragment.toRange() = CloneRange(file.path, lines.first, lines.last)

            val scope = GlobalSearchScope.projectScope(project)
            val pathFilter: (String) -> Boolean = { true }
            val ktFiles = readAction {
                FilenameIndex.getAllFilesByExt(project, "kt", scope).filter { pathFilter(it.path) }
            }
            println("Scanning ${ktFiles.size} Kotlin file(s)")

            val wrapper = LocalInspectionToolWrapper(DuplicateInspection())
            // Same dedup as the published recipe — DuplicatedCode emits one descriptor
            // per fragment-as-main, so a 2-fragment cluster surfaces twice.
            val seenKeys = mutableSetOf<String>()
            val clusters = mutableListOf<CloneCluster>()

            for (vf in ktFiles) {
                val perFile = readAction {
                    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@readAction emptyList<CloneCluster>()
                    val raw: List<ProblemDescriptor> = InspectionEngine.inspectEx(
                        listOf(wrapper),
                        psiFile,
                        psiFile.textRange,
                        psiFile.textRange,
                        false,
                        false,
                        true,
                        EmptyProgressIndicator(),
                        PairProcessor<LocalInspectionToolWrapper, Any> { _, _ -> true },
                    ).values.flatten()

                    raw.filterIsInstance<DuplicateProblemDescriptor>().mapNotNull { dpd ->
                        val tc: TextClone = dpd.textClone
                        val main = tc.main.toRange()
                        val dups = tc.duplicates.map { it.toRange() }
                        val key = (listOf(main) + dups)
                            .map { "${it.path}:${it.startLine}-${it.endLine}" }
                            .sorted()
                            .joinToString("|")
                        if (seenKeys.add(key)) CloneCluster(main = main, duplicates = dups) else null
                    }
                }
                clusters += perFile
            }

            println("CLUSTERS_FOUND: ${clusters.size}")
            clusters.forEach { c ->
                println("CLUSTER main=${c.main.path}:${c.main.startLine}-${c.main.endLine} duplicates=${c.duplicates.size}")
                c.duplicates.forEach { d -> println("  dup=${d.path}:${d.startLine}-${d.endLine}") }
            }
            println("DEMO_DUPLICATES_HIT: ${if (clusters.any { it.main.path.endsWith("DemoDuplicates.kt") || it.duplicates.any { d -> d.path.endsWith("DemoDuplicates.kt") } }) "yes" else "no"}")
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(code, taskId = "find-duplicates-recipe-test", reason = "issue #33 typed access"),
            NoOpProgressReporter,
        )

        val text = textOf(result)
        println("Test output:\n$text")

        assertFalse("Recipe must run without error. Output:\n$text", result.isError)
        assertTrue(
            "Recipe must report a CLUSTERS_FOUND marker. Output:\n$text",
            text.contains("CLUSTERS_FOUND:"),
        )
        assertTrue(
            "DuplicatedCode inspection must flag the byte-identical methods in DemoDuplicates.kt. Output:\n$text",
            text.contains("DEMO_DUPLICATES_HIT: yes"),
        )
        assertTrue(
            "Cluster line must reference the fixture path. Output:\n$text",
            text.contains("CLUSTER main=") && text.contains("DemoDuplicates.kt"),
        )
    }

}
