# Maven Test Execution Dialog: Root Cause & Fix

Investigation of `=== MODAL DIALOG DETECTED === Modal entity: com.intellij.openapi.progress.impl.JobProviderWithOwnerContext`
killing Maven test execution launched via `MavenRunConfigurationType.runConfiguration()` in `steroid_execute_code` with
`dialog_killer: true`.

> **Correction to earlier notes in this file.** A previous version of this document attributed the modal to
> `ApplicationManager.invokeAndWait` in `MavenRunConfigurationType.runConfiguration` (L188) and proposed switching to
> `ProgramRunnerUtil.executeConfiguration()`. That diagnosis was wrong. `invokeAndWait` only dispatches to EDT; it does
> not create a `JobProviderWithOwnerContext`. Switching entry points to `ProgramRunnerUtil.executeConfiguration()` does
> not help because the execution path still calls `MavenRunConfiguration.getState()` → `MavenShCommandLineState` →
> `runWithModalProgressBlocking`. Evidence for the correct root cause is below.

## TL;DR

- **`JobProviderWithOwnerContext` is not a UI dialog.** It is a private class inside `PlatformTaskSupport.kt` that
  `runWithModalProgressBlocking(project, title) { … }` passes to `LaterInvocator.enterModal()` so its coroutine can
  pump Swing events while holding a modal context.
- **Maven's `MavenShCommandLineState.startProcess()` wraps the entire Maven process startup in
  `runWithModalProgressBlocking`.** This is the default path (`Registry.is("maven.use.scripts") == true` in IU-261).
  Gradle's `ExternalSystemTaskRunner` does not do this, which is why `GradleTestExecutionTest` works.
- **Our `ModalityStateMonitor` treats every modal-entity entry as "modal dialog detected" and cancels the execution
  coroutine.** That cancellation propagates into the Maven startup coroutine and kills the Maven process before
  `SMTRunnerEventsListener.onTestingStarted` can fire.
- **Fix:** filter `beforeModalityStateChanged` by `modalEntity is com.intellij.openapi.application.impl.JobProvider` and
  skip cancellation for those. `JobProvider` is the internal marker interface for coroutine-backed modal progress;
  `DialogWrapper` does not implement it, so genuine UI dialogs still cancel execution as before.

## Evidence Chain

### 1. Who creates `JobProviderWithOwnerContext`?

`community/platform/platform-impl/src/com/intellij/openapi/progress/impl/PlatformTaskSupport.kt`

```kotlin
// L368–L412  (runWithModalProgressBlockingInternal)
private fun <T> CoroutineScope.runWithModalProgressBlockingInternal(
    dispatcher: CoroutineDispatcher?,
    descriptor: ModalIndicatorDescriptor,
    action: suspend CoroutineScope.() -> T,
): T {
    return inModalContext(JobProviderWithOwnerContext(coroutineContext.job, descriptor.owner)) { newModalityState ->
        …
    }
}

// L435
private class JobProviderWithOwnerContext(val modalJob: Job, val owner: ModalTaskOwner) : JobProvider { … }
```

`inModalContext(entity) { … }` calls `LaterInvocator.enterModal(entity)` around the block. That is what triggers
`ModalityStateListener.beforeModalityStateChanged(entering = true, modalEntity = <the JobProviderWithOwnerContext>)`.

Reflection confirms it at runtime (via `steroid_execute_code` in the running IDE):

```
com.intellij.openapi.progress.impl.JobProviderWithOwnerContext
  Superclass:  java.lang.Object
  Interfaces:  com.intellij.openapi.application.impl.JobProvider
  Fields:      modalJob: kotlinx.coroutines.Job
               owner:    com.intellij.platform.ide.progress.ModalTaskOwner
```

The only source file mentioning the class in the IntelliJ tree is `PlatformTaskSupport.kt` (confirmed via
`PsiSearchHelper.processAllFilesWithWord`).

### 2. Who calls `runWithModalProgressBlocking` in Maven?

`community/plugins/maven/src/main/java/org/jetbrains/idea/maven/execution/run/MavenShCommandLineState.kt`

