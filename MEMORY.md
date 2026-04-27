# MEMORY

## 2026-04-26 - Current DPAIA Direction

- Long-term goal: MCP Steroid should help agents complete DPAIA Maven and Gradle tasks with fewer tokens, fewer tool errors, and lower runtime than vanilla runs.
- Hard constraints from the user: do not add methods to `McpSteroid*` interfaces and do not add MCP tools. Improve server behavior, prompt resources, and DPAIA case quality instead.
- External methodology anchors:
  - `https://github.com/karpathy/autoresearch`: narrow autonomous experiment loop; modify the "research org code" / prompt, run a fixed benchmark, keep or revert based on measured improvement.
  - `https://jonnyzzz.com/RLM.md`: keep context outside the model where possible; assess, grep, partition, execute, synthesize, verify.
  - `https://run-agent.sh`: use traceable role-specific agent runs with persisted prompts/stdout/stderr and consensus for non-trivial direction changes.
- Current run-agent review artifacts are under `/tmp/mcp-steroid-review/runs/`.

## 2026-04-26 - Three-Agent Review Consensus

- Valid completed reviewers:
  - Claude: `/tmp/mcp-steroid-review/runs/run_20260426-201025-47047`
  - Codex: `/tmp/mcp-steroid-review/runs/run_20260426-201025-47048`
  - Gemini with provided keys: `/tmp/mcp-steroid-review/runs/run_20260426-201242-49168`
- Failed/redundant runs:
  - First Gemini start failed because `GEMINI_API_KEY` was absent.
  - Extra fallback Claude was stopped after consensus because it produced no output and was no longer needed.
- Consensus:
  - Claude and Codex both selected `ArenaTestRunner.buildPrompt()` MCP prompt cleanup as the lowest-risk next step.
  - Gemini selected a Gradle-focused prompt resource as useful follow-up.
  - Decision: do the arena prompt cleanup first, then consider Gradle resource work.

## 2026-04-26 - Recent GitHub Comment Context

- PR #26, "Improving speed for iterative/sequential tests running (~x10 performance)", is open with `CHANGES_REQUESTED`.
- Review concerns:
  - `npmBuild` declares too few inputs and can leave stale `dist/index.js`.
  - tessdata skip logic ignores `tessdataVersion` and can reuse old files after a version bump.
- This PR is related to iterative test speed, but it is not the next DPAIA prompt improvement.

## DPAIA Lessons To Preserve

- Arena prompt recipes are high-impact because agents follow them directly.
- MCP resources are low-impact unless explicitly requested; prior runs showed agents rarely read them spontaneously.
- Contradictory edit guidance is dangerous. The dedicated `steroid_apply_patch` path is the fast path for multi-site edits; the old `applyPatch {}` DSL inside `steroid_execute_code` still works but has Kotlin compilation overhead and should not be the recommended arena path.
- Do not optimize by adding DSL/interface surface. Prefer clearer routing, shorter prompts, and measured scenario runs.

## 2026-04-26 - Measured Prompt Cleanup Run

- Scenario: `DpaiaPetclinicRest37Test.claude with mcp`.
- Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`.
- Result: agent fixed the task in 111s, used MCP, and passed 184/184 Maven tests.
- Raw NDJSON metrics from `docs/autoresearch/dpaia/metrics.py`: 13 total tool calls, 2 agent `steroid_execute_code` calls, 11 native calls, 2 native Edit calls, 2 Bash calls, no tool errors, no `steroid_apply_patch`.
- Harness decoded summary reported 4 `exec_code` calls because it includes extra decoded lines around the run; prefer the raw metrics script for agent-only tool mix.
- Follow-up chosen from evidence: strengthen `steroid_apply_patch` prompt schema/wording because the agent used native Edit for an import+method change.

## 2026-04-26 - Current Diff Review Consensus

- Review artifacts:
  - Initial block review: `/tmp/mcp-steroid-review/runs-current/`.
  - Final approve review: `/tmp/mcp-steroid-review/runs-current-2/`.
- Claude, Codex, and Gemini all caught the same blocker in the first pass: the arena prompt used `path` while the current repo's `ApplyPatchToolHandler` schema requires `file_path`.
- After correcting the prompt and `TASKS.md`, all three approved.
- Consensus next step: re-run `DpaiaPetclinicRest37Test.claude with mcp` and compare native Edit count against the 2026-04-26 baseline of 2.

## 2026-04-26 - Corrected Apply-Patch Prompt Measurement

- Scenario: `DpaiaPetclinicRest37Test.claude with mcp`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-001705-dpaia__spring__petclinic__rest-37-mcp`.
- Result: agent fixed the task in 123s, used MCP, and passed 184/184 Maven tests.
- Arena summary JSON: `agent_duration_ms=123813`, `exec_code_calls=2`, `read_calls=2`, `edit_calls=0`, `write_calls=0`, `bash_calls=3`, `glob_calls=0`, `grep_calls=2`.
- Raw NDJSON metrics: 12 total calls, 3 MCP Steroid calls, 9 native calls, 1 `mcp__mcp-steroid__steroid_apply_patch`, 2 `mcp__mcp-steroid__steroid_execute_code`, 0 tool errors.
- Delta from the prior run: native Edit 2 -> 0, apply-patch false -> true, total tool calls 13 -> 12, errors stayed 0. Runtime regressed 111s -> 123s because Claude made 3 Bash Maven verification calls after the IDE compile check.
- Follow-up chosen from evidence: tighten verification guidance so a successful IDE build plus targeted Maven test does not routinely trigger duplicate Maven runs. Keep Gradle prompt resources as the larger follow-up.
- Implemented next low-hanging prompt tweak in `ArenaTestRunner.buildPrompt()`: do not rerun a completed Maven/Gradle target solely because `tail`/`grep` hid the `BUILD SUCCESS` summary; reruns after code changes, real failures, incomplete runs, or Gradle skipped tests remain required. Next measurement should check Bash count <=2 while preserving 184/184 tests.

