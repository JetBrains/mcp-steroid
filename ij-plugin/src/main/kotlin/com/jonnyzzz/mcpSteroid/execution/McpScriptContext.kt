/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Context provided to exec_code scripts.
 *
 * IMPORTANT: Script code runs in a suspend context; exec_code executes the script body directly.
 * waitForSmartMode() is called automatically before your script starts.
 * This context provides helper functions for common IntelliJ operations.
 *
 * ## Quick Reference
 *
 * ```kotlin
 * // waitForSmartMode() is called automatically before your script starts
 * // Read PSI/VFS data (use helpers - no imports needed!):
 * val psiFile = readAction {
 *     PsiManager.getInstance(project).findFile(virtualFile)
 * }
 *
 * // Or use even simpler helpers:
 * val psiFile = findPsiFile("/path/to/file.kt")
 *
 * // Modify PSI/VFS:
 * writeAction {
 *     document.setText("new content")
 * }
 *
 * // Get search scopes easily:
 * val scope = projectScope()  // or allScope()
 * ```
 *
 * NEVER use runBlocking - it causes deadlocks.
 */
interface McpScriptContext {
    /** The IntelliJ Project this execution is associated with */
    val project: Project

    /** Original tool execution parameters */
    val params: JsonElement

    /** Allows binding a disposable to the execution context; use coroutineScope {} for coroutine API */
    val disposable: Disposable

    /** Allows to check if the context is disposed */
    val isDisposed: Boolean

    // ============================================================
    // Output Methods
    // ============================================================

    /**
     * Print values to the output, separated by spaces, followed by a newline.
     * Each argument is converted to a string via toString().
     *
     * ```kotlin
     * println("Hello", "World", 42)  // prints: "Hello World 42"
     * println()  // prints empty line
     * ```
     */
    fun println(vararg values: Any?)

    /**
     * Serialize an object to pretty-printed JSON and output it.
     * Uses Jackson ObjectMapper with indentation.
     *
     * ```kotlin
     * printJson(mapOf("name" to "value", "count" to 42))
     * ```
     */
    fun printJson(obj: Any?)

    /**
     * Report an error to the MCP client with stack trace.
     * Does not mark the execution as failed.
     *
     * Recommended for error handling - includes full stack trace in output.
     */
    fun printException(message: String, throwable: Throwable)

    /**
     * Report progress to the MCP client.
     * Messages are throttled to at most once per second to avoid overwhelming the connection.
     *
     * ```kotlin
     * progress("Starting analysis...")
     * // do work
     * progress("Processing file 1 of 10")
     * // more work
     * progress("Analysis complete")
     * ```
     */
    fun progress(message: String)

    /**
     * Capture a screenshot of the IDE frame and send it as image content in the MCP response.
     * The image and related artifacts are saved under the execution folder using fixed filenames:
     * - screenshot.png
     * - screenshot-tree.md
     * - screenshot-meta.json
     *
     * The fileName parameter is ignored to keep filenames stable across tools.
     *
     * @param fileName ignored; kept for compatibility (default: ide-screenshot.png)
     * @return absolute path to the saved screenshot, or null if capture failed
     */
    suspend fun takeIdeScreenshot(fileName: String = "ide-screenshot.png"): String?

    // ============================================================
    // IDE Utilities - Waiting
    // ============================================================

    /**
     * Wait for indexing to complete (smart mode).
     * The exec_code call invokes waitForSmartMode() automatically before your script runs.
     * Call this only if you need to wait again after triggering indexing.
     *
     * ```kotlin
     * // If you trigger indexing mid-script:
     * waitForSmartMode()
     * // Now safe to use indices and PSI
     * ```
     */
    suspend fun waitForSmartMode()

    // ============================================================
    // IDE Utilities - Daemon Code Analysis
    // ============================================================

    /**
     * Check whether code highlighting is completed and up to date for the selected file in the editor.
     * This method returns a result instantly without waiting for any delay.
     *
     * ```kotlin
     * val file = findProjectFile("src/Main.kt") ?: error("File not found")
     * // Open file in editor first
     * withContext(Dispatchers.EDT) {
     *     FileEditorManager.getInstance(project).openFile(file, true)
     * }
     * // Check analysis completed
     * val completed = isEditorHighlightingCompleted(file)
     * if (completed) {
     *     println("Analysis completed!")
     * }
     * ```
     */
    suspend fun isEditorHighlightingCompleted(file: VirtualFile): Boolean

