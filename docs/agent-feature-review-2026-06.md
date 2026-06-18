# Agent feature review — top missing features for MCP Steroid / devrig (2026-06-11)

Deliverable of a multi-agent, hands-on dogfooding experiment. **Method**: (1) a 4-lens Claude
swarm and (2) Codex (via run-agent.sh) each explored the LIVE tooling (steroid_* MCP tools against
running IDEA/GoLand/idea-community backends + the devrig CLI) and produced top-20 lists with reproduced
evidence; (3) merged to a ranked top-30; (4) five validation iterations in which agents re-tested every
claim against the live tooling (each entry deep-validated at least once; refuted claims dropped, new
finds admitted); (5) final 3-lens quorum (agent value, philosophy fit, evidence rigor) with hands-on
spot re-checks. Every `eid_*` cites a real steroid_execute_code execution; evaluated at HEAD bb738d57
(plugin 0.100-409f23a2 / devrig 0.100.19999-SNAPSHOT-ddf8027a).

Ranks 1–26 are quorum-validated; 27–29 are contested (approved by 2 of 3 lenses). One entry from the
top-30 was dropped as refuted (refactoring dry-run guidance — already shipped in 6 articles).

## The list

### 1. Canonical 'verify no red code after edit' recipe: compile/type-error check that works unfocused

- **kind**: recipe · **confidence**: validated
- **why**: Core promise is edit-then-verify, but every documented red-code path fails unfocused: runInspectionsDirectly misses type errors; highlight-wait helpers time out and return empty, indistinguishable from 'clean'. Highest-frequency gap. Unanimous approval.
- **evidence**: Hands-on: 'fun broken(): Int { return "not an int" }' gave waitForEditorHighlighting(45s)=false, getHighlightsWhenReady=0 highlights (eid_20260610T225908); apply-patch.md has zero verify/compile/highlight mentions at HEAD bb738d57.

### 2. Single-call 'run test class, await, return structured results' recipe with SMTRunner getDelegate unwrap

- **kind**: recipe · **confidence**: validated
- **why**: Test running is the hottest verification loop; the shipped test/ corpus prescribes 5+ calls and its console cast fails on 2026.1 Ultimate (profiler-widget wrapper). Proven single-call pattern removes 4 round-trips per run. Unanimous approval.
- **evidence**: Hands-on IU-261.25134.95 (eid_20260610T231316): temp JUnit config + executeConfiguration + in-script poll = exitCode 0 in one call; direct SMTRunnerConsoleView cast FALSE, getDelegate() unwrap read 12 tests/0 defects. grep getDelegate over prompts = 0 files.

### 3. Structured execute_code envelope: payload channel, final-expression value, bounded logs, artifact path

- **kind**: response-shape · **confidence**: validated
- **why**: Unbounded output hits MCP token limits; payloads must be scraped from mixed [PRE]/[RUN] text; the final-expression value is still dropped (a new HINT now explains it, per quorum re-check, but the value itself remains irrecoverable).
- **evidence**: Iter-3 repro (eid_20260610T232504): script ending in a string expression returned only the canned HINT; output.jsonl holds 4 lines, marker absent. 20k-println test returned 1,808,991 chars raw; grep 'output.jsonl' over prompts = 0. Codex FEATURE_02.

### 4. Resource discovery overhaul: complete index, task-based search, fuzzy-suggest on not-found, fix doc drift

- **kind**: recipe · **confidence**: validated
- **why**: Discoverability gates every recipe; both models hit it. 102 articles exist but skill.md references only ~31 unique URIs — over half the corpus unreachable; a one-char typo yields a bare error with no suggestion. Re-verified by quorum at HEAD.
- **evidence**: Quorum re-check at HEAD bb738d57: 102 .md articles vs 31 unique mcp-steroid:// URIs (44 ref lines) in prompt/skill.md; drift skill.md:159 'use list_mcp_resources' vs :318 'NOT exposed via resources/list'; typo fetch returned bare 'Resource not found'.

### 5. Verification primitives must distinguish 'clean' from 'did not run': structured results, fail-fast errors