## 2026-04-26 - Verification Prompt Review Consensus

- Review artifacts:
  - Initial verification wording review: `/tmp/mcp-steroid-review/runs-current-3/`.
  - Follow-up approve review: `/tmp/mcp-steroid-review/runs-current-4/`.
- First pass: Codex requested changes and Claude flagged the same issue as a nit. The phrase "Run each Maven/Gradle verification target at most once" was too broad and could discourage legitimate reruns after fixes, incomplete runs, or Gradle skipped tests.
- Fix applied: narrow the instruction to completed runs where `tail`/`grep` merely hid `BUILD SUCCESS`; explicitly preserve reruns after code changes, real failures, incomplete runs, or Gradle skipped-test behavior.
- Follow-up pass: Claude, Codex, and Gemini all approved.
- Next low-hanging fruit from consensus: measure this prompt on `DpaiaPetclinicRest37Test.claude with mcp`; if it behaves well, add a prompt regression test so the broad wording and contradictory edit guidance do not reappear.

## 2026-04-26 - Verification Prompt Measurement

- Scenario: `DpaiaPetclinicRest37Test.claude with mcp`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-003310-dpaia__spring__petclinic__rest-37-mcp`.
- Result: agent fixed the task in 101s, used MCP, and passed 184/184 Maven tests.
- Arena summary JSON: `agent_duration_ms=101319`, `exec_code_calls=3`, `read_calls=2`, `edit_calls=0`, `write_calls=0`, `bash_calls=2`, `glob_calls=0`, `grep_calls=2`.
- Raw NDJSON metrics: 11 total calls, 3 MCP Steroid calls, 8 native calls, 1 `mcp__mcp-steroid__steroid_apply_patch`, 2 `mcp__mcp-steroid__steroid_execute_code`, 2 Bash, 0 tool errors.
- Delta from the prior 123s run: Bash 3 -> 2, total tool calls 12 -> 11, runtime 123s -> 101s, native Edit stayed 0, apply-patch stayed true, pass rate stayed 184/184.
- Next low-hanging fruit: add a prompt regression test for the DPAIA arena MCP block, especially preventing broad "run at most once" wording and ensuring `steroid_apply_patch` uses `file_path`.

## 2026-04-26 - Arena Prompt Regression Test

- Added `ArenaPromptContractTest` in `test-experiments`.
- It builds the MCP arena prompt without starting Docker and asserts:
  - `steroid_apply_patch` is present.
  - Apply-patch examples use `"file_path"`, not `"path"`.
  - The older `applyPatch {}` DSL is mentioned only as the path to avoid.
  - Broad "Run each Maven/Gradle verification target at most once" wording is absent.
  - Legitimate rerun cases remain explicit.
  - Full-suite success remains required before `ARENA_FIX_APPLIED: yes`.
- Validation: `./gradlew :test-experiments:test --tests '*ArenaPromptContractTest*' --warning-mode all` passed.
- Next candidate: Gradle-focused MCP prompt/resource work, but measure one Gradle scenario first or add a similarly narrow prompt contract before broad resource changes.

## 2026-04-27 - Next-Step Review: Apply-Patch Routing First

- Review artifacts: `/tmp/mcp-steroid-review/runs-next-20260427/`.
- Claude, Codex, and Gemini all selected `update-apply-patch-tool-description-routing` as the next low-hanging fruit.
- Rationale: `ArenaTestRunner.buildPrompt()` already routes multi-site edits to the dedicated `steroid_apply_patch` tool, but the global `execute-code-tool-description.md` still taught the slower `steroid_execute_code` + script-context `applyPatch` DSL as the default. This contradiction affects every MCP session before a Gradle-specific resource is read.
- Implemented resource changes:
  - `prompts/src/main/prompts/skill/execute-code-tool-description.md` now recommends `steroid_apply_patch` for 2+ literal edit sites and keeps the script-context DSL as a fallback only when the patch must run inside the same `steroid_execute_code` script.
  - `prompts/src/main/prompts/skill/execute-code-overview.md` and `prompts/src/main/prompts/skill/coding-with-intellij.md` now point multi-site literal edits at `steroid_apply_patch`.
  - `prompts/src/main/prompts/ide/apply-patch.md` now frames the DSL as the lower-level fallback and links the dedicated tool description.
  - `PromptRoutingContractTest` guards the global execute-code tool description against routing ordinary multi-site edits back through `steroid_execute_code`.
- Validation: scoped `:prompts:test` selection passed via IntelliJ Gradle runner:
  `*PromptRoutingContractTest*`, `*MarkdownArticleContractTest*`, and `*ExecuteCodeToolDescriptionKtBlocksCompilationTest*` with `--warning-mode all`.
- Next measurement target: repeat `DpaiaPetclinicRest37Test.claude with mcp`; target 184/184 tests, 0 native Edit, `steroid_apply_patch` used, 0 tool errors, and no regression versus the 101s run.

## 2026-04-27 - Apply-Patch Routing Measurement

- Scenario: `DpaiaPetclinicRest37Test.claude with mcp`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-073953-dpaia__spring__petclinic__rest-37-mcp`.
- Command: `ANTHROPIC_API_KEY=$(cat ~/.anthropic) GEMINI_API_KEY=$(cat ~/.vertex) OPENAI_API_KEY=$(cat ~/.openai) ./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`.
- Result: agent fixed the task in 116s, used MCP, and passed 184/184 Maven tests.
- Arena summary JSON: `agent_duration_ms=116000`, `exec_code_calls=2`, `read_calls=2`, `edit_calls=0`, `write_calls=0`, `bash_calls=2`, `glob_calls=0`, `grep_calls=1`.
- Raw NDJSON metrics: 10 total calls, 3 MCP Steroid calls, 7 native calls, 1 `mcp__mcp-steroid__steroid_apply_patch`, 2 `mcp__mcp-steroid__steroid_execute_code`, 2 Bash, 0 tool errors.
- Delta from the prior 101s run: native Edit stayed 0, `steroid_apply_patch` stayed true, Bash stayed 2, total tool calls improved 11 -> 10, and runtime moved 101s -> 116s. This is acceptable PetclinicRest37 variance and does not show a prompt-routing regression.
- Next low-hanging fruit: pick and measure one Gradle DPAIA scenario before changing Gradle guidance, then add or tighten a Gradle-focused MCP prompt resource based on observed failures.

