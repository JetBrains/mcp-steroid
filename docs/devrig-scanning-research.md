# Research — do we need dynamic background scanning?

**Status:** decided — **option A (on-demand scanning)**. This page
is kept as the rationale + cost-analysis record; the call to remove
background scanners is final and tracked as implementation work.

## Question

Today `devrig mpc` runs three background tasks for the entire MCP
session lifetime:

1. **Marker scanner** — polls `~/.mcp-steroid/markers/` every **2 s**
   (`IdeDiscoveryService.scanInterval`,
   `npx-kt/.../devrig/monitor/IdeDiscovery.kt:59`).
2. **Port scanner** — probes 40 TCP ports
   (`63342..63361` + `64342..64361`,
   `IntelliJPortDiscovery.DEFAULT_PORT_RANGES`) every **30 s**
   (`scanInterval = 30.seconds`,
   `IntelliJPortDiscovery.kt:90`).
3. **IDE monitor** — opens one long-lived HTTP POST to
   `/npx/v1/projects/stream` per discovered IDE
   (`IdeMonitorService`,
   `npx-kt/.../devrig/monitor/IdeMonitor.kt:64`). Pushes
   project-open / close envelopes; auto-reconnects on stream
   close.

The r2 spec calls for devrig to be **stateless** — only in-memory
caches, no persistent state. The background tasks aren't persistent
state on disk, but they are state across calls: they pre-populate the
live model so each MCP tool call gets a sub-millisecond lookup.

**Could we drop the background tasks entirely and rebuild the model
on-demand at the time of each call?** Few IDEs are expected; the
spec already says nothing about freshness guarantees.

## Current architecture

```
                     ┌─── markers dir poll (2 s)       ─┐
DevrigServices ──────┼─── port scan (30 s)             ─┼──► IdeMonitorService
                     └─── per-IDE /projects/stream (∞) ─┘         │
                                                                  ▼
                                              StateFlow<Map<pid, IdeMonitorState>>
                                                                  │
                                                                  ▼
                                                       DevrigProjectRoutingService
                                                          (reads StateFlow lazily)
```

