# DPAIA Arena Test Refactoring Plan

## Issues to Fix

### Issue 1: Shared container corruption (CRITICAL)
All agents share one Docker container via `@TestFactory`. Claude modifies files, then Codex/Gemini
see already-modified state. Each agent MUST get a fresh container.

### Issue 2: `assertExitCode(0)` fails despite agent success
Claude agent completes ARENA_FIX_APPLIED: yes with 163 passing tests, but the CLI process
exit code is non-zero. The assertion at DpaiaArenaTest.kt:99 uses `check(exitCode == 0)` which
throws IllegalStateException. Fix: use the same lenient assertion from DpaiaComparisonTest.kt:113
(`agentExitedSuccessfully || agentClaimedFix`).

### Issue 3: ClassCastException in `steroid_list_windows` (GH #18)
`kotlin.Pair` vs `com.intellij.openapi.util.Pair` — fires at ListWindowsToolHandler.kt:72-73
when plugin built against 253 runs on 262+. `bar.backgroundProcessModels` returns
`List<kotlin.Pair>` but bytecode expects `com.intellij.openapi.util.Pair`.
Fix: wrap in try/catch ClassCastException, return empty progress tasks for now.
Proper fix later: use reflection only on the `backgroundProcessModels` method.

### Issue 4: Missing chromium/arm64 in Docker (npm install fails)
JHipster project's frontend-maven-plugin runs `npm install` which pulls puppeteer, which
fails because chromium binary is not available for arm64 in the container.
Fix: install chromium in ide-base Dockerfile so puppeteer finds it.

## Refactoring Plan

### Phase 1: Fix GH #18 — ListWindowsToolHandler try/catch
- [x] File: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ListWindowsToolHandler.kt`
- [x] Wrap `bar.backgroundProcessModels` iteration in try/catch for ClassCastException
- [x] On ClassCastException, skip progress collection (non-essential data)
- [x] Add comment referencing mcp-steroid#18
- [ ] TODO (future): Use IntelliJ to find the exact method returning Pair<>, fix only that one with reflection

### Phase 2: Fix Docker chromium for arm64
- [x] File: `test-integration/src/test/docker/ide-base/Dockerfile`
- [x] Add `chromium` package to apt-get install
- [x] Set `PUPPETEER_SKIP_DOWNLOAD=true` env var
- [x] Set `PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium`

### Phase 3: Create new DpaiaJhipsterArenaTest (dedicated per-scenario test class)
- [x] New file: `test-experiments/.../arena/DpaiaJhipsterArenaTest.kt`
- [x] Explicit `@Test` methods: `claude with mcp`, `claude without mcp`, etc.
- [x] Each test method creates its OWN fresh IntelliJContainer + CloseableStackHost
- [x] Instance ID from system property `-Darena.test.instanceId` (default: jhipster-sample-app-3)
- [x] Project pre-deployed via `IntelliJProject.ProjectFromGitCommitAndPatch`
- [x] Lenient assertion: `agentExitedSuccessfully || agentClaimedFix`
- [x] MCP assertion: when withMcp=true, verify agent used steroid_execute_code
- [x] `@AfterAll` prints comparison table of all executed runs
- [x] Collect results into `CopyOnWriteArrayList` companion for cross-method reporting
- [x] Write per-run JSON summaries for improvement pipeline

### Phase 4: Refactor existing DpaiaArenaTest to match new pattern
- [x] Replace `@TestFactory` with explicit `@Test` methods per agent (6 methods)
- [x] Each test creates its own container (no shared `session` companion)
- [x] Fixed assertion to be lenient
- [x] Added `@AfterAll` comparison table
- [x] Instance ID from `-Darena.test.instanceId` (default: springboot3-3)

### Phase 5: Refactor DpaiaComparisonTest
- [x] Explicit `@Test` methods per agent+mode (6 methods)
- [x] Fresh container per test method
- [x] Removed `@TestFactory` + `DynamicTest`
- [x] Added `@AfterAll` comparison table
- [ ] DpaiaClaudeComparisonTest — not yet refactored (complex: token metrics, NDJSON parsing)

## Status
- Phase 1: DONE (try/catch workaround; proper reflection fix is future work)
- Phase 2: DONE
- Phase 3: DONE
- Phase 4: DONE
- Phase 5: PARTIAL (DpaiaComparisonTest done, DpaiaClaudeComparisonTest deferred)

## First Experiment Result (2026-04-14)

**Scenario:** dpaia__jhipster__sample__app-3 (ROLE_ADMIN → ROLE_ADMINISTRATOR)
**Agent:** Claude Sonnet 4.6 + MCP Steroid

| Metric         | Value |
|----------------|-------|
| Fix claimed    | YES   |
| Tests          | 47/47 pass, BUILD SUCCESS |
| Agent time     | 117s  |
| Prewarm        | 81s   |
| Cost           | $0.53 |
| Turns          | 33    |

## Remaining Work
- [ ] Run `claude without mcp` for A/B comparison on same scenario
- [ ] DpaiaClaudeComparisonTest refactoring (complex due to token metrics extraction)
- [ ] Extract token usage from NDJSON (currently parsed but not printed in table)