## 2026-04-27 - Apply-Patch Persistence Fix

- Updated and inspected `~/Work/intellij` before touching our implementation. Reference point: IntelliJ's `ApplyTextFilePatch.updateDocumentContent()` calls `Document.setText(...)` and then `FileDocumentManager.saveDocument(document)`.
- Bug evidence: a new TDD test read the patched file with `Files.readString(...)` immediately after `ctx.applyPatch { ... }` returned; it failed before the fix because only the IDE document/PSI had changed.
- Fix: `executeApplyPatch()` now saves every touched document before returning, verifies that the document is no longer unsaved, wraps save failures in `ApplyPatchException`, and rethrows `ProcessCanceledException`.
- Added coverage:
  - `ApplyPatchTest.testSingleHunkPersistsToDiskBeforeReturning`
  - read-only/save-failure coverage in `ApplyPatchTest`
  - `ApplyPatchToolIntegrationTest` over actual MCP HTTP `tools/call`, with direct disk assertions for single hunk, multi-hunk same file, multiple files, missing old string, non-unique old string, missing file, read-only/save failure, and empty hunks.
- Validation: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.execution.ApplyPatchTest' --tests 'com.jonnyzzz.mcpSteroid.server.ApplyPatchToolIntegrationTest' --rerun-tasks --warning-mode all` passed.
- Review artifacts: `/tmp/mcp-steroid-review/apply-patch-persistence-20260427/runs/`. Claude/Codex/Gemini approved the core fix direction; Claude/Codex specifically requested save-failure/read-only hardening, which was added.

## 2026-04-27 - Gradle Microshop-2 Measurement After Persistence Fix

- Scenario: `DpaiaMicroshop2Test.claude with mcp`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-090258-dpaia__spring__boot__microshop-2-mcp`.
- Result: agent fixed the task, used MCP, exited 0, and full Gradle suite passed. Agent time 171s.
- Arena summary: `exec_code=3`, `Read/Edit/Write=0/0/3`, `Glob/Grep/Bash=0/0/4`.
- Raw metrics: 12 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, estimated 8 patch hunks, 0 native Edit, 0 Read, 3 Write for new files, 4 Bash, 0 tool errors, total tokens 1,052,439.
- Delta versus the earlier stale-disk Microshop-2 failure: 248s -> 171s, 41 calls -> 12 calls, native Edit 14 -> 0, Read 11 -> 0, errors 7 -> 0. The agent no longer reported that `steroid_apply_patch` failed to persist to disk.
- Remaining low-hanging issue: the agent still wasted one Bash call with `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-arm64`, got `invalid source release: 24`, then corrected to JDK 25. Prompt/prewarm output should expose the exact configured JDK path more directly.

## 2026-04-27 - IntelliJ Monorepo thisLogger Readiness

- Added `IntelliJThisLoggerLookupTest` as a Docker integration regression for MCP-driven semantic search on `IntelliJProject.IntelliJMasterProject`.
- Initial implementation with `waitForProjectReady(requireIndexingComplete = true)` plus `waitForSmartMode()` still failed with `IndexNotReadyException`; GitHub issue #29 tracks the failed readiness contract and the rejected `repeat(12)` retry workaround.
- Marinade guidance says to wait for project initialization, no indexing, no modal dialogs, and no startup/indexing background tasks. IntelliJ source adds the missing API contract: `waitForSmartMode()` does not guarantee another dumb mode will not begin before the next statement; for initial import/configuration, use `Observation.awaitConfiguration(project)`, and for indexed reads use `smartReadAction(project)`.
- `~/Work/intellij` references checked:
  - `DumbService.kt`: `waitForSmartMode()` explicitly has no post-return smart-mode guarantee and points at `Observation.awaitConfiguration`.
  - `coroutines.kt`: `smartReadAction(project)` runs through `ReadConstraint.inSmartMode(project)`.
  - `IndexingTestUtil` / `TestObservation`: test-framework helpers, not appropriate for a normal MCP script running inside the IDE.
- A `run-agent.sh` Codex review from Marinade artifacts (`/Users/jonnyzzz/Work/marinade/runs/run_20260427-083709-8424`) agreed with this approach.
- Validation: `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij ./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJThisLoggerLookupTest' --rerun-tasks --warning-mode all` passed in 8m41s.
- Successful markers: `CONFIGURATION_COMPLETE=false`, `LOGGER_FILE=/home/agent/project-home/community/platform/util/src/com/intellij/openapi/diagnostic/logger.kt`, `THISLOGGER_REFERENCE_COUNT=4191`, `THISLOGGER_FILE_COUNT=1526`.
- Follow-ups: the green run still logged severe Kotlin FIR resolve errors and an `ExceptionCaptureService` NPE while capturing them; also, IntelliJ checkout setup reused a cached TeamCity ZIP even when `MCP_STEROID_INTELLIJ_CHECKOUT_DIR` was set.

