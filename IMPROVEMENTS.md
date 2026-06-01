# IMPROVEMENTS

## `steroid_execute_code` modality redesign — one `modal` enum + context APIs (2026-05-31)

### Decision

Replace the three former modal/dialog booleans (`dialog_killer`, `cancel_on_modal`, `allow_modal`) and the
runtime escape hatch `doNotCancelOnModalityStateChange()` with **one `modal` enum parameter** plus a small
set of composable `McpScriptContext` methods. The enum has three profiles:

- `smart_non_modal` (default) — sweep leftover modal dialogs (deepest-first) → require non-modal
  (fail + screenshot if one survives) → commit + save documents + refresh VFS → wait for indexing → run
  with a monitor that closes + FAILS the call on a modal appearing mid-run. The safe choice for any
  PSI / editing / build / test work.
- `non_modal` — assert a non-modal IDE at the start only (fail + screenshot if modal); no sweep, sync,
  smart-mode wait, or monitor. For "I need a non-modal start but will prep myself."
- `unleashed` — no sweep / checks / validation; run against whatever state exists. Trivial, hardcoded,
  non-PSI IDE actions only.

Fine control (callable from any mode): `closeModalDialogs(): Int`, `monitorAndCloseModalDialogs()`,
`allowModalDialog()`, `syncDocuments()`, `waitForSmartMode()`. The profiles are just sugar over these.

### Rationale

The booleans were co-dependent and contradictory, not orthogonal (see
`docs/exec-code-options-redesign.md` Revision 4's full 8-row S×A×R table):

- `dialog_killer` ∧ `cancel_on_modal` were AND-ed; `cancel_on_modal` was vestigial (never in schema,
  always true, its monitor already removed).
- `allow_modal` (tolerate a modal) ∧ a "close-and-fail" stance form a genuine **contradiction** — one says
  proceed, the other fails — yet the booleans let you set both.
- `doNotCancelOnModalityStateChange()` was misnamed (it no longer cancels execution, only stops the killer)
  and was a runtime hatch where a request-time stance belongs.

One enum makes the contradictory combinations **unrepresentable**, collapses the real 3-state intent space
into a single named choice, and moves every genuinely-independent action onto explicit context methods that
`unleashed` / `non_modal` code can opt into on demand. Net surface: 1 enum + 5 context methods, zero
co-dependent combos. Behavior table: `docs/exec-code-options-redesign.md` (LOCKED, Revision 5 + final
refinements); canonical agent-facing reference: `prompts/src/main/prompts/skill/execute-code-tool-description.md`.

### Observations / follow-ups (from aligning the prompt corpus)

- **Deliberate gap: there is no "close a mid-run dialog and keep going" mode.** `smart_non_modal` closes a
  mid-run modal *and fails*. A script that must tolerate dialogs popping up has to use `unleashed` +
  `closeModalDialogs()` and accept no PSI-consistency guarantees. This is documented in
  `execute-code-tool-description.md`; revisit only if a real caller needs the close-and-continue stance.
- **The corpus claimed `waitForSmartMode()` "runs automatically before your script" unconditionally.** That
  is true only under the default `smart_non_modal` — it is skipped under `non_modal` / `unleashed`. Added a
  short qualifier in `ide/overview.md`, `lsp/overview.md`, `prompt/skill.md`,
  `skill/coding-with-intellij-intro.md`, `skill/coding-with-intellij-threading.md`, and
  `ide/find-duplicates.md`. The unqualified phrasing was the same over-promise flagged in the redesign doc's
  quorum notes.
- `mcp-steroid-info.md` (server system prompt) had no mention of modality at all; added a brief default-
  behavior note pointing at the canonical tool description.
- Old `dialog_killer` / `allow_modal` / `cancel_on_modal` / `doNotCancelOnModalityStateChange` references
  had already been purged from the corpus by the prior commit; this pass confirmed none remain.

---

# IMPROVEMENTS — branch `mcp-5`

## Current state after devrig cleanup (2026-05-19)

The original `mcp-5` notes below describe the history of the npx-kt monitor
work. Current code has since moved on:

- The user-facing tool is `devrig`; Gradle module names remain `:npx-kt` and
  `:npx`.
- The active Kotlin package is `com.jonnyzzz.mcpSteroid.devrig`.
- The npm TypeScript MCP proxy and Kotlin attic implementation were removed.
- Remaining `Npx*` names are bridge protocol names for the IDE-side
  `/npx/v1/*` routes, not active npm proxy code.

Process notes and friction encountered while implementing the `npx-kt`
project-monitoring service (push-style HTTP, JSON PID markers).

## Decisions taken (with the user, before coding)

- **PID file format**: replace the existing `.<pid>.mcp-steroid` text file
  with a single-file JSON document (`schema=1`, `pid`, `mcpUrl`, `ide`,
  `plugin`, `createdAt`). Rejected: sibling-file or hybrid layouts (extra
  surfaces to keep in sync).
- **Streaming**: `application/x-ndjson`, one complete JSON object per line.
  Rejected: SSE (extra framing for no benefit; we don't need named event
  types here).
- **Event payload**: full snapshot every emit
  (`{type:"snapshot", seq, projects:[...]}`). Consumer state stays trivial —
  replace, don't merge. Rejected: delta events (more consumer state, more
  edge cases on missed messages / reconnect).
- **npx-kt wiring**: instantiate the new monitoring services in `Main.kt`
  alongside the new stdio MCP server. Legacy proxy path
  (`legacyProxyMain`, `ServerRegistry`, `NpxBeacon`) is left alone.

## Friction / observations

- Existing IDE marker file is consumed by **three** readers
  (`npx-kt::Utils.kt::scanMarkers`, the npm-distributed `npx/` TS proxy,
  `:test-helper:NpxProxyInstaller`). The TS reader is out of scope for this
  branch; the Kotlin readers are updated in lockstep with the writer.
- `NoLargeInlineStringsTest` and the `mcp-steroid://` URI lint rules don't
  apply here — no prompt content, no `mcp-steroid://` URIs added.