- **kind**: response-shape · **confidence**: validated
- **why**: Empty results are ambiguous and helpers fail unsafely: getHighlightsWhenReady returns [] instantly for unopened files (indistinguishable from clean); runInspectionsDirectly returns emptyMap on missing PSI and throws a raw platform IAE on binary files.
- **evidence**: Live eid_20260611T005512 (IU-261.25134.95): unopened file -> getHighlightsWhenReady size=0 in 3ms, waitForEditorHighlighting=false in 1ms; runInspectionsDirectly(gradle-wrapper.jar) threw IAE 'restrictRange must not be null'. KDoc says 'empty list if timeout'; impl returns emptyMap on null PSI.

### 6. Structured build error extraction for ProjectTaskManager/JPS builds (file:line:message, modules)

- **kind**: recipe · **confidence**: validated
- **why**: Both models reproduced the same dead end: an IDE build reports only hasErrors and timing, so on failure the agent learns nothing and falls back to shell Gradle — defeating the IDE-runner advantage on the build-verify loop.
- **evidence**: Reflection over ProjectTaskManagerImpl$MyResult showed only isAborted/hasErrors/anyTaskMatches/getContext (eid_20260610T224319); Codex FEATURE_07 identical. grep 'CompilerMessage|ProblemsView|BuildViewManager' over prompts = 0 files.

### 7. Document and control execution lifecycle: queue metadata, eid_* artifact folder, cancel-by-execution-id

- **kind**: reliability · **confidence**: validated
- **why**: Undocumented serialization on a shared compile queue hits multi-agent sessions with no owner/queue-position metadata; the client cancels at ~60s while the IDE-side script keeps running; audit trail grows unbounded; no cancel verb exists.
- **evidence**: Probe eid_20260610T234949 printed 'Waiting for previous compilation to complete...' with zero metadata (CodeEvalManager.kt:45); eid_* count reached 100; live re-check 2026-06-11: devrig --help lists only mcp/backend/project/install — no cancel/abort. Codex FEATURE_19.

### 8. Project routing resilience: stable aliases plus self-correcting unknown-id errors on MCP and devrig CLI

- **kind**: response-shape · **confidence**: validated
- **why**: Both models failed on project_name routing; pid-salted names go stale across restarts. Errors carry a 19-frame stack, no candidates, and claim never-existed names are 'no longer present'. Aliases + inlined candidates make failures zero-or-one trips.
- **evidence**: DevrigProjectRoutingService.kt:46-48 throws ProjectRouteNotFoundException carrying only the bad name though routes() holds the valid map one line above. Live: 'nonexistent-project-QQQQ' got a 19-frame stack, no candidates. Codex FEATURE_01.

### 9. Decompiler legal-notice modal from debugger pause poisons non-modal EDT; recurs — dismissal isn't acceptance

- **kind**: reliability · **confidence**: validated
- **why**: Pausing in a sourceless JDK frame pops the modal decompiler notice; under 'unleashed' it survives the call and all later non-modal EDT dispatch queues forever (zero-output timeouts). closeModalDialogs only dismisses — the next pause re-poisons.
- **evidence**: Iter-4 (eid_20260610T234844): PropertiesComponent 'decompiler.legal.notice.accepted' == null AFTER recovery — dismissed, not accepted, will reappear; key verified in IU java-decompiler.jar bytecode. Iter-3: 110s zero-output hang traced to the open modal. grep decompiler over prompts = 0.

### 10. Execution-timeout errors must carry IDE-state diagnostics: open modals, non-modal EDT health, block location

- **kind**: response-shape · **confidence**: validated
- **why**: Timeouts today return only a canned CRITICAL-RULES hint — zero info on WHY. The reproduced cause was a leftover modal blocking all non-modal EDT dispatch; diagnosis needed a hand-written ModalityState.any() probe no agent would write unaided.
- **evidence**: eid_20260610T233112: 110s timeout, zero output; response and output.jsonl held only TimeoutCancellationException + irrelevant runBlocking hint. Re-confirmed fresh: 8s timeout (eid_20260611T005530) returned only the canned 4-rule HINT — no modal list, no EDT health.

### 11. Clean up execute_code compile-failure responses: de-duplicate kotlinc error blocks, context-sensitive hints