## 2026-04-27 - Indexing Guidance Resource Fix

- Fixed MCP server/resource guidance that treated `waitForSmartMode()` as a stable handoff for indexed reads.
- `ExecutionSuggestionService` now gives `IndexNotReadyException` / dumb-mode failures the actionable hint: after project open/import/sync/configuration, call `Observation.awaitConfiguration(project)`, then keep the whole indexed PSI query inside `smartReadAction { }`.
- Updated `McpScriptContext` KDoc plus prompt resources `prompt/skill.md`, `coding-with-intellij-intro.md`, `coding-with-intellij-patterns.md`, `coding-with-intellij-psi.md`, and `coding-with-intellij-threading.md`.
- Added `IndexingGuidanceContractTest` so prompt resources do not reintroduce "smart mode is confirmed for the duration" or "safe to use indices immediately" wording.
- Validation: `SkillReferenceHintTest` passed; scoped prompt contract and changed Kt-block tests passed after forced `:prompts:generatePrompts --rerun-tasks`.

## 2026-04-27 - Exception Capture and IntelliJ Checkout Follow-ups

- Fixed the local failure behind the green `IntelliJThisLoggerLookupTest` run's secondary NPE: `ExceptionCaptureService` no longer assumes `LogRecord.parameters` is non-null, logs capture failures to stderr, and still rethrows `ProcessCanceledException`.
- Regression coverage: `ExceptionCaptureServiceTest.testJulSevereErrorWithNullParametersIsCaptured`.
- Validation: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.execution.ExceptionCaptureServiceTest' --rerun-tasks --warning-mode all` passed.
- The Kotlin FIR severe error itself remains a follow-up: `KaFirReferenceResolver` / `Expected FirResolvedContractDescription but FirLazyContractDescriptionImpl` came from the Kotlin plugin during the monorepo semantic lookup.
- Fixed IntelliJ checkout cache precedence: explicit configured ZIPs and checkout directories now win before reusing `ultimate-git-clone-linux.zip` from cache, local checkout packaging uses `Path.toUri()` so `git clone` receives a `file:///...` URL, and the generated ZIP preserves the source checkout's real `origin` remote for in-container fetches.
- Regression coverage: `IntelliJGitCloneZipTest.configured checkout replaces stale cached archive`.
- Validation: `./gradlew :test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJGitCloneZipTest' --rerun-tasks --warning-mode all` passed.
- Review artifacts:
  - Initial pass: `/tmp/mcp-steroid-review/exception-checkout-20260427/runs/`. Codex requested changes for hidden `ProcessCanceledException` handling and the host-only `file:///Users/...` origin in local-checkout ZIPs.
  - Follow-up pass: `/tmp/mcp-steroid-review/exception-checkout-20260427/runs-followup/` plus replacement Claude run under `/tmp/mcp-steroid-review/exception-checkout-20260427/runs-followup-2/`.
- Final review consensus: Claude, Codex, and Gemini approved the current diff. Next low-hanging fruit is Gradle/JDK prompt guidance by 2/3 reviewers; Kotlin FIR severe-log investigation remains tracked separately.

## 2026-04-27 - Gradle/JDK Prompt Guidance Measurement

- Fixed the Microshop-2 Java 21 dead-end by routing DPAIA prompts through the case-configured JDK version.
- `ArenaTestRunner.buildPrompt()` now prints `Configured project JDK version`, makes the first MCP call resolve and print `Recommended JAVA_HOME`, and tells Bash Gradle commands to use the exact printed path. It explicitly forbids wildcard JAVA_HOME assignments because Bash does not expand globs in assignment words.
- Regression coverage: `ArenaPromptContractTest.gradle prompt exposes configured jdk before first bash gradle call`.
- Validation: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest' --rerun-tasks --warning-mode all` passed.
- Review artifacts:
  - Initial pass: `/tmp/mcp-steroid-review/gradle-jdk-prompt-20260427/runs/`. Codex requested changes for the bad `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-*` copyable command.
  - Follow-up pass: `/tmp/mcp-steroid-review/gradle-jdk-prompt-20260427/runs-followup/`. Claude, Codex, and Gemini approved.
