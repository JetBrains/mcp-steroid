# Internal & Experimental API Usages — Plugin Verifier Report

Generated from plugin verifier output for `com.jonnyzzz.mcp-steroid` against IU-253.28294.334.

**Verdict**: Compatible. 29 usages of internal API. 11 usages of experimental API.

---

## Summary Table

| # | Internal API | Plugin File | Public Alternative | Priority |
|---|---|---|---|---|
| 1 | `PluginMainDescriptor` / `ContentModuleDescriptor` / `getContentModules()` | `ScriptClassLoaderFactory.kt` | `IdeaPluginDescriptor.contentModules` extension (`@Experimental`) | **Done** |
| 2 | `ComponentManagerEx.getServiceAsync()` | DialogKiller, ExecutionManager, etc. (8 sites) | Replaced `serviceAsync<T>()` with stable `service<T>()` | **Done** |
| 3 | `LaterInvocator.addModalityStateListener()` | `ModalityStateMonitor.kt` | **None** — gap in public API | Cannot fix |
| 4 | `Utils.expandActionGroupSuspend()` | `ActionDiscoveryToolHandler.kt` | **None** — can reduce to `Utils.initUpdateSession()` + public `UpdateSession.expandedChildren()` | Cannot fix |
| 5 | `DaemonCodeAnalyzerImpl.getLineMarkers()` | `ActionDiscoveryToolHandler.kt` | `DocumentMarkupModel.getAllHighlighters()` + `LineMarkerGutterIconRenderer` | **Done** |
| 6 | `IntentionActionDescriptor.isError()` / `isInformation()` | `ActionDiscoveryToolHandler.kt` | Infer from `IntentionsInfo` list membership | **Done** |
| 7 | `AppLifecycleListener.appStarted()` | `SteroidsMcpServerAppLifecycleListener` | Switch to public `appFrameCreated()` | **Done** |
| 8 | `OpenProjectTask` internal constructor | `OpenProjectToolHandler.kt` | `OpenProjectTask.build().copy(...)` | **Done** |

| # | Experimental API | Plugin File | Stable Alternative | Priority |
|---|---|---|---|---|
| 9 | `writeAction {}` | `ReviewManager.kt`, `McpScriptContextImpl.kt` | `edtWriteAction {}` (stable since 2025.3) | **Done** (ReviewManager); McpScriptContextImpl kept for agent script API compat |
| 10 | `serviceAsync<T>()` | DialogKiller, ExecutionManager, etc. | `service<T>()` (sync, stable) — only matters during startup | **Done** |

---

## 1. PluginMainDescriptor / ContentModuleDescriptor / getContentModules()

**Used in**: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/koltinc/ScriptClassLoader.kt`

**Current code**:
```kotlin
import com.intellij.ide.plugins.PluginMainDescriptor
// ...
if (descriptor is PluginMainDescriptor) {
    loaders += descriptor.contentModules.mapNotNull { it.pluginClassLoader }
}
```

**Internal APIs**: `PluginMainDescriptor` (class), `ContentModuleDescriptor` (class), `getContentModules()` (method) — all `@ApiStatus.Internal`.

**Public alternative**: `IdeaPluginDescriptor.contentModules` extension property from `IdeaPluginDescriptorExtensions.kt` (`@Experimental`, available since 2025.2):

```kotlin
import com.intellij.ide.plugins.contentModules  // extension property
// ...
loaders += descriptor.contentModules.mapNotNull { it.pluginClassLoader }
```

The extension handles the `is PluginMainDescriptor` check internally and returns `List<IdeaPluginDescriptor>` (public type). No cast needed.

**Source**: `platform/core-impl/src/com/intellij/ide/plugins/IdeaPluginDescriptorExtensions.kt:33-35`

---

## 2. ComponentManagerEx.getServiceAsync()

**Used in**: `DialogKiller.kt`, `DialogWindowsLookup.kt`, `ModalityStateMonitor.kt`, `ExecutionManager.kt`, `ListWindowsToolHandler.kt` (8 callsites total)

**Finding**: MCP Steroid already uses the correct public wrapper `serviceAsync<T>()` from `com.intellij.openapi.components.serviceAsync`. The verifier flags the transitive call to `ComponentManagerEx.getServiceAsync()` which `serviceAsync<T>()` calls internally.

`serviceAsync<T>()` is `@Experimental` (not `@Internal`) — promoted from Internal+Experimental to Experimental-only in March 2025 (commit `14d244f669430`). On master (2026.2), `ComponentManagerEx` itself was promoted to fully public (no annotation).

**No action needed.** The plugin uses the correct API. The verifier false-positive will resolve itself when targeting newer IDE versions.

**Stable fallback**: If `@Experimental` is a concern, replace with synchronous `service<T>()` — identical post-startup since services are already initialized. JetBrains docs: "In most cases, you should use `service` instead, even in suspending context."

---

## 3. LaterInvocator.addModalityStateListener()

**Used in**: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ModalityStateMonitor.kt`

