IDE: Apply Patch — Atomic Multi-Site Edit

Apply N literal-text substitutions across one or more files as a single atomic undoable command. PSI stays in sync; the tail-of-exec VFS refresh handles disk sync automatically.

Default agent path: use the dedicated `steroid_apply_patch` MCP tool for ordinary multi-site literal edits. It exposes this same engine directly and avoids a `steroid_execute_code` kotlinc compile cycle.

This article documents the lower-level script-context DSL. Use it only when the patch must happen inside the same `steroid_execute_code` script as surrounding IntelliJ API work.

```kotlin
val result = applyPatch {
    hunk("/abs/path/ServiceA.java", "LoggerFactory.getLogger(\"old\")", "LoggerFactory.getLogger(ServiceA.class)")
    hunk("/abs/path/ServiceA.java", "log.warn(\"deprecated\")", "log.info(\"v2\")")
    hunk("/abs/path/ServiceB.java", "MAX_RETRIES = 3", "MAX_RETRIES = 5")
}
println(result)
// apply-patch: 3 hunks across 2 file(s) applied atomically.
//   [#0] /abs/path/ServiceA.java:17:5  (33→39 chars)
//   [#1] /abs/path/ServiceA.java:42:9  (24→18 chars)
//   [#2] /abs/path/ServiceB.java:88:13 (15→15 chars)
```

`applyPatch` and its builder (`hunk(filePath, oldString, newString)`) are members of the `McpScriptContext` that hosts every `steroid_execute_code` call — no import, no boilerplate.

## Shipping the same edit across N files — use the `forEach` idiom

When the same `old_string → new_string` applies to many files (e.g. adding
the same annotation to each microservice's `*Application.java`), **do not
repeat the old/new pair N times**. The `applyPatch { }` lambda is plain
Kotlin — loop over paths:

```kotlin
val oldPat = "@SpringBootApplication\npublic class"
val newPat = "@SpringBootApplication\n@ComponentScan(\"shop\")\npublic class"
applyPatch {
    listOf(
        "/abs/product-service/.../ProductServiceApplication.java",
        "/abs/product-composite/.../ProductCompositeServiceApplication.java",
        "/abs/recommendation-service/.../RecommendationServiceApplication.java",
        "/abs/review-service/.../ReviewServiceApplication.java",
    ).forEach { hunk(it, oldPat, newPat) }
}
```

Token cost: old/new strings ship once + N paths. For 4 files with identical
~370-char Edit payloads in the native chain (1 480 chars total), this
idiom ships ~892 chars (≈ 40% smaller).

## Shortest unique anchor — don't over-quote `old_string`

Agents often carry 300+ chars of `old_string` for safety. Pre-flight
already rejects non-unique matches with an `ApplyPatchException` naming
both offsets — so you can safely minimize. A 30–60 char unique signature
(e.g. `"@SpringBootApplication\npublic class MyServiceApplication"`) is
usually enough and cuts payload 50–70%.

## Semantics

- **Pre-flight validation** (read action): every `oldString` must occur **exactly once** in its file. A missing or non-unique hunk throws `ApplyPatchException` with hunk index, path, and both offsets — and **no edit lands**. All-or-nothing.
- **Apply** (write action + one `CommandProcessor.executeCommand`): all hunks combine into a single undo step named `MCP Steroid: apply-patch (N hunks)`. Multi-hunk edits in the same file are applied in descending offset order automatically so earlier edits don't shift later ones.
- **PSI commit** inside the same write action so follow-on `findMethodsByName`, `ReferencesSearch`, inspections, etc. in the **same** script see the new tree.
- **VFS refresh** is scheduled by MCP Steroid on exec_code tail (non-blocking). Do not call `VfsUtil.markDirtyAndRefresh` yourself.

## Error handling

Catch `ApplyPatchException` if you want to recover; otherwise let it propagate so the whole `steroid_execute_code` call fails cleanly — the codebase is guaranteed untouched because pre-flight runs before any write:

```kotlin
try {
    applyPatch {
        hunk("/abs/path/File.java", "maybeMissing", "replacement")
    }
} catch (e: ApplyPatchException) {
    println("Patch failed cleanly; no files modified. Reason: ${e.message}")
}
```

## When to use this vs other patterns

- **One literal, one occurrence, one file** — the compact `findProjectFile + String.replace + VfsUtil.saveText` pattern in the `steroid_execute_code` tool description is equivalent and slightly shorter.
- **Cross-file semantic rename with type-aware reference chasing** — use [`mcp-steroid://lsp/rename`](mcp-steroid://lsp/rename). `RenameProcessor` follows imports, overrides, method references that literal-text match cannot see.
- **Anything else with 2+ edit sites** — prefer `steroid_apply_patch`. Use `applyPatch { … }` only when you need to combine the patch with other `steroid_execute_code` operations in the same script.

## Why this is the right shape

The dedicated `steroid_apply_patch` tool ships **data only**. The script-context DSL is still much smaller than hand-rolled write-action code, but it pays a `steroid_execute_code` compile cycle. Implementation lives in the plugin (`com.jonnyzzz.mcpSteroid.execution.ApplyPatch`), so the semantics — descending-offset ordering per file, per-hunk line/column capture under a single read action, PSI commit inside the write action — are tested once and can't drift per caller.

# See also

- [LSP Rename — semantic RenameProcessor](mcp-steroid://lsp/rename)
- [Apply Patch Tool](mcp-steroid://skill/apply-patch-tool-description)
- [Move Class](mcp-steroid://ide/move-class)
- [Change Signature](mcp-steroid://ide/change-signature)
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill)