## Closing notes (branch ready for review)

Eight commits on top of `main`, intentionally split so each is reviewable in
isolation:

1. `mcp-5: seed branch with IMPROVEMENTS.md`
2. `mcp-5: pid marker file is now schema-versioned JSON`
3. `mcp-5: legacy npx-kt + test-helper consume the JSON pid marker`
4. `ij-plugin: NDJSON projects-stream endpoint + ProjectsStreamService`
5. `npx-kt: IDE monitoring stack — discovery + per-IDE NDJSON consumer`
6. `npx-kt: tests for IdeDiscoveryService + IdeMonitorService roundtrip`
7. `mcp-5: close out IMPROVEMENTS.md with test summary + follow-up list`
8. `mcp-5: pid marker carries the IDE's MCP port + bearer token`
9. `mcp-5: log self-review findings + port/token addition in IMPROVEMENTS`
10. `mcp-5: attach IntelliJ's bundled MCP server to pid marker via optional descriptor`
11. `mcp-5: log IntelliJ HTTP-server research + optional-descriptor pattern in IMPROVEMENTS`
12. `npx-kt: active port-scan discovery of IntelliJ-family IDEs`

Test coverage:
- `:mcp-steroid-server:test` — `PidMarkerTest` (6: roundtrip, pretty-print
  includes new port + token fields, forward-compat unknown fields,
  required-field rejection, filename contract, legacy marker without
  port/token falls back to defaults).
- `:npx-kt:test` — `MarkerScanTest` (7), `IdeDiscoveryServiceTest` (4),
  `IdeMonitorServiceTest` (3: roundtrip snapshots, multi-snapshot updates,
  Authorization header sent / suppressed), legacy `StdioServerProtocolTest`
  (61, untouched).
- `:ij-plugin:test` — `NpxProjectsStreamRouteTest` (4: initial snapshot,
  flow update, periodic ping, client-info parse with future-field
  tolerance), full pre-existing suite still green.

## Self-review findings (after the initial 6-commit drop)

- **PidMarker omits the MCP server's port and bearer token.** The IDE
  already owns both (`SteroidsMcpServer.port` and
  `NpxBridgeService.token`); without them on the marker, npx-kt must
  parse the URL and has no way to authenticate. Addressed by commit 8 —
  the new `port: Int` + `token: String` fields are optional (defaults
  `0` / `""`) so older markers still decode. npx-kt's
  `IdeMonitorService` now sets `Authorization: Bearer <token>` when the
  marker carries a non-empty token.
- **`IdeMonitorService` does not detect when a marker is rewritten with
  a different `mcpUrl` for the same pid.** Workers are keyed by pid; if
  an IDE restarts its MCP server on a different port within the same
  process, the worker keeps reconnecting to the old URL. Discovery polls
  the file every 2 s and picks up the new `DiscoveredIde` value, but the
  orchestrator's `if (workers.containsKey(pid)) continue` skips
  respawn. Filed as a follow-up; not load-bearing for the current
  open/close push goal.
- **The `/projects/stream` route is not yet auth-gated.** With the token
  now on the marker, the IDE can enforce
  `NpxBridgeService.isAuthorized()` on the projects-stream route
  whenever it wants — `IdeMonitorService` already sends the header. Not
  in this branch to keep the behaviour change focused.
## Branch findings — IntelliJ's HTTP servers (research follow-up)

- **`docs/intellij-builtin-servers.md`** catalogues both the platform's
  always-on Netty HTTP server (REST under `/api/*` — `about`, `file`,
  `settings`, `installPlugin`, `toolbox`, `projectSet`, `logs`,
  `startUpMeasurement`, plus plugin-provided handlers) and the
  optional MCP Server plugin (`com.intellij.mcpServer`). Use the doc
  before adding any cross-process integration that talks to the IDE
  outside of the `mcp-steroid` ktor server.
- **MCP Server plugin** is bundled in IDEA 2025.3+ but **off by
  default**. Default port 64342, bound to 127.0.0.1, exposes `/sse`
  (and `/stream` in 2026.1+). Force-enable system properties:
  `-Didea.mcp.server.force.enable=true`,
  `-Didea.mcp.server.force.port=<int>`.
- **Optional dependency wiring (no reflection).** We expose the
  bundled MCP server's endpoint shape on `PidMarker.intellijMcpServer`
  via the canonical IntelliJ optional-plugin pattern:
  `bundledPlugin("com.intellij.mcpServer")` in Gradle for compile
  access, `<depends optional="true" config-file="mcpServer-integration.xml">`
  in `plugin.xml`, and `mcpServer-integration.xml` registering
  `IntelliJMcpServerProbeImpl` only when the dep is satisfied. When
  the dep is missing the class is never loaded, so there's no
  `NoClassDefFoundError` window and no reflection involved.
- **API version skew.** The 253 bundle of `McpServerService` exposes
  `isRunning`, `getPort`, `getServerSseUrl`; `getServerStreamUrl` was
  added later. The probe derives the streamable HTTP URL from the SSE
  URL (same listener, sibling path) so the marker carries both. If
  the `/stream` endpoint isn't live on an older bundle, the client
  observes that and falls back to SSE.

## Branch findings — active port-scan discovery (commit 12)

- **Why active scan, on top of marker discovery?** The
  `.<pid>.mcp-steroid` marker only fires for IDEs that have the
  `mcp-steroid` plugin installed and started. Active port scanning
  finds **any** JetBrains IDE running on localhost (vanilla IntelliJ,
  PyCharm without our plugin, etc.) by probing `/api/about` on the
  IntelliJ Platform's known port ranges.
- **Default scan ranges**: `63342..63361` (Netty built-in HTTP server,
  the platform picks the first free port in that 20-port window) and
  `64342..64361` (bundled MCP Server plugin's
  `DEFAULT_MCP_PORT + 19` fallback range).
