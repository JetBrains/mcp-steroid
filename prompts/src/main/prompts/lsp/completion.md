LSP: textDocument/completion - Code Completion

This example demonstrates how to get code completion suggestions

```kotlin
import com.intellij.codeInsight.completion.CompletionContributor

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val line = 10      // TODO: 1-based line number
val column = 15    // TODO: 1-based column number (position where completion is triggered)
val maxResults = 20  // Maximum number of results to return


// Find the virtual file
val virtualFile = findFile(filePath)
    ?: return println("File not found: $filePath")

val (psiFile, document) = readAction {
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
    psiFile to document
}
if (psiFile == null) {
    return println("Cannot parse file: $filePath")
}
if (document == null) {
    return println("Cannot get document for: $filePath")
}

// Convert line/column to offset
val offset = document.getLineStartOffset(line - 1) + (column - 1)

val result = readAction {
    // Get element at position
    val element = psiFile.findElementAt(offset)
        ?: psiFile.findElementAt(offset - 1)
        ?: return@readAction "No element at position ($line:$column)"

    // Get completion contributors registered for this file's language via the
    // public extension point (CompletionContributor.EP, "com.intellij.completion.contributor").
    // A bean's `language` attribute is a Language ID; "any" or empty matches all languages.
    val languageIds = generateSequence(psiFile.language) { it.baseLanguage }.map { it.id }.toSet()
    val contributors = CompletionContributor.EP.extensionList.filter { ep ->
        ep.language.isNullOrEmpty() || ep.language == "any" || ep.language in languageIds
    }

    buildString {
        appendLine("Completion at $filePath:$line:$column")
        appendLine("=========================================")
        appendLine()
        appendLine("Context element: ${element.text?.take(30)}")
        appendLine("Element type: ${element.javaClass.simpleName}")
        appendLine()

        // Check what's available at this position
        val parent = element.parent
        appendLine("Parent: ${parent?.javaClass?.simpleName}")
        appendLine()

        // List available contributors
        appendLine("Available completion contributors:")
        contributors.take(5).forEach { ep ->
            appendLine("  - ${ep.implementationClass}")
        }
        appendLine()

        // For a real completion, you would need to set up the full
        // completion infrastructure. Here we show what's available:
        appendLine("Note: Full programmatic completion requires")
        appendLine("      setting up CompletionProcess which is")
        appendLine("      typically triggered by user action.")
        appendLine()
        appendLine("Alternative approaches:")
        appendLine("1. Use the IDE's completion action directly")
        appendLine("2. Analyze the PSI context to suggest completions")
        appendLine("3. Use CodeInsightTestCase for testing completions")
    }
}

println(result)
```

# See also

IDE power operations:
- [Extract Method](mcp-steroid://ide/extract-method) - Refactoring example

Overview resources:
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
