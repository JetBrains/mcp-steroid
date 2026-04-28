# TASKS

Current focus: make MCP Steroid measurably better than vanilla agent runs on DPAIA Maven and Gradle projects by reducing tokens, tool errors, and wall-clock time.

## Guardrails

- Do not add methods to `McpSteroid*` interfaces.
- Do not add MCP tools.
- Prefer MCP server behavior, prompt resources, and DPAIA prompt/case improvements.
- Keep changes measurable with DPAIA metrics: tokens, tool calls, tool errors, native edit calls, apply-patch usage, and wall time.
- Run Docker-backed `:test-integration` / `:test-experiments` tests one at a time.

## Methodology

- Use Karpathy-style autoresearch: one narrow hypothesis, one change, one measured run, one evidence note.
- Use RLM-style context control: grep/partition first, then read only the relevant files.
- Use `run-agent.sh` reviews for non-trivial direction changes. Require three reviews and consensus before selecting the next low-hanging fruit.
- Keep run-agent artifacts outside this repository unless explicitly asked to preserve them.

## Completed This Iteration

- [x] DPAIA arena prompt cleanup: remove stale `applyPatch {}` DSL guidance and duplicate MCP-mode prompt rules from `ArenaTestRunner.buildPrompt()`.
  - Files: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaTestRunner.kt`
  - Consensus: Claude and Codex reviewers selected this as the next low-hanging fruit; Gemini selected Gradle prompt resources as a follow-up.
  - Expected effect: lower input tokens, fewer contradictory edit-path choices, fewer slow `steroid_execute_code` patch attempts.
  - Validation: `./gradlew :test-experiments:compileTestKotlin --warning-mode all` passed.

- [x] Measured one Claude+MCP DPAIA scenario after the prompt cleanup.
  - Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 111s.
  - Tool mix: 2 agent `steroid_execute_code` calls in raw metrics, 3 Read, 1 Glob, 1 Grep, 2 native Edit, 2 Bash, `steroid_apply_patch` not used.
  - Lesson: correctness is good, but the edit prompt still needs a stronger dedicated `steroid_apply_patch` cue for import+method edits.

- [x] Tightened the next low-hanging arena edit guidance from the measured run.
  - Kept the `steroid_apply_patch` JSON example on `file_path`, matching `ApplyPatchToolHandler` and the generated tool description.
  - Explicitly classified "imports plus method" as a multi-hunk patch.
  - Review: first 3-agent pass blocked on an accidental `path`/`file_path` mismatch; after correction, Claude/Codex/Gemini approved the diff and agreed the next low-hanging fruit is to re-run the same scenario and measure native Edit reduction.

- [x] Measured the corrected `steroid_apply_patch` prompt on the same Claude+MCP DPAIA scenario.
  - Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-001705-dpaia__spring__petclinic__rest-37-mcp`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 123s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 12 total calls, 3 MCP calls, 9 native calls, 2 `steroid_execute_code`, 1 `steroid_apply_patch`, 2 Read, 2 Grep, 0 native Edit, 3 Bash, 0 errors.
  - Delta versus the prior run: native Edit 2 -> 0 and apply-patch false -> true; runtime 111s -> 123s because verification used 3 Bash Maven calls instead of 2.
  - Lesson: edit-path guidance worked. The next low-hanging prompt issue is verification: avoid duplicate Maven/Bash checks after a successful IDE build plus targeted test.

- [x] Route prompt resources to the dedicated `steroid_apply_patch` tool for multi-site literal edits.
  - Review artifacts: `/tmp/mcp-steroid-review/runs-next-20260427/`.
  - Consensus: Claude, Codex, and Gemini selected `update-apply-patch-tool-description-routing` before Gradle-resource work.
  - Files: `prompts/src/main/prompts/skill/execute-code-tool-description.md`, `prompts/src/main/prompts/skill/execute-code-overview.md`, `prompts/src/main/prompts/skill/coding-with-intellij.md`, `prompts/src/main/prompts/ide/apply-patch.md`, `prompts/src/test/kotlin/com/jonnyzzz/mcpSteroid/prompts/PromptRoutingContractTest.kt`.
  - Expected effect: stop teaching the slower `steroid_execute_code` + script-context `applyPatch` route as the default after DPAIA showed the dedicated MCP tool applies multi-file patches in tens of ms.
  - Validation: `:prompts:test --tests '*PromptRoutingContractTest*' --tests '*MarkdownArticleContractTest*' --tests '*ExecuteCodeToolDescriptionKtBlocksCompilationTest*' --warning-mode all` passed via IntelliJ Gradle runner.

