# TODO

- [ ] **Native MCP tools — implement per `docs/native-mcp-tools-design.md`** (spec landed first;
  research 3×-quorum validated + live-tested on IU-261.25134.95, 2026-07-22):
  - [ ] Scenario B (chosen first step): `IntelliJMcpServerProbe.listNativeTools()` (+ drop the
    banned `internal` on `IntelliJMcpServerProbeImpl`), `GET …/native-tools` bridge route,
    `mcp-steroid-server` DTOs (`available`/`unfiltered` on the wire, no `backend_name`),
    `devrig project tools <project_name> [--json]` (ProjectCommand → `invokeWithoutSubcommand`),
    explicit 404="plugin too old" branch, WirePristinenessTest + contract pins,
    `:test-integration` canary (list + `find_files_by_glob` call), wire-table entry.
  - [ ] Scenario A follow-up: `prompts/src/main/prompts/skill/native-mcp-tools.md` article
    (guard fence → LIST → schema-first → CALL → caveats), one full KtBlock matrix run before
    merge; same PR fixes stale `required_plugins` in `coding-with-intellij-patterns.md` (3 sites).

- [ ] **Move the Docker test images off Node 20** — `20.20.2` (Iron) is the last LTS release of a line
  whose maintenance window has closed, so it stops receiving security fixes. All four images
  (`test-integration/.../ide-base`, `test-helper/.../{claude,codex,gemini}-cli`) pin it via
  `ARG NODE_VERSION` + per-arch sha256 from `nodejs.org/dist/vN/SHASUMS256.txt`. Bumping means
  checking that `@anthropic-ai/claude-code`, `@openai/codex` and `@google/gemini-cli` all run on the
  newer line first, then updating the version and BOTH digests in each file.