- **Threading model**: a fixed-size daemon-thread pool named
  `mcp-steroid-port-scan-<n>` is wrapped as a `CoroutineDispatcher`
  via `Executors.asCoroutineDispatcher()`. Probes are launched with
  `async(scanDispatcher)` + `awaitAll()`. This keeps a slow TCP
  connect on one port from stalling the stdio MCP server's
  dispatcher or the marker discovery's polling.
- **Failure modes are normal**: connection-refused on a port (no IDE
  listening) and JSON-200-without-IDE-fields (a non-IDE web server
  happens to share the port) both filter to `null` without
  propagating. The scan is a probe, not a contract — a non-IDE port
  is not an error.
- **Shutdown discipline**: `IntelliJPortDiscovery` implements
  `Closeable`. `Main.kt` calls `close()` after cancelling the scan
  loop; the executor is also drained inside the `start { … }` job's
  `finally` block (on `NonCancellable`) so in-flight probes don't
  leak when the parent scope is cancelled.

## Out-of-scope follow-ups (logged for later)

- The port-discovery output is currently informational only — it
  isn't consumed by `IdeMonitorService` (which still streams projects
  only from `mcp-steroid`-aware IDEs). Future work: cross-reference
  the two flows so the monitor can also surface "IntelliJ detected
  at :63344 but no `mcp-steroid` plugin loaded" states.
- We don't yet probe the MCP server plugin's `/sse` endpoint
  directly to confirm it's enabled. The `/api/about` probe only
  tells us the IDE itself is alive. A second pass on the bundled
  MCP server port range that does a HEAD on `/sse` would close
  that gap.

## Out of scope (filed for follow-up)

- The npm-distributed `npx/` TypeScript proxy still parses the legacy text
  format. Updating it to consume the JSON marker (and the new streaming
  endpoint) is a separate piece of work — different language, different
  deploy pipeline.
- The monitoring stack does not yet feed back into `legacyProxyMain`'s
  `ServerRegistry`. Replacing the polling refresh loop with the push-based
  state from `IdeMonitorService` is the natural next step but was kept out
  of this branch to keep changesets small.
- Reconnect-on-half-open: `IdeMonitorService` reconnects on stream close,
  but does not yet treat "no envelope received in N×ping" as a hint to
  proactively drop and reconnect. Trivial to add once we have telemetry on
  how often the IDE actually pings under load.

## Mid-flight clarifications (user, after PidMarker scaffold landed)

- **Forward/backward compat is universal**: `ignoreUnknownKeys = true`
  applies to **every** decoder we touch in this branch — JSON marker file,
  NDJSON wire frames, any request/response body. PidMarker already does
  this; the npx-kt monitor and the IDE-side stream parsers must follow
  suit.
- **Liveness**: IDE emits a `ping` envelope on the projects stream every N
  seconds (target 5s) so the monitor can distinguish "no project changes"
  from "TCP socket silently dead". Reading a `ping` resets a stale-watchdog
  on the consumer; missing it past `N * 3` triggers a reconnect.
- **Client identification**: npx-kt announces itself to the IDE on connect
  (clientId, clientPid, clientVersion, platform/arch). Cleanest fit is a
  `POST /npx/v1/projects/stream` whose request body carries the
  client-info JSON; the response keeps the streaming NDJSON shape. IDE
  logs the announcement and includes `clientInstanceId` on the streamed
  envelopes for traceability.

---

## Reviewer suggestions

### 2026-05-31 — Claude (Opus 4.8)

1. **`monitorAndCloseModalDialogs()` is missing from the "prep yourself" method lists in three places — agents under `non_modal`/`unleashed` can't discover the during-run monitor.**
   - In `ExecuteCodeTool.kt` the schema string reads: `do those yourself via the context methods (syncDocuments, waitForSmartMode, closeModalDialogs).` → change to `(syncDocuments, waitForSmartMode, closeModalDialogs, monitorAndCloseModalDialogs, allowModalDialog).`
   - In the `NON_MODAL` enum KDoc: `The script prepares what it needs via [McpScriptContext] (`closeModalDialogs`, `syncDocuments`, `waitForSmartMode`, ...).` → spell out all five and drop the `...`: `(`closeModalDialogs`, `monitorAndCloseModalDialogs`, `allowModalDialog`, `syncDocuments`, `waitForSmartMode`).`
   - In `mcp-steroid-info.md`: `Finer control (`closeModalDialogs()`, `syncDocuments()`, `waitForSmartMode()`, `allowModalDialog()`) lives in the script-context methods.` → add `monitorAndCloseModalDialogs()` to the list.
   - Rationale: the article body lists all five context methods, but the three reference surfaces an agent is most likely to read first each omit the one method that lets `non_modal` code reproduce `smart_non_modal`'s during-run protection — a `non_modal` user has no path to the monitor. A trailing `...` is not a discoverable API.

2. **`allowModalDialog()` has no documented scope/duration — agents can't tell if one call covers one dialog, all subsequent dialogs, or the rest of the run.**
   - Article line: ``- `allowModalDialog()` — suspend that watcher so a dialog your script opens **on purpose** is left alone (call it just before opening the dialog).`` → append a scope sentence, e.g. `It suppresses the close-and-fail watcher for the remainder of the call (it does not re-arm); after it the monitor no longer guards against unexpected modals.` (Confirm the actual semantics against `McpScriptContextImpl` before wording — the redesign doc itself never pins down whether the suppression is one-shot or run-long.)
   - Rationale: an agent opening two dialogs in sequence, or wanting the monitor back after its own dialog closes, has no way to reason about behavior from the current text. The same ambiguity is in the enum KDoc and schema string (`call allowModalDialog() from the script first`), which both imply a per-dialog "first" without saying so.