- **kind**: response-shape · **confidence**: validated
- **why**: Every compile failure prints the kotlinc block twice; exception blocks repeat 2-3x; the canned HINT ignores the actual diagnostic — recommending an EDT wrapper for a pure generics error and imports for the common string-template '$' trap.
- **evidence**: Reproduced in all iterations incl. quorum re-run (eid_20260611T010424): duplicated kotlinc block. eid_20260610T225846 'echo tick-$i' failed 'unresolved reference i' yet got the import HINT — escaping \$i fixed it.

### 12. Map compile-error and stack-trace line numbers back to the submitted script's lines

- **kind**: response-shape · **confidence**: validated
- **why**: The wrapper preamble offsets every diagnostic, and the offset varies with the script's import block and differs for runtime frames — 'fix line N' self-correction loops are structurally broken since no constant correction exists.
- **evidence**: Variable offsets across all iterations: 2-line script reported input.kt:26 (eid_20260610T224019); ~+23 vs +3 (eid_20260610T225847/225846); submitted line 2 reported as input.kt:27, +25 (eid_20260610T234906); +25 re-reproduced in quorum re-run eid_20260611T010424.

### 13. Context API matrix: suspend helpers cannot be called inside the context's non-suspend readAction wrappers

- **kind**: recipe · **confidence**: validated
- **why**: Structural trap: findPsiFile/runInspectionsDirectly/applyPatch are suspend, but readAction/smartReadAction take non-suspend ()->T — the guide's 'wrap PSI reads in smartReadAction' cannot compile, and the canned HINT recommends the failing pattern.
- **evidence**: Quorum re-repro eid_20260611T010424: smartReadAction { findPsiFile(...)?.name } failed 'suspension functions can only be called within coroutine body'; HINT rule #2 recommends smartReadAction. McpScriptContext.kt:369/383/398 vs :451/:473/:483 prove the mismatch. Codex FEATURE_04.

### 14. Write-lock waits invisible in execute_code output: no-op writeAction parked ~477s with zero feedback

- **kind**: reliability · **confidence**: validated
- **why**: Every edit path parks on the write lock, and the wait can be arbitrarily long when another agent wedges the EDT — nothing is emitted while parked, so 'computing' and 'blocked on someone's lock' are indistinguishable; the 600s timeout hides the cause.
- **evidence**: eid_20260610T232742 (IU-261.25134.95): instrumented no-op writeAction logged 'requesting' at 303ms and 'inside' at 477991ms — 8 minutes of dead air in both response and output.jsonl. Controls on same backend: 1-3ms uncontended. No prompts article mentions write-lock waits.

### 15. screenshot-tree.md misses renderer-painted content: tab titles, JTree/JList items, ActionButton identity

- **kind**: response-shape · **confidence**: validated
- **why**: extractText covers only JLabel/AbstractButton/JTextComponent at 120 chars; everything painted via renderers — editor tab titles, all JTree/JList content, status bar, action ids — is absent, forcing pixel inspection for strings Swing already exposes.
- **evidence**: Fresh capture eid_20260611T005552 (w-406ffa85): 591-line tree has 14 quoted strings; all 8 EditorTabLabel nodes untitled; 0 of ~30 visible Gradle module names; 71 anonymous ActionButtons. SwingComponentTreeProvider.kt:57-63 confirms 3-type scope. Codex FEATURE_17.

### 16. Output duplication on read-action retry: smartReadAction lambdas re-run and every println emits per attempt

- **kind**: reliability · **confidence**: validated
- **why**: Silent correctness bug: platform smartReadAction re-runs the lambda when a write action cancels it, and println output is captured per attempt — 2 results look like 4 with no signal, baked into the audit trail. Buffer per attempt or warn loudly.
- **evidence**: Deterministic repro (eid_20260610T233831): no-op writeAction fired mid-read; the single println emitted 'READ start 3ms' AND 'READ start 206ms' — lambda ran twice, recorded success. McpScriptContextImpl.kt:524 delegates to the platform retry primitive; threading article silent.

### 17. Fix false 'threading violations throw immediately' claim — violations inconsistent in BOTH directions

