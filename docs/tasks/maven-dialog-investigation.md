# Investigation: Maven Test Execution Dialog Problem

You are a **research + debug agent**. Investigate why `MavenRunConfigurationType.runConfiguration()` triggers a modal dialog that the dialog_killer cancels, preventing Maven test execution.

## Problem

When running Maven tests via `MavenRunConfigurationType.runConfiguration()` inside `steroid_execute_code` with `dialog_killer: true`, a modal dialog appears:

```
=== MODAL DIALOG DETECTED ===
Modal entity: com.intellij.openapi.progress.impl.JobProviderWithOwnerContext@3935e9e5
=== END MODAL DIALOG ===
```

The dialog_killer kills this modal, which also kills the Maven execution. The `SMTRunnerEventsListener.onTestingStarted` never fires because Maven never actually starts.

This is different from the "Resolving SDKs..." modal (which is `UnknownSdkTracker`). The `JobProviderWithOwnerContext` appears to be the Maven runner's own progress task.

## Questions to Answer

1. **What creates JobProviderWithOwnerContext?** Search `~/Work/intellij` for this class. Is it created by the Maven runner? By the Run framework? By the progress system?

2. **Is MavenRunConfigurationType.runConfiguration() the right API?** Check if there's a better way to run Maven tests that doesn't trigger a modal. Alternatives to investigate:
   - `MavenRunner.run()` (simpler API)
   - `ExternalSystemUtil.runTask()` with Maven system ID
   - Direct `ProgramRunnerUtil.executeConfiguration()` with a pre-built config
   - Running Maven via `MavenProjectsManager` APIs

3. **Why does the dialog_killer kill this modal?** The dialog_killer is supposed to kill blocking UI dialogs (like "Resolve SDKs", "Trust project", etc.) — not progress indicators. Is `JobProviderWithOwnerContext` incorrectly classified as a modal dialog? Check:
   - How does the dialog_killer detect modals?
   - What makes `JobProviderWithOwnerContext` appear as a modal entity?
   - Is there a way to whitelist it?

4. **Does the same problem happen with Gradle?** The `GradleTestExecutionTest` passes — what's different about Gradle's execution path?

## Research Approach

**Use MCP Steroid on the IntelliJ project** (`steroid_execute_code` with `project_name="intellij"`):

```
// Find JobProviderWithOwnerContext
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val scope = GlobalSearchScope.allScope(project)
val files = readAction { FilenameIndex.getVirtualFilesByName("JobProviderWithOwnerContext.java", scope) + 
    FilenameIndex.getVirtualFilesByName("JobProviderWithOwnerContext.kt", scope) }
```

Also search for:
- How `MavenRunConfigurationType.runConfiguration()` creates the execution environment
- What `ApplicationManager.getApplication().invokeAndWait()` does inside the Maven runner
- Whether Maven uses `ProgressManager.run(Task.Modal(...))` vs `Task.Backgroundable(...)`

**Use the debugger** if needed:
- Set a breakpoint in `JobProviderWithOwnerContext` constructor
- Run Maven test execution and see what calls it
- Check the stack trace to find the actual dialog creation point

**Check the MCP Steroid dialog_killer code**:
- `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/` — search for `dialog_killer` or `DialogKiller`
- Understand what it considers a "modal" and why `JobProviderWithOwnerContext` triggers it

## Check IDE Logs

After reproducing the issue, check:
- `run-*/intellij/*/idea.log` for Maven-related log entries
- Stack traces around the time of the modal detection
- Any warnings about "modal dialog" or "progress" in the log

## Deliverables

1. Root cause: what creates the modal and why
2. Whether we're using the wrong API for Maven test execution
3. Recommended fix (whitelist, different API, or both)
4. If different API: a code snippet that works without triggering a modal

Log findings to `docs/tasks/maven-dialog-findings.md`.