- Measurement: `ANTHROPIC_API_KEY=$(cat ~/.anthropic) GEMINI_API_KEY=$(cat ~/.vertex) OPENAI_API_KEY=$(cat ~/.openai) ./gradlew :test-experiments:test --tests '*DpaiaMicroshop2Test.claude with mcp' --rerun-tasks --warning-mode all`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-115129-dpaia__spring__boot__microshop-2-mcp`.
- Result: agent fixed the task, used MCP, exited 0, and full Gradle suite passed. Agent time 136s.
- First MCP output: `Recommended JAVA_HOME: /usr/lib/jvm/temurin-25-jdk-arm64`.
- Decoded log check: both Bash Gradle calls used `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64`; no `temurin-21` Gradle call and no `invalid source release: 24`.
- Raw metrics: 15 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, estimated 8 patch hunks, 0 native Edit, 3 Read, 3 Write, 2 Glob, 2 Bash, 0 tool errors, total tokens 979,647.
- Delta versus the 171s baseline: Bash 4 -> 2, agent time 171s -> 136s, total tokens 1,052,439 -> 979,647, tool errors stayed 0, native Edit stayed 0.
- Prompt hardening after review: all copyable Gradle test templates/examples now include `JAVA_HOME=<Recommended JAVA_HOME>` before `./gradlew`.
- Decoded-log guard added after the measurement: `AgentOutputMetrics.findDecodedGradleCommandsWithUnexpectedJavaHome()` now flags Gradle Bash commands that omit `JAVA_HOME`, use a literal wildcard, or use a path outside the expected JDK prefix, including absolute `gradlew` wrapper paths.
- Regression coverage: `ExtractDecodedLogMetricsTest.microshop gradle bash commands use configured jdk without wildcard` and `ExtractDecodedLogMetricsTest.detects gradle bash commands with lower jdk or wildcard java home`.
- Validation after the guard: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest' --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ExtractDecodedLogMetricsTest' --rerun-tasks --warning-mode all` passed.
- Final review artifacts:
  - First final pass: `/tmp/mcp-steroid-review/gradle-jdk-prompt-20260427/final-runs/`. Codex requested changes for copyable Gradle examples without `JAVA_HOME` and absolute `gradlew` paths missed by decoded-log detection.
  - Follow-up final pass: `/tmp/mcp-steroid-review/gradle-jdk-prompt-20260427/final-runs-2/`. Claude, Codex, and Gemini approved.
- Next low-hanging consensus: investigate the Kotlin FIR severe logs next (Claude + Gemini); Maven fallback JDK guidance remains the other reviewed candidate (Codex).

## 2026-04-27 - IntelliJ Monorepo thisLogger FIR Avoidance

- Reproduced the remaining `IntelliJThisLoggerLookupTest` problem with TDD: the existing `ReferencesSearch.search(target, scope)` script still found 4192 references but emitted severe Kotlin FIR logs (`KaFirReferenceResolver`, `Expected FirResolvedContractDescription but FirLazyContractDescriptionImpl`), and the new test assertion failed on those post-lookup log lines.
- Reviewed `~/Work/intellij` search APIs before changing the test: `CacheManager.getVirtualFilesWithWord` is the low-level IdIndex-backed word lookup used by IntelliJ/Kotlin search code to narrow candidates without resolving every reference.
- Fix: keep the real IntelliJ Ultimate monorepo and `Observation.awaitConfiguration(project)` + `smartReadAction(project)` flow, but replace full Kotlin reference resolution with `CacheManager.getVirtualFilesWithWord(target.name!!, UsageSearchContext.IN_CODE, scope, true)` and `KtCallExpression` PSI filtering for actual `thisLogger()` call sites. The test now asserts the lookup window does not log the FIR severe signatures.
- Validation:
  - Compile check via IntelliJ Gradle run config: `:test-experiments:compileTestKotlin --warning-mode all` passed.
  - Failing TDD run: `test-experiments/build/test-logs/test/run-20260427-124607-intellij-thislogger-lookup` failed on the FIR severe-log assertion after the `ReferencesSearch` script emitted the Kotlin FIR exception.
  - Fixed run: `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij ./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJThisLoggerLookupTest' --rerun-tasks --warning-mode all` passed in 21m40s.
  - Fixed markers: `THISLOGGER_LOOKUP_STRATEGY=INDEXED_WORD_PLUS_KOTLIN_PSI`, `THISLOGGER_REFERENCE_COUNT=2670`, `THISLOGGER_FILE_COUNT=1522`.
- Review artifacts:
  - Initial pass: `/tmp/mcp-steroid-review/thislogger-fir-20260427/runs/`. Gemini and Claude approved; Codex requested the explicit strategy-marker assertion and stale `CLAUDE.md` consensus cleanup.
  - Follow-up pass: `/tmp/mcp-steroid-review/thislogger-fir-20260427/followup/runs/`. Claude, Codex, and Gemini approved.
- Next low-hanging consensus: add the Gradle-focused MCP prompt resource by 2/3 reviewers (Codex + Gemini). Maven fallback JDK guidance remains Claude's candidate.

## 2026-04-27 - Gradle Prompt Resource