- [x] Make `steroid_apply_patch` save touched documents before returning and cover disk persistence with integration tests.
  - Reference checked: after updating `~/Work/intellij`, IntelliJ's `ApplyTextFilePatch.updateDocumentContent()` uses `Document.setText(...)` followed by `FileDocumentManager.saveDocument(document)`.
  - Files: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ApplyPatch.kt`, `ApplyPatchTest.kt`, `ApplyPatchToolIntegrationTest.kt`.
  - Coverage: single hunk, same-file multi-hunk descending offsets, multiple files, empty hunks, missing file, missing old string, non-unique old string, read-only/save failure, and direct disk reads after the MCP HTTP tool returns.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.execution.ApplyPatchTest' --tests 'com.jonnyzzz.mcpSteroid.server.ApplyPatchToolIntegrationTest' --rerun-tasks --warning-mode all` passed.
  - Review: three-agent pass approved the persistence fix direction and requested explicit save-failure/read-only coverage; the hardening was implemented.

- [x] Configure DPAIA Microshop cases to use JDK 25 during IDE setup and compile warmup. Superseded for Gradle 8.14.3 Microshop cases by the later JDK 24 compatibility fix below.
  - Files: `DpaiaCuratedCases.kt`, DPAIA runner/comparison setup call sites, `intelliJ-container.kt`, `mcp-steroid.kt`, `DpaiaConfigTest.kt`, `IdeTestHelpersTest.kt`.
  - Fixes: project SDK replacement now updates mismatched SDKs, compile warmup receives the case's configured JDK, and JAVA_HOME lookup fails fast unless a JDK path with `bin/javac` is emitted.
  - Validation: `:test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IdeTestHelpersTest' --rerun-tasks --warning-mode all` passed.

- [x] Add a real IntelliJ Ultimate monorepo `thisLogger` lookup regression test.
  - Files: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/IntelliJThisLoggerLookupTest.kt`.
  - Research: Marinade's MCP Steroid guide waits for `projectInitialized`, `indexingInProgress == false`, and no modal/background indexing, but IntelliJ's own API docs warn that `waitForSmartMode()` has no guarantee another dumb mode will not start before the next statement.
  - Fix direction from `~/Work/intellij`: use `Observation.awaitConfiguration(project)` for initial import/configuration readiness, then run the whole indexed lookup inside `smartReadAction(project)`.
  - Validation: `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij ./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJThisLoggerLookupTest' --rerun-tasks --warning-mode all` passed in 8m41s with 4,191 references across 1,526 files.

- [x] Route MCP indexing guidance away from `waitForSmartMode()` as an indexed-read handoff.
  - Files: `ExecutionSuggestionService.kt`, `McpScriptContext.kt`, `prompt/skill.md`, `coding-with-intellij-{intro,patterns,psi,threading}.md`, `SkillReferenceHintTest.kt`, `IndexingGuidanceContractTest.kt`.
  - Fix: `IndexNotReadyException` and smart-mode hints now recommend `Observation.awaitConfiguration(project)` after import/sync/configuration and `smartReadAction { }` around the whole indexed PSI query.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.SkillReferenceHintTest' --rerun-tasks --warning-mode all` passed; scoped `:prompts:test` for `IndexingGuidanceContractTest`, `MarkdownArticleContractTest`, and changed Kt blocks passed after forced prompt regeneration.

- [x] Harden IDE exception capture for JUL records with null parameters.
  - Evidence: the green IntelliJ monorepo `thisLogger` lookup logged Kotlin FIR severe errors, then `ExceptionCaptureService` crashed while reading a nullable `LogRecord.parameters` array.
  - Files: `ExceptionCaptureService.kt`, `ExceptionCaptureServiceTest.kt`.
  - Fix: capture failures now log to stderr instead of breaking the original IDE error path, `ProcessCanceledException` is still rethrown, and null parameters are handled explicitly.
  - Validation: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.execution.ExceptionCaptureServiceTest' --rerun-tasks --warning-mode all` passed.
  - Review: initial Codex pass caught hidden `ProcessCanceledException` handling in plugin-id lookup; follow-up Claude/Codex/Gemini pass approved.

