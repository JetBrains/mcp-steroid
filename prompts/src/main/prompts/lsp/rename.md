LSP: textDocument/rename - Rename Symbol

Semantic, cross-file rename using IntelliJ's RenameProcessor — atomic, PSI-backed, updates all references.

```kotlin
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.usageView.UsageInfo

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val line = 10      // TODO: 1-based line number where symbol is defined or used
val column = 15    // TODO: 1-based column number
val newName = "newSymbolName"  // TODO: Set the new name
val dryRun = true  // Set to false to actually perform the rename

// Semantic rename: resolves the PsiNamedElement at (line, column), finds all usages
// through the type system (not text), and rewrites them atomically. This handles
// imports, qualified references, overrides, interface implementations, and string
// literals in annotations — things a `Regex("\b${name}\b") + replaceString` pass misses.
//
// If the position does not resolve to a PsiNamedElement (plain text, comment, unsupported
// language), this recipe prints a diagnostic and stops. Fall back to the built-in Edit
// tool with `replace_all=true` for pure-text rename in that case.

data class RenamePlan(
    val element: PsiNamedElement,
    val oldName: String,
    val usages: Array<UsageInfo>,
    val processor: RenameProcessor,
    val analysis: String,
)

// Returns either an error message (String) when the position does not resolve to a
// renameable declaration, or a fully-populated RenamePlan with the rendered analysis.
val plan: Any = readAction {
    val virtualFile = findFile(filePath)
        ?: return@readAction "File not found: $filePath"

    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        ?: return@readAction "Cannot parse file: $filePath"

    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@readAction "Cannot get document for: $filePath"

    val offset = document.getLineStartOffset(line - 1) + (column - 1)

    val elementAtOffset = psiFile.findElementAt(offset)
        ?: return@readAction "No element at position ($line:$column)"

    // Walk up to the nearest PsiNamedElement (declaration). If the caret is on a
    // reference usage, resolve through the reference to the declaration.
    val named = PsiTreeUtil.getParentOfType(elementAtOffset, PsiNamedElement::class.java, false)
        ?: (elementAtOffset.reference?.resolve() as? PsiNamedElement)
        ?: return@readAction "No PsiNamedElement at ($line:$column). " +
            "Semantic rename is not applicable. Fall back to Edit(replace_all=true) for text-only rename."

    val oldName = named.name ?: return@readAction "Element at ($line:$column) is unnamed"

    // Build the rename processor. Do NOT search in comments/strings by default —
    // the semantic rename handles real references; comment/string edits are usually
    // unwanted side effects. Flip the booleans below to opt in.
    val processor = RenameProcessor(project, named, newName,
        /* isSearchInComments = */ false,
        /* isSearchTextOccurrences = */ false)

    // Pre-compute usages for the dry-run preview. Also build the rendered analysis
    // text here so every PSI/document property access stays inside the read action.
    val usages = processor.findUsages()
    val analysis = buildString {
        appendLine("Rename (semantic, PSI-backed): $oldName -> $newName")
        appendLine("Declaration: ${named.javaClass.simpleName}")
        appendLine("Usages across project: ${usages.size}")
        appendLine()
        usages.take(20).forEach { u ->
            val uVf = u.virtualFile?.path ?: "<unknown>"
            val uDoc = u.file?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
            val uOff = u.navigationOffset
            val uLine = if (uDoc != null && uOff >= 0) uDoc.getLineNumber(uOff) + 1 else -1
            appendLine("  - $uVf:$uLine")
        }
        if (usages.size > 20) {
            appendLine("  ... and ${usages.size - 20} more")
        }
    }
    RenamePlan(named, oldName, usages, processor, analysis)
}

if (plan is String) {
    println(plan)
    return
}

val renamePlan = plan as RenamePlan

if (dryRun) {
    println(renamePlan.analysis)
    println()
    println("(Dry run - no changes made. Set dryRun=false to perform the rename.)")
    return
}

// Apply the rename atomically. RenameProcessor wraps the whole update in a single
// CommandProcessor command, so it is undoable as one unit. Either every reference
// updates or none do. Use writeIntentReadAction (not writeAction): refactoring
// processors perform their own read/write action management internally, matching
// the pattern used by safe-delete / move-class / change-signature recipes.
writeIntentReadAction { renamePlan.processor.run() }
writeAction { PsiDocumentManager.getInstance(project).commitAllDocuments() }

println(renamePlan.analysis)
println()
println("Rename applied: ${renamePlan.oldName} -> $newName (${renamePlan.usages.size} usages updated atomically)")
```

# See also

IDE power operations:
- [Change Signature](mcp-steroid://ide/change-signature) - Add/reorder parameters
- [Move Class](mcp-steroid://ide/move-class) - Move classes between packages

Overview resources:
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
