/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Atomic multi-site literal-text patch for MCP Steroid agents.
 *
 * Exposed on [McpScriptContext] as a single `applyPatch { hunk(...); hunk(...) }`
 * call so the agent ships **data only** — no readAction / writeAction /
 * CommandProcessor boilerplate. The script source for a 3-hunk patch shrinks
 * from ~90 lines of Kotlin to ~5 lines, with identical atomicity guarantees.
 *
 * Semantics:
 * - Pre-flight (read action): every `oldString` must be present **exactly once**
 *   in its file. Missing or non-unique hunks throw [ApplyPatchException] with
 *   hunk index, path, and offset info — no edits land.
 * - Apply (write action + single CommandProcessor command): every
 *   `Document.replaceString` call combines into one undoable step. Multi-hunk
 *   edits in the same file are applied in **descending offset order** so earlier
 *   edits don't shift later ones.
 * - Commit + save: `PsiDocumentManager.commitAllDocuments()` flushes into PSI
 *   and every touched document is saved so native tools and build processes see
 *   the updated file bytes immediately after the tool returns.
 * - Tail-of-exec VFS refresh (non-blocking) is already scheduled by
 *   `ExecutionManager`; callers never need to force one.
 */
class ApplyPatchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ApplyPatchHunk(val filePath: String, val oldString: String, val newString: String)

data class AppliedHunk(
    val index: Int,
    val path: String,
    val line: Int,      // 1-based
    val column: Int,    // 1-based
    val oldLen: Int,
    val newLen: Int,
)

data class ApplyPatchResult(val applied: List<AppliedHunk>) {
    val hunkCount: Int get() = applied.size
    val fileCount: Int get() = applied.map { it.path }.distinct().size

    /**
     * Multi-line audit trail suitable for `println(result)`. Example:
     *
     * ```
     * apply-patch: 3 hunks across 2 file(s) applied atomically.
     *   [#0] /abs/A.java:17:5  (12→18 chars)
     *   [#1] /abs/A.java:42:9  (5→9 chars)
     *   [#2] /abs/B.java:88:13 (7→5 chars)
     * ```
     */
    override fun toString(): String = buildString {
        append("apply-patch: ")
        append(hunkCount)
        append(" hunk")
        if (hunkCount != 1) append("s")
        append(" across ")
        append(fileCount)
        append(" file(s) applied atomically.")
        applied.forEach { h ->
            append("\n  [#").append(h.index).append("] ").append(h.path)
            append(':').append(h.line).append(':').append(h.column)
            append("  (").append(h.oldLen).append("→").append(h.newLen).append(" chars)")
        }
    }
}

/**
 * Builder for the `applyPatch { … }` DSL. Instances are single-use — one call
 * per `applyPatch { }` invocation.
 */
class ApplyPatchBuilder internal constructor() {
    internal val hunks = mutableListOf<ApplyPatchHunk>()

    /**
     * Register one literal-text substitution. `oldString` must occur exactly
     * once in the file at [filePath]; if it doesn't, the whole patch aborts
     * before any edit lands. `filePath` is an absolute filesystem path.
     */
    fun hunk(filePath: String, oldString: String, newString: String) {
        hunks += ApplyPatchHunk(filePath, oldString, newString)
    }
}