- **kind**: reliability · **confidence**: validated
- **why**: PSI access outside readAction only soft-asserts: the script continues, prints its result, is recorded SUCCESS — agents ship the broken pattern. Inverse proven too: off-EDT resume() threw and hard-failed the script yet the resume took effect.
- **evidence**: eid_20260610T223836: un-read-actioned .text printed the exception capture then the correct result; artifacts show success.txt. eid_20260610T232750/232808: resume() threw 'Access is allowed from EDT only' but the session resumed anyway. Claim ships at threading.md:9 and prompt/skill.md:202.

### 18. Local History safety net: label-before-patch + Label.revert rollback and past-content recovery recipes

- **kind**: recipe · **confidence**: validated
- **why**: applyPatch is atomic but undo is unreachable from a later call (UndoManager resolves to BackendUndoManager needing a FileEditor; git checkout is destructive). Local History is the IDE-only escape hatch, proven in one call — the corpus teaches none.
- **evidence**: Hands-on (eid_20260610T234927, IU-261.25134.95, one call): putSystemLabel succeeded; Label.revert(Project, VirtualFile) is real; getByteContent recovered 13,310 bytes of past README.md. grep 'LocalHistory|UndoManager' over prompts = 0 files.

### 19. Project/module-scope inspection run recipe (GlobalInspectionContext + AnalysisScope)

- **kind**: recipe · **confidence**: validated
- **why**: 'Inspect Code on the module I just changed' is the standard pre-commit sweep, but runInspectionsDirectly is per-file and a file loop is wrong for global inspections needing cross-file context. Proven live; recipe must warn that NPE'd tools report success.
- **evidence**: Hands-on eid_20260611T005719 (IU-261.25134.95): GlobalInspectionContextImpl+AnalysisScope on 24-file dir gave 'SameParameterValue | ServerUrlWriter.kt:132' in 743ms after 3 undocumented pitfalls: ProgressWindow required; unset ctx.currentScope NPEs RefManagerImpl; results in problemElements.

### 20. Generic cross-call run-console reading: RunContentManager to ConsoleViewImpl on EDT, plus NDJSON streaming

- **kind**: recipe · **confidence**: validated
- **why**: Diagnose-and-fix for apps/servers means 'start it, poke it, read output across calls'. The descriptor-poll pattern is taught only in Maven/Gradle test articles; for plain run configs agents rediscover the EDT requirement and deferred-flush buffering.
- **evidence**: Proven twice: eid_20260610T231145/231228 started a /bin/sh ticker via OSProcessHandler, re-found the descriptor via RunContentManager.allDescriptors on EDT, flushDeferredText(), read full console. ConsoleViewImpl appears only in execute-code-maven.md and execute-code-gradle.md at HEAD.

### 21. Recipe: resolve a declaration by FQN across Java and Kotlin, with the Kotlin light-class pitfall documented

- **kind**: recipe · **confidence**: validated
- **why**: Agents know symbols by FQN but every navigation/refactor recipe is (file,line,column)-addressed, forcing a read-the-file detour. Kotlin trap: indexes return SymbolLightSimpleMethod, which misbehaves in RenameProcessor without .navigationElement.
- **evidence**: eid_20260610T223905: PsiShortNamesCache returned SymbolLightSimpleMethod; RenameProcessor dry-run on the light method vs its navigationElement gave different usage results. grep 'navigationElement|SymbolLight' over prompts = 0 files; lsp/rename is position-addressed.

### 22. Agent-artifact-safe search scopes: exclude eid_* execution folders and agent worktrees from index results

- **kind**: context-api · **confidence**: validated
- **why**: The tool pollutes its own search surface: prior execute_code scripts and scratch worktrees rank above real source, so every index-backed recipe (FilenameIndex, find-usages, duplicates) silently degrades the longer an agent session runs.
- **evidence**: Codex: FilenameIndex returned .idea/mcp-steroid/eid_*/compiled/input.kt and .claude/worktrees/... before normal project files (FEATURE_03). Pollution compounds: eid_* folders grew 83 to 99 to 100 across iterations, each holding compiled/input.kt.