- Added `mcp-steroid://skill/execute-code-gradle` as a focused prompt resource for Gradle sync/test work inside `steroid_execute_code`.
- The resource routes agents to `ExternalSystemUtil.refreshProject(...)` plus `Observation.awaitConfiguration(project)` after Gradle file edits, and to `ExternalSystemUtil.runTask(...)` with `GradleConstants.SYSTEM_ID` for Gradle tests.
- It explicitly keeps `ProcessBuilder("./gradlew")` banned inside `steroid_execute_code` and scopes Bash `./gradlew` fallback to shell-level final verification or IDE-runner fallback outside `steroid_execute_code`.
- Routing links were added from `execute-code-overview.md`, `execute-code-tool-description.md`, and `coding-with-intellij.md`.
- Regression coverage: `GradlePromptContractTest` checks the rendered prompt anchors and overview link; generated `ExecuteCodeGradleKtBlocksCompilationTest` compiled all Kotlin blocks for IDEA and IDEA EAP.
- Validation: IntelliJ Gradle runner passed `:prompts:generatePrompts :prompts:test --tests 'com.jonnyzzz.mcpSteroid.prompts.GradlePromptContractTest' --tests '*ExecuteCodeGradleKtBlocksCompilationTest*' --tests 'com.jonnyzzz.mcpSteroid.prompts.MarkdownArticleContractTest' --warning-mode all`. The first run failed on two non-kotlin fences; those were converted to prose/inline commands and the rerun exited 0.
- Review artifacts: `/tmp/mcp-steroid-review/gradle-prompt-resource-20260427/runs/`. Claude, Codex, and Gemini approved.
- Next low-hanging consensus: measure `DpaiaMicroshop2Test.claude with mcp` with this resource in place and compare to the 136s JDK-fixed baseline. Track full-suite pass/fail, Bash Gradle calls, any resource fetch/use, nested `ProcessBuilder`, token count, tool errors, and wall time.

## 2026-04-27 - Gradle Prompt Resource Measurement

- Scenario: `DpaiaMicroshop2Test.claude with mcp`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-135940-dpaia__spring__boot__microshop-2-mcp`.
- Host Gradle run: IntelliJ Gradle runner with API keys injected into the run configuration environment.
- Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full in-project Gradle suite passed. Agent time was 170.8s.
- Raw metrics: 28 total calls, 4 MCP calls, 24 native calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 12 Read, 3 Glob, 3 Write, 5 Bash, 2 tool errors, 1,458,578 total tokens, 0 resource fetches.
- Delta versus the 136s JDK-fixed baseline: agent time 136s -> 170.8s, Bash 2 -> 5, total calls 15 -> 28, tokens 979,647 -> 1,458,578, native Edit stayed 0, `steroid_apply_patch` stayed true.
- Agent behavior: it did not fetch `mcp-steroid://skill/execute-code-gradle`; it used `steroid_apply_patch`, then `steroid_execute_code` for an IDE build that returned `Build errors: false, aborted: true`, then fell back to Bash Gradle with the correct JDK.
- Lesson: the new resource is valid but not discoverable enough for this arena path. The next low-hanging work should route the Gradle resource URI through high-impact prompts or execution failure guidance when Gradle verification is needed after an IDE build abort.

## 2026-04-27 - Gradle Resource Routing After Measurement

- Review artifacts: `/tmp/mcp-steroid-review/gradle-resource-measurement-20260427/runs/`.
- Claude, Codex, and Gemini approved the measurement interpretation.
- Reviewer split:
  - Claude recommended high-impact arena prompt routing because resources are low-priority unless a higher-priority prompt tells agents to fetch them.
  - Codex recommended result-boundary guidance for `steroid_execute_code` outputs that show `errors=false, aborted=true`.
  - Gemini recommended sync-before-Bash abort guidance in arena/resource prompts and produced a rough edit.
- Implemented the narrow common path first: Gradle arena prompts now tell agents to call `steroid_fetch_resource` for `mcp-steroid://skill/execute-code-gradle` before Gradle sync/test work inside `steroid_execute_code`; Maven prompts route aborted IDE builds to `mcp-steroid://skill/execute-code-maven` and do not mention the Gradle resource.
- Prompt resources `execute-code-tool-description.md` and `execute-code-overview.md` now say `errors=false, aborted=true` should run the matching sync pattern before Bash fallback, using full Maven/Gradle resource URIs.
- Regression coverage: `ArenaPromptContractTest` asserts Gradle prompts contain the Gradle URI and Maven prompts do not. Changed prompt resources are covered by generated KtBlocks compilation tests plus `MarkdownArticleContractTest`.
- Validation: `ArenaPromptContractTest` passed through IntelliJ Gradle; `:prompts:generatePrompts :prompts:test --tests '*ExecuteCodeToolDescriptionKtBlocksCompilationTest*' --tests '*ExecuteCodeOverviewKtBlocksCompilationTest*' --tests 'com.jonnyzzz.mcpSteroid.prompts.MarkdownArticleContractTest' --warning-mode all` passed through IntelliJ Gradle.

## 2026-04-27 - Gradle Final-Tasks Wait and Microshop JDK 24

