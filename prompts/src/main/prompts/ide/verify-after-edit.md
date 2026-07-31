Verify After Edit

Canonical verification loop and result vocabulary for edit -> verify workflows.

Use this as the hub before declaring an edit safe. A verification result must say
whether the check actually ran. An empty list of findings is not enough.

## Result vocabulary

Every verification recipe should print a DTO with these fields:

- `status`: one of `clean`, `findings`, `errors`, `passed`, `failed`,
  `did_not_run`, or `check_failed`
- `check_ran`: `true` only after the intended check started against the intended
  target
- `target`: file, module, task, test pattern, or scope
- `diagnostics`: bounded list of structured records when available
- `message`: short explanation for `did_not_run` and `check_failed`

Meaning:

- `clean`: the intended static/compile check ran and produced no diagnostics
- `findings`: inspections or static analysis found non-compiler problems
- `errors`: compiler/build diagnostics were reported
- `passed`: the intended tests ran and passed
- `failed`: the intended tests ran and failed, or a build/test process exited non-zero
- `did_not_run`: the target was missing, scope was empty, no tests were observed,
  or no run descriptor appeared
- `check_failed`: the verification mechanism started but timed out, crashed, or
  returned partial/untrusted results

Never collapse `did_not_run` or `check_failed` to `clean`.

## Canonical order

1. Let `steroid_execute_code` use its default `smart_non_modal` preflight. That
   commits documents, refreshes VFS, waits for smart mode, and fails if a modal
   blocks the IDE.
2. Run a compiler/build check that catches type errors:
   - JPS/non-delegated IDE builds: `mcp-steroid://ide/jps-build-errors`
   - Gradle/Maven delegated builds: use the matching build-tool recipe and
     preserve the same result vocabulary
3. Run local inspections on edited files for fast IDE findings. Use
   `runInspectionsDirectly(file)` and always include `failedTools`.
4. For module-wide local inspection sweeps, use
   `mcp-steroid://ide/module-inspection-sweep`.
5. For a targeted test-class run with structured results, use
   `mcp-steroid://test/run-test-class-structured`.

Do not use daemon highlighting as the primary verification signal. Highlighting
can depend on editor state and may return an empty result when it did not perform
the check you intended.

## File inspection DTO pattern

```kotlin[AI,IC,IU]
import com.intellij.openapi.fileEditor.FileDocumentManager

val targetPath = "src/main/java/com/example/Foo.java" // TODO: changed file
val vf = findProjectFile(targetPath)
if (vf == null) {
    printJson(
        mapOf(
            "status" to "did_not_run",
            "check_ran" to false,
            "target" to targetPath,
            "message" to "File not found in the project VFS.",
        )
    )
    return
}

val result = runInspectionsDirectly(vf)
val findings = readAction {
    result.entries.flatMap { (toolId, descriptors) ->
        descriptors.map { descriptor ->
            val element = descriptor.psiElement
            val file = element?.containingFile?.virtualFile
            val line = element?.textRange?.startOffset?.let { offset ->
                FileDocumentManager.getInstance()
                    .getDocument(file ?: vf)
                    ?.getLineNumber(offset)
                    ?.plus(1)
            }
            mapOf(
                "toolId" to toolId,
                "path" to (file?.path ?: vf.path),
                "line" to line,
                "message" to descriptor.descriptionTemplate,
                "elementText" to (element?.text ?: ""),
            )
        }
    }
}

val status = when {
    result.failedTools.isNotEmpty() -> "check_failed"
    findings.isNotEmpty() -> "findings"
    else -> "clean"
}

printJson(
    mapOf(
        "status" to status,
        "check_ran" to true,
        "target" to targetPath,
        "diagnostics" to findings,
        "failedTools" to result.failedTools,
    )
)
```

Pitfalls:

- `runInspectionsDirectly` is file-scoped. It is not a replacement for a compiler
  check when the edit can break other files.
- `ProblemDescriptor` contains live PSI. Snapshot fields inside `readAction { }`;
  do not `printJson(result)` directly.
- `ProjectTaskManager.Result` only tells you flags such as errors/aborted; it
  does not expose diagnostics. Use `mcp-steroid://ide/jps-build-errors` when you
  need structured JPS compiler messages.

# See also

- [JPS Build Errors](mcp-steroid://ide/jps-build-errors) - Run a JPS build and collect structured compiler diagnostics.
- [Module Inspection Sweep](mcp-steroid://ide/module-inspection-sweep) - Run file-scoped inspections over a module without internal batch-inspection APIs.
- [Run Test Class (Structured)](mcp-steroid://test/run-test-class-structured) - Single-call test-class verification with structured results.
- [Inspection Summary](mcp-steroid://ide/inspection-summary) - List enabled inspections before relying on an inspection sweep.