```kotlin
// L130–L141
private fun startProcess(debug: Boolean): ProcessHandler {
    return runWithModalProgressBlocking(myConfiguration.project, RunnerBundle.message("maven.target.run.label")) {
        val eelApi = myConfiguration.project.getEelDescriptor().toEelApi()
        val executionParameters = collectParameters(eelApi, debug)
        val processHandler = runProcessInEel(eelApi, executionParameters)
        JavaRunConfigurationExtensionManager.instance
            .attachExtensionsToProcess(myConfiguration, processHandler, environment.runnerSettings)
        return@runWithModalProgressBlocking processHandler
    }
}
```

`MavenRunConfiguration.getState(...)` selects which state to use based on `Registry.is("maven.use.scripts")`:

```java
// MavenRunConfiguration.java L133–L145
if (Registry.is("maven.use.scripts")) {
    if (env.getTargetEnvironmentRequest() instanceof LocalTargetEnvironmentRequest) {
        return new MavenShCommandLineState(env, this);   // NEW — uses runWithModalProgressBlocking
    } else {
        return new MavenTargetShCommandLineState(env, this);
    }
}
return new MavenCommandLineState(env, this);             // LEGACY — synchronous, no modal progress
```

In IU-261.23567 `Registry.is("maven.use.scripts")` evaluates to **`true`** (confirmed live).

Every programmatic entry point funnels through this state:

```
MavenRunConfigurationType.runConfiguration(project, params, …)            // L146–L196
  → ApplicationManager.invokeAndWait { runner.execute(environment) }      // L188 (just EDT dispatch)
    → MavenRunConfiguration.getState()        → MavenShCommandLineState
    → MavenShCommandLineState.startProcess()
      → runWithModalProgressBlocking(project, …) { … }
        → inModalContext(JobProviderWithOwnerContext(job, owner)) { … }
          → LaterInvocator.enterModal(jobProvider)
            → ModalityStateListener.beforeModalityStateChanged(entering = true,
                                                               modalEntity = <JobProviderWithOwnerContext>)
```

`MavenRunner.run(params, settings, onComplete)` (L60 of `MavenRunner.java`) internally calls
`MavenRunConfigurationType.runConfiguration(...)` — same path. `ProgramRunnerUtil.executeConfiguration()` with a
pre-built `MavenRunConfiguration` also reaches `getState()` and gets the same `MavenShCommandLineState`. There is no
"alternative Maven API" that avoids `runWithModalProgressBlocking` on the supported path.

### 3. Why does `dialog_killer: true` kill Maven?

Contrary to the name, the thing killing Maven is not `DialogKiller` (which closes `DialogWrapper` windows). The killer
is `ModalityStateMonitor`, installed alongside by `withModalityMonitoring { … }`.

`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ModalityStateMonitor.kt`

```kotlin
// L71–L99
private val listener = object : ModalityStateListener {
    override fun beforeModalityStateChanged(entering: Boolean, modalEntity: Any) {
        if (!isMonitoring || !cancelOnModality) return
        if (entering) {
            log.info("Modal dialog detected during execution $executionId: $modalEntity")
            val basicInfo = ModalDialogInfo(modalEntity = modalEntity)
            modalDialogChannel.trySend(basicInfo)
            …
        }
    }
}

// L206–L221
coroutineScope {
    val executionDeferred = async { block() }        // the Maven task runs here
    select {
        monitor.onModalDialog { dialogInfo ->
            executionDeferred.cancel("Modal dialog detected: ${dialogInfo.modalEntity}")
            Pair(null, dialogInfo)
        }
        executionDeferred.onAwait { result -> Pair(result, null) }
    }
}
```

The listener has **no filter** — every `entering=true` callback causes `executionDeferred.cancel(...)`. When Maven's
`startProcess` enters the modal context, cancellation propagates into the `runProcessInEel { … }` coroutine and the
Maven process is never fully launched (or is cancelled immediately after launch). `SMTRunnerEventsListener.onTestingStarted`
never fires.