- [x] Make IntelliJ monorepo test setup prefer explicitly configured local checkouts over stale cached ZIPs.
  - Evidence: `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij` still reused an older cached TeamCity ZIP before updating in-container.
  - Files: `intelliJ-git.kt`, `IntelliJGitCloneZipTest.kt`.
  - Fix: configured ZIPs and configured checkout directories now win before cache reuse; local checkout ZIP creation uses a proper `file:///` clone URI and preserves the source checkout's real `origin` remote for in-container fetches.
  - Validation: `./gradlew :test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJGitCloneZipTest' --rerun-tasks --warning-mode all` passed.
  - Review: initial Codex pass caught the container-unusable `file:///Users/...` origin in generated ZIPs; follow-up Claude/Codex/Gemini pass approved.

## Next Candidates

- [x] Reduce redundant Maven verification in the DPAIA arena prompt.
  - Evidence: the latest successful run compiled with `steroid_execute_code`, then ran targeted Maven tests, then ran the full Maven suite. That kept correctness but increased Bash calls from 2 to 3 and runtime from 111s to 123s.
  - Implemented wording: do not rerun a completed Maven/Gradle target solely to recover `BUILD SUCCESS` hidden by `tail`/`grep`; explicitly allow reruns after code changes, real failures, incomplete runs, or Gradle skipped-test behavior.
  - Review: first 3-agent pass requested narrowing the wording; follow-up pass under `/tmp/mcp-steroid-review/runs-current-4/` approved with Claude/Codex/Gemini consensus.
  - Measurement: `DpaiaPetclinicRest37Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-003310-dpaia__spring__petclinic__rest-37-mcp` passed 184/184 tests in 101s with 0 native Edit, 1 `steroid_apply_patch`, 2 Bash, and 0 tool errors.
  - Delta versus the prior 123s run: Bash 3 -> 2, total tool calls 12 -> 11, runtime 123s -> 101s, pass rate unchanged.

- [x] Measure the dedicated apply-patch routing resource change.
  - Scenario: repeat `DpaiaPetclinicRest37Test.claude with mcp` first because it is the stable measured case.
  - Target: keep 184/184 tests, 0 native Edit, `steroid_apply_patch` used, 0 tool errors, and no regression versus the 101s run.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-073953-dpaia__spring__petclinic__rest-37-mcp`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 116s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 10 total calls, 3 MCP calls, 7 native calls, 2 `steroid_execute_code`, 1 `steroid_apply_patch`, 2 Read, 1 Grep, 0 native Edit, 2 Bash, 0 errors.
  - Delta versus the 101s run: `steroid_apply_patch` stayed true, native Edit stayed 0, Bash stayed 2, total calls 11 -> 10, runtime 101s -> 116s. This is within the observed PetclinicRest37 variance and confirms the global prompt-resource routing is not regressing the stable Maven case.

- [x] Add a prompt-size or prompt-shape regression check for the DPAIA arena MCP block.
  - Files likely under `test-experiments/src/test/kotlin/.../arena/`.
  - Expected effect: prevent reintroducing large contradictory prompt blocks.
  - Consensus note: Codex specifically recommended asserting that the prompt does not reintroduce broad "run at most once" wording and still contains explicit rerun-required cases.
  - Measured prerequisite: the verification-guidance tweak succeeded on the 101s run, so this is now the next low-hanging fruit.
  - Implemented in `ArenaPromptContractTest`; validation: `./gradlew :test-experiments:test --tests '*ArenaPromptContractTest*' --warning-mode all` passed.

- [x] Pick and measure one Gradle DPAIA scenario before changing Gradle guidance.
  - Goal: establish a concrete baseline for Gradle cold-starts, skipped-test behavior, and IntelliJ Gradle runner errors before editing prompt resources.
  - Scenario: `DpaiaMicroshop2Test.claude with mcp`.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-090258-dpaia__spring__boot__microshop-2-mcp`.
  - Result: fix claimed, MCP used, full Gradle suite passed, agent time 171s.
  - Raw metrics: 12 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 8 patch hunks, 0 native Edit, 0 Read, 3 Write for new files, 4 Bash, 0 tool errors, 1.05M tokens.
  - Delta versus the stale-disk failure run: 248s -> 171s, tool errors 7 -> 0, native Edit 14 -> 0, Read 11 -> 0. The "apply_patch did not persist" fallback disappeared.