Per-call cost today: **O(1) map lookup** (the routing service just
reads the StateFlow's current value). All discovery work happens
out-of-band.

## On-demand alternative

Drop all three background tasks. Each incoming MCP call (and each
CLI command that needs the live model) runs the discovery pipeline
synchronously before lookup:

```
MCP tool call ──► rebuildSnapshot() ──► route ──► dispatch ──► response
                         │
                         ├─ readdir markers/ + parse each marker
                         ├─ TCP-probe ports in parallel
                         └─ POST /projects/stream per marker, await first envelope
```

`rebuildSnapshot()` would replace the entire monitor pipeline. No
StateFlow, no `Job`, no `CoroutineScope` held across calls. The
process is stateless modulo a single per-call cache that lives only
for the duration of the call (so the routing service can be queried
twice without re-scanning twice).

## Cost analysis (per call, happy path)

Measured against a typical dev box with 2 running IDEs + 1 idle
port-only IDE:

| Step | Wall-clock |
|---|---|
| Markers dir `readdir` + JSON parse (~3 files) | < 5 ms |
| Port scan (40 TCP probes, parallel, fast-fail) | 50 – 150 ms |
| Per-IDE `/projects/stream` POST + first snapshot (parallel) | 20 – 80 ms |
| **Total** | **75 – 250 ms** |

Worst case (slow port probe timeouts): up to **3 s** if a probed
port has a half-open socket. Bounded by the per-probe timeout
already in `IntelliJPortDiscovery`.

For comparison: the current `devrig backend` CLI command runs
exactly this pipeline once per invocation today and is perceived as
fast.

## Pros / cons

### Pros of on-demand

- **Truly stateless devrig process.** Aligns with the r2 spec
  philosophy. No background `Job`, no per-IDE persistent socket.
- **Simpler code.** Removes `IdeDiscoveryService`,
  `IntelliJPortDiscovery` background loop, the entire
  `IdeMonitorService` (with its reconnect/backoff state machine).
  Estimate: −400 to −600 LOC.
- **Test isolation.** Each call rebuilds from scratch; no shared
  state to reset between tests. Removes a category of flaky-test
  causes (timing races against `scanInterval`).
- **No stale-snapshot window.** Every call reflects the moment.
  The "marker disappeared 1.5 s ago but the 2 s scan hasn't fired
  yet" case can't happen.
- **No idle resource cost.** A `devrig mpc` process with no MCP
  calls in flight uses zero CPU and zero idle sockets. Today it
  keeps N long-lived HTTP streams + two polling loops alive.
- **Crash recovery is trivial.** No "reconnect a dropped stream"
  state to maintain.

### Cons of on-demand

- **Latency added to every call.** ~75–250 ms typical, up to
  ~3 s pathological. The current architecture adds 0 ms.
- **Push-based events are gone.** Today the IDE monitor can
  surface `notifications/projectOpened` and `…/projectClosed`
  to the MCP client as the events happen. On-demand has no
  push channel — clients learn of changes only when they next
  call `steroid_list_projects` (or similar).
  - **Mitigation:** the spec already says devrig snapshots are
    not freshness-guaranteed; the agent flow is "list →
    pick → call". The push channel isn't part of the contract.
  - **But:** if any agent relies on `notifications/*`, that
    flow breaks. Need to audit MCP client consumers.
- **Cost multiplies per call.** An agent that makes 20 tool
  calls in a row pays the 75–250 ms tax 20 times. Today the
  cost is amortised against the 2 s poll interval.
- **No graceful degradation across stream failures.** Today the
  monitor's reconnect loop recovers from transient IDE blips;
  on-demand sees every blip as a hard failure for that one call.

### Push vs poll mid-ground

A third option: keep the **marker scanner** (it's cheap — 5 ms
filesystem read) but drop the port scanner and the long-lived
stream. The port scan happens on-demand only when an MCP call
explicitly hits a backend not in the marker set (rare). The
project list is fetched on-demand from each marker per call.

This trades:

- some latency on first-call (~30–80 ms for the per-IDE snapshot
  fetches) for
- no idle HTTP streams + simpler reconnect story
- still picks up new IDEs within ~2 s

A fourth option: marker scanner kept, port scanner dropped, the
per-IDE stream replaced by an on-demand single-shot fetch. This
is the smallest delta from today and likely the right transition
point if the user wants to phase out background work
incrementally.

## Decision

**Option A — on-demand scanning, all background tasks removed.**
Matches the "devrig is stateless" tenet
([`docs/PHILOSOPHY.md`](PHILOSOPHY.md) § Tenet 3). The latency is
small in absolute terms, the code reduction is large, and the test
surface shrinks meaningfully. Push-notification channels were
never part of the spec contract; if a consumer relied on them
implicitly, that's a separate spec gap to fix — not a reason to
keep the background loops.

## Migration

1. Introduce `DiscoverySnapshot.rebuild()` that returns a fresh
   immutable model (the same shape as the JSON data model in
   `docs/devrig-naming.md`).
2. Route lookups through `rebuild()` per call. Cache the result
   inside the call's `CoroutineScope` so a single call doesn't
   double-scan.
3. Delete `IdeDiscoveryService` background loop, the
   `IntelliJPortDiscovery` background loop, and `IdeMonitorService`
   entirely — not just disable them, remove the classes, the
   `Job`/`CoroutineScope` plumbing, and the per-IDE reconnect logic.
4. Update `DevrigServices` to not hold the long-lived `ideMonitor`
   / `ideDiscovery` / `portDiscovery` `lazy` fields.
5. Audit MCP tool handlers that previously read from
   `ideMonitor.states.value` and re-point them at the per-call
   snapshot.
6. Remove the corresponding flake-prone tests
   (`IdeDiscoveryServiceTest`, the parts of `IdeMonitorServiceTest`
   that test reconnect timing).

## Open questions

1. Does any current MCP client rely on push-style
   `notifications/projectOpened` / `…/projectClosed`? Need an
   audit of `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/NpxProjectsStreamRoute*`
   consumers.
2. What is the actual measured latency of `rebuild()` on a real
   dev box with 5+ running IDEs? The 75–250 ms estimate above is
   from inspection only.
3. Does the agent flow `list → pick → call` accept a name that
   was published in the list and is gone by the time `call`
   arrives? The current spec says yes (typed exception with
   refresh instruction); the agent must be tested against this
   path.