**Current code**:
```kotlin
import com.intellij.openapi.application.impl.LaterInvocator
// ...
LaterInvocator.addModalityStateListener(listener, disposable)
```

**Internal APIs**: `LaterInvocator` (class, `@Internal`), `addModalityStateListener()` (method on internal class).

**No public alternative exists.** The `ModalityStateListener` interface IS public (`core-api`, in api-dump.txt), but `LaterInvocator.addModalityStateListener()` is the **only registration point** in the entire IntelliJ Platform. There is no message bus topic, no `Application` method, and no extension point for modality listeners.

Every consumer in IntelliJ (including JetBrains' own Maven plugin `MavenMergingUpdateQueue`) uses this exact internal call. This is a gap in the public API — the listener interface was made public but the registration was left in `core-impl`.

**Risk assessment**: Low risk of breakage. 5+ internal consumers, well-established pattern. A JetBrains API change would likely provide a replacement for the already-public listener interface.

**Alternatives considered**:
- AWT `Toolkit.addAWTEventListener(WINDOW_EVENT_MASK)` — fires after window shown (not before modality change), misses non-AWT entities like `JobProvider`
- Polling `LaterInvocator.isInModalContext()` — also internal, misses transient states
- `ApplicationListener` — no modality-related callbacks

---

## 4. Utils.expandActionGroupSuspend()

**Used in**: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ActionDiscoveryToolHandler.kt`

**Current code**:
```kotlin
import com.intellij.openapi.actionSystem.impl.Utils
// ...
val actions = withContext(Dispatchers.EDT) {
    Utils.expandActionGroupSuspend(group, presentationFactory, dataContext, place, ActionUiKind.POPUP, false)
}
```

**Internal APIs**: `Utils` (class, `@Internal`), `expandActionGroupSuspend()` (method on internal class).

**No fully public alternative exists.** `ActionGroup.getChildren()` is `@OverrideOnly` (not for calling). `DefaultActionGroup.getChildren(null)` logs an error. The platform provides no public headless action expansion API.

**Partial improvement** — reduce internal surface to `Utils.initUpdateSession()` + public `UpdateSession.expandedChildren()`:

```kotlin
val event = AnActionEvent.createEvent(dataContext, null, place, ActionUiKind.NONE, null)
event.updateSession = UpdateSession.EMPTY
Utils.initUpdateSession(event)  // still @Internal, but thinner surface

val session = event.updateSession
val actions = session.expandedChildren(group)  // PUBLIC API
    .filter { it !is Separator && session.presentation(it).isEnabledAndVisible }
```

This pattern is used by `SimpleRunMarkerCommandProvider`, `GotoActionModel`, `RunLineMarkerContributor`, and 3+ other IntelliJ classes.

**Risk assessment**: Both `expandActionGroupSuspend` and `initUpdateSession` are stable internal APIs with many callers. Unlikely to break without replacement.

---

## 5. DaemonCodeAnalyzerImpl.getLineMarkers()

**Used in**: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ActionDiscoveryToolHandler.kt`

**Current code**:
```kotlin
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
// ...
DaemonCodeAnalyzerImpl.getLineMarkers(document, project)
```

**Internal APIs**: `DaemonCodeAnalyzerImpl` (class, `@Internal`), `getLineMarkers()` (method on internal class — the method itself has no annotation).

**Public alternative** — iterate markup model highlighters directly:

```kotlin
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.codeInsight.daemon.LineMarkerInfo

val markupModel = DocumentMarkupModel.forDocument(document, project, true)
val lineMarkers = markupModel.getAllHighlighters()
    .filter { it.isValid }
    .mapNotNull { highlighter ->
        (highlighter.gutterIconRenderer as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)
            ?.lineMarkerInfo
    }
```

All APIs used are public: `DocumentMarkupModel.forDocument()`, `MarkupModel.getAllHighlighters()`, `RangeHighlighter.getGutterIconRenderer()`, `LineMarkerInfo.LineMarkerGutterIconRenderer` (public nested class in `lang-api`), `getLineMarkerInfo()`.

**Caveat**: `getAllHighlighters()` returns ALL highlighters, not just line markers. The `gutterIconRenderer` filter handles this. Less efficient than the internal `processRangeHighlightersOverlappingWith` but functionally identical for full-document scope.

---

## 6. IntentionActionDescriptor.isError() / isInformation()

**Used in**: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ActionDiscoveryToolHandler.kt`

**Current code**:
```kotlin
// In toIntentionActionInfo():
isError = descriptor.isError,
isInformation = descriptor.isInformation
```

**Internal APIs**: `HighlightInfo.IntentionActionDescriptor.isError()` and `.isInformation()` — both `@ApiStatus.Internal`. The `mySeverity` field is private with no public getter.

**Public alternative** — infer severity from `IntentionsInfo` list membership:

`ShowIntentionsPass.getActionsToShow()` returns `IntentionsInfo` with three pre-categorized lists:
- `errorFixesToShow` → descriptors where severity >= ERROR
- `intentionsToShow` → descriptors where severity == INFORMATION
- `inspectionFixesToShow` → everything else

The plugin already maps each list separately, so severity can be inferred from which list a descriptor belongs to instead of calling the internal methods:

```kotlin
intentions = intentionsInfo.intentionsToShow.map { toIntentionActionInfo(it, isError = false, isInformation = true) },
errorFixes = intentionsInfo.errorFixesToShow.map { toIntentionActionInfo(it, isError = true, isInformation = false) },
inspectionFixes = intentionsInfo.inspectionFixesToShow.map { toIntentionActionInfo(it, isError = false, isInformation = false) },
```

---

## 7. AppLifecycleListener.appStarted()

**Used in**: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/SteroidsMcpServerAppLifecycleListener.kt`

**Internal API**: `AppLifecycleListener.appStarted()` — `@ApiStatus.Internal`. Its Javadoc says: *"Plugins must use `ProjectActivity` and track successful once-per-application run instead."*

**Lifecycle timeline**:
1. `appFrameCreated(args)` — **public** — before project opening
2. Project opening / welcome screen
3. `LoadingState.APP_STARTED`
4. `appStarted()` — **internal** — after project opening processed
5. Post-open tasks

**Public alternative**: Switch to `appFrameCreated()` (public, in api-dump.txt). It fires slightly earlier (before project open), but since `startServerIfNeeded()` is idempotent and doesn't depend on a project being open, this is fine:

```kotlin
class SteroidsMcpServerAppLifecycleListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        // Start MCP server early, before project opens
        SteroidsMcpServer.getInstance().startServerIfNeeded()
    }
}
```

The plugin already has `SteroidsMcpServerStartupActivity` (a `ProjectActivity`) as belt-and-suspenders for project-open timing.

**Other public methods on AppLifecycleListener**: `appFrameCreated()`, `welcomeScreenDisplayed()`, `projectFrameClosed()`, `projectOpenFailed()`, `appClosing()`, `appWillBeClosed()`.

---

## 8. OpenProjectTask Internal Constructor

**Used in**: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectToolHandler.kt`

**Current code** (uses `@Internal` builder function):
```kotlin
val task = OpenProjectTask {
    forceOpenInNewFrame = true
    showWelcomeScreen = false
    projectToClose = null
    runConfigurators = true
}
```

**Internal API**: `OpenProjectTask(buildAction: OpenProjectTaskBuilder.() -> Unit)` top-level function — `@ApiStatus.Internal`.

**Public alternative** — use `build()` (public companion) + `copy()` (public data class method):

```kotlin
val task = OpenProjectTask.build().copy(
    forceOpenInNewFrame = true,
    showWelcomeScreen = false,
    projectToClose = null,
    runConfigurators = true,
)
```

Both `OpenProjectTask.build()` and `copy()` are public (no `@Internal`). The named params (`forceOpenInNewFrame`, `showWelcomeScreen`, etc.) are all public properties.

Alternatively, `ProjectUtil.openOrImport(path, projectToClose, forceOpenInNewFrame)` avoids `OpenProjectTask` entirely but doesn't control `showWelcomeScreen` or `runConfigurators`.

---

## 9. writeAction {} (Experimental)

**Used in**: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/review/ReviewManager.kt`, `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/McpScriptContextImpl.kt`

**Experimental API**: `com.intellij.openapi.application.writeAction {}` — `@ApiStatus.Experimental`.

**Will NOT stabilize with current semantics.** JetBrains plans to repurpose `writeAction` to run on background threads (not EDT). An attempted deprecation in favor of `edtWriteAction` was reverted in Feb 2025 due to too many callers, but the intent remains.

**Stable replacement**: `edtWriteAction {}` — identical behavior (dispatch to EDT + write lock), stable API (no annotation), available since 2025.3. Already adopted in 177 files across IntelliJ community.

```kotlin
// Before (experimental, semantics will change)
suspend fun example() = writeAction { /* ... */ }

// After (stable, EDT-pinned permanently)
suspend fun example() = edtWriteAction { /* ... */ }
```

**Note**: `McpScriptContext.writeAction {}` is exposed to AI agent scripts. Renaming it would break existing agent code. Options: (a) keep `writeAction` name on the context API but implement via `edtWriteAction` internally, (b) add `edtWriteAction` as new method and deprecate the old name.

---

## 10. serviceAsync<T>() (Experimental)

**Used in**: DialogKiller, DialogWindowsLookup, ModalityStateMonitor, ExecutionManager, ListWindowsToolHandler

**Experimental API**: `serviceAsync<T>()` from `com.intellij.openapi.components` — `@Experimental`.

**Stable alternative**: `service<T>()` (synchronous, no annotation). For post-startup usage where services are already initialized, `service<T>()` returns instantly. JetBrains docs: *"In most cases, you should use `service` instead, even in suspending context. This function is mostly useful during startup."*

**Low priority** — `@Experimental` is a weaker signal than `@Internal`. The API trajectory is toward stabilization, not removal.