- The explicit fetch-resource boundary hint was not the real Microshop-2 build-abort root cause. Two focused runs showed the IDE Gradle build aborted because DPAIA Microshop cases were configured for Java 25 while Gradle 8.14.3 reports `The maximum compatible Gradle JVM version is 24`.
- `mcpTriggerImportAndWait()` now sets the linked Gradle JVM to the configured project SDK before refresh and waits for `ProjectDataImportListener.onFinalTasksFinished` plus `waitForSmartMode()` for Gradle imports. This follows IntelliJ source patterns in `ImportGradleProjectCommand.java` and `GradleOperationUtil.kt`; `ProjectDataManagerImpl.java` emits `onImportFinished` before final tasks and `onFinalTasksFinished` after them.
- Docker IDE base now installs Temurin 24, and DPAIA Microshop Gradle cases use `projectJdkVersion = "24"` so Gradle 8.14.3 can run under a compatible daemon JVM.
- Validation:
  - `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.DpaiaConfigTest' --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest' --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ExtractDecodedLogMetricsTest' --rerun-tasks --warning-mode all` passed.
  - `./gradlew :test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.GradleCompileTest' --rerun-tasks --warning-mode all` passed in run `test-integration/build/test-logs/test/run-20260427-162138-gradle-compile` with `GRADLE_JVM=25`, `BUILD_ERRORS=false`, and `BUILD_ABORTED=false`.
  - `DpaiaMicroshop2Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-161050-dpaia__spring__boot__microshop-2-mcp` passed with `Recommended JAVA_HOME: /usr/lib/jvm/temurin-24-jdk-arm64`, IDE Gradle using Temurin 24, and `Build errors: false, aborted: false`.
- Metrics after the JDK24/final-tasks fix: 1,773,570 tokens, 36 calls, 3 MCP calls, 33 native calls, 5 Bash calls, 0 tool errors. Correctness is fixed, but native exploration is now the next bottleneck.
- Review artifacts: `/tmp/mcp-steroid-review/gradle-jdk24-finaltasks-20260427/runs/`. Claude, Codex, and Gemini approved the patch with no blockers. Next low-hanging direction from the review: update Gradle arena/resource guidance so agents use the working IDE-native Gradle path, and update `execute-code-gradle.md` away from stale `Observation.awaitConfiguration(project)` Gradle sync guidance toward `ProjectDataImportListener.onFinalTasksFinished`.

## 2026-04-27 - Gradle Resource Routing Measurement

- Scenario: `DpaiaMicroshop2Test.claude with mcp`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-142637-dpaia__spring__boot__microshop-2-mcp`.
- Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 142.0s.
- Raw metrics: 10 total calls, 4 MCP calls, 6 native calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 3 Write, 2 Bash, 0 tool errors, 764,238 total tokens, 0 resource fetches.
- Delta versus the 170.8s post-resource run: total calls 28 -> 10, Bash 5 -> 2, tool errors 2 -> 0, tokens 1,458,578 -> 764,238, runtime 170.8s -> 142.0s. Delta versus the 136s JDK-fixed baseline: Bash stayed 2, tool errors stayed 0, but runtime and tokens still did not beat baseline.
- Agent behavior: after the IDE build printed `Build errors: false, aborted: true`, the decoded log says it needed Gradle sync but then used Bash Gradle directly. The raw thinking mentions fetching the Gradle skill, but no `steroid_fetch_resource` tool call was made.
- Lesson: arena/prompt routing helped with waste, but did not satisfy the resource-use criterion. The next low-hanging fix is Codex's reviewed result-boundary idea: when `steroid_execute_code` output reports an aborted build without errors, append a short resource/sync hint directly to that tool result, using generated prompt article classes instead of hardcoded MCP URIs in production Kotlin.

## 2026-04-27 - Aborted Build Result-Boundary Guidance

- Implemented the next low-hanging fix after the 0-resource-fetch Microshop-2 routing measurement.
- `ExecuteCodeToolHandler` now post-processes successful tool results: if text output contains `Build errors: false, aborted: true` or `Compile errors: false, aborted: true`, it appends a `HINT` telling agents to call `steroid_fetch_resource` for the detected Gradle/Maven resource before falling back to Bash, run sync/configuration, and retry the IDE build/test.
- Build-system detection is intentionally local and cheap: root `settings.gradle*`, `build.gradle*`, or `gradlew` selects the Gradle article URI; root `pom.xml` selects the Maven article URI; ambiguous, missing, or null base paths list both resources as "the matching resource".
- Production Kotlin uses `ExecuteCodeGradlePromptArticle().uri` and `ExecuteCodeMavenPromptArticle().uri`; no hardcoded `mcp-steroid://...` resource strings were added.
- Tests: `ExecuteCodeBuildAbortGuidanceTest` covers Gradle, Maven, mixed roots, unknown roots, null base paths, successful build no-op, and preservation of the original tool result.
- Validation: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.ExecuteCodeBuildAbortGuidanceTest' --tests 'com.jonnyzzz.mcpSteroid.NoHardcodedMcpSteroidUriUsageTest' --rerun-tasks --warning-mode all` passed.
- Review artifacts: `/tmp/mcp-steroid-review/build-abort-guidance-20260427/runs/`; Claude, Codex, and Gemini approved. Claude/Codex suggested optional ambiguous/null coverage, which was added before commit.
- Next measurement: rerun `DpaiaMicroshop2Test.claude with mcp`; first success criterion is `fetch_resource_calls >= 1` at the aborted-build boundary while keeping the full Gradle suite green.

## 2026-04-27 - Aborted Build Boundary Measurement

