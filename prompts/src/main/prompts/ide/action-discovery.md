IDE: Discover IDE actions at caret

List IDE actions, intentions, quick-fixes and gutters at a caret position inside one `steroid_execute_code` call — no dedicated MCP tool.

# When to use this recipe

You need an action's ID to invoke it via `ActionManager.getInstance().getAction(id)` and you don't already know the id — or you want to confirm an intention / quick-fix / gutter action is enabled at a specific offset. Typical follow-on: fire `ActionUtil.performAction(action, event)` with a `DataContext` built from the editor.

## The recipe

```kotlin
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

// 1. Resolve file + open editor + position caret on EDT.
val filePath = "${project.basePath}/src/main/kotlin/com/example/Service.kt"
val caretOffset = 220

val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
    ?: error("File not found: $filePath")

val editor = withContext(Dispatchers.EDT) {
    val editors = FileEditorManager.getInstance(project).openFile(vf, true)
    editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
        ?: error("No text editor for $filePath")
}
val psiFile = readAction { PsiManager.getInstance(project).findFile(vf) }
    ?: error("PsiFile not found")
val document = readAction { PsiDocumentManager.getInstance(project).getDocument(psiFile) }
    ?: error("No document")
val safeOffset = caretOffset.coerceIn(0, document.textLength)

withContext(Dispatchers.EDT) {
    editor.caretModel.moveToOffset(safeOffset)
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
}

// 2. Wait for the daemon to finish so intentions/inspections are populated.
DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
val deadline = System.currentTimeMillis() + 15_000L
while (System.currentTimeMillis() < deadline) {
    if (!DaemonCodeAnalyzer.getInstance(project).isRunning) break
    kotlinx.coroutines.delay(150)
}
kotlinx.coroutines.delay(300)

// 3. Collect intentions / quick-fixes / inspections / gutters at the caret.
val intentions = readAction { ShowIntentionsPass.getActionsToShow(editor, psiFile) }

fun describe(d: HighlightInfo.IntentionActionDescriptor): String {
    val action = d.action
    return "  text=\"${action.text}\" family=\"${action.familyName}\" class=${action.javaClass.simpleName}"
}

println("=== INTENTIONS (${intentions.intentionsToShow.size}) ===")
intentions.intentionsToShow.forEach { println(describe(it)) }
println("=== ERROR FIXES (${intentions.errorFixesToShow.size}) ===")
intentions.errorFixesToShow.forEach { println(describe(it)) }
println("=== INSPECTION FIXES (${intentions.inspectionFixesToShow.size}) ===")
intentions.inspectionFixesToShow.forEach { println(describe(it)) }
println("=== GUTTERS (${intentions.guttersToShow.size}) ===")
intentions.guttersToShow.forEach { g -> println("  ${g.javaClass.simpleName}: $g") }

// 4. Optional: list editor-popup action ids (e.g. "EditorPopupMenu1", "EditorGutterPopupMenu").
val groupIds = listOf("EditorPopupMenu1", "EditorGutterPopupMenu")
for (id in groupIds) {
    val action = ActionManager.getInstance().getAction(id)
    println("group $id: ${if (action is ActionGroup) "present" else "missing"}")
}

// 5. Acting on the result. Each intention's `action.javaClass.simpleName`
//    is enough to identify a registered quick-fix; text + family name
//    describe what it does. To invoke a registered action by id (RunClass,
//    DebugClass, a refactoring), build a DataContext from the editor and
//    use ActionUtil.performAction.
val targetAction = ActionManager.getInstance().getAction("RunClass") ?: error("Action not registered")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val event = AnActionEvent.createEvent(
        dataContext, targetAction.templatePresentation.clone(), "EditorPopup", ActionUiKind.NONE, null,
    )
    ActionUtil.performAction(targetAction, event)
}
```

## Notes

- The `DaemonCodeAnalyzer` wait loop is required: `ShowIntentionsPass.getActionsToShow` returns whatever the daemon has computed at call time, so call it after the daemon settles.
- Place identifiers like `EditorPopup` (passed to `AnActionEvent.createEvent`) come from `ActionPlaces`; check `mcp-steroid://ide/run-configuration` and `mcp-steroid://prompt/test-skill` for context-action patterns by IDE.
- The caret matters: an action that depends on PSI context (refactoring, test runner) is enabled only when the caret is on the relevant identifier.

# See also

- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill)
- [Inspection + Quick Fix](mcp-steroid://ide/inspect-and-fix)
- [Run Configuration](mcp-steroid://ide/run-configuration)
- [Apply Patch — Atomic Multi-Site Edit](mcp-steroid://ide/apply-patch)
