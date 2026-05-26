Add Inline Breakpoint (Lambda / Variant)

Stop on a specific lambda or sub-statement of a line — pick a breakpoint variant instead of the default whole-line position.

## When to use this

A line breakpoint stops at the **first** executable instruction on the line. For multi-statement Kotlin/Java lines that contain lambdas or chained calls, the default position is often the wrong one. Examples:

- `collection.map { it.someMethod().plus(42) }` — default stops at `collection.map(...)` call entry; you usually want to stop inside `{ it.someMethod()... }` (the lambda body).
- `list.filter { it > 0 }.map { it * 2 }` — two lambdas on one line; choose which one.
- Kotlin `return if (x) a else b` lines that compile to a conditional return — pick the *conditional return* variant.

IntelliJ exposes these via `XLineBreakpointType.computeVariantsAsync(project, position)`. Each variant carries the precise source range it covers (`variant.getHighlightRange()`) and a human-readable label (`variant.getText()` — e.g., `"Line"`, `"Lambda body"`, `"Lambda 1 of 2"`). Setting a breakpoint with `variant.createProperties()` instructs the JVM debugger to stop only at the variant's bytecode location — still a single scoped `BreakpointRequest`, just at a non-line-start position.

## Script

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import org.jetbrains.concurrency.await
import java.nio.file.Paths

val filePath = "src/main/kotlin/com/example/MyClass.kt"
val lineNumberInEditor = 49           // editor line, 1-based
val variantHint = "lambda"            // case-insensitive substring of variant.getText(); use "line" for whole-line

val projectRoot = project.basePath ?: error("Project basePath is null")
val absolutePath = Paths.get(projectRoot, filePath).toString()
val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
    ?: error("File not found: $absolutePath")

val lineIndex = lineNumberInEditor - 1  // API is 0-indexed

// Find the matching breakpoint type for this file+line
val rawType = readAction {
    XDebuggerUtil.getInstance().lineBreakpointTypes
        .firstOrNull { it.canPutAt(virtualFile, lineIndex, project) }
} ?: error("No breakpoint type available for $filePath:$lineNumberInEditor")

@Suppress("UNCHECKED_CAST")
val bpType = rawType as XLineBreakpointType<XBreakpointProperties<*>>

// Build an XSourcePosition for the line
val sourcePosition = readAction {
    XDebuggerUtil.getInstance().createPosition(virtualFile, lineIndex)
        ?: error("Could not create source position for $filePath:$lineNumberInEditor")
}

// Ask the breakpoint type for variants on this line.
// computeVariantsAsync returns org.jetbrains.concurrency.Promise — await it as a suspending call.
val variants = readAction { bpType.computeVariantsAsync(project, sourcePosition) }.await()

if (variants.isEmpty()) {
    println("No variants for $filePath:$lineNumberInEditor — language has only the whole-line position.")
    println("Use mcp-steroid://debugger/add-breakpoint instead.")
    return
}

// Print the variants so the agent can see what's available on this line
println("Variants on $filePath:$lineNumberInEditor:")
variants.forEachIndexed { i, v ->
    val r = v.highlightRange
    val rangeText = if (r != null) "[${r.startOffset}..${r.endOffset}]" else "[whole line]"
    println("  #$i $rangeText  ${v.text}")
}

// Choose a variant by hint (case-insensitive substring of getText()).
// Common variant texts include "Line", "Lambda body", "Lambda 1", "Conditional return".
val chosen = variants.firstOrNull { it.text.contains(variantHint, ignoreCase = true) }
    ?: error("No variant matches hint \"$variantHint\". Available: ${variants.map { it.text }}")

println("Chosen variant: ${chosen.text}")

// Idempotency: if a matching breakpoint already exists at this line+variant, do nothing.
val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
val existingAtLine = breakpointManager.findBreakpointsAtLine(bpType, virtualFile, lineIndex)
val alreadyHere = existingAtLine.any { bp -> bpType.variantAndBreakpointMatch(bp, chosen) }
if (alreadyHere) {
    println("Breakpoint for variant \"${chosen.text}\" already exists at $filePath:$lineNumberInEditor")
    return
}

// createProperties() encodes the variant's exact location (e.g., lambda ordinal for JavaLineBreakpointProperties)
val properties = readAction { chosen.createProperties() }
// Same write-intent contract as the plain add-breakpoint recipe: the
// Kotlin breakpoint type's listeners assert write-intent during install.
// Bare `readAction` fails that assertion; `writeAction` is the wrong
// contract for this site.
val breakpoint = writeIntentReadAction {
    breakpointManager.addLineBreakpoint(bpType, virtualFile.url, lineIndex, properties)
}
println("Created breakpoint at $filePath:$lineNumberInEditor [${chosen.text}]: $breakpoint")
```

## Notes

```kotlin
// Notes on the variant API:
//
// 1. variants.text values you will see in practice:
//    - "Line"                       — whole-line position (same as add-breakpoint.md)
//    - "Lambda body"                — single lambda on the line
//    - "Lambda 1", "Lambda 2", ...  — multiple lambdas on the same line
//    - "Line and all Lambdas"       — multi-variant: stops at every position (rare, expensive)
//    - "Conditional return"         — Kotlin conditional-return position
//
// 2. variant.highlightRange gives the precise source range the variant covers.
//    Cross-reference it against the line's text from Document to pick reliably:
//      val doc = FileDocumentManager.getInstance().getDocument(virtualFile)
//      val text = doc?.getText(variant.highlightRange!!)
//
// 3. Do NOT pick the "Line and all Lambdas" multi-variant programmatically — it
//    sets a stop at every sub-position on the line, which is what makes whole-line
//    breakpoints on lambda-heavy code slow. Prefer the most specific variant.
//
// 4. For Rider/C# and other non-Java languages, computeVariantsAsync usually
//    returns an empty list — the language only exposes the whole-line position.
//    The script above falls back gracefully; use add-breakpoint.md for those.
//
// 5. addLineBreakpoint(type, fileUrl, lineIndex, properties) accepts the
//    variant's properties directly — internally this is what
//    XDebuggerUtilImpl.addLineBreakpoint(manager, variant, file, line) does.
println("See script above for the complete variant-aware add-breakpoint pattern")
```

# See also

Related debugger operations:
- [Add Breakpoint](mcp-steroid://debugger/add-breakpoint) - Whole-line variant (single-statement lines, simple cases)
- [Remove Breakpoint](mcp-steroid://debugger/remove-breakpoint) - Remove breakpoints from a line
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debug session

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Choosing the right stopping primitive
