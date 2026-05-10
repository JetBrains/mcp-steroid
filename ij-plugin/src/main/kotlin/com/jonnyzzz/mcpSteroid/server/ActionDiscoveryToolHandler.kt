/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class ActionDiscoveryResponse(
    val projectName: String,
    val filePath: String,
    val caretOffset: Int,
    val documentLength: Int,
    val languageId: String,
    val languageDisplayName: String,
    val fileType: String,
    val actionGroups: List<ActionGroupInfo>,
    val intentions: IntentionsPayload,
    val gutterIcons: List<GutterIconInfo>,
    val notes: List<String> = emptyList(),
)

@Serializable
data class ActionGroupInfo(
    val groupId: String,
    val place: String,
    val actions: List<ActionInfo>,
    val missing: Boolean = false,
)

@Serializable
data class ActionInfo(
    val id: String?,
    val text: String?,
    val description: String?,
    val className: String,
    val enabled: Boolean,
    val visible: Boolean,
    val isSeparator: Boolean = false,
)

@Serializable
data class IntentionsPayload(
    val intentions: List<IntentionActionInfo>,
    val errorFixes: List<IntentionActionInfo>,
    val inspectionFixes: List<IntentionActionInfo>,
    val notificationActions: List<IntentionActionInfo>,
    val gutterActions: List<ActionInfo>,
)

@Serializable
data class IntentionActionInfo(
    val text: String?,
    val familyName: String?,
    val displayName: String?,
    val className: String,
    val isError: Boolean,
    val isInformation: Boolean,
)

@Serializable
data class GutterIconInfo(
    val line: Int,
    val startOffset: Int,
    val endOffset: Int,
    val tooltip: String?,
    val clickAction: ActionInfo?,
    val popupActions: List<ActionInfo>,
)


class ActionDiscoveryToolHandlerIJ : ActionDiscoveryToolHandler {
    private val json = Json { encodeDefaults = true }

    override suspend fun discoverActions(projectName: String, actionDiscoveryParams: ActionDiscoveryParams): ToolCallResult {
        val project = readAction {
            ProjectManager.getInstance().openProjects.find { it.name == projectName }
        } ?: return ToolCallResult.errorResult("Project not found: $projectName")

        val groups = actionDiscoveryParams.actionGroups ?: listOf(IdeActions.GROUP_EDITOR_POPUP, IdeActions.GROUP_EDITOR_GUTTER)

        project.executionStorage.writeToolCall(
            toolName = "steroid_action_discovery",
            arguments = json.encodeToJsonElement(actionDiscoveryParams).jsonObject,
            taskId = actionDiscoveryParams.taskId
        )

        val filePath = actionDiscoveryParams.filePath

        val virtualFile = resolveVirtualFile(project, filePath)
            ?: return ToolCallResult.errorResult("File not found: $filePath")
        val psiFile = readAction { PsiManager.getInstance(project).findFile(virtualFile) }
            ?: return ToolCallResult.errorResult("PSI not available for: $filePath")
        val document = readAction { PsiDocumentManager.getInstance(project).getDocument(psiFile) }
            ?: return ToolCallResult.errorResult("No document for: $filePath")

        val safeOffset = actionDiscoveryParams.caretOffset.coerceIn(0, document.textLength)

        val textEditor = openTextEditor(project, virtualFile, safeOffset)
            ?: return ToolCallResult.errorResult("No text editor available for: $filePath")
        val editor = textEditor.editor
        if (editor.isDisposed) return ToolCallResult.errorResult("Editor disposed for: $filePath")

        awaitSmartMode(project)
        withContext(Dispatchers.EDT) { }
        withContext(Dispatchers.EDT) {
            if (!editor.isDisposed) {
                editor.caretModel.moveToOffset(safeOffset)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }

        DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "steroid_action_discovery")
        val highlightCompleted = awaitHighlighting(project, textEditor)

        val intentionsInfo = withContext(Dispatchers.Default) {
            readAction { ShowIntentionsPass.getActionsToShow(editor, psiFile) }
        }

        //TODO: looks like a bug that dataContext is not used
        val dataContext = withContext(Dispatchers.EDT) {
            DataManager.getInstance().getDataContext(editor.component)
        }

        // ActionGroup expansion was removed (TODO-INTERNAL-API.md): the previous
        // implementation called Utils.expandActionGroupSuspend (an @ApiStatus.Internal
        // platform API) and assumption is the field is unused by callers. We keep the
        // schema and surface the group's missing/present status so the response shape
        // stays compatible.
        val actionGroupInfo = groups.map { groupId ->
            val place = placeForGroup(groupId)
            val action = ActionManager.getInstance().getAction(groupId)
            ActionGroupInfo(
                groupId = groupId,
                place = place,
                actions = emptyList(),
                missing = action !is ActionGroup
            )
        }

        val gutterIcons = collectGutterIcons(project, document)

        val notes = buildList {
            if (!highlightCompleted) {
                add("Daemon highlighting did not complete within the timeout; results may be partial.")
            }
        }

        val response = ActionDiscoveryResponse(
            projectName = project.name,
            filePath = virtualFile.path,
            caretOffset = safeOffset,
            documentLength = document.textLength,
            languageId = psiFile.language.id,
            languageDisplayName = psiFile.language.displayName,
            fileType = psiFile.fileType.name,
            actionGroups = actionGroupInfo,
            intentions = IntentionsPayload(
                // Severity is inferred from list membership instead of calling @Internal
                // IntentionActionDescriptor.isError()/isInformation() methods
                intentions = intentionsInfo.intentionsToShow.map { toIntentionActionInfo(it, isError = false, isInformation = true) },
                errorFixes = intentionsInfo.errorFixesToShow.map { toIntentionActionInfo(it, isError = true, isInformation = false) },
                inspectionFixes = intentionsInfo.inspectionFixesToShow.map { toIntentionActionInfo(it, isError = false, isInformation = false) },
                notificationActions = intentionsInfo.notificationActionsToShow.map { toIntentionActionInfo(it, isError = false, isInformation = false) },
                gutterActions = intentionsInfo.guttersToShow.map { toActionInfo(it, null) }
            ),
            gutterIcons = gutterIcons,
            notes = notes
        )

        val json = McpJson.encodeToString(ActionDiscoveryResponse.serializer(), response)
        return ToolCallResult(content = listOf(ContentItem.Text(text = json)))
    }