    /**
     * Wait for the code analysis process to complete highlighting on the given file.
     * The file must be open in the editor for highlighting to work.
     *
     * @param file The virtual file to wait for analysis completion
     * @param timeout Maximum time to wait (default: 30 seconds)
     * @return true if highlighting completed, false if timeout occurred
     *
     * ```kotlin
     * val file = findProjectFile("src/Main.kt") ?: error("File not found")
     * // Open file in editor first
     * withContext(Dispatchers.EDT) {
     *     FileEditorManager.getInstance(project).openFile(file, true)
     * }
     * // Wait for analysis
     * val completed = waitForEditorHighlighting(file)
     * if (completed) {
     *     println("Analysis complete!")
     * }
     * ```
     */
    suspend fun waitForEditorHighlighting(file: VirtualFile, timeout: Duration = 30.seconds): Boolean

    /**
     * Waits for the daemon analysis to complete and then returns highlights for the file.
     * Returns highlights with severity of at least WEAK_WARNING by default.
     *
     * **NOTE**: This method relies on the daemon code analyzer which may return stale results
     * if the IDE window is not focused (see GitHub issue #20). For reliable results regardless
     * of window focus, use [runInspectionsDirectly] instead.
     *
     * @param file The virtual file to get highlights for.
     * @param minSeverityValue Minimum severity value (default: WEAK_WARNING). Use HighlightSeverity.*.myVal.
     * @param timeout Maximum time to wait for analysis (default: 30 seconds).
     * @return List of HighlightInfo for the file, or empty list if timeout.
     *
     * ```kotlin
     * val file = findProjectFile("src/Main.kt") ?: error("File not found")
     * // Open file in editor
     * withContext(Dispatchers.EDT) {
     *     FileEditorManager.getInstance(project).openFile(file, true)
     * }
     * // Get all warnings and errors
     * val highlights = getHighlightsWhenReady(file)
     * highlights.forEach { info ->
     *     println("${info.severity}: ${info.description}")
     * }
     * ```
     */
    suspend fun getHighlightsWhenReady(
        file: VirtualFile,
        minSeverityValue: Int = 200, // HighlightSeverity.WEAK_WARNING.myVal
        timeout: Duration = 30.seconds
    ): List<HighlightInfo>

    /**
     * Run inspections directly on a file without relying on the daemon code analyzer.
     *
     * This method bypasses the daemon's focus-dependent caching and runs inspections directly
     * using InspectionEngine.inspectEx(). It works reliably regardless of whether the IDE
     * window is focused or active.
     *
     * Use this method when you need accurate inspection results in automated/headless scenarios.
     *
     * @param file The virtual file to inspect
     * @param includeInfoSeverity Whether to include INFO-level problems (default: false)
     * @return Map of inspection tool ID to a list of ProblemDescriptors found
     *
     * ```kotlin
     * val file = findProjectFile("src/Main.kt") ?: error("File not found")
     *
     * val problems = runInspectionsDirectly(file)
     * problems.forEach { (toolId, descriptors) ->
     *     descriptors.forEach { problem ->
     *         println("[$toolId] ${problem.descriptionTemplate}")
     *     }
     * }
     * ```
     *
     * @see getHighlightsWhenReady for daemon-based highlights (requires window focus)
     */
    suspend fun runInspectionsDirectly(
        file: VirtualFile,
        includeInfoSeverity: Boolean = false
    ): Map<String, List<ProblemDescriptor>>

    // ============================================================
    // Modal Dialog Control
    // ============================================================

    /**
     * Disable automatic cancelation when a modal dialog appears.
     *
     * By default, if a modal dialog appears during code execution, the execution
     * is canceled and a screenshot of the dialog is returned. Call this method
     * to disable this behavior - useful when your code intentionally shows dialogs
     * (like refactoring confirmations).
     *
     * ```kotlin
     * // Disable modal dialog cancellation before invoking refactoring
     * doNotCancelOnModalityStateChange()
     *
     * // Now refactoring dialogs won't cancel execution
     * ActionManager.getInstance().getAction("ExtractMethod")
     *     // ...
     * ```
     */
    fun doNotCancelOnModalityStateChange()

