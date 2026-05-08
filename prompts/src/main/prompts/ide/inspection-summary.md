IDE: Inspection Summary

Lists every enabled inspection (e.g. `DuplicatedCode` for duplicate code / clones / DRY, `RedundantCast`, `UnusedDeclaration`). Find a short-name before targeting it.

```kotlin
import com.intellij.profile.codeInspection.InspectionProjectProfileManager

// Configuration - modify these for your use case
val maxInspections = 50


val result = readAction {
    val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
    @Suppress("UNCHECKED_CAST")
    val tools = profile.getAllEnabledInspectionTools(project) as List<Any?>
    val states = tools.mapNotNull { item ->
        when (item) {
            is com.intellij.codeInspection.ex.ScopeToolState -> item
            is com.intellij.codeInspection.ex.Tools -> item.defaultState
            else -> null
        }
    }

    buildString {
        appendLine("Enabled inspections (${states.size}):")
        appendLine()
        states.take(maxInspections).forEach { state ->
            val tool = state.tool
            val shortName = tool.shortName
            val displayName = tool.displayName
            val group = tool.groupDisplayName
            appendLine("  - $shortName: $displayName ($group)")
        }
        if (states.size > maxInspections) {
            appendLine()
            appendLine("... and ${states.size - maxInspections} more inspections")
        }
    }
}

println(result)
```

# See also

- [Inspection + Quick Fix](mcp-steroid://ide/inspect-and-fix) - Run a *single named* inspection on a file and apply its quick fix
- [Find Duplicate Code](mcp-steroid://ide/find-duplicates) - Run the bundled `DuplicatedCode` inspection across the project and walk every clone cluster typed
- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