    private suspend fun resolveVirtualFile(project: Project, filePath: String): VirtualFile? {
        val trimmed = filePath.trim()
        if (trimmed.isEmpty()) return null
        val urlCandidates = buildList {
            add(trimmed)
            if (!trimmed.contains("://") && trimmed.contains(":/")) {
                add(trimmed.replaceFirst(":/", "://"))
                add(trimmed.replaceFirst(":/", ":///"))
            }
        }

        val vfs = VirtualFileManager.getInstance()
        for (candidate in urlCandidates) {
            readAction { vfs.findFileByUrl(candidate) }?.let { return it }
        }

        val isAbsolute = runCatching { Path.of(trimmed).isAbsolute }.getOrDefault(false)
        val fs = LocalFileSystem.getInstance()
        if (isAbsolute) {
            readAction { fs.findFileByPath(trimmed) }?.let { return it }
        }

        val basePath = project.basePath ?: project.guessProjectDir()?.path ?: return null
        val relative = trimmed.trimStart('/')
        val path = "$basePath/$relative"
        return readAction { fs.findFileByPath(path) }
    }

    private suspend fun openTextEditor(project: Project, file: VirtualFile, caretOffset: Int): TextEditor? {
        return withContext(Dispatchers.EDT) {
            val editors = FileEditorManager.getInstance(project).openFile(file, true)
            val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
            if (textEditor != null && !textEditor.editor.isDisposed) {
                val editor = textEditor.editor
                val safeOffset = caretOffset.coerceIn(0, editor.document.textLength)
                editor.caretModel.moveToOffset(safeOffset)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
            textEditor
        }
    }

    private suspend fun awaitSmartMode(project: Project) {
        if (!DumbService.isDumb(project)) return
        suspendCancellableCoroutine { cont ->
            fun waitForSmart() {
                DumbService.getInstance(project).smartInvokeLater {
                    if (DumbService.isDumb(project)) {
                        waitForSmart()
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
            waitForSmart()
        }
    }

    private suspend fun awaitHighlighting(project: Project, editor: TextEditor): Boolean {
        return withTimeoutOrNull(10_000.milliseconds) {
            while (!DaemonCodeAnalyzerEx.isHighlightingCompleted(editor, project)) {
                delay(50.milliseconds)
            }
            true
        } ?: false
    }

    private suspend fun collectGutterIcons(
        project: Project,
        document: Document,
    ): List<GutterIconInfo> {
        // Use public MarkupModel API instead of @Internal DaemonCodeAnalyzerImpl.getLineMarkers()
        val lineMarkers = readAction {
            val markupModel = DocumentMarkupModel.forDocument(document, project, true)
            markupModel.allHighlighters
                .filter { it.isValid }
                .mapNotNull { highlighter ->
                    (highlighter.gutterIconRenderer as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)
                        ?.lineMarkerInfo
                }
        }
        return lineMarkers.mapNotNull { marker ->
            val renderer = marker.createGutterRenderer() ?: return@mapNotNull null
            val clickAction = renderer.clickAction?.let { toActionInfo(it, null) }
            // popupActions used to expand renderer.popupMenuActions via the
            // @ApiStatus.Internal Utils.expandActionGroupSuspend; that path is
            // dropped (TODO-INTERNAL-API.md). Field kept for response shape.
            val popupActions: List<ActionInfo> = emptyList()
            val tooltipText = readAction { renderer.tooltipText }
            GutterIconInfo(
                line = document.getLineNumber(marker.startOffset) + 1,
                startOffset = marker.startOffset,
                endOffset = marker.endOffset,
                tooltip = tooltipText,
                clickAction = clickAction,
                popupActions = popupActions
            )
        }
    }

    private fun toIntentionActionInfo(
        descriptor: HighlightInfo.IntentionActionDescriptor,
        isError: Boolean,
        isInformation: Boolean,
    ): IntentionActionInfo {
        val action = descriptor.action
        return IntentionActionInfo(
            text = action.text,
            familyName = action.familyName,
            displayName = descriptor.displayName,
            className = action.javaClass.name,
            isError = isError,
            isInformation = isInformation,
        )
    }

    private fun toActionInfo(action: AnAction, presentation: Presentation?): ActionInfo {
        val template = action.templatePresentation
        return ActionInfo(
            id = ActionManager.getInstance().getId(action),
            text = presentation?.text ?: template.text,
            description = presentation?.description ?: template.description,
            className = action.javaClass.name,
            enabled = presentation?.isEnabled ?: template.isEnabled,
            visible = presentation?.isVisible ?: template.isVisible,
            isSeparator = action is Separator
        )
    }

    private fun placeForGroup(groupId: String): String = when (groupId) {
        IdeActions.GROUP_EDITOR_GUTTER -> ActionPlaces.EDITOR_GUTTER_POPUP
        IdeActions.GROUP_EDITOR_TAB_POPUP -> ActionPlaces.EDITOR_TAB_POPUP
        else -> ActionPlaces.EDITOR_POPUP
    }
}