    // ============================================================
    // Read/Write Actions - Convenience Wrappers
    // ============================================================
    // These save you from importing com.intellij.openapi.application.readAction/writeAction

    /**
     * Execute a block under read lock.
     * Use for all PSI/VFS/index reads.
     *
     * ```kotlin
     * val psiFile = readAction {
     *     PsiManager.getInstance(project).findFile(virtualFile)
     * }
     * ```
     *
     * @see com.intellij.openapi.application.readAction
     */
    suspend fun <T> readAction(action: () -> T): T

    /**
     * Execute a block under write lock on EDT.
     * Use for all PSI/VFS/document modifications.
     *
     * ```kotlin
     * writeAction {
     *     document.insertString(0, "// Header\n")
     * }
     * ```
     *
     * @see com.intellij.openapi.application.writeAction
     */
    suspend fun <T> writeAction(action: () -> T): T

    /**
     * Execute a read action that automatically waits for smart mode.
     * Combines waitForSmartMode() + readAction {} in one call.
     *
     * ```kotlin
     * val classes = smartReadAction {
     *     KotlinClassShortNameIndex.get("MyClass", project, projectScope())
     * }
     * ```
     *
     * @see com.intellij.openapi.application.smartReadAction
     */
    suspend fun <T> smartReadAction(action: () -> T): T

    // ============================================================
    // Search Scopes - Convenience Methods
    // ============================================================

    /**
     * Get a search scope covering all project files (excludes libraries).
     *
     * ```kotlin
     * val scope = projectScope()
     * FilenameIndex.getFilesByName(project, "build.gradle.kts", scope)
     * ```
     */
    fun projectScope(): GlobalSearchScope

    /**
     * Get a search scope covering project files AND all libraries.
     *
     * ```kotlin
     * val scope = allScope()
     * JavaPsiFacade.getInstance(project).findClass("java.util.List", scope)
     * ```
     */
    fun allScope(): GlobalSearchScope

    // ============================================================
    // File Access - Convenience Methods
    // ============================================================

    /**
     * Find a VirtualFile by an absolute path.
     * Returns null if the file doesn't exist.
     *
     * ```kotlin
     * val vf = findFile("/path/to/file.kt")
     * if (vf != null) {
     *     val content = String(vf.contentsToByteArray())
     * }
     * ```
     */
    fun findFile(absolutePath: String): VirtualFile?

    /**
     * Find a PsiFile by an absolute path.
     * Requires a read action context or uses one internally.
     * Returns null if the file doesn't exist or can't be parsed.
     *
     * ```kotlin
     * val psiFile = findPsiFile("/path/to/file.kt")
     * println(psiFile?.name)
     * ```
     */
    suspend fun findPsiFile(absolutePath: String): PsiFile?

    /**
     * Find a VirtualFile relative to the project base path.
     * Returns null if the file doesn't exist.
     *
     * ```kotlin
     * val vf = findProjectFile("src/main/kotlin/MyClass.kt")
     * ```
     */
    fun findProjectFile(relativePath: String): VirtualFile?

    /**
     * Find project files by a glob pattern relative to the project root.
     *
     * Uses Java NIO glob matching. Common glob patterns:
     * - starstar-slash-star.kt for all Kotlin files recursively
     * - src/main/starstar-slash-Demo-star.kt for demo classes
     * - star.md for markdown files in root
     *
     * Returns files sorted by absolute path for deterministic results.
     */
    suspend fun findProjectFiles(globPattern: String): List<VirtualFile>

    /**
     * Find a PsiFile relative to the project base path.
     * Returns null if the file doesn't exist or can't be parsed.
     *
     * ```kotlin
     * val psiFile = findProjectPsiFile("src/main/kotlin/MyClass.kt")
     * ```
     */
    suspend fun findProjectPsiFile(relativePath: String): PsiFile?
}