### 23. Recipe: one-call debugger frame snapshot via XStackFrame.computeChildren with HiddenStackFramesItem unwrap

- **kind**: recipe · **confidence**: validated
- **why**: At every breakpoint the first question is 'what is the state here', but eval is one-expression-per-call (5-10 round-trips per frame). One-call snapshot proven twice, but needs ~120 lines of non-obvious async-callback boilerplate agents get wrong.
- **evidence**: eid_20260610T231509: single call attached to a JDWP debuggee, paused, walked frames (HiddenStackFramesItem unwrap needed), ran computeChildren via an XCompositeNode collector. Re-proven on IC (eid_20260610T233015). grep computeChildren over prompts + plugin src = 0.

### 24. Recipe: conditional, exception, and non-suspending logging breakpoints (tracepoints)

- **kind**: recipe · **confidence**: validated
- **why**: Conditions replace fragile step-N loops; exception breakpoints answer 'where is this thrown' in one shot; suspendPolicy=NONE + logExpression pairs with NDJSON monitoring. All proven, but discovery took FOUR attempts due to undocumented pitfalls.
- **evidence**: eid_20260610T233827 (IU): one call created a conditional non-suspending tracepoint plus a java-exception breakpoint, read both back correctly, removed both. grep conditionExpression|suspendPolicy over prompts = 0. Pitfalls measured: async toggle, ctor arity, generic variance.

### 25. Out-of-process hung-backend diagnosis recipe: pid to jcmd Thread.print, managed logPath, idea.log dirs

- **kind**: recipe · **confidence**: validated
- **why**: When the EDT freezes every in-IDE endpoint is dead, but the capability exists out-of-process (pids exposed, jcmd ships with Java 25, devrig exposes logPath). Nothing teaches it — iter-3's wedged-EDT backend was diagnosable only out-of-band.
- **evidence**: Hands-on: 'jcmd 16764 Thread.print' returned a full dump of the marker-discovered IDEA (162 threads); 'devrig backend stop --json' lists running backends WITH logPath; live re-check: devrig verbs still lack logs/threads; no jcmd article at HEAD. The EDT hang was the live proof of need.

### 26. Action-discovery safety guardrails: discovery recipes must not execute UI actions when copied verbatim

- **kind**: recipe · **confidence**: validated
- **why**: Agents copy recipe blocks wholesale; a discovery article whose final snippet performs an action turns a read-only probe into a mutation or modal hang. Splitting discover vs perform is a cheap fix to a sharp edge in the most copied content.
- **evidence**: ide/action-discovery.md lines 90-97 still end the single kotlin fence with ActionUtil.performAction on 'RunClass' — a verbatim copy opens the file, moves the caret, and executes. Re-verified by both validators through iteration 5 at HEAD bb738d57. Codex FEATURE_12.

### 27. Read-only execute_code mode: in-script guard proven impossible — enforcement needs a tool-boundary flag

- **kind**: tool-change · **confidence**: contested
- **why**: Review/planning tasks need an enforced no-write contract; testing REFUTED the recipe-level guard, so only a read_only param can deliver it. Sunk: agent-value reviewer demoted as safety-not-capability; the other two lenses approved.
- **evidence**: eid_20260610T233857: ApplicationListener.beforeWriteActionStart threw IllegalStateException; the IDE captured it (NestedLocksThreadingSupport.fireBeforeWriteActionStart) and the write COMPLETED — listenerFired=true, wrote=true. ExecCodeParams at HEAD has no read_only param. Codex FEATURE_11.

### 28. Bridge backend_name vs managed-id: lifecycle verbs reject ide-XXXX; running managed rows lack managedDetail

- **kind**: devrig-cli · **confidence**: contested
- **why**: Lifecycle verbs still reject backend_name; managedDetail ships only for installed rows, so a running managed backend can't be mapped to a stoppable id. Sunk: agent-value reviewer demoted as setup-time, low-frequency; error now hints id listing.
- **evidence**: Live 0.100.19999-ddf8027a: 'backend stop ide-2FlunefY --json' fails 'Unknown argument(s)' (error now hints 'run with no id to list valid ids'). list_projects: installed row ide-2FlunefY carries managedDetail.managedId, but RUNNING managed marker row ic-phhp9dDU has no managedDetail.

