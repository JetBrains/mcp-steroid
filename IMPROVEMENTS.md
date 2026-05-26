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