- [x] Add a Gradle-focused MCP prompt resource modeled after the Maven patterns.
  - Files likely under `prompts/src/main/prompts/skill/`.
  - Consensus status: the follow-up `thisLogger` FIR review selected this by 2/3 reviewers (Codex + Gemini); Maven fallback JDK guidance remains Claude's candidate.
  - Expected effect: fewer Bash Gradle cold starts and fewer hand-rolled IntelliJ Gradle snippets.
  - Implemented files: `prompts/src/main/prompts/skill/execute-code-gradle.md`, links from `execute-code-overview.md`, `execute-code-tool-description.md`, and `coding-with-intellij.md`, plus `GradlePromptContractTest`.
  - Validation: IntelliJ Gradle runner passed `:prompts:generatePrompts :prompts:test --tests 'com.jonnyzzz.mcpSteroid.prompts.GradlePromptContractTest' --tests '*ExecuteCodeGradleKtBlocksCompilationTest*' --tests 'com.jonnyzzz.mcpSteroid.prompts.MarkdownArticleContractTest' --warning-mode all` after fixing the initial non-kotlin fence contract failure.
  - Review: `/tmp/mcp-steroid-review/gradle-prompt-resource-20260427/runs/`; Claude, Codex, and Gemini approved.
  - Measurement: `DpaiaMicroshop2Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-135940-dpaia__spring__boot__microshop-2-mcp` passed, but the agent fetched 0 resources and still used Bash Gradle 5 times after an aborted IDE build. Agent time was 170.8s versus the 136s JDK-fixed baseline.
  - Lesson: the resource content is valid, but discoverability/routing is still weak for arena agents.

- [x] Improve Gradle resource discovery/routing from DPAIA/MCP prompts.
  - Evidence: the first post-resource Microshop-2 measurement did not fetch `mcp-steroid://skill/execute-code-gradle`.
  - Expected effect: fewer Bash Gradle calls after an IDE build abort and fewer hand-rolled Gradle snippets.
  - Review: `/tmp/mcp-steroid-review/gradle-resource-measurement-20260427/runs/`; all reviewers approved the interpretation. Claude recommended high-impact arena prompt routing, Codex recommended execute-result abort guidance, and Gemini recommended both prompt/resource-table abort guidance and edited a rough version.
  - Implemented direction: route Gradle arena prompts through `steroid_fetch_resource` for `mcp-steroid://skill/execute-code-gradle`, keep Maven prompts on `mcp-steroid://skill/execute-code-maven`, and add prompt-resource abort guidance with full resource URIs.
  - Validation: `ArenaPromptContractTest` passed via IntelliJ Gradle runner; prompt resource generation plus `ExecuteCodeToolDescriptionKtBlocksCompilationTest`, `ExecuteCodeOverviewKtBlocksCompilationTest`, and `MarkdownArticleContractTest` passed via IntelliJ Gradle runner.
  - Note: an initial combined multi-module Gradle run was stopped after a thread dump showed the `--tests` filter leaked and launched `DpaiaArenaTest.codex without mcp`; the scoped module reruns above are the valid validation signal.

- [x] Measure Gradle resource routing on Microshop-2.
  - Target: full-suite pass, `fetch_resource_calls >= 1` for `mcp-steroid://skill/execute-code-gradle`, fewer Bash Gradle calls than the 5-call measurement, and no new tool errors.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-142637-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 142.0s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 10 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 3 Write, 2 Bash, 0 tool errors, 764,238 tokens, and 0 resource fetches.
  - Delta versus the 170.8s post-resource run: total calls 28 -> 10, Bash 5 -> 2, tool errors 2 -> 0, tokens 1,458,578 -> 764,238, runtime 170.8s -> 142.0s. Delta versus the 136s JDK-fixed baseline: Bash stayed 2, tool errors stayed 0, but tokens stayed higher and runtime was 6s slower.
  - Lesson: arena/prompt routing reduced waste but still failed the primary resource-use criterion. The decoded log shows the agent considered fetching the Gradle skill after `Build errors: false, aborted: true`, but used Bash instead; prompt-only routing is not enough at this decision point.

- [x] Add result-boundary guidance for aborted IDE builds from `steroid_execute_code`.
  - Evidence: both Microshop-2 routing runs hit `Build errors: false, aborted: true` after an IDE build; the agent then fell back to Bash without calling `steroid_fetch_resource`, even when the arena prompt told it to fetch the Gradle resource.
  - Scope: `ExecuteCodeToolHandler` now appends a concise next-action hint to `steroid_execute_code` results when build output indicates `errors=false, aborted=true`, using generated Maven/Gradle prompt article classes rather than hardcoded `mcp-steroid://...` string literals.
  - Expected effect: make the resource URI visible exactly at the failure boundary, before the agent chooses Bash.
  - Review: `/tmp/mcp-steroid-review/build-abort-guidance-20260427/runs/`; Claude, Codex, and Gemini approved. Optional fallback-case coverage requested by Claude/Codex was added.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.ExecuteCodeBuildAbortGuidanceTest' --tests 'com.jonnyzzz.mcpSteroid.NoHardcodedMcpSteroidUriUsageTest' --rerun-tasks --warning-mode all` passed.

