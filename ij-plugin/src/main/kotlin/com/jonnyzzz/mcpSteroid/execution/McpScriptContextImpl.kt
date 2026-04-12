/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiManager
import com.intellij.util.PairProcessor
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration
import com.intellij.openapi.application.readAction as intellijReadAction
import com.intellij.openapi.application.writeAction as intellijWriteAction
import com.intellij.openapi.application.smartReadAction as intellijSmartReadAction

/**
 * Implementation of McpScriptContext.
 *
 * Key features:
 * - Has a Disposable that scripts can use to register cleanup
 * - Rejects output operations after disposed
 * - Supports progress reporting via MCP notifications (throttled to 1/sec)
 * - No coroutineScope property - suspend functions get scope implicitly
 */
class McpScriptContextImpl(
    override val project: Project,
    override val params: JsonElement,
    val executionId: ExecutionId,
    override val disposable: Disposable,
    private val resultBuilder: ExecutionResultBuilder,
    private val modalityMonitor: ModalityStateMonitor,
) : McpScriptContext {
    private val log = Logger.getInstance(McpScriptContextImpl::class.java)

    private val objectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
        // Don't fail on empty beans
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }.writerWithDefaultPrettyPrinter()

    private val disposed = AtomicBoolean(false).also {
        Disposer.register(disposable) { it.set(true) }
    }

    override val isDisposed: Boolean
        get() = disposed.get()

    private fun checkDisposed() {
        if (disposed.get()) {
            throw IllegalStateException("Context has been disposed - cannot perform output operations")
        }
    }

    override fun println(vararg values: Any?) {
        checkDisposed()
        resultBuilder.logMessage(values.joinToString(" ") { it?.toString() ?: "null" })
    }

    override fun printException(message: String, throwable: Throwable) {
        checkDisposed()
        resultBuilder.logException(message, throwable)
    }

    override fun printJson(obj: Any?) {
        checkDisposed()
        try {
            val jsonString = when (obj) {
                null -> "null"
                is String -> obj
                else -> objectMapper.writeValueAsString(obj)
            }
            resultBuilder.logMessage(jsonString)
        } catch (e: Exception) {
            resultBuilder.logMessage("Failed to serialize to JSON: ${e.message}")
        }
    }

    override fun progress(message: String) {
        checkDisposed()
        log.info("[$executionId] progress: $message")
        // Report progress directly without storing in output
        resultBuilder.logProgress(message)
    }

    override suspend fun takeIdeScreenshot(fileName: String): String? {
        checkDisposed()
        if (fileName.isNotBlank() && fileName != "ide-screenshot.png") {
            resultBuilder.logMessage("NOTE: takeIdeScreenshot ignores custom fileName and uses screenshot.png.")
        }
        return try {
            val artifacts = VisionService.capture(project, executionId)
            resultBuilder.logImage("image/png", Base64.getEncoder().encodeToString(artifacts.imageBytes), artifacts.meta.imageFile)
            resultBuilder.logMessage("window_id: ${artifacts.meta.windowId}")
            resultBuilder.logMessage("Screenshot saved to ${artifacts.imagePath}")
            resultBuilder.logMessage("Component tree saved to ${artifacts.treePath}")
            resultBuilder.logMessage("Screenshot metadata saved to ${artifacts.metaPath}")
            artifacts.imagePath.toString()
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            resultBuilder.logException("Failed to capture IDE screenshot", e)
            null
        }
    }

    override suspend fun waitForSmartMode() {
        checkDisposed()
        if (!DumbService.isDumb(project)) return

        log.info("[$executionId] Waiting for indexing to complete...")
        resultBuilder.logProgress("Waiting for indexing to complete...")

        suspendCancellableCoroutine { cont ->
            fun waitForSmart() {
                if (disposed.get()) {
                    cont.cancel()
                    return
                }
                DumbService.getInstance(project).smartInvokeLater {
                    if (disposed.get()) {
                        cont.cancel()
                    } else if (DumbService.isDumb(project)) {
                        waitForSmart()
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
            waitForSmart()
        }
    }

    override fun doNotCancelOnModalityStateChange() {
        checkDisposed()
        log.info("[$executionId] Modal dialog cancellation disabled by script")
        modalityMonitor.doNotCancelOnModalityStateChange()
    }

    // ============================================================
    // Daemon Code Analysis
    // ============================================================

    override suspend fun isEditorHighlightingCompleted(file: VirtualFile): Boolean {
        checkDisposed()

        val editor = withContext(Dispatchers.EDT) {
            FileEditorManager.getInstance(project).getSelectedEditor(file)
        } ?: return false

        return readAction {
            DaemonCodeAnalyzerEx.isHighlightingCompleted(editor, project)
        }
    }

    override suspend fun waitForEditorHighlighting(file: VirtualFile, timeout: Duration): Boolean {
        checkDisposed()
        log.info("[$executionId] Waiting for daemon analysis on ${file.name}...")
        resultBuilder.logProgress("Waiting for daemon analysis on ${file.name}...")

        // First wait for smart mode
        waitForSmartMode()

        // Find the editor for the file
        val editor = withContext(Dispatchers.EDT) {
            FileEditorManager.getInstance(project).getEditors(file)
                .filterIsInstance<TextEditor>()
                .firstOrNull()
        }

        if (editor == null) {
            log.warn("[$executionId] No text editor found for ${file.name}, cannot wait for highlighting")
            return false
        }

        // Wait for highlighting to complete
        val completed = withTimeoutOrNull(timeout) {
            while (!disposed.get()) {
                val isComplete = withContext(Dispatchers.EDT) {
                    DaemonCodeAnalyzerEx.isHighlightingCompleted(editor, project)
                }
                if (isComplete) break
                delay(50)
            }
            true
        } ?: false

        if (completed) {
            log.info("[$executionId] Daemon analysis completed for ${file.name}")
        } else {
            log.warn("[$executionId] Timeout waiting for daemon analysis on ${file.name}")
        }
        return completed
    }

    override suspend fun getHighlightsWhenReady(
        file: VirtualFile,
        minSeverityValue: Int,
        timeout: Duration
    ): List<HighlightInfo> {
        checkDisposed()

        // Wait for analysis to complete
        val completed = waitForEditorHighlighting(file, timeout)
        if (!completed) {
            return emptyList()
        }

        // Get document for the file
        val document = readAction {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
        } ?: return emptyList()

        // Get all highlights
        return readAction {
            getHighlightsFromDaemon(document, minSeverityValue)
        }
    }

    private fun getHighlightsFromDaemon(document: Document, minSeverityValue: Int): List<HighlightInfo> {
        val allHighlights = mutableListOf<HighlightInfo>()

        DaemonCodeAnalyzerEx.processHighlights(
            document,
            project,
            null, // null severity means all severities
            0,
            document.textLength
        ) { info ->
            if (info.severity.myVal >= minSeverityValue) {
                allHighlights.add(info)
            }
            true // continue processing
        }

        return allHighlights
    }

    // ============================================================
    // Direct Inspection Execution (bypasses daemon focus check)
    // ============================================================

    override suspend fun runInspectionsDirectly(
        file: VirtualFile,
        includeInfoSeverity: Boolean
    ): Map<String, List<ProblemDescriptor>> {
        checkDisposed()
        log.info("[$executionId] Running inspections directly on ${file.name}...")
        resultBuilder.logProgress("Running inspections on ${file.name}...")

        // Wait for smart mode first
        waitForSmartMode()

        return readAction {
            val psiFile = PsiManager.getInstance(project).findFile(file)
            if (psiFile == null) {
                log.warn("[$executionId] Could not find PSI file for ${file.name}")
                return@readAction emptyMap()
            }

            // Get inspection profile and enabled tools
            val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
            val toolWrappers = profile.getAllEnabledInspectionTools(project)
                .mapNotNull { toolState ->
                    val tool = toolState.tool
                    if (tool is LocalInspectionToolWrapper) {
                        // Filter by severity if needed
                        val key = HighlightDisplayKey.find(tool.shortName)
                        if (key != null) {
                            val severity = profile.getErrorLevel(key, psiFile).severity
                            if (includeInfoSeverity || severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal) {
                                tool
                            } else {
                                null
                            }
                        } else {
                            // Include tool if we can't determine severity
                            tool
                        }
                    } else {
                        null
                    }
                }

            if (toolWrappers.isEmpty()) {
                log.info("[$executionId] No applicable inspection tools found")
                return@readAction emptyMap()
            }

            log.info("[$executionId] Running ${toolWrappers.size} inspections on ${file.name}")

            // Run inspections directly - bypasses daemon focus check
            val results = InspectionEngine.inspectEx(
                toolWrappers,
                psiFile,
                psiFile.textRange,
                psiFile.textRange,
                false,  // isOnTheFly = false (batch mode)
                false,  // inspectInjectedPsi
                true,   // ignoreSuppressedElements
                EmptyProgressIndicator(),
                PairProcessor.alwaysTrue()
            )

            // Convert to map of tool ID -> problems
            results.mapKeys { (wrapper, _) -> wrapper.shortName }
                .filterValues { it.isNotEmpty() }
        }
    }

    // ============================================================
    // Read/Write Actions - Convenience Wrappers
    // ============================================================

    override suspend fun <T> readAction(action: () -> T): T = intellijReadAction(action)

    override suspend fun <T> writeAction(action: () -> T): T = intellijWriteAction(action)

    override suspend fun <T> smartReadAction(action: () -> T): T = intellijSmartReadAction(project, action)

    // ============================================================
    // Search Scopes
    // ============================================================

    override fun projectScope(): GlobalSearchScope = GlobalSearchScope.projectScope(project)

    override fun allScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)

    // ============================================================
    // File Access
    // ============================================================

    override fun findFile(absolutePath: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(absolutePath)

    override suspend fun findPsiFile(absolutePath: String): PsiFile? {
        val vf = findFile(absolutePath) ?: return null
        return readAction { PsiManager.getInstance(project).findFile(vf) }
    }

    override fun findProjectFile(relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        return findFile("$basePath/$relativePath")
    }

    override suspend fun findProjectFiles(globPattern: String): List<VirtualFile> {
        if (globPattern.isBlank()) return emptyList()

        val projectRoot = project.guessProjectDir()
            ?: project.basePath?.let(::findFile)
            ?: return emptyList()

        val primaryMatcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$globPattern")
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }

        val fallbackMatcher = runCatching {
            val adjusted = globPattern.replace('/', File.separatorChar)
            if (adjusted == globPattern) null else FileSystems.getDefault().getPathMatcher("glob:$adjusted")
        }.getOrNull()

        return readAction {
            val matches = mutableListOf<VirtualFile>()
            VfsUtilCore.iterateChildrenRecursively(projectRoot, null) { file ->
                val relativePathText = VfsUtilCore.getRelativePath(file, projectRoot, '/')
                    ?: return@iterateChildrenRecursively true
                val relativePath = runCatching { Path.of(relativePathText) }
                    .getOrElse { return@iterateChildrenRecursively true }

                if (primaryMatcher.matches(relativePath) || (fallbackMatcher?.matches(relativePath) == true)) {
                    matches += file
                }
                true
            }

            matches.sortedBy { it.path }
        }
    }

    override suspend fun findProjectPsiFile(relativePath: String): PsiFile? {
        val basePath = project.basePath ?: return null
        return findPsiFile("$basePath/$relativePath")
    }
}