`DialogKiller` itself is innocent here: it enumerates `DialogWrapperDialog` windows via
`DialogWindowsLookup.findDialogsOwnedBy(project)`. `JobProviderWithOwnerContext` has no associated `DialogWrapper`
window at this point (the `deferredDialog` inside `PlatformTaskSupport` is still completing asynchronously), so
`DialogKiller` finds nothing to close. The execution was already cancelled by `ModalityStateMonitor` before any UI
appeared.

### 4. Why doesn't Gradle have this problem?

`runWithModalProgressBlocking` usages across all of IntelliJ `/gradle/` sources (excluding Qodana/Android tests):
**zero**. `ExternalSystemTaskRunner.kt` / `ExternalSystemExecuteTaskTask.java` / `ExternalSystemUtil.java` run Gradle
tasks through the external-system progress mechanism, which never enters a modal context. Hence `GradleTestExecutionTest`
passes: no `JobProviderWithOwnerContext` event is fired, monitor stays quiet, test flow completes.

### 5. `JobProvider` is a clean discriminator

```java
// community/platform/core-impl/src/com/intellij/openapi/application/impl/JobProvider.java
@ApiStatus.Internal
public interface JobProvider extends ModalContextProjectLocator {
    @NotNull Job getJob();
}
```

Runtime verification in the current IDE:

```
DialogWrapper.class.isAssignableFrom(JobProvider) == false
```

i.e. `DialogWrapper` does **not** implement `JobProvider`. Every legitimate UI dialog the monitor is supposed to catch
(Trust Project, Resolving SDKs, SDK selection, etc.) arrives with its `DialogWrapper` as `modalEntity` — those will
still cancel execution. Only coroutine-based modal progress tasks implement `JobProvider`, and those are exactly the
ones that should NOT cancel the enclosing `steroid_execute_code` coroutine.

## Is `MavenRunConfigurationType.runConfiguration()` the right API?

Yes. Alternatives considered:

| Candidate | Outcome |
|-----------|---------|
| `MavenRunner.run(params, settings, onComplete)` | Internally calls `MavenRunConfigurationType.runConfiguration(...)` — same path, same modal. |
| `ExternalSystemUtil.runTask()` with Maven system ID | Maven is only registered as an external-system runner for **import/sync**, not for `run`/`test`. Not a runtime option. |
| `ProgramRunnerUtil.executeConfiguration()` with a pre-built `MavenRunConfiguration` | Same `getState()` path, same `MavenShCommandLineState`, same modal. |
| `MavenProjectsManager` APIs | Manage import/sync only — cannot run a goal. |
| Registry flip `maven.use.scripts=false` | Falls back to legacy `MavenCommandLineState` whose `startProcess()` is synchronous (no `runWithModalProgressBlocking`). Works, but is a deprecated path and a global switch — fragile. |

The API is fine. The issue is on our side: our monitor is too aggressive.

## Recommended Fix (the one change we actually need)

Filter `JobProvider` modal entities out of `ModalityStateMonitor` so they do not cancel execution:

```kotlin
// ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ModalityStateMonitor.kt  (L71–L99)
private val listener = object : ModalityStateListener {
    override fun beforeModalityStateChanged(entering: Boolean, modalEntity: Any) {
        if (!isMonitoring || !cancelOnModality) return
        if (!entering) return

        // Coroutine-based modal progress (runWithModalProgressBlocking / withModalProgressBlocking)
        // enters modal context via a JobProvider entity, not a UI dialog. Maven's
        // MavenShCommandLineState.startProcess() uses this wrapper; Gradle does not.
        // Cancelling execution on these modals kills the run-configuration's own startup
        // coroutine, so the process never actually starts and SMTRunnerEventsListener never fires.
        if (modalEntity is com.intellij.openapi.application.impl.JobProvider) {
            log.info("Skipping JobProvider modal entity (progress task, not a UI dialog): $modalEntity")
            return
        }

        log.info("Modal dialog detected during execution $executionId: $modalEntity")
        modalDialogChannel.trySend(ModalDialogInfo(modalEntity = modalEntity))
        screenshotScope.launch { … }
    }
}
```