- [ ] **runInspectionsDirectly follow-ups (#69 ask 1)** — deliberately deferred, not work-in-progress.
  - *Deferred:* a `PsiFile`-accepting overload (and any richer per-file batch surface). It is a
    `McpScriptContext` surface growth — gated by PHILOSOPHY Tenet 3 / the 3-reviewer consensus, same
    as the explicit-`Project` overload (#94). Revisit only if that gate is cleared.
  - *Already shipped (2026-06, NOT part of this item):* per-tool crash isolation (#93), per-file
    PSI-invalid tolerance, and the additive `InspectionRunResult.failedTools` section — all without
    touching the argument list.

- [ ] Backend management follow-ups (deferred, surfaced during the design):
  - Stream download progress to the agent (downloads can take minutes; CLI is silent until done).
  - Consider enriching `backend --json` / `backend download --json` with release date + download channel so agents can reason about staleness; consider exposing `IdeProduct` metadata (license tier, launcher) for richer IDE choice.
  - Optional explicit `open_project` target (by managed-backend id / pid) for the case where the agent wants a specific backend even when several are running — today the global lock makes "prefer managed" sufficient.

- **plugins[] enumeration (follow-up to closed #88):** surface more IDE plugins on `BackendInfo.plugins[]`
  (e.g. the built-in IDE MCP server as `kind: "intellij-native-mcp"`). Needs an additive wire extension:
  optional `PidMarker.plugins: List<PluginInfo>? = null` (ij-plugin writes relevant plugins; old devrig
  ignores unknown key; new devrig falls back to the singular `plugin` field), devrig-side id→kind
  classification, PidMarker contract-test updates. Spec in the #88 closing comments.

- [ ] **Fix the pre-existing `:prompts:test` failure** (broken on `main` since before 2026-06-09):
  `MarkdownArticleContractTest.testNoNonKotlinFences` fails on
  `debugger/debug-attach-remote-jvm.md` (5 ```text fences at lines 10/26/66/101/123). The contract
  bans non-kotlin fences; rewrite those blocks as prose/inline code or ```kotlin. Until fixed, every
  prompts contract run reports this one failure (sessions treat it as "green if sole failure" — debt).
- [ ] **devrig-naming.md id-scheme drift**: the naming-contract doc still specifies the old
  slug/bootHash exposed ids (`IntelliJ_IDEA_2025.3.3-AbC4Df01`) while the implementation has moved to
  `productCode-hash8` backend_names (`iu-9fk2a0xQ`) and pid-salted project names. The plugins[] section
  was fixed (2026-06-10); the id-scheme sections need their own reconciliation pass.
- [ ] **list_windows graceful degradation**: devrig's `steroid_list_windows` is all-or-nothing — one
  IDE failing its `/windows` fetch errors the whole call (`coroutineScope` + `error(...)`), unlike
  `list_projects` which degrades per-backend. Return partial windows + a per-backend error marker.

- [ ] **red-code reporter false-positives on Kotlin files**: `reportProjectRedCode` (PSI reference scan,
  `mcp-steroid-import.kt`) reports Kotlin stdlib/operator references (`mutableMapOf`, `runCatching`,
  `trim()`, `!!`, `=`) as UNRESOLVED — 95/646 on the stock Gradle test-project's `SsrRunCatchingDemo.kt`
  while the project is actually green. Java-only Keycloak showed 1/25747, so the scan is sound for Java;
  the Kotlin path needs K2-aware handling for operator/implicit references (or skip
  `KtOperationReference`-style refs / restrict the sample to Java files). Non-fatal today (logged, never
  thrown), but the signal is noise for Kotlin projects. Found validating #200's settle on
  GradleCompileTest (2026-07-02).

- [ ] **`install --check` vs the literal Tenet-3 reading (review follow-up to #86)**: `--check` itself
  is read-only, but `runsTool()` in `npx-kt/.../Main.kt` returns true for `DevrigCommandInstall`, so the
  shared CLI startup still fires the PostHog beacon (`beacon.captureStarted`) and the background update
  check — and the beacon may write `~/.mcp-steroid/.devrig-user-id` on first run (`DevrigBeacon.distinctId`).
  This is common to every devrig tool command, not specific to --check. If a strictly side-effect-free
  `--check` ever matters (e.g. for CI probes), make `runsTool()` return `!check` for install — decide
  deliberately, since it also silences the update notice for that invocation (2026-06-12).

- [ ] install.ps1 Windows smoke test: the devrig bootstrap installer (#97) was verified end-to-end on macOS (sh) and parse/behavior-checked under pwsh in Docker, but has never executed on real Windows PowerShell 5.1 — run it on a Windows box before promoting the PowerShell one-liner beyond the docs page (2026-06-12).
- [ ] **inspect-and-fix recipe idiom follow-up (#81 review minor)**: the main recipe runs
  `InspectionEngine.inspectEx` under plain `readAction { }` while the cross-project section uses
  `smartReadAction` — unify on `smartReadAction` (kotlin-fence change → re-run the scoped
  `InspectAndFixKtBlocksCompilationTest`).
- [ ] **Hardcoded-URI lint gap (#81 review minor)**: `NoHardcodedMcpSteroidUriUsageTest` scans only
  ij-plugin/prompts/prompt-generator src/main — `mcp-steroid-server/src/main` is not covered and
  already carries a pre-existing `mcp-steroid://prompt/skill` literal in `FetchResourceToolHandler`'s
  param description. Extend the lint to that module and replace the literal.
- [ ] **ContentPart.kt `enterElseIf` bug (found by #98-t2 review, pre-existing)**: `ConditionalState.enterElseIf`
  overwrites `frame.previousFilters` with only the latest branch filter, so a 3+-branch chain
  `IF[A]/ELSE_IF[B]/ELSE_IF[C]` computes the third branch as `not(B).and(C)` instead of
  `not(A).and(not(B)).and(C)`. No current article uses 3+ branches, but the corpus now leans harder
  on conditionals — fix with a unit test before anyone writes one.
- [ ] **#98 residual corpus-escape vectors (by design, documented)**: SHORTHAND_LIST_PATTERN only matches the
  current list shape, and the availability audit is non-transitive (an article referenced only from a
  skill/-root article's body escapes). Extend if a future gating bug slips through.
- [ ] **DataGrip (DB) caveat**: test-run/debug articles are now fetchable in DB where they are meaningless
  (graceful error at runtime); add a one-line DB caveat if dogfooding surfaces confusion.

- [ ] **Arena verification: extend to Gradle-based arena cases.** `test-experiments/build/arena-*` currently collects
  results only from Maven-execution scenarios. Wire harness to also run FAIL_TO_PASS classes via `./gradlew` for
  Gradle-based projects (Spring Boot, Gradle wrapper scenarios) so verification matches the same surefire XML +
  `verified_ftp_rate` grading used in Maven cases — eliminating the `self-reported ARENA_FIX_APPLIED` bypass.

- [ ] **Arena overlays: extend local-patch support to other curated cases.** Service-125x (with security/status-transition
  slice test overlay) now runs with a local test-patch overlay; same pattern scales to other scenario-specific edge cases
  (Rider/.NET audit overlays, permission-model refinements). Document the overlay-harness contract and extend the pattern
  to the next priority case (TBD during arena maintenance).

- [ ] **Arena repeat-run statistics protocol.** After implementing A/B arms (Task 2), design a repeat-run frame-based
  protocol so agents across multiple arena executions contribute to a running aggregate (per-scenario, per-mode).
  Deliverable: CSV aggregation rules and significance-test formula suitable for comparing Claude vs. Codex over 100+
  runs. Deliberately deferred (out of scope for the initial three-arm work); prioritize after initial data collection
  stabilizes the measurement machinery (2026-Q3 or later).

## IntelliJ-family IDE coverage (IU/IC/AI) — backlog

- [ ] **Integration test lanes for IntelliJ Community (IC) and Android Studio (AI).** The `[IU,IC,AI]`
  gating now claims IntelliJ-family Java/Kotlin/PSI/SSR/debugger recipes work in IDEA Ultimate, IDEA
  Community, and Android Studio. We currently only prove the Ultimate side (KtBlock compiles against the
  `idea` distribution; `PromptArticlePerIdeFetchIntegrationTest` covers the non-IU PyCharm/Rider/CLion
  negative direction). Add Docker IDE lanes (or KtBlock distributions) for **IC** and **AI** so a positive
  fetch + a representative `steroid_execute_code` recipe is proven on both. Needs an `IdeProduct.IntelliJCommunity`
  (`IC`) and Android Studio (`AI`) image/distribution.
- [ ] **API-difference audit near Spring etc.** Some Ultimate-bound APIs (Spring, `JUnitConfiguration`'s
  framework integrations, etc.) genuinely differ or are absent in IC/AI. The IC/AI lanes above will surface
  these — keep `[IU]`-only on the genuinely Ultimate-bound fences and split the recipe where the API differs.
- [ ] **Corpus-wide `[IU]` → `[IU,IC,AI]` sweep.** This PR converted only its own articles. Audit the rest of
  `prompts/src/main/prompts/**` for `[IU]` fences/sections whose APIs are actually in IC/AI and widen them
  (leaving genuinely Ultimate-bound ones, e.g. `skill/coding-with-intellij-spring.md`, as `[IU]`).

- [ ] **IDE-assisted hints on exec_code compile errors.** Today the agent's script is compiled by the
  Kotlin daemon out-of-band; compile failures return raw kotlinc text with no IDE assistance. Idea: on
  `unresolved reference: Xxx`, resolve the short name via the IDE's indexes (`PsiShortNamesCache` /
  `JavaPsiFacade`) and append the exact missing `import` line (the IDE's auto-import hint, server-side);
  similarly map known error texts (`Read access is allowed…`, `suspension functions can only be called…`)
  to one-line fixes + article URI. Generalizes the existing aborted-build `guidanceFor()` REQUIRED-ACTION
  hint into a rule registry. Deferred until the slimmed `execute-code-tool-description` router is validated
  on TC — the hint text and the article it links must agree, so the routing table has to settle first.

- [ ] **Keep `ARENA_ARM_TIMEOUT_MINUTES` and the TC arena cap in step.** The TC side now derives
  `executionTimeoutMin` from each agent's arm count (`DpaiaArenaAgentSpec.arms` × `ARENA_ARM_TIMEOUT_MIN`
  + overhead, in the separate `~/work/mcp-steroid-teamcity` repo), but that 150 is a hand-mirrored copy of
  `ARENA_ARM_TIMEOUT_MINUTES` here — the DSL runtime cannot read these sources. Changing either constant,
  or adding an arm to `DpaiaScenarioBaseTest`, means updating the spec in that repo too.
- [ ] **Count arena tool calls from the raw NDJSON, not the decoded transcript.** `extractDecodedLogMetrics`
  now matches the tool by name instead of by substring, which reproduces the NDJSON ground truth on all four
  arms of the 2026-07-28 run. The decoded transcript is still the weaker source — `test-integration/CLAUDE.md`
  documents that agents echo fetched article bodies into it — and the raw `agent-*-raw.ndjson` is what the
  analysis itself counted. `PrintCsvPrintToonPromptTest.readAgentExecCodeBodies` already parses all three
  agent shapes; reusing it here would also give per-call payload and result sizes, which is what the
  cost comparison actually turns on. Four call sites (`DpaiaScenarioBaseTest`, `DpaiaArenaTest`,
  `DpaiaClaudeComparisonTest`, `SemanticRunRecorder`).
- [ ] **Corpus-wide A/B for the slim router.** Only `skill/execute-code-tool-description` exists in two
  variants; the sibling articles it routes to are shared by all four arms. A clean corpus-wide comparison
  would need slim counterparts for the routed articles (~7 files) — measure the tool-definition-only win
  first and decide whether the duplication pays for itself.