3. **The "refresh VFS" step in `smart_non_modal` collides with the standalone "VFS refresh before and after every call" section — it reads as two different, possibly redundant refreshes.**
   - The `smart_non_modal` row/KDoc/schema all list `refresh VFS` as a pre-flight step, while the later section states `MCP Steroid schedules two refreshes for you` on *every* call regardless of mode.
   - Suggested fix in `execute-code-tool-description.md`: in the Modality section add one clause — `(the before/after VFS refreshes below run in every mode; what `smart_non_modal` adds on top is the commit + save of documents via `syncDocuments()`).`
   - Rationale: as written, an agent cannot tell whether `unleashed` skips the VFS auto-refresh (it does not) or whether `smart_non_modal` does a third refresh. Separating "always-on VFS refresh" from "mode-gated commit+save" removes the apparent contradiction.

4. **No mode documents post-flight document sync — an agent that edits a `Document` under `smart_non_modal` and returns may leave it uncommitted/unsaved.**
   - The article documents only the *pre-flight* `syncDocuments()` for `smart_non_modal` and a tail *VFS* refresh; it never says whether `smart_non_modal` re-runs commit+save *after* the body (earlier redesign revisions had this as step 6; Revision 5's equivalence list dropped it).
   - Suggested fix: add one line to the `smart_non_modal` description stating whether a post-flight commit+save runs, e.g. `Documents your script edits are committed + saved again after the body returns (post-flight) before the VFS refresh.` — or, if it does not, state that explicitly so agents know to call `syncDocuments()` at the end of an editing script.
   - Rationale: the threading table already warns `You still need ...commitAllDocuments() inside your script if the same script both writes and reads back PSI`, but that is about *intra-script* reads; it does not answer whether edits survive to disk after the call under the default mode. This is the single most load-bearing unknown for editing scripts.

5. **Schema-string `smart_non_modal` omits the diagnostic-capture detail that the KDoc and article both promise.**
   - Schema string: `a modal that appears mid-run is closed and the run FAILS (if your script opens a dialog on purpose, call allowModalDialog() from the script first).` → add the capture, matching the KDoc/article: `...is closed and the run FAILS (a screenshot + thread dump are captured; if your script opens a dialog on purpose, call allowModalDialog() first).`
   - Rationale: the screenshot+thread-dump capture is the agent's primary debugging signal when a run fails on an unexpected modal. The enum KDoc and the article table both state it; the schema string — the surface most clients render inline — drops it, so a schema-only reader doesn't know failure output includes a screenshot to inspect.

Added a new "## Reviewer suggestions" section with five concrete suggestions (context-method list gaps across 3 files, `allowModalDialog()` scope, VFS-refresh double-documentation, missing post-flight-sync wording, and schema↔KDoc diagnostic-capture parity.

### 2026-05-31 — Gemini CLI

1. **`waitForSmartMode()` timeout outcome is undefined — agents don't know if a long indexing pass fails the script or is just skipped.**
   - Article/KDoc: `waitForSmartMode() — wait for indexing; asserts non-modal (fails on a modal).` → change to `(fails on a modal or if the internal deadlock-safety timeout is reached).`
   - Rationale: The redesign doc identifies the timeout as a "deadlock safety net," but neither the agent-facing tool description nor the KDoc state that hitting this limit is a fatal error. Knowing it fails helps agents decide whether to wait or use `smartReadAction {}` for best-effort reads.

2. **`unleashed` mode has no modal-safe way to commit or save documents — `syncDocuments()` is unusable when running under a modal.**
   - `syncDocuments()` asserts non-modal and fails. For `unleashed` scripts (which specifically run *under* modals, e.g. to test dialog state), this means they cannot use the standard helper to flush their logs or edits to disk.
   - Suggested fix: Add `saveAllDocuments()` (no assert) or a `force` parameter to `syncDocuments(force: Boolean = false)` that skips the modal assert for the `saveAllDocuments` and `refreshVfs` portions.
   - Rationale: Scripts running under a modal still need a way to persist their results to the VFS/disk before finishing.

3. **`closeModalDialogs()` returns a low-signal `Int` count — agents cannot tell *what* they closed without a heavy screenshot-analysis turn.**
   - `closeModalDialogs(): Int` → change to `closeModalDialogs(): List<String>` (returning dialog titles or class names) or explicitly document that it logs closed titles to the console automatically.
   - Rationale: High-signal text feedback (e.g. "Closed 'Extract Method' dialog") is much cheaper for an agent to process than fetching and analyzing a screenshot to confirm it nuked the right thing.

4. **Missing standalone `assertNonModal()` context method — `non_modal` scripts have no way to perform a manual gate check without side effects.**
   - The `non_modal` profile is defined as "assert non-modal + nothing else," but this check is currently only exposed to scripts as a side effect of `syncDocuments()` or `waitForSmartMode()`.
   - Suggested fix: Add `assertNonModal()` to `McpScriptContext` (fails with screenshot if modal).
   - Rationale: Completes the composable context-API model by exposing the "gate policy" logic as an independent method, allowing `unleashed` scripts to check state without triggering a VFS sync or indexing wait.

5. **Idempotency of `monitorAndCloseModalDialogs()` is undocumented — complicates logic in complex/multi-block scripts.**
   - Suggested fix: Explicitly state in the KDoc and article that calling `monitorAndCloseModalDialogs()` is a no-op if the monitor is already active.
   - Rationale: Simplifies script design by allowing agents to "ensure monitoring is on" before sensitive operations without worrying about double-registering listeners or throwing errors.

### 2026-05-31 — Codex

1. **The top-level `modal` wording still frames indexing as "modality", even though the enum also controls preparation steps.**
   - In `ExecuteCodeTool.kt` enum KDoc: ``How `steroid_execute_code` treats IDE modality (modal dialogs / indexing) around the script.`` → change to ``How `steroid_execute_code` prepares the IDE and handles modal dialogs around the script.``
   - In the schema string: `How to treat IDE modality around the script.` → change to `IDE preparation and modal-dialog policy for the script.`
   - Rationale: indexing is not modal-dialog handling, and the default also commits/saves documents, so schema-only readers need a broader but more precise frame.

2. **`non_modal` reads like a whole-run guarantee, but the documented behavior only checks the start state.**
   - Article row: ``Require a non-modal IDE at the start (fail with a screenshot if modal); do **nothing** else — no sweep, no commit, no indexing wait. **Not sufficient for PSI/editing** unless you call `syncDocuments()` / `waitForSmartMode()` yourself.`` → change to ``Require a non-modal IDE at the start (fail with a screenshot if modal); do **nothing** else — no sweep, no commit, no indexing wait, and no during-run monitor. The guarantee is start-only: modals appearing later are ignored unless you call `monitorAndCloseModalDialogs()`. **Not sufficient for PSI/editing** unless you call `syncDocuments()` / `waitForSmartMode()` yourself.``
   - Schema string fragment: `'non_modal': only assert a non-modal IDE at the start (fail with a screenshot if modal) and do NOTHING else — no dialog sweep, no commit, no indexing wait;` → change to `'non_modal': only assert a non-modal IDE at the start (fail with a screenshot if modal) and do NOTHING else — no dialog sweep, no commit, no indexing wait, no during-run monitor; later modals are ignored unless the script calls monitorAndCloseModalDialogs();`
   - Rationale: agents may pick `non_modal` expecting protection against modals for the entire script, but it is only the initial gate.

3. **`monitorAndCloseModalDialogs()` does not clearly distinguish monitoring from the immediate sweep.**
   - KDoc: `Start watching for modal dialogs for the rest of the execution. When one appears it is closed` → change to `Start watching for modal dialogs for the rest of the execution. This does not perform an immediate sweep; call closeModalDialogs() first if you need to handle a dialog already on screen. When a modal dialog is detected it is closed`
   - Article line: ``- `monitorAndCloseModalDialogs()` — start a watcher for the rest of the run: a modal that appears is closed`` → change to ``- `monitorAndCloseModalDialogs()` — start a watcher for the rest of the run; it does not perform an immediate sweep, so call `closeModalDialogs()` first for dialogs already on screen. A modal detected by the watcher is closed``
   - Rationale: `unleashed` scripts with an existing modal need to know that starting the monitor is not the same as calling the one-shot cleaner.

4. **`closeModalDialogs()` diagnostic wording is ambiguous about per-dialog versus per-sweep artifacts.**
   - Context KDoc: `Captures a diagnostic screenshot and a thread dump (recorded with the execution) before closing.` → change to `Captures one thread dump for the sweep and a diagnostic screenshot before each dialog is closed (recorded with the execution).`
   - Article line: ``close all showing modal dialogs (deepest-first), capturing a screenshot + thread dump first`` → change to ``close all showing modal dialogs (deepest-first), capturing one thread dump for the sweep and a screenshot before each dialog is closed``
   - Rationale: this matches the current implementation shape and prevents agents from expecting either one screenshot for the whole sweep or one thread dump per dialog.

Added four Codex reviewer suggestions covering modal framing, start-only `non_modal`, monitor-vs-sweep behavior, and close-dialog diagnostic wording.

### 2026-05-31 — Claude (Opus 4.8, 1M context)

1. **The `smart_non_modal` descriptions promise "wait for indexing" but never carry the point-in-time / `smartReadAction` caveat — only the standalone context-method bullet does, and a default-mode agent never reads that bullet.**
   - The caveat exists only at `execute-code-tool-description.md` line 82–83 (``waitForSmartMode() — ... Point-in-time only — still use `smartReadAction { }` for index-dependent reads.``). The three places that describe the **default's automatic** smart-mode wait all omit it:
     - Enum KDoc (`ExecuteCodeTool.kt`): `wait for indexing (smart mode), then run with the modal-dialog monitor active` → `wait for indexing (smart mode; point-in-time — still use smartReadAction { } for index-dependent reads), then run with the modal-dialog monitor active`.
     - Article table row: `commit + save documents, refresh the VFS, wait for indexing — then run` → `...refresh the VFS, wait for indexing (point-in-time only; index-dependent reads still need smartReadAction { }) — then run`.
     - Schema string: `commit+save documents, refresh VFS, wait for indexing, then run while watching for modals` → `...refresh VFS, wait for indexing (point-in-time — use smartReadAction { } for index reads), then run while watching for modals`.
   - Rationale: the redesign doc explicitly required "wait_for_smart_mode description must not over-promise (point-in-time; smartReadAction still needed)" (`docs/exec-code-options-redesign.md` line 290). Because `smart_non_modal` runs the wait *for* the agent, the agent has no reason to read the context-method bullet where the caveat currently lives — so the one surface they do read (the default's own description) is exactly where the over-promise survives.

2. **`smart_non_modal`'s automatic "wait for indexing" is dumb→smart-mode only; it does NOT await external-system (Gradle/Maven) configuration, so a PSI query on a freshly-opened Gradle project can still race import — and the repo's own guidance already prefers `awaitConfiguration`.**
   - Add a note to the Modality section of `execute-code-tool-description.md` after the table: `The default's indexing wait is dumb→smart-mode only. On a freshly-opened or re-synced Gradle/Maven project the real readiness boundary is project configuration, not smart mode — call Observation.awaitConfiguration(project) yourself (see mcp-steroid://skill/execute-code-gradle) before index-dependent reads; smart_non_modal does not await it.`
   - Rationale: `docs/CLAUDE.md` and the arena recipes already codify "prefer `Observation.awaitConfiguration(project)` + `smartReadAction(project)` over `waitForSmartMode()` for indexed reads" (regression `IntelliJThisLoggerLookupTest`). An agent trusting the default to "wait for indexing" before a `ReferencesSearch`/`FilenameIndex` call on a just-opened Gradle project gets stale/empty results; nothing in the modal docs warns of this, and the default's reassuring "wait for indexing" phrasing actively hides it.

3. **No guidance tells agents the default is correct for read-only scripts — the visible "commit + save documents" step invites defensive downgrading to `non_modal`, which silently drops the smart-mode wait their indexed reads depend on.**
   - Add one line to the Modality section: `For a read-only script (navigation, find-references, inspection report) keep smart_non_modal: the commit + save step is a no-op when nothing is dirty, and you still get the smart-mode wait and during-run monitor. Dropping to non_modal to "skip the write prep" is a mistake — it removes the indexing wait, and index-dependent reads then race dumb mode.`
   - Rationale: an agent reading that the default "commits + saves documents" for a pure `ReferencesSearch` will reasonably assume `non_modal` is the leaner, correct choice and switch — losing the exact `waitForSmartMode()` that makes indexed reads reliable (the failure mode in #2). The docs say the default "is right for almost everything" but never close the loop that "almost everything" *includes read-only work and here's why the write-side prep is free."

4. **Under `smart_non_modal` a call can fail before the script body ever runs (gate, bounded commit guard, bounded smart-mode guard), but nothing tells the agent that — so a pre-flight failure gets debugged as a bug in the agent's Kotlin.**
   - Add to the Modality section: `Note: under smart_non_modal the call can FAIL before your script body runs — a modal surviving the initial sweep (gate fail + screenshot), or the bounded commit / smart-mode pre-flight step hitting its deadlock-safety timeout. Such a failure is not a bug in your code; check the screenshot / error text before rewriting the script.`
   - Rationale: the redesign doc gives `smart_non_modal` a multi-step pre-flight (sweep → gate → commit guard via `withTimeout → ToolCallErrorException` → bounded `waitForSmartMode()`), each an independent failure point that runs *before* the body. The agent-facing surfaces describe these as setup the tool does *for* you, with no hint they can fail standalone — so the natural response to a failure is to edit the (innocent) script and burn a retry turn, rather than inspect the captured diagnostics.

Added four new suggestions distinct from the earlier Claude/Gemini/Codex passes: default-mode point-in-time caveat parity, the `awaitConfiguration` gap for external-system projects, read-only "keep the default" guidance, and pre-flight failure attribution.

### 2026-05-31 — Gemini CLI

1. **Explicitly distinguish "Modal Dialog" from "Modality State" in failure triggers.**
   - Target: `ExecuteCodeTool.kt` schema/KDoc and `execute-code-tool-description.md`.
   - Change: "fail ... if a modal survives" → "fail ... if a **modal dialog** survives (non-dialog modality like background progress is tolerated but skips prep steps like sync/wait)."
   - Rationale: Revision 4 of the design doc clarifies that progress-only modality is tolerated. However, the agent-facing docs use the broad term "modal," which technically includes background indexing. Clarifying that only `DialogWrapper` instances trigger a fatal failure prevents agents from fearing background tasks will arbitrarily break their scripts.

2. **Include `project.save()` in `syncDocuments()` or add a standalone `saveProject()` context method.**
   - Target: `McpScriptContext` API and `execute-code-tool-description.md`.
   - Change: Expand `syncDocuments()` to include `project.save()` or add `saveProject()`.
   - Rationale: Currently, `syncDocuments()` focuses on `Document` and `PSI` persistence. For scripts that modify project structure (adding modules, changing libraries, or editing `.idea` files), saving documents is insufficient. Ensuring project-level settings are flushed to disk is essential for subsequent external tools (like `grep` or `Bash`) to see the updated state.

3. **Gate failure (`smart_non_modal` / `non_modal`) should capture a Thread Dump for consistency.**
   - Target: `ExecuteCodeTool.kt` schema and `ModalMode` KDoc.
   - Change: "fail with a screenshot" → "fail with a screenshot + thread dump."
   - Rationale: The during-run monitor already captures both. The initial gate failure often occurs because a modal dialog is stuck due to a background process or a deadlock. Providing the thread dump at the gate prevents a diagnostic "blind spot" when the IDE is already in a bad state before the script starts.

4. **Provide a scoped `withModalDialogAllowed { ... }` context method.**
   - Target: `McpScriptContext` API and `execute-code-tool-description.md`.
   - Change: Add a lambda-based `withModalDialogAllowed { ... }` helper.
   - Rationale: `allowModalDialog()` currently leaves it ambiguous whether the suppression is one-shot or run-long (as noted by Claude). A scoped version is idiomatically safer for Kotlin scripts, ensuring the monitor is re-armed automatically after the intended interaction even if the block throws an exception.

### 2026-05-31 — Codex (GPT-5)

1. **`non_modal` tells agents to self-prep with `closeModalDialogs()`, but the body never runs if a modal exists at the initial gate.**
   - Schema string fragment: `do those yourself via the context methods (syncDocuments, waitForSmartMode, closeModalDialogs)` → `do document/index prep yourself via context methods (syncDocuments, waitForSmartMode); if you may need to close an already-open modal from the script, use modal=unleashed and call closeModalDialogs() first.`
   - Article row fragment: `"I need a non-modal IDE but will manage commits / indexing / dialogs myself."` → `"I need a clean non-modal start and will manage commits / indexing / later dialogs myself."`
   - Rationale: `non_modal` fails before user code on an existing modal, so listing `closeModalDialogs()` as a way to prepare that mode is misleading for the exact leftover-dialog case agents would try to handle.

2. **`unleashed` is documented as only "trivial" work, but the locked behavior table uses it for intentional modal-dialog workflows.**
   - Article row: `Trivial / hardcoded IDE actions only. NOT for PSI or code-editing flows (no consistency guarantees).` → `Intentional modal-dialog workflows (open/inspect/screenshot/close a dialog yourself) and trivial hardcoded IDE actions. NOT for PSI or code-editing flows (no consistency guarantees).`
   - Schema string fragment: `for trivial / hardcoded IDE actions ONLY, never for PSI/editing.` → `for intentional modal-dialog workflows or trivial / hardcoded IDE actions ONLY, never for PSI/editing.`
   - Rationale: without a positive modal-dialog example, agents may avoid the one mode that the design explicitly requires for tests and UI-management scripts where a modal must survive long enough to inspect.

3. **`McpScriptContext` top-level KDoc still says `waitForSmartMode()` is automatic without naming the default mode.**
   - Context KDoc: `waitForSmartMode() is called automatically before your script starts.` → `Under the default modal=smart_non_modal profile, waitForSmartMode() is called automatically before your script body starts; other modal modes must call it explicitly if they need it.`
   - Quick-reference comment: `// waitForSmartMode() is called automatically before your script starts` → `// Under modal=smart_non_modal, waitForSmartMode() is called automatically before your script body starts`
   - Rationale: this stale context API doc contradicts the new `non_modal` / `unleashed` semantics and can mislead agents reading generated context docs instead of the tool article.

4. **The MCP server instruction text says the default "monitors for modals" but omits that the monitor closes them and fails the call.**
   - `mcp-steroid-info.md` sentence: `then monitors for modals during the run.` → `then closes any modal dialog that appears during the run and fails the call with diagnostics.`
   - Rationale: "monitors" sounds observational; the default is actively destructive/failing, which is the key fact an agent needs before running UI actions under `smart_non_modal`.

Added four Codex (GPT-5) suggestions covering `non_modal` self-prep wording, legitimate `unleashed` modal workflows, stale context KDoc auto-wait wording, and active monitor behavior in the server text.

### 2026-05-31 — Claude (Opus 4.8, 1M context) — second pass

1. **The "Quick Start" bullet — the most-read surface in the whole article — describes `smart_non_modal`'s pre-flight but stops at "all before your script", omitting the during-run monitor and its fail-the-call behavior.**
   - `execute-code-tool-description.md` lines 53–55: `With the default \`modal=smart_non_modal\`, leftover modal dialogs are closed, the IDE is required non-modal, documents are committed/saved + VFS refreshed, and \`waitForSmartMode()\` runs — all before your script. See "Modality (the \`modal\` option)" below.` → append the run-time half: `... runs — all before your script; then a monitor watches the run and **closes any modal that appears mid-script and FAILS the call** (call \`allowModalDialog()\` first if you open one on purpose). See "Modality (the \`modal\` option)" below.`
   - Rationale: an agent that opens a dialog mid-script under the default gets a *failed call*, which is the single most surprising behavior of the mode. The Quick Start is where agents calibrate expectations before reading the table; today it presents `smart_non_modal` as purely pre-flight setup, so the mid-run fail reads as an inexplicable error rather than documented behavior. (Distinct from the earlier "pre-flight can fail before the body" suggestion — that is about steps *before* the body; this is the *during-run* monitor missing from Quick Start.)

2. **"fail with a screenshot" / "screenshot + thread dump captured" appears ~10 times across all three surfaces, but nothing tells the agent HOW to retrieve the captured artifacts — agents are promised a debugging signal with no path to it.**
   - Every mode description and context-method bullet promises a screenshot/thread dump on failure (e.g. article line 69 `the call **fails with a screenshot**`, line 78 `screenshot + thread dump captured`), but no surface states whether they arrive inline in the tool-call error payload, as a file path, or require a follow-up `steroid_take_screenshot` call.
   - Suggested fix: add one line to the Modality section of `execute-code-tool-description.md`, e.g. `When a call fails on a modal, the captured screenshot and thread dump are returned in the tool-call error payload — read them there before retrying; you do not need a separate steroid_take_screenshot call.` (Confirm the actual delivery mechanism against `ScriptExecutor.kt` / the failure-result builder before finalizing the wording.)
   - Rationale: the capture is repeatedly sold as the agent's primary diagnostic, but a signal the agent can't locate is worthless — without this line an agent that hits a gate/monitor failure either re-runs blind or burns a turn calling `steroid_take_screenshot` (which captures *current* state, not the state at failure). This is the missing other half of every "fail with a screenshot" promise.

3. **The relationship between the `timeout` request parameter and `smart_non_modal`'s bounded pre-flight guards (commit + `waitForSmartMode`) is undocumented — an agent cannot tell whether `timeout` covers the pre-flight or only the script body.**
   - `ExecuteCodeTool.kt` line 104: `"Execution timeout in seconds (default: $defaultTimeoutSeconds, configurable via mcp.steroid.execution.timeout registry key)"` → clarify scope, e.g. `"Execution timeout in seconds for your script body (default: $defaultTimeoutSeconds, configurable via mcp.steroid.execution.timeout registry key). smart_non_modal's pre-flight commit and smart-mode waits have their own internal deadlock-safety bounds and are not governed by this value."` (verify the actual scoping in `ScriptExecutor.kt` first).
   - Rationale: the redesign doc gives the commit and smart-mode steps their own `withTimeout → ToolCallErrorException` bounds independent of the user `timeout`. An agent that lowers `timeout` expecting a fast bail-out on a slow-indexing project will still wait out the internal smart-mode bound, and an agent debugging a "timed out" failure can't tell whether its body or the pre-flight blew the budget. Naming the boundary makes the failure attributable.

4. **`non_modal`'s "Use it for" cell is self-referential ("I need a non-modal IDE but will manage … myself"), giving no concrete task — unlike `smart_non_modal` ("PSI / code-editing / build / test") and `unleashed` ("trivial / hardcoded IDE actions").**
   - `execute-code-tool-description.md` line 70 "Use it for" cell: `"I need a non-modal IDE but will manage commits / indexing / dialogs myself."` → give a real example: `A non-PSI read that only needs a stable non-modal start and no commit/index prep — e.g. reading run-configuration or VCS-status state — where smart_non_modal's commit + smart-mode wait would be wasted work.`
   - Rationale: every other mode anchors the choice to a concrete task shape; `non_modal`'s cell just restates the mechanism, so an agent weighing it against the default has nothing to pattern-match its task against and defaults back to `smart_non_modal` (or, worse, picks `non_modal` for editing because "I'll manage it myself" sounds capable). A concrete "when this and not the default" example is what makes the three-way choice actionable.

Added a second-pass set of four suggestions distinct from all prior iterations: Quick Start omits the during-run monitor/fail, no surface explains how to retrieve the captured screenshot/thread dump, the `timeout` param vs pre-flight-bound scope is undocumented, and `non_modal`'s "Use it for" cell lacks a concrete task example.

### 2026-05-31 — Gemini CLI

1. **Add `isModal(): Boolean` and `isSmartMode(): Boolean` context methods for non-fatal state probing.**
   - Rationale: Complements the existing `assertNonModal()` suggestion. Essential for `unleashed` or `non_modal` scripts to perform safe conditional branching (e.g., "if smart then refactor else log-and-skip") without triggering the fatal assertions built into `syncDocuments()` or `waitForSmartMode()`.

2. **Add an `awaitDispose: Boolean = true` parameter to `closeModalDialogs()`.**
   - Rationale: Closing a dialog in IntelliJ is often an asynchronous `dispose()` call. A script calling `closeModalDialogs()` immediately followed by `syncDocuments()` might still hit the "modal survives" gate if the IDE hasn't finished clearing the modality stack. Awaiting disposal makes the sequence deterministic and prevents race-condition failures.

3. **Add a `projectOnly: Boolean = false` parameter to `syncDocuments()`.**
   - Rationale: In multi-project IDE setups, `FileDocumentManager.getInstance().saveAllDocuments()` (which `syncDocuments()` likely uses) is a global, expensive operation that flushes every open project. Allowing agents to scope the sync to the current `project` significantly improves performance and reduces disk I/O for scripts working in a single workspace.

4. **Refactor the `modal` parameter schema description in `ExecuteCodeTool.kt` for brevity.**
   - Rationale: The current 20-line description bloats the tool-definition context sent to agents and makes CLI `help` output difficult to scan. Move the detailed mode-by-mode prose to the `mcp-steroid://skill/execute-code-tool-description` prompt/article and keep the JSON schema description to a 3-5 line summary with a pointer to the full docs.

5. **`waitForSmartMode()` should return the duration waited (in milliseconds) or a boolean indicating if it waited.**
   - Rationale: Provides high-signal performance telemetry. A script that sees a 0ms wait (or `false`) knows the indices are already "warm" and can proceed with heavy PSI queries immediately; a long wait informs the agent that the project is "cold" or resource-heavy.

### 2026-05-31 — Codex (GPT-5, second pass)

1. **`non_modal` currently gets a hidden post-flight `syncDocuments()`, contradicting the locked "assert-only" profile.**
   - In `ScriptExecutor.kt`, change:
     ```kotlin
     // Post-flight: re-sync to disk iff we are non-modal NOW (a fresh read — the body may have
     // opened or closed a modal). Skipped for `unleashed` (no disk-consistency contract).
     if (exec.modal != ModalMode.UNLEASHED && !isModalEdt()) {
     ```
     to:
     ```kotlin
     // Post-flight: re-sync to disk only for `smart_non_modal`, whose profile promises the
     // document-consistency contract. `non_modal` is intentionally start-gate-only.
     if (exec.modal == ModalMode.SMART_NON_MODAL && !isModalEdt()) {
     ```
   - Rationale: the design table says `non_modal` does no sweep, sync, smart-mode wait, or monitor; silently syncing after the body makes the mode more stateful than its schema/KDoc/article promise.

2. **`waitForSmartMode()` is still unbounded in implementation, despite the locked design calling it bounded.**
   - In `McpScriptContextImpl.kt`, add a timeout constant after `SYNC_DOCUMENTS_TIMEOUT`:
     ```kotlin
     /** Deadlock guard for [waitForSmartMode] when indexing never reaches smart mode. */
     private val WAIT_FOR_SMART_MODE_TIMEOUT = 60.seconds
     ```
     and wrap the existing `suspendCancellableCoroutine { ... }` body:
     ```kotlin
     try {
         suspendCancellableCoroutine { cont ->
     ```
     to:
     ```kotlin
     try {
         withTimeout(WAIT_FOR_SMART_MODE_TIMEOUT) {
             suspendCancellableCoroutine { cont ->
     ```
     with a matching `catch (e: TimeoutCancellationException)` that captures `waitForSmartMode-timeout` and throws a `ToolCallErrorException`.
   - Rationale: agents are told the smart-mode wait is bounded; an unbounded wait can hang before the script body and makes the new `modal` default less predictable.

3. **The default empty modal sweep captures a thread dump even when there is nothing to close.**
   - In `McpScriptContextImpl.closeModalDialogs()`, change:
     ```kotlin
     captureThreadDump("closeModalDialogs")
     val found = dialogWindowsLookup().withDialogWindows(project) { it.size }
     // killProjectDialogs captures a screenshot before closing each dialog (VisionService).
     dialogKiller().killProjectDialogs(
     ```
     to:
     ```kotlin
     val found = dialogWindowsLookup().withDialogWindows(project) { it.size }
     if (found == 0) return 0
     captureThreadDump("closeModalDialogs")
     // killProjectDialogs captures a screenshot before closing each dialog (VisionService).
     dialogKiller().killProjectDialogs(
     ```
   - Rationale: `smart_non_modal` calls this on every execution; empty, healthy runs should not attach diagnostic thread dumps when the docs say diagnostics are captured before closing dialogs.

4. **The monitor docs sound event-driven, but the implementation polls once per second.**
   - In `McpScriptContext.kt`, change `Start watching for modal dialogs for the rest of the execution. When one appears it is closed` to `Poll for showing modal dialogs for the rest of the execution. A modal dialog still showing at a poll tick is closed`.
   - In `execute-code-tool-description.md`, change ``- `monitorAndCloseModalDialogs()` — start a watcher for the rest of the run: a modal that appears is closed`` to ``- `monitorAndCloseModalDialogs()` — poll for showing modal dialogs for the rest of the run; a modal still showing at a poll tick is closed``.
   - Rationale: a brief dialog that opens and closes between 1s checks will not be observed; the wording should set agent expectations to the actual polling semantics.

Added four second-pass Codex suggestions covering hidden `non_modal` post-sync, the missing smart-mode timeout, empty-sweep diagnostics, and polling-vs-event wording.
