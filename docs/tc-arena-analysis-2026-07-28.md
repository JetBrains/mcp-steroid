# TeamCity Arena Analysis — 2026-07-28 · FeatureService125 · Claude Opus 5 · four arms

Single-build deep dive: one DPAIA scenario, one agent, four arms, traces read end-to-end.

| | |
|---|---|
| Build | [`1014673398`](https://buildserver.labs.intellij.net/buildConfiguration/mcp_steroid_IntegrationTests_DpaiaArena_FeatureService125_Claude/1014673398) — `0.101.590-jb-45b9629` |
| Config | `mcp_steroid_IntegrationTests_DpaiaArena_FeatureService125_Claude` |
| Branch / commit | `issue-251-arena-project-name` / `45b9629` |
| Scenario | `dpaia__feature__service-125` (Maven, Spring Boot, 5 FAIL_TO_PASS classes) |
| Agent | Claude Code, model `claude-opus-5`, per-arm timeout 1800 s |
| Wall clock | 08:56:54 → 11:04:26 UTC (**2 h 07 m**), `BUILD SUCCESSFUL`, 4/4 tests pass |
| Artifacts | one run-dir zip per arm (`agent-claude-code-1-raw.ndjson`, `-decoded.txt`, `agent-result.patch`, `session-info.txt`) |

The four-arm layout (`mcp`, `devrig`, `mcp-slim`, `none`), the surefire-XML verification step and the
`tests_tampered` flag are **branch-only** at the time of writing — they live on
`issue-251-arena-project-name`, not on `main`. The two `AgentOutputMetrics.kt` defects in §6 exist on
`main` as well.

## Executive summary

| Metric | Value |
|---|---|
| Arms run | 4 (`claude+mcp`, `claude+devrig`, `claude+mcp-slim`, `claude+none`) |
| Arms that solved the task | **4/4**, verified FTP 5/5 (100 %) in every arm |
| Full suite after each arm | 84 tests, 0 failures, `BUILD SUCCESS` |
| Cheapest arm | `mcp` — $2.89 |
| Most expensive arm | `none` — $4.28 (+48 %) |
| Model turns | 21 / 21 / 17 / **57** (`mcp` / `devrig` / `slim` / `none`) |
| Peak context | 140 k / 144 k / 159 k / 153 k tokens — spread of 9 % |
| MCP resource reads (`steroid_fetch_resource`) | **0/4 runs** |
| Wall clock lost to a single import-settle timeout | **20 m 20 s** (first arm only) |
| Harness metric defects found | 3 (tool-call counter, test counter, tamper flag) |

The scenario does not discriminate between arms on correctness — all four produced equivalent,
green solutions. The signal is in cost, turn count and trace shape.

## 1. Outcome per arm

| Arm | Fix | Agent time | Verified FTP (surefire XML) | Patch |
|---|---|---|---|---|
| `claude+mcp` | YES | 883 s | 5/5 (100 %) | 12 files, +562 −49 |
| `claude+devrig` | YES | 823 s | 5/5 (100 %) | 11 files, +521 −48 |
| `claude+mcp-slim` | YES | 935 s | 5/5 (100 %) | 14 files, +528 −60 |
| `claude+none` | YES | 770 s | 5/5 (100 %) | 12 files, +497 −47 |

FAIL_TO_PASS classes, all green in all arms: `ReleaseControllerTests` (15), `ReleaseQueryEndpointsIT`
(23), `ReleaseServiceIntegrationTest` (7), `ReleaseStatusTransitionValidatorTest` (25),
`ReleaseApiSecuritySliceTest` (7).

All four arms converged on the same design: Flyway migration `V5` adding `planned_release_date` /
`release_owner`, an extended `ReleaseStatus`, a `ReleaseStatusTransitionValidator` plus
`InvalidStatusTransitionException` enforced in the update path, paginated query endpoints backed by
JPA Specifications, and audit logging.

## 2. Where the two hours went

Per-arm phase boundaries, from the build log:

| Phase | `mcp` | `devrig` | `mcp-slim` | `none` |
|---|---|---|---|---|
| container start → import complete | **28.1 min** | 4.2 min | 3.9 min | 3.7 min |
| ↳ of which the import-settle window | **20.6 min** | 22 s | 71 s | 74 s |
| agent session | 14.7 min | 13.7 min | 15.6 min | 12.8 min |
| post-agent verification (surefire re-run) + teardown | 11.1 min | 5.1 min | 5.3 min | 5.5 min |
| **arm total** | **53.9 min** | 23.1 min | 24.8 min | 21.3 min |

The 30-minute gap between the first arm and the rest is **not** an arm property. `with mcp` ran first
and paid for cold caches: it is the only arm whose log contains Maven `Downloading from …` lines
(warm `~/.m2` afterwards). While Maven was still downloading dependencies plus sources and javadocs
(`[IMPORT] Maven source/doc download: sources=true docs=true`), the settle loop in
`test-integration/src/main/kotlin/.../infra/mcp-steroid-import.kt` could never collect its 10
consecutive quiet rounds and burned the full `settleTimeoutMs = 20 min`, ending in
`WARNING: project did not fully settle within the deadline`. Arms 2–4 print `Settled` in about a
minute.

Two signatures alternate in the failing window, and that alternation is also why the stuck detector
never fires:

- `configuring=true dumb=false tasks=` — `Observation.awaitConfiguration` hitting its 60 s timeout;
- `configuring=false dumb=false tasks=?  0%;   0%` — two progress indicators with no text, pinned at 0 %.

Rounds with `configuring=true` refresh `lastChangeAt`, so the `stuckTimeoutMs = 3 min` guard cannot
trip, and the deadline is the only exit.

**Consequence for comparisons:** arm wall-clock is not comparable across a build. Compare the agent
session (`duration_ms` from the agent's `result` event, mirrored as `Agent time` in the arena block).

## 3. Cost and context

| Arm | turns | API calls | session | cost | output tokens | peak context | Σ cache-read | context/call |
|---|---|---|---|---|---|---|---|---|
| `mcp` | 21 | 21 | 14.7 min | **$2.89** | 45 394 | 139 815 | 1 688 920 | 87 334 |
| `devrig` | 21 | 21 | 13.7 min | $3.30 | 58 238 | 144 460 | 1 841 920 | 94 678 |
| `mcp-slim` | 17 | 17 | 15.6 min | $3.55 | 69 277 | **159 429** | 1 607 996 | 104 123 |
| `none` | **57** | 38 | 12.8 min | **$4.28** | 49 663 | 152 698 | **4 244 328** | 115 584 |

**How to read `cache_read_input_tokens`.** It is a token count, summed over every API call in the
session — each call re-reads the whole cached prefix, so the total is roughly
*(average context) × (number of calls)*. It is **not** a context size. `none` reads 2.5× more cached
prompt tokens than `mcp` almost entirely because it makes 38 calls instead of 21, not because its
context is bigger: peak context differs by 9 % (153 k vs 140 k). The defensible per-call statement is
that `mcp` carried 25 % less context per call (87 k vs 116 k).

**Claim that survives this data:** with MCP the agent reached the same verified result in half the
model round-trips and 33 % cheaper. Context size was comparable. n=1, one scenario, one model — not a
trend yet.

**The slim tool description did not pay off here.** `mcp-slim` served a 13 429-char
`steroid_execute_code` description instead of 27 037 (≈3.4 k tokens saved per call in the tool
definition), and came out the slowest (935 s), the most expensive ($3.55), with the largest per-call
context (104 k) and twice the `exec_code` calls (10 vs 5). Whatever the definition saved, extra calls
spent. One sample — but the "slim saves tokens" hypothesis is not supported by this run.

## 4. Trace walkthrough

### `claude+mcp` — 20 tool calls, 0 tool errors

`ToolSearch` ×2 → `steroid_list_projects` → `exec_code` (first-call recipe: project readiness, Docker,
Maven/JDK, VCS state) → **`Read` ×5** (the five FAIL_TO_PASS tests + `test-data.sql`) → `exec_code`
(dump production sources) → `exec_code` (migrations + `ReleaseStatus.RELEASED` usages) → `exec_code`
(**whole implementation in one script**) → `exec_code` (compile check via `ProjectTaskManager`) →
`Bash` ×6 (`spotless:apply`, targeted runs, full `mvnw test`).

Cleanest arm: no retries, no failed tool calls. It read tests with plain `Read` and wrote production
code through a single `exec_code` script — a hybrid strategy, and the cheapest of the four.

### `claude+devrig` — 20 tool calls, 1 tool error

Same shape, with two losses:

1. The source dump returned **66 011 characters across 1 783 lines**, tripping the tool-result limit.
   Claude saved it to a file and then spent **three `Read` calls** on its own
   `…/tool-results/mcp-mcp-steroid-steroid_execute_code…` file — three turns for content it had
   already fetched.
2. The implementation script failed its own guard —
   `IllegalStateException: field anchor count wrong` — and was retried "with single-line anchors".
   This is the agent's own pre-edit assertion, not an MCP Steroid failure; fail-fast worked as
   designed.

### `claude+mcp-slim` — 16 tool calls, 1 tool error, most IDE-centric

`exec_code` ×10, `Bash` ×4, and **`Read`/`Edit`/`Write` = 0/0/0**: all reading and all editing went
through the IDE. One anchor failure too (`Commands anchor not found`), followed by a diagnostic
`exec_code` ("why literal anchors did not match — line separators / whitespace") and a second
implementation attempt.

### `claude+none` — 56 tool calls, 5 tool errors

`Bash` ×17, `Edit` ×16, `Read` ×15, `Write` ×8. Five calls failed with
`File has not been read yet. Read it first before writing to it.` 57 turns against 17–21 elsewhere.
The trace is finely sliced: a whole-file `Write` of `ReleaseService.java`, then four consecutive
`Edit`s on the same file, then more.

### Common to all arms

- **No arm read any `mcp-steroid://` resource** — `steroid_fetch_resource` was never called. The
  `mcp` arm even selected it via `ToolSearch` and then never used it. This extends the existing
  finding (0/69 baseline runs, 0/196 in the April analysis) to 0/3 MCP arms here.
- Every arm ran `./mvnw spotless:apply` and finished with a full `mvnw test`.
- Prompt length: 29 385 chars for the MCP arms vs 15 669 for `none` — MCP instructions nearly double
  the task prompt.

## 5. Harness defects found

### a) `exec_code` calls are counted by substring — CSV counters are inflated

`test-experiments/src/test/kotlin/.../arena/AgentOutputMetrics.kt:159` tests
`line.contains("steroid_execute_code")` **first**, so any `>>` line that merely mentions the tool name
lands in the counter:

- `>> ToolSearch (select:steroid_list_projects,steroid_execute_code,…)`
- `>> Read (…/tool-results/mcp-mcp-steroid-steroid_execute_code…)`

This run: `mcp` reported 6 `exec_code` calls, actually **5**; `devrig` reported 10, actually **7** —
and its `Read` count came out as 1 instead of 4, because three reads were absorbed into
`exec_code`. `mcp-slim` matched (10/10) by luck. Match the tool name as a token after `>> `, not with
`contains`.

### b) `tests_run` is scraped from agent prose and takes the last match

`AgentOutputMetrics.kt:65` (`extractTestMetrics`) picks the **last** `Tests run: N, Failures: …` line
in the agent's output. All four arms actually ran 84 tests; three arms happened to end on the
aggregate line and reported 84, while `none` ended on a per-class line and is recorded as
`Tests: 7 run, 7 pass` in both its arena block and the comparison table. The authoritative number
(`Verified FTP 5/5` from surefire XML) is correct everywhere. Until this is fixed,
`tests_run` / `tests_pass` in `arena-comparison.csv` must not be compared across arms.

### c) `tests_tampered=true` in all four arms carries no signal

The flag fired for every arm. Yet the test-file diffs are byte-identical across arms — sha256 of the
per-file diff is `ce9900daf916` for `ReleaseControllerTests.java` and `9a0284de363a` for
`test-data.sql` in all four — while production diffs differ. So the change is not agent-authored and
the flag cannot be distinguishing behaviour.

Leading hypothesis: the baseline hashes are taken immediately before the agent starts
(`ArenaVerification.kt`, `hashTestFiles(extractPatchFilePaths(testPatch))`), prep compiles with
`-Dspotless.check.skip=true`, and then every agent runs `./mvnw spotless:apply`, which reformats the
patched test sources. Not proven by this build: the harness prints only the boolean, never the
changed paths. Log the post-agent hash map (or the pre/post diff) so the flag becomes diagnosable —
otherwise it is 4/4 noise.

### d) The import-settle deadline is burned in full on the first arm

See §2: 20 m 20 s lost, `WARNING: project did not fully settle`, stuck detector unable to fire
because the busy signature alternates. Warming `~/.m2` before the first arm (or treating an in-flight
Maven source/javadoc download as a distinct, reportable state) would remove ~20 minutes from a
2-hour build and make arm wall-clock comparable.

### e) A swallowed EDT failure in the plugin-install prep step

```
[PLUGIN-INSTALL] Installing plugins: [com.intellij.bigdatatools.kafka]
ERROR: Unexpected error during execution: InvocationTargetException (no message)
Caused by: RuntimeExceptionWithAttachments: Access is allowed from Event Dispatch Thread (EDT) only
OK Plugin installation complete
```

The plugin was not installed; prep reported success.

## 6. Takeaways

1. `dpaia__feature__service-125` does not discriminate arms for Opus 5 — all four are green. Use cost
   and round-trip count as the signal, not pass/fail.
2. The winning shape in this run is the **hybrid**: read tests with `Read`, write production code in
   one `exec_code` script (`mcp`, $2.89). Fully IDE-driven (`slim`) was more expensive; shell-only
   (`none`) was the most expensive and needed 2.7× the turns.
3. Large `exec_code` dumps are a real cost: one 66 k-character result cost `devrig` three turns of
   re-reading its own saved tool result. Recipes that dump sources should chunk or filter.
4. Fix §5a and §5b before the next cross-arm comparison — otherwise the CSV's tool-call and test
   counters are wrong. §5c makes the tamper flag diagnosable.
5. MCP resources remain unread (0/4). Anything an agent must know still has to arrive via the task
   prompt or `exec_code` output.

## Reproducing this analysis

```bash
# build state, tests, artifacts (helpers read a token from ~/.config/teamcity/token)
tc-rest "/builds/id:1014673398?fields=state,status,statusText,finishDate"
tc-rest "/testOccurrences?locator=build:(id:1014673398)&fields=count,testOccurrence(name,status,duration)"
tc-rest "/builds/id:1014673398/artifacts/children"

# full build log (works on running builds too) and the per-arm run dirs
tc-build-log 1014673398 /tmp/build.log
tc-artifact 1014673398 run-20260728-090118-dpaia__feature__service-125-mcp.zip
```

Per-arm agent numbers come from the `{"type":"result"}` event of each
`agent-claude-code-1-raw.ndjson` (`duration_ms`, `num_turns`, `total_cost_usd`, `usage`); context
figures come from per-API-call `usage` blocks deduplicated by `message.id`; tool-call sequences come
from `tool_use` blocks in the same file (the decoded transcript duplicates content and is not a
reliable counting source — see §5a).