- [x] Measure aborted-build result-boundary guidance on Microshop-2.
  - Target: full-suite pass, `fetch_resource_calls >= 1`, no more than 2 Bash Gradle calls, and no new tool errors.
  - First attempt `run-20260427-144355-dpaia__spring__boot__microshop-2-mcp` failed before the agent ran because the Docker IDE container disappeared during repository setup; this is not a benchmark result.
  - Valid run dir: `test-experiments/build/test-logs/test/run-20260427-150914-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 169.6s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 15 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 3 Read, 3 Write, 1 Glob, 3 Bash, 0 tool errors, 985,678 tokens, and 0 resource fetches.
  - Lesson: the result-boundary hint was visible in the tool result, but Claude still ignored `steroid_fetch_resource` and used Bash. A URI-only/fetch-only hint is insufficient.

- [x] Review the failed fetch-resource boundary measurement and pick the next low-hanging correction.
  - Review artifacts: `/tmp/mcp-steroid-review/build-abort-boundary-measurement-20260427/runs/`.
  - Consensus: Claude, Codex, and Gemini all selected the cheapest next change first: make the aborted-build boundary guidance forceful, name Claude's actual `mcp__mcp-steroid__steroid_fetch_resource` tool, and keep resource URIs generated from prompt article classes.
  - Follow-up rule from reviewers: if this still produces 0 fetches, stop iterating on fetch-only wording and reconsider inline Gradle sync guidance or removing the failed hint.

- [x] Make aborted-build boundary guidance explicit and line-separated.
  - Scope: `ExecuteCodeBuildAbortGuidance` now emits `REQUIRED ACTION` / `NEXT TOOL CALL` wording with `mcp__mcp-steroid__steroid_fetch_resource`, and appends the guidance with a leading newline so decoded logs do not collapse `aborted: true` into the hint.
  - Tests: `ExecuteCodeBuildAbortGuidanceTest` now asserts the exact Claude-visible tool name, compiler-error no-op behavior, and separate-line formatting.
  - Validation: IntelliJ Gradle runner passed `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.ExecuteCodeBuildAbortGuidanceTest' --tests 'com.jonnyzzz.mcpSteroid.NoHardcodedMcpSteroidUriUsageTest' --rerun-tasks --warning-mode all`.

- [x] Measure explicit aborted-build boundary guidance on Microshop-2.
  - Target: full-suite pass, `fetch_resource_calls >= 1`, no more than 2 Bash Gradle calls, and no new tool errors.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-151926-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 174.0s.
  - Decoded evidence: line 743 showed `Build errors: false, aborted: true`; line 744 showed the separate-line `REQUIRED ACTION ... NEXT TOOL CALL must be mcp__mcp-steroid__steroid_fetch_resource ...`; line 747 immediately used Bash Gradle anyway.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 19 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 5 Read, 2 Glob, 4 Write, 2 Bash, 1 native Read error, 1,255,211 tokens, and 0 resource fetches.
  - Lesson: even an exact Claude tool-name boundary instruction is ignored in this scenario. The fetch-only boundary hypothesis has failed; the next low-hanging correction should be reviewed as either inline minimal Gradle sync guidance in the tool result or removal/replacement of the hint.

- [x] Review the explicit-hint failure and choose the next low-hanging correction.
  - Candidate: append a short inline Gradle sync/configuration recipe at the aborted-build boundary instead of requiring `steroid_fetch_resource`, or revert the fetch-only hint if the inline recipe would cost too many tokens.