- Scenario: `DpaiaMicroshop2Test.claude with mcp`.
- First attempt run dir: `test-experiments/build/test-logs/test/run-20260427-144355-dpaia__spring__boot__microshop-2-mcp`. It failed before the agent ran because the Docker IDE container disappeared during repository setup (`docker exec test -d /repo-cache/...` timed out, then `git clone` saw "container ... is not running"). Do not use it as a benchmark result.
- Valid run dir: `test-experiments/build/test-logs/test/run-20260427-150914-dpaia__spring__boot__microshop-2-mcp`.
- Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 169.6s.
- The new hint appeared exactly at the build-abort boundary: `Build errors: false, aborted: true` followed by `HINT: IDE build was aborted without compiler errors. Call steroid_fetch_resource for mcp-steroid://skill/execute-code-gradle before falling back to Bash...`.
- Raw metrics: 15 total calls, 4 MCP calls, 11 native calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 3 Read, 3 Write, 1 Glob, 3 Bash, 0 tool errors, 985,678 total tokens, 0 resource fetches.
- Delta versus the 142.0s prompt-routing run: time 142.0s -> 169.6s, total calls 10 -> 15, Bash 2 -> 3, tokens 764,238 -> 985,678, errors stayed 0. Delta versus the 136s JDK-fixed baseline: Bash 2 -> 3 and runtime/tokens worse.
- Lesson: putting the resource URI in the tool result made the guidance visible but still did not cause Claude to call `steroid_fetch_resource`. The next change needs stronger actionability at the boundary, likely either naming the exact Claude MCP tool call or embedding the minimal Gradle sync recipe inline instead of requiring a fetch.

## 2026-04-27 - Explicit Aborted Build Boundary Hint Measurement

- Review artifacts: `/tmp/mcp-steroid-review/build-abort-boundary-measurement-20260427/runs/`.
- Claude, Codex, and Gemini all selected the same low-cost next step: make the boundary hint forceful and Claude-specific by naming `mcp__mcp-steroid__steroid_fetch_resource`, while still sourcing Maven/Gradle resource URIs from generated prompt article classes.
- Implemented in `c29e13b4` (`ij-plugin: make build abort guidance explicit`): `ExecuteCodeBuildAbortGuidance` now emits `REQUIRED ACTION` / `NEXT TOOL CALL` wording and prepends a newline before appended guidance so decoded logs no longer join `aborted: true` to the instruction.
- Focused validation passed through the IntelliJ Gradle runner: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.ExecuteCodeBuildAbortGuidanceTest' --tests 'com.jonnyzzz.mcpSteroid.NoHardcodedMcpSteroidUriUsageTest' --rerun-tasks --warning-mode all`.
- Measurement run dir: `test-experiments/build/test-logs/test/run-20260427-151926-dpaia__spring__boot__microshop-2-mcp`.
- Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 174.0s.
- Decoded evidence: line 743 showed `Build errors: false, aborted: true`; line 744 showed the separate-line `REQUIRED ACTION ... NEXT TOOL CALL must be mcp__mcp-steroid__steroid_fetch_resource ...`; line 747 immediately used Bash Gradle anyway.
- Raw metrics: 19 total calls, 4 MCP calls, 15 native calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 5 Read, 2 Glob, 4 Write, 2 Bash, 1 native Read error, 1,255,211 total tokens, 0 resource fetches.
- Lesson: fetch-only boundary wording has failed even with the exact Claude tool name. Stop iterating on wording for this scenario; the next reviewed low-hanging correction should choose between inline minimal Gradle sync guidance at the boundary and removing/replacing the failed hint.

## 2026-04-27 - Gradle IDE Guidance Measurement

- Patch review artifacts: `/tmp/mcp-steroid-review/gradle-ide-guidance-20260427/runs/`. Claude, Codex, and Gemini approved the prompt/resource patch with no blockers.
- The patch updated `mcp-steroid://skill/execute-code-gradle` to use `ProjectDataImportListener.onFinalTasksFinished` as the Gradle sync boundary instead of `Observation.awaitConfiguration(project)`. It also inlined a first IDE-native Gradle build recipe in Gradle DPAIA arena prompts and kept the Gradle resource fetch as the aborted-build fallback.
- Validation passed through the IntelliJ Gradle runner:
  - `:prompts:generatePrompts :prompts:test --tests com.jonnyzzz.mcpSteroid.prompts.GradlePromptContractTest --tests '*ExecuteCodeGradleKtBlocksCompilationTest*' --tests com.jonnyzzz.mcpSteroid.prompts.MarkdownArticleContractTest --warning-mode all`
  - `:test-experiments:test --tests com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest --warning-mode all`
- Measurement scenario: `DpaiaMicroshop2Test.claude with mcp`, run dir `test-experiments/build/test-logs/test/run-20260427-185422-dpaia__spring__boot__microshop-2-mcp`.
- Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, the inline IDE build printed `Build errors: false, aborted: false`, and the full Gradle suite passed. Agent time was 145s.
- Raw metrics: 1,370,218 total tokens, 26 total calls, 4 MCP calls, 22 native calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 12 Read, 4 Glob, 3 Write, 2 Bash, 0 tool errors, and 0 resource fetches.
- Delta versus the post-JDK24/final-tasks baseline (`run-20260427-161050-dpaia__spring__boot__microshop-2-mcp`): tokens 1,773,570 -> 1,370,218, calls 36 -> 26, native calls 33 -> 22, Bash 5 -> 2, Read 17 -> 12, Glob 7 -> 4, errors stayed 0.
- Measurement review artifacts: `/tmp/mcp-steroid-review/gradle-ide-guidance-measurement-20260427/runs/`. Claude, Codex, and Gemini approved the patch and converged on the next low-hanging improvement: batch source discovery and related file reads in one IDE/VFS `steroid_execute_code` pass before falling back to native `Glob`/`Read`.