Why this is the right shape:

1. **Semantically correct.** `JobProvider` is `@ApiStatus.Internal` but its purpose is documented by the single
   implementation (`JobProviderWithOwnerContext`): "wraps a kotlinx Job + modal owner for pump-style progress".
   Every user-facing `DialogWrapper` will still fire the listener with a non-`JobProvider` entity and cancel
   execution — the "Resolving SDKs" / "Trust Project" use case is preserved.
2. **Narrow.** A single `is` check. No reflection, no string matching on class names, no registry flip.
3. **Future-proof.** Any new platform code that wraps long operations in `runWithModalProgressBlocking` (Maven
   alone has hits in `MavenEelUtil`, `MavenProjectsManagerEx`, `MavenShCommandLineState`, `MavenProjectAsyncBuilder`,
   `MavenCatalog`) will be handled without us enumerating them.
4. **`DialogKiller` stays as-is.** `DialogWindowsLookup` enumerates `DialogWrapperDialog` windows — a
   `JobProviderWithOwnerContext` never appears there, so `DialogKiller` will not find anything to close, which is
   correct.

### Test to add

`ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/execution/ModalityStateMonitorTest.kt`: assert that a `JobProvider`
modal entity posted via `LaterInvocator.enterModal(...)` does **not** cancel a `withModalityMonitoring { … }` block,
while a plain `Any` entity still does.

`test-integration/.../MavenTestExecutionTest.kt` should then pass end-to-end and can be removed from the
"dialog_killer-killed" row in `docs/integration-tests-catalog.md`.

## What NOT to do

- **Do not set `maven.use.scripts=false`** as a workaround. The legacy `MavenCommandLineState` path still works
  today but is on its way out (the script-based runner is the new default because it supports EEL / remote / WSL /
  targets). Flipping the registry hides the bug from ourselves and breaks the moment upstream removes the legacy
  path.
- **Do not tell agents to use `dialog_killer: false` for Maven.** That also disables closure of real blocking
  dialogs (Trust Project, SDK selection…). A Maven run-config invocation from a fresh workspace can trip those;
  we want the killer active, just not the false-positive progress detection.
- **Do not switch the Maven recipe to `ProgramRunnerUtil.executeConfiguration()`.** (This was the earlier,
  incorrect proposal.) It calls the same `MavenShCommandLineState` and hits the same modal.
- **Do not add a class-name string check for `"JobProviderWithOwnerContext"`.** The interface check is strictly
  stronger and doesn't break if the class is renamed or a second implementation appears.

## References

- IntelliJ sources (local clone `~/Work/intellij`):
  - `community/platform/platform-impl/src/com/intellij/openapi/progress/impl/PlatformTaskSupport.kt` L368–L435
  - `community/platform/core-impl/src/com/intellij/openapi/application/impl/JobProvider.java`
  - `community/plugins/maven/src/main/java/org/jetbrains/idea/maven/execution/run/MavenShCommandLineState.kt` L97–L141
  - `community/plugins/maven/src/main/java/org/jetbrains/idea/maven/execution/run/MavenCommandLineState.java` L389–L400
  - `community/plugins/maven/src/main/java/org/jetbrains/idea/maven/execution/MavenRunConfiguration.java` L132–L145
  - `community/plugins/maven/src/main/java/org/jetbrains/idea/maven/execution/MavenRunConfigurationType.java` L146–L196
  - `community/plugins/maven/src/main/java/org/jetbrains/idea/maven/execution/MavenRunner.java` L60–L90
- MCP Steroid sources:
  - `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ModalityStateMonitor.kt` L71–L225
  - `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/DialogKiller.kt`
  - `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/DialogWindowsLookup.kt`
  - `prompts/src/main/prompts/skill/execute-code-maven.md` L49–L86 (the recipe agents follow)
- Existing notes: `docs/integration-tests-catalog.md` L8, L23; `docs/autoresearch-v2/MESSAGE-BUS.md`
  ("dialog_killer kills Maven's own JobProviderWithOwnerContext progress dialog").
