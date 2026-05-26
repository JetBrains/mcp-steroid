Anchor-safe editing

Locate, excerpt, unique-occurrence check, apply, verify — the four-step recipe to never hit "anchor not found" again.

# When to use this recipe

You are about to mutate an existing file by literal-text replacement —
via the `applyPatch { hunk(...) }` DSL inside `steroid_execute_code` or a
manual `content.replace(OLD, NEW)` shape. The patch will be rejected if
the `old_string` you ship isn't present **exactly once** in the file.
This page is the four-step pre-flight that makes the patch land on the
first attempt.

## Step 1 — Locate the file via the index, not a hand-typed path

Hand-typing absolute paths is the #1 cause of `file not found` rejections.
`FilenameIndex.getVirtualFilesByName` is O(1) over the project index —
pass the basename, scope to the project, pick the unique match:

```kotlin
import com.intellij.psi.search.FilenameIndex

val targetBasename = "FeatureService.java"
val candidates = readAction {
    FilenameIndex.getVirtualFilesByName(targetBasename, projectScope()).toList()
}
when (candidates.size) {
    0 -> error("No file named '$targetBasename' in project — check the basename / project scope")
    1 -> println("RESOLVED: ${candidates.single().path}")
    else -> {
        println("AMBIGUOUS: ${candidates.size} candidates — disambiguate by path substring:")
        candidates.forEach { println("  ${it.path}") }
        error("Disambiguation needed before patching")
    }
}
```

If you already have a relative path from the project root,
`findProjectFile(relPath)` is the shorter shape — but it returns `null`
when the path doesn't exist; print the resolution before any `!!`.

## Step 2 — Print the excerpt around the intended anchor BEFORE patching

Print the slice of text you think you're replacing. This is what catches
whitespace drift, line-ending differences, and stale assumptions about
file content — every time, before you build the patch:

```kotlin
import com.intellij.psi.search.FilenameIndex

val vf = readAction {
    FilenameIndex.getVirtualFilesByName("FeatureService.java", projectScope()).single()
}
val content = String(vf.contentsToByteArray(), vf.charset)
val anchorPrefix = "fun findByStatus("
val idx = content.indexOf(anchorPrefix)
check(idx >= 0) { "anchor '$anchorPrefix' not in ${vf.path} — refresh your model of the file" }

val start = (idx - 80).coerceAtLeast(0)
val end = (idx + 250).coerceAtMost(content.length)
println("EXCERPT [${vf.path} @ offset $idx]:")
println(content.substring(start, end))
```

## Step 3 — Verify exactly-one-occurrence BEFORE applying

The patch engine rejects multi-occurrence anchors with
`old_string occurs more than once` — but rejection costs a tool call.
Count it yourself first; if it's > 1, expand `old_string` with
surrounding context (preceding line, enclosing class) until unique:

```kotlin
import com.intellij.psi.search.FilenameIndex

val vf = readAction {
    FilenameIndex.getVirtualFilesByName("FeatureService.java", projectScope()).single()
}
val content = String(vf.contentsToByteArray(), vf.charset)
val oldString = "fun findByStatus(status: Status): List<Feature>"
val occurrences = oldString.toRegex(RegexOption.LITERAL).findAll(content).count()
check(occurrences == 1) {
    "old_string occurs $occurrences time(s) in ${vf.path} — must be exactly 1. " +
        "Expand with surrounding context until it's unique."
}
```

Prefer expanding **upward** (preceding identifier line, enclosing
class/function signature) over downward (lines that follow the
target). The upward direction keeps the surrounding context stable
across iterations — expanding downward often pulls in lines that the
patch itself will change, so the second attempt's anchor breaks the
same way as the first.

## Step 4 — Apply, then verify

Apply the patch via the `applyPatch { hunk(...) }` DSL inside
`steroid_execute_code` (one or many hunks). Then re-read the file and
assert the new text is present — the patch engine guarantees
atomicity but does not guarantee semantic correctness:

```kotlin
import com.intellij.psi.search.FilenameIndex

val vf = readAction {
    FilenameIndex.getVirtualFilesByName("FeatureService.java", projectScope()).single()
}
val oldString = "fun findByStatus(status: Status): List<Feature>"
val newString = "fun findByStatus(status: Status, limit: Int = 50): List<Feature>"

applyPatch {
    hunk(filePath = vf.path, oldString = oldString, newString = newString)
}

val after = readAction { String(vf.contentsToByteArray(), vf.charset) }
check(after.contains(newString)) { "patch reported success but new_string not in ${vf.path}" }
println("PATCHED: ${vf.path}")
```

For a single literal-text replacement *outside* a multi-hunk
transaction, the read-outside-write-inside shape covers the same flow
without `applyPatch`: see the "writeAction — Read Outside, Write
Inside" example in
[Threading and Read/Write Actions](mcp-steroid://skill/coding-with-intellij-threading).

# See also

- [Apply Patch — Atomic Multi-Site Edit](mcp-steroid://ide/apply-patch)
- [McpScriptContext API Reference](mcp-steroid://skill/coding-with-intellij-context-api)
- [Threading and Read/Write Actions](mcp-steroid://skill/coding-with-intellij-threading)
- [VFS access](mcp-steroid://skill/coding-with-intellij-vfs)