- [x] Fix the real Gradle abort root causes found while reviewing the explicit-hint failure.
  - Review artifacts: `/tmp/mcp-steroid-review/gradle-jdk24-finaltasks-20260427/runs/`.
  - Consensus: Claude, Codex, and Gemini approved the patch with no blockers. All agreed `ProjectDataImportListener.onFinalTasksFinished` is the right Gradle import boundary and JDK 24 is the right Microshop fix for Gradle 8.14.3.
  - Files: `test-integration/src/main/kotlin/com/jonnyzzz/mcpSteroid/integration/infra/mcp-steroid.kt`, `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/GradleCompileTest.kt`, `test-integration/src/test/docker/ide-base/Dockerfile`, and Microshop DPAIA config/contract/metrics tests.
  - Fixes: Gradle import setup now sets the linked Gradle JVM from the configured project SDK, waits for `onFinalTasksFinished` before smart mode, and rethrows import failures. Docker IDE base installs Temurin 24. Microshop Gradle cases now use JDK 24 because Gradle 8.14.3 rejects Java 25 as a daemon JVM.
  - Validation: `:test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.GradleCompileTest' --rerun-tasks --warning-mode all` passed with `GRADLE_JVM=25`, `BUILD_ERRORS=false`, `BUILD_ABORTED=false`; DPAIA config/contract/metrics tests passed; `DpaiaMicroshop2Test.claude with mcp` passed with `Recommended JAVA_HOME: /usr/lib/jvm/temurin-24-jdk-arm64` and `Build errors: false, aborted: false`.
  - Measurement: post-fix Microshop-2 used 1,773,570 tokens, 36 calls, 3 MCP calls, 33 native calls, 0 errors, and 5 Bash calls. Correctness improved, but token/tool cost regressed.
  - Next low-hanging consensus: move Gradle agents away from native exploration now that the IDE path works. The concrete follow-up is to inline/update IDE-native Gradle build/sync guidance, including the `onFinalTasksFinished` wait in `execute-code-gradle.md`, then remeasure Microshop-2.

- [x] Next low-hanging Gradle improvement: make JDK choice harder to miss in DPAIA prompts.
  - Evidence: Microshop-2 eventually chose JDK 25 and passed, but first tried `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-arm64` and hit `invalid source release: 24`.
  - Fix: the arena prompt now exposes the case-configured JDK version, has the first MCP call print `Recommended JAVA_HOME`, and tells agents to use the exact printed value instead of wildcard assignments or lower JDKs.
  - Consensus: follow-up Claude and Codex review selected this over Kotlin FIR investigation as the next low-risk item; Gemini selected Kotlin FIR, so this is a 2/3 consensus.
  - Review: initial Codex pass caught the invalid copyable `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-*` assignment; follow-up Claude/Codex/Gemini pass approved the wildcard-free prompt.
  - Validation: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest' --rerun-tasks --warning-mode all` passed.
  - Measurement: `DpaiaMicroshop2Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-115129-dpaia__spring__boot__microshop-2-mcp` passed in 136s agent time. First MCP call printed `Recommended JAVA_HOME: /usr/lib/jvm/temurin-25-jdk-arm64`; both Bash Gradle calls used `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64`; no `invalid source release: 24`.
  - Delta versus the 171s baseline: Bash 4 -> 2, agent time 171s -> 136s, native Edit stayed 0, tool errors stayed 0, full Gradle suite passed.

