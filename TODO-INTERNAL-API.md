# TODO: closed IntelliJ Platform APIs

Backlog of `@ApiStatus.Internal` references in the plugin sources, with
context for either justifying or replacing each one. The plugin verifier
report at `ij-plugin/build/reports/pluginVerifier/IU-*/plugins/*/internal-api-usages.txt`
is the authoritative list per IDE build.

## Resolved

### Action group expansion — DROPPED

`ActionDiscoveryToolHandler.expandActionGroup` previously called
`com.intellij.openapi.actionSystem.impl.Utils.expandActionGroupSuspend`
to enumerate visible actions of a group (default editor popup +
gutter, plus per-gutter-renderer popup-menu actions).

The function was removed under the assumption that no live caller
relies on the `actionGroups[*].actions` or `gutterIcons[*].popupActions`
fields. The MCP tool's response shape is preserved — those arrays are
now always empty and `actionGroups[*].missing` still reports whether
the group ID resolves to a real `ActionGroup`.

If a future caller actually needs expanded actions, the right path is
NOT to bring `Utils.expandActionGroupSuspend` back: instead, drive
`ActionGroup.getChildren(AnActionEvent)` directly under a presentation
factory, then filter by `Presentation.isVisible && isEnabled`. That
loses the async `update()` resolution but stays on public API.

### Modality state monitoring — REPLACED

`ModalityStateMonitor` previously called
`com.intellij.openapi.application.impl.LaterInvocator.addModalityStateListener`
and discriminated coroutine-progress entries via
`com.intellij.openapi.application.impl.JobProvider`.

Replaced with an EDT-pump polling loop driven by
`DialogWindowsLookup.withModalityCheck`. The poll dispatches a no-op
coroutine to `Dispatchers.EDT` with a 100 ms timeout — if the timeout
fires AND there is a `DialogWrapperDialog.isShowing` window, we treat
that as a real modal dialog. This pattern naturally filters out
`Task.Modal` / `runWithModalProgressBlocking` progress, which don't
park EDT, removing the need for the `JobProvider` discriminator.

## Outstanding

(none beyond what the verifier still reports — keep this list aligned
with `internal-api-usages.txt`.)