internal suspend fun executeApplyPatch(
    project: Project,
    hunks: List<ApplyPatchHunk>,
    resolveFile: (String) -> VirtualFile?,
): ApplyPatchResult {
    require(hunks.isNotEmpty()) { "applyPatch { … } called with zero hunks" }

    // Pre-flight: resolve path -> Document, validate single-occurrence per hunk,
    // capture line/column now while we hold the read action.
    data class ResolvedHunk(
        val index: Int,
        val filePath: String,
        val document: Document,
        val offset: Int,
        val oldLen: Int,
        val newString: String,
        val line: Int,
        val column: Int,
    )

    val resolved: List<ResolvedHunk> = readAction {
        hunks.mapIndexed { index, hunk ->
            // Caller (McpScriptContextImpl) supplies a resolver that handles
            // LocalFileSystem, light-test temp://, and mock VFS consistently.
            val vf: VirtualFile = resolveFile(hunk.filePath)
                ?: throw ApplyPatchException(
                    "Hunk #$index: file not found: ${hunk.filePath}"
                )
            if (!vf.isWritable) {
                throw ApplyPatchException(
                    "Hunk #$index: file is read-only: ${hunk.filePath}"
                )
            }
            val document = FileDocumentManager.getInstance().getDocument(vf)
                ?: throw ApplyPatchException(
                    "Hunk #$index: no Document available for ${hunk.filePath}"
                )
            val text = document.text
            val first = text.indexOf(hunk.oldString)
            if (first < 0) {
                throw ApplyPatchException(
                    "Hunk #$index: old_string not found in ${hunk.filePath} — verify with Grep first"
                )
            }
            val second = text.indexOf(hunk.oldString, first + 1)
            if (second >= 0) {
                throw ApplyPatchException(
                    "Hunk #$index: old_string occurs more than once in ${hunk.filePath} " +
                        "(first at offset $first, next at $second) — " +
                        "expand old_string with surrounding context to make it unique"
                )
            }
            val lineIdx = document.getLineNumber(first)
            val column = first - document.getLineStartOffset(lineIdx)
            ResolvedHunk(
                index = index,
                filePath = hunk.filePath,
                document = document,
                offset = first,
                oldLen = hunk.oldString.length,
                newString = hunk.newString,
                line = lineIdx + 1,
                column = column + 1,
            )
        }
    }

    // Group by Document and apply in DESCENDING offset order so earlier
    // replacements don't shift later offsets. Edits from different files can
    // run in any order; the descending rule applies per file.
    val groupedDescending = resolved.groupBy { it.document }
        .mapValues { (_, hs) -> hs.sortedByDescending { it.offset } }

    val commandName = "MCP Steroid: apply-patch (${resolved.size} hunk${if (resolved.size == 1) "" else "s"})"

    // The write phase runs on the EDT with write-lock + CommandProcessor undo group.
    //
    // Modality: `ModalityState.any()` is explicitly NOT safe here (per JavaDoc at
    // platform/core-api/src/com/intellij/openapi/application/ModalityState.java —
    // `.any()` is for purely UI helpers and must not touch PSI/VFS/model). We use
    // `nonModal()` so dispatch waits until any modal dialog dismisses.
    //
    // `withContext(Dispatchers.EDT + …)` detects the already-on-EDT case and runs
    // inline. Tests that want to drive this path must NOT block EDT in the caller
    // — see `ApplyPatchTest`, which overrides `runInDispatchThread() = false` so
    // `timeoutRunBlocking` runs on the JUnit worker thread and leaves EDT free
    // to dispatch the write phase (an EDT-parked-in-runBlocking caller would
    // deadlock here).
    withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
        WriteCommandAction.runWriteCommandAction(project, commandName, null, Runnable {
            val fileDocumentManager = FileDocumentManager.getInstance()
            for ((_, hunksInFile) in groupedDescending) {
                for (h in hunksInFile) {
                    h.document.replaceString(h.offset, h.offset + h.oldLen, h.newString)
                }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            for ((document, hunksInFile) in groupedDescending) {
                val filePath = hunksInFile.first().filePath
                try {
                    fileDocumentManager.saveDocument(document)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw ApplyPatchException("Failed to save patched document to disk: $filePath", e)
                }
                if (fileDocumentManager.isDocumentUnsaved(document)) {
                    throw ApplyPatchException(
                        "Failed to save patched document to disk: $filePath"
                    )
                }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        })
    }

    return ApplyPatchResult(
        applied = resolved.map { h ->
            AppliedHunk(
                index = h.index,
                path = h.filePath,
                line = h.line,
                column = h.column,
                oldLen = h.oldLen,
                newLen = h.newString.length,
            )
        }
    )
}