- [x] Add a decoded-log regression check for Microshop-2 JDK usage.
  - Evidence: the measured run's decoded log shows both Bash Gradle calls used `/usr/lib/jvm/temurin-25-jdk-arm64`; reviewers suggested pinning this so `temurin-21` or literal `*` does not return.
  - Fix: `AgentOutputMetrics` now extracts decoded Bash commands and flags Gradle commands that omit `JAVA_HOME`, use a literal wildcard, or do not start with the expected JDK prefix, including absolute `gradlew` wrapper paths.
  - Regression coverage: `ExtractDecodedLogMetricsTest.microshop gradle bash commands use configured jdk without wildcard` and `ExtractDecodedLogMetricsTest.detects gradle bash commands with lower jdk or wildcard java home`.
  - Validation: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest' --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ExtractDecodedLogMetricsTest' --rerun-tasks --warning-mode all` passed.

- [x] Investigate severe Kotlin FIR resolve logs from the IntelliJ monorepo `thisLogger` lookup.
  - Evidence: the green lookup run logged `KaFirReferenceResolver` / `Expected FirResolvedContractDescription but FirLazyContractDescriptionImpl` errors, followed by an `ExceptionCaptureService` null-pointer while capturing the IDE error.
  - Fix: the monorepo test now asserts no lookup-time FIR severe logs, and the MCP script avoids the Kotlin FIR resolve path by using `CacheManager.getVirtualFilesWithWord(..., UsageSearchContext.IN_CODE, ...)` plus Kotlin PSI `KtCallExpression` filtering for real `thisLogger()` calls.
  - Consensus: final Gradle/JDK review selected this as the next low-hanging task by 2/3 reviewers; Maven fallback JDK guidance remains the other candidate.
  - Validation: the old `ReferencesSearch` TDD run failed on the new FIR log assertion; the fixed run passed with `THISLOGGER_LOOKUP_STRATEGY=INDEXED_WORD_PLUS_KOTLIN_PSI`, `THISLOGGER_REFERENCE_COUNT=2670`, and `THISLOGGER_FILE_COUNT=1522`.
  - Review: first pass had Gemini/Claude approve and Codex request a strategy-marker assertion plus stale `CLAUDE.md` note cleanup; follow-up Claude/Codex/Gemini pass approved.

- [x] Make IntelliJ monorepo test setup prefer an explicitly configured local checkout when appropriate.
  - Evidence: the `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij` run still reused an existing cached TeamCity ZIP before updating the checkout in-container.
  - Fix: `ensureIntelliJGitCloneZipInCache()` honors configured ZIPs/checkouts before cache reuse, preserves the source checkout's real `origin` remote in generated ZIPs, and has explicit regression coverage.

- [x] Update Gradle arena/resource guidance to use the now-working IDE-native Gradle path.
  - Review: `/tmp/mcp-steroid-review/gradle-ide-guidance-20260427/runs/`; Claude, Codex, and Gemini approved. The shared next step was to remeasure Microshop-2.
  - Files: `prompts/src/main/prompts/skill/execute-code-gradle.md`, `GradlePromptContractTest.kt`, `ArenaTestRunner.kt`, `ArenaPromptContractTest.kt`.
  - Fix: `execute-code-gradle.md` now uses `ProjectDataImportListener.onFinalTasksFinished` for Gradle sync readiness instead of `Observation.awaitConfiguration(project)`, and the Gradle arena prompt inlines an IDE build recipe before Bash fallback.
  - Validation: IntelliJ Gradle runner passed the scoped prompt generation/tests and `ArenaPromptContractTest`.

- [x] Measure updated Gradle IDE guidance on Microshop-2.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-185422-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, IDE build printed `Build errors: false, aborted: false`, and the full Gradle suite passed. Agent time was 145s.
  - Raw metrics: 1,370,218 tokens, 26 total calls, 4 MCP calls, 22 native calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 12 Read, 4 Glob, 3 Write, 2 Bash, 0 errors, 0 resource fetches.
  - Delta versus the post-JDK24/final-tasks baseline: tokens 1,773,570 -> 1,370,218, calls 36 -> 26, native calls 33 -> 22, Bash 5 -> 2, Read 17 -> 12, Glob 7 -> 4, errors stayed 0.
  - Measurement review: `/tmp/mcp-steroid-review/gradle-ide-guidance-measurement-20260427/runs/`; Claude, Codex, and Gemini approved with no blockers.

- [ ] Next low-hanging Gradle improvement: batch source discovery and related file reads through IDE/VFS APIs.
  - Evidence: after the IDE Gradle build fix, the remaining waste is concentrated in 12 native `Read` and 4 native `Glob` calls.
  - Consensus: the measurement review unanimously recommended a narrow prompt/resource recipe that reads 3+ related project files in one `steroid_execute_code` call using IDE/VFS APIs before falling back to native `Glob`/`Read`.
  - Target: reduce native source exploration calls while keeping Microshop-2 green, `steroid_apply_patch` used, Bash <=2, and 0 tool errors.

## Recent PR Follow-up

- [ ] PR #26 needs fixes before merge.
  - `npx/build.gradle.kts`: include `package-lock.json` and likely `tsconfig.json` as `npmBuild` inputs.
  - `ocr-tesseract/build.gradle.kts`: make tessdata download skipping version-aware.

## RLM Code Review (v0.93.0..HEAD, 2026-04-28)

Scope: 195 files / ~14K LOC / 125 commits between tag `v0.93.0` and commit
`47e03ef2` (the disable-apply-patch commit on `main`). Review followed RLM
partition+map+reduce: 4 parallel sub-agents each owned a slice (production,
tests, integration/experiments, prompts+kotlin-cli); orchestrator verified
each non-trivial claim with `grep`/file reads before logging.

Apply-patch–specific findings live in `TODO-APPLY-PATCH.md` so the main
DPAIA / autoresearch flow stays focused.

### Fixed (2026-04-28)

- [x] **`test-experiments/.../arena/DpaiaArenaTest.kt`** — removed the
  `Assumptions.assumeTrue` filter and the matching `arena.test.agents`
  system-property kdoc. Subset selection now runs through Gradle's
  `--tests` pattern matching, which already covered the same surface
  (`--tests '*DpaiaArenaTest.claude with mcp'` etc.). No more runtime
  test-skip pattern. (was MAJOR.)
- [x] **`test-integration/.../infra/intelliJ-container.kt`** —
  `waitForIdeWindow` now emits a `console.writeInfo` heartbeat every
  ~10 s (`elapsed=Ns`, last status). Long polls are no longer
  indistinguishable from hangs — the CLAUDE.md "1-minute investigate"
  rule now sees diagnostic output between polls. (was MAJOR.)
- [x] **`ij-plugin/.../execution/DialogWindowsLookup.kt`** — added a
  three-line comment in `canPumpEdtNonModal` explaining that the outer
  `withContext(CoroutineName(...))` deliberately stays on the caller's
  dispatcher; the inner `async(Dispatchers.EDT)` does the dispatch. No
  behavioral change. (was MINOR.)
- [x] **`ij-plugin/.../test/.../ScriptExecutorTest.kt`** — refreshed the
  stale "10-second timeout versus 60-second exec timeout" comment near
  line 73 to describe the current 60 s `timeoutRunBlocking` rationale.
  (was MINOR.)
- [x] **`ij-plugin/.../test/.../ExecuteFeedbackToolHandlerTest.kt`** —
  `assertEquals(true, err!!.contains(...))` → `assertTrue(...)`; dropped
  the now-unused `assertEquals` import. (was MINOR.)

Verification: `:ij-plugin:compileKotlin :ij-plugin:compileTestKotlin
:test-experiments:compileTestKotlin :test-integration:compileKotlin` all
green; `:ij-plugin:test --tests '*ScriptExecutor*' '*ExecuteFeedbackToolHandler*'
'*DialogKiller*' '*VfsRefreshService*'` ran 25/25 (8+7+6+4) on the touched
suites, fresh timestamps.

### Re-evaluated, no longer flagged

- `test-integration/.../OpenProjectTrustIntegrationTest.kt:101` —
  re-reading the code: line 88 already passes `mcpListWindows(timeoutSeconds = 120)`,
  so the per-iteration MCP call IS bounded; the agent's claim of
  unbounded silent hang was wrong. The outer poll uses `Thread.sleep`
  with a fixed 180 s budget, which is acceptable for a JUnit poll
  (not a coroutine).
- `test-integration/.../IntelliJContainerTest.kt:28` — `Thread.sleep(3000)`
  is a deliberate post-creation hold to let the IDE settle; the
  `@Timeout(value = 15, unit = MINUTES)` already protects against hang.
  Pre-existing and harmless.
- `ij-plugin/.../test/.../VfsRefreshServiceTest.kt:68-73` — the test
  documents its own limitation (cannot null out `basePath` on a light
  fixture without reflection). The null-base-path branch is exercised
  by integration tests that close projects. Coverage is acceptable.

### IntelliJ inspections — automated run blocked

- [ ] **N/A automated.** Three approaches tried via `steroid_execute_code`:
  (1) `InspectionEngine.runInspectionOnFile` — failed with API
      signature mismatch (it now takes a single `InspectionToolWrapper` and
      a `GlobalInspectionContext`, not a list + `ProgressIndicator`).
  (2) `DaemonCodeAnalyzerImpl.runMainPasses` directly — fails the
      `assertUnderDaemonProgress()` check at
      `community/platform/lang-impl/src/com/intellij/codeInsight/daemon/impl/DaemonCodeAnalyzerImpl.java:485`
      because the script runs without a `DaemonProgressIndicator`.
  (3) `MainPassesRunner.runMainPasses(files, severity)` — the canonical
      wrapper IntelliJ itself uses (e.g. `CodeSmellDetectorImpl.java:130`).
      Compiles and is the right API per the IntelliJ source agent
      reviewed at `~/Work/intellij`, but times out from
      `steroid_execute_code` even with 3 small files at 240 s. Likely
      cause: it pumps EDT internally and the suspend script holds
      resources that prevent forward progress.
  Manual workaround for full IntelliJ-inspection coverage of the
  changed Kotlin files: open each in the IDE, then
  **Code → Inspect Code… → Custom Scope: changed files since v0.93.0**,
  and triage results into this section. The agent-review findings above
  already covered the banned-pattern + stability surface that IntelliJ
  inspections would flag.

### Verification notes

- All 9 `ApplyPatchTest` engine-level tests pass on `main` after disable
  (verified: `:ij-plugin:test --tests '*ApplyPatchTest*'` 9/9).
- All 30 `McpServerIntegrationTest` cases pass on `main`; `tools/list`
  results no longer include `steroid_apply_patch`. No other test referenced
  the removed tool.
- The `apply-patch` branch retains the full feature surface for re-enable.
- No force-push was used; `origin/main` and `jb/main` are fast-forwards
  from `349d649c` to `47e03ef2`.
