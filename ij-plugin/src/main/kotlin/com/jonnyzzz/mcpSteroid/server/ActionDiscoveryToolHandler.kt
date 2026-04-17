/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.Utils
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
import com.jonnyzzz.mcpSteroid.mcp.*
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.coroutines.resume

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

/**
 * Handler for the steroid_action_discovery MCP tool.
 */
class ActionDiscoveryToolHandler : McpRegistrar {
    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_action_discovery",
            description = "Discover available editor actions, quick-fixes, and gutter actions for a file and caret offset.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("project_name") {
                        put("type", "string")
                        put("description", "Name of the project containing the file (from steroid_list_projects).")
                    }
                    putJsonObject("file_path") {
                        put("type", "string")
                        put("description", "Absolute path or project-relative path to the file.")
                    }
                    putJsonObject("caret_offset") {
                        put("type", "integer")
                        put("description", "Caret offset within the file (default: 0).")
                    }
                    putJsonObject("action_groups") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Optional list of action group IDs to expand (default: editor popup + gutter).")
                    }
                    putJsonObject("max_actions_per_group") {
                        put("type", "integer")
                        put("description", "Limit the number of actions returned per action group (default: 200).")
                    }
                    putJsonObject("task_id") {
                        put("type", "string")
                        put("description", "Optional task ID for log grouping.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("project_name"))
                    add(JsonPrimitive("file_path"))
                }
            },
            ::handle
        )
    }

    private suspend fun handle(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments ?: return errorResult("Missing arguments")
        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        val filePath = args["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: file_path")
        val caretOffset = args["caret_offset"]?.jsonPrimitive?.intOrNull ?: 0
        val actionGroups = parseActionGroups(args["action_groups"]?.jsonArray)
        val maxActions = args["max_actions_per_group"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 200
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull

        val project = readAction {
            ProjectManager.getInstance().openProjects.find { it.name == projectName }
        } ?: return errorResult("Project not found: $projectName")

        project.executionStorage.writeToolCall(
            toolName = "steroid_action_discovery",
            arguments = context.params.arguments,
            taskId = taskId
        )

        val virtualFile = resolveVirtualFile(project, filePath)
            ?: return errorResult("File not found: $filePath")
        val psiFile = readAction { PsiManager.getInstance(project).findFile(virtualFile) }
            ?: return errorResult("PSI not available for: $filePath")
        val document = readAction { PsiDocumentManager.getInstance(project).getDocument(psiFile) }
            ?: return errorResult("No document for: $filePath")
        val safeOffset = caretOffset.coerceIn(0, document.textLength)

        val textEditor = openTextEditor(project, virtualFile, safeOffset)
            ?: return errorResult("No text editor available for: $filePath")
        val editor = textEditor.editor
        if (editor.isDisposed) return errorResult("Editor disposed for: $filePath")

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

        val dataContext = withContext(Dispatchers.EDT) {
            DataManager.getInstance().getDataContext(editor.component)
        }

        val actionGroupInfo = actionGroups.map { groupId ->
            val place = placeForGroup(groupId)
            val action = ActionManager.getInstance().getAction(groupId)
            if (action !is ActionGroup) {
                ActionGroupInfo(
                    groupId = groupId,
                    place = place,
                    actions = emptyList(),
                    missing = true
                )
            } else {
                val actions = expandActionGroup(action, dataContext, place, maxActions)
                ActionGroupInfo(
                    groupId = groupId,
                    place = place,
                    actions = actions
                )
            }
        }

        val gutterIcons = collectGutterIcons(project, document, dataContext, maxActions)

        val notes = buildList<String> {
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

    private fun parseActionGroups(array: JsonArray?): List<String> {
        array ?: return listOf(IdeActions.GROUP_EDITOR_POPUP, IdeActions.GROUP_EDITOR_GUTTER)
        val groups = array
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return groups
    }

    private fun resolveVirtualFile(project: Project, filePath: String): VirtualFile? {
        val trimmed = filePath.trim()
        if (trimmed.isEmpty()) return null
        val vfs = VirtualFileManager.getInstance()
        val urlCandidates = buildList {
            add(trimmed)
            if (!trimmed.contains("://") && trimmed.contains(":/")) {
                add(trimmed.replaceFirst(":/", "://"))
                add(trimmed.replaceFirst(":/", ":///"))
            }
        }
        for (candidate in urlCandidates) {
            vfs.findFileByUrl(candidate)?.let { return it }
        }

        val isAbsolute = runCatching { Path.of(trimmed).isAbsolute }.getOrDefault(false)
        val fs = LocalFileSystem.getInstance()
        if (isAbsolute) {
            fs.findFileByPath(trimmed)?.let { return it }
        }

        val basePath = project.basePath ?: project.guessProjectDir()?.path ?: return null
        val relative = trimmed.trimStart('/')
        val path = "$basePath/$relative"
        return fs.findFileByPath(path)
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
        return withTimeoutOrNull(10_000) {
            while (!DaemonCodeAnalyzerEx.isHighlightingCompleted(editor, project)) {
                delay(50)
            }
            true
        } ?: false
    }

    private suspend fun expandActionGroup(
        group: ActionGroup,
        dataContext: DataContext,
        place: String,
        maxActions: Int,
    ): List<ActionInfo> {
        if (maxActions <= 0) return emptyList()
        val presentationFactory = PresentationFactory()
        val actions = withContext(Dispatchers.EDT) {
            Utils.expandActionGroupSuspend(
                group,
                presentationFactory,
                dataContext,
                place,
                ActionUiKind.POPUP,
                false
            )
        }
        return actions.take(maxActions).map { action ->
            val presentation = presentationFactory.getPresentation(action)
            toActionInfo(action, presentation)
        }
    }

    private suspend fun collectGutterIcons(
        project: Project,
        document: Document,
        dataContext: DataContext,
        maxActions: Int,
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
            val popupGroup = renderer.popupMenuActions
            val popupActions = if (popupGroup is ActionGroup && maxActions > 0) {
                expandActionGroup(popupGroup, dataContext, ActionPlaces.EDITOR_GUTTER_POPUP, maxActions)
            } else {
                emptyList()
            }
            GutterIconInfo(
                line = document.getLineNumber(marker.startOffset) + 1,
                startOffset = marker.startOffset,
                endOffset = marker.endOffset,
                tooltip = renderer.tooltipText,
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

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = "ERROR: $message")),
        isError = true
    )
}