### 29. Cross-backend plugin skew: raw plugins[] now visible in list_projects, but no computed skew signal

- **kind**: reliability · **confidence**: contested
- **why**: plugins[] (commit 8ffb65b5) fixes raw visibility; remaining gap is convenience only — the agent can diff versions itself; the stderr warning stays invisible and major.minor-only. Sunk: agent-value reviewer calls it largely absorbed.
- **evidence**: Live steroid_list_projects returns plugins[] per backend with exact versions: 0.100-409f23a2 on iu-SoqG4IVP/go-xgXVtRjX vs 0.100.19999-SNAPSHOT-ddf8027a on ic-phhp9dDU. No skew field in any tool response; BackendVersionSkew.kt still stderr-only and base-version-only.

## Final-quorum changelog

- DROPPED old rank 22 (refactoring dry-run): demoted by all 3 reviewers; evidence reviewer found it stale — dryRun=true ships as default in 6 articles at HEAD bb738d57.
- SUNK old ranks 28, 16, 30 to final 27-29: each demoted by one reviewer (agent value) but approved by philosophy + evidence lenses; marked confidence=contested.
- Sunk order 28 > 16 > 30: 28 has explicit philosophy defense (documents ruled-out API path); 16 is setup-time tooling; 30 is largely absorbed by shipped plugins[].
- Ranks 1-15 unchanged: unanimously approved, all validated; quorum reviewers independently re-reproduced ranks 1, 4, 5, 11, 12, 13.
- Old ranks 17-21, 23-27, 29 promoted to fill gaps (now final 16-26).
- Rank 3 updated per philosophy reviewer's re-check: a new HINT now explains the dropped final-expression value, but the value itself is still irrecoverable.
- Rank 4 evidence updated to quorum recount: 31 unique URIs (44 ref lines) vs 102 articles, plus the :159 vs :318 doc drift.
- Ranks 11/12/13 evidence refreshed with quorum re-run eid_20260611T010424 (duplicated kotlinc block, +25 offset, suspend-in-smartReadAction failure).
- All evidence fields trimmed to single strongest citation per hard output limits; 29 entries total (30 minus 1 drop).

## Quorum notes

- **agent value**: Spot-checked hands-on: rank 4 — 102 articles vs 31 unique URI refs in skill.md, typo fetch gives bare not-found; ranks 1/5 — live probe on unopened README.md gave waitForEditorHighlighting=false and 0 highlights, indistinguishable from clean (helpers also take inconsistent Duration-vs-Int args); ranks 11/12 — my own compile failures printed the kotlinc block twice with an irrelevant canned HINT, offsets +3 then +25 with one import; rank 29 — performAction still ends the fence; rank 30 — plugins[] skew visible live. Top 14 are the real agent-capability levers.
- **philosophy fit + feasibility (docs/PHILOSOPHY.md tenets 1-5)**: 29/30 fit the tenets: recipes are the prescribed lever; response-shape items improve existing tool output (additive wire, Tenet 5 OK); devrig items reshape devrig-owned output and stay stateless. Rank 28 kept: it acknowledges Tenet 1 and documents the ruled-out API path (EventDispatcher swallows throwing beforeWriteActionStart), satisfying criterion 1; consensus gate still applies. Live re-checks: rank 3 partially mitigated (new HINT explains dropped final expression, value still dropped); rank 16 error now hints id listing; ranks 4/30 confirmed unchanged.
- **evidence rigor**: Re-ran 6 checks. Rank 4: 102 articles vs 31 unique URIs (44 lines) in skill.md; drift :159 vs :318; typo fetch = bare 'Resource not found'. Rank 13: eid_20260611T010424 reproduced suspend-in-smartReadAction compile failure, HINT rule #2 recommends the failing pattern; same run shows duplicated kotlinc block (rank 11) and +25 line offset (rank 12). Rank 16: devrig stop rejects ide-2FlunefY; running managed row ic-phhp9dDU lacks managedDetail. Rank 30: plugins[] live, skewed versions, no skew signal. Rank 29: performAction('RunClass') still ends the fence. Rank 22 demoted.
