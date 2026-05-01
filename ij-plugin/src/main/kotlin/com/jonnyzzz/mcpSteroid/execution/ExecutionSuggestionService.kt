/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.prompts.generated.debugger.OverviewPromptArticle as DebuggerOverview

inline val Project.executionSuggestionService: ExecutionSuggestionService get() = service()

/**
 * Project-level service that generates error-specific suggestions/hints
 * for LLM agents after code execution completes.
 *
 * Called once at the end of execution, consolidating hint generation that
 * was previously scattered across CodeEvalManager, ScriptExecutor, and ExecutionManager.
 */
@Service(Service.Level.PROJECT)
class ExecutionSuggestionService(
    @Suppress("unused")
    private val project: Project,
) {

    /**
     * Brief reminder of critical rules - included in error responses.
     */
    private val criticalRules = buildString {
        appendLine("CRITICAL RULES:")
        appendLine("1. Code runs as a suspend script body")
        appendLine("2. Use smartReadAction {} for index-dependent PSI reads")
        appendLine("3. After project import/sync/configuration, await Observation.awaitConfiguration(project)")
        append("4. Never use runBlocking - you're already in a coroutine context")
    }

    /**
     * Generate suggestions based on the execution result.
     * Called once after execution completes, before the response is built.
     *
     * @param isFailed whether the execution failed
     * @param errorMessages collected error messages from the execution
     * @return list of suggestion strings (empty if no suggestions)
     */
    fun generateSuggestions(
        isFailed: Boolean,
        errorMessages: List<String>,
        userOutputCount: Int = -1,
    ): List<String> {
        if (!isFailed) {
            // Successful execution that printed NOTHING is the #1 reason agents
            // think `steroid_execute_code` is broken: their last expression's value
            // is not auto-printed (Kotlin script != REPL). Tell them once, plainly.
            if (userOutputCount == 0) return listOf(emptyOutputHint)
            return emptyList()
        }

        val combined = errorMessages.joinToString("\n")
        if (combined.isBlank()) return emptyList()

        val hint = computeHint(combined)
        return if (hint.isNotBlank()) listOf(hint) else emptyList()
    }

    /**
     * Hint shown when a script SUCCEEDED but produced zero user output.
     * Public for unit tests; pattern-matched on the `println(...)` substring.
     */
    val emptyOutputHint: String = "TIP: Script ran with NO output. The last expression's value is NOT auto-printed in steroid_execute_code (this is a Kotlin script, not a REPL). Wrap your final value in `println(value)` for plain text or `printJson(value)` for structured data, otherwise the agent sees an empty result."

    /**
     * Returns error-specific hint based on pattern matching against the error message.
     */
    fun computeHint(errorMessage: String): String {
        return when {
            errorMessage.contains("unresolved label", ignoreCase = true) &&
                (errorMessage.contains("executeSteroidCode", ignoreCase = true) ||
                    errorMessage.contains("executeSuspend", ignoreCase = true)) ->
                "TIP: Do not use return@executeSteroidCode or return@executeSuspend. Your script is already the suspend function body. " +
                    "Use plain return (or return@withContext inside withContext blocks)."

            errorMessage.contains("ApplicationConfiguration", ignoreCase = true) &&
                errorMessage.contains("constructor", ignoreCase = true) &&
                errorMessage.contains("protected", ignoreCase = true) ->
                "TIP: Do not construct ApplicationConfiguration(...) directly. Reuse an existing run configuration " +
                    "or create one via RunManager.createConfiguration(...) using ApplicationConfigurationType factory."

            errorMessage.contains("actual type is 'PsiFile', but 'VirtualFile' was expected", ignoreCase = true) ||
                (errorMessage.contains("unresolved reference 'path'", ignoreCase = true) &&
                    errorMessage.contains("PsiFile", ignoreCase = true)) ||
                (errorMessage.contains("unresolved reference 'url'", ignoreCase = true) &&
                    errorMessage.contains("PsiFile", ignoreCase = true)) ->
                "TIP: Use VirtualFile-based APIs. Find files with FilenameIndex.getVirtualFilesByName(...) " +
                    "or convert psiFile.virtualFile before calling FileDocumentManager/getting path/url."

            errorMessage.contains("unresolved reference 'findFiles'", ignoreCase = true) ||
                errorMessage.contains("unresolved reference 'contentsToByteArray'", ignoreCase = true) ->
                "TIP: findFiles(...) is not available in script context. Use findProjectFiles(globPattern) " +
                    "or FilenameIndex.getVirtualFilesByName(...), then read content with VfsUtilCore.loadText(virtualFile) " +
                    "or readAction { psiFile.text }."

            errorMessage.contains("Unresolved reference", ignoreCase = true) ->
                "TIP: Add missing top-level imports if needed. Imports are optional but must appear before code statements."

            errorMessage.contains("Dumb mode") ||
                errorMessage.contains("smart mode") ||
                errorMessage.contains("IndexNotReadyException") ->
                "TIP: For indexed PSI queries, use smartReadAction { ... } around the whole query. " +
                    "After opening/importing/syncing a project, call Observation.awaitConfiguration(project) first; " +
                    "waitForSmartMode() is only a point-in-time wait."

            errorMessage.contains("Read access") || errorMessage.contains("Write access") ->
                "TIP: Wrap PSI/VFS access in readAction {} or writeAction {}."

            errorMessage.contains("EDT", ignoreCase = true) ->
                "TIP: This operation requires EDT. Use: withContext(Dispatchers.EDT) { }"

            errorMessage.contains("JavaLineBreakpointProperties", ignoreCase = true) || errorMessage.contains("\"props\" is null") ->
                "TIP: Use XDebuggerUtil.toggleLineBreakpoint() instead of breakpointManager.addLineBreakpoint() with null properties. See ${DebuggerOverview().uri}"

            errorMessage.contains("breakpoint", ignoreCase = true) || errorMessage.contains("debug", ignoreCase = true) ->
                "TIP: For debugger help, see ${DebuggerOverview().uri} resource (run resources/list)"

            errorMessage.contains("runBlocking") ->
                "TIP: Never use runBlocking - the script body already runs in a coroutine context."

            // Note: "Service is dying" errors are now handled automatically with retry logic
            // in CodeEvalManager.kt. If this error reaches here, it means all retries failed.

            else -> criticalRules
        }
    }
}
