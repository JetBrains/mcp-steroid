# TODO

- [x] **Bundling devrig inside the IDE plugin — REJECTED** (2026-07-28). devrig is the future, not the
  plugin, so the plugin stays bundled inside devrig; devrig fetching the plugin on demand would add a
  runtime dependency on the plugin repository that can break in our own `backend download/start` flow.
  Freshness is solved devrig-side instead (devrig keeps itself current → so does the plugin it carries;
  known work, devrig-owned). Measurements + the full rationale:
  [`docs/devrig-bundled-in-plugin-spike.md`](docs/devrig-bundled-in-plugin-spike.md).
- [ ] **The IDE plugin is the migration path onto devrig** — it must move existing plugin users onto a
  correct, current devrig by running our canonical install scripts (`DevrigSetup.kt` downloads
  `install.sh` / `install.ps1` to `~/.mcp-steroid/update/install-<pid>.sh|.ps1` and runs the file via
  `installerCommands`; the `curl … | sh` / `irm … | iex` one-liner is only the settings page's copyable
  display string). Done since: the offer lives on the settings page with an **Install devrig** button,
  every surface renders the one launcher-exists probe (`devrigInstalled` — devrig self-updates by
  design, so there is no staleness axis; only the plugin's own update check compares `version.json`),
  the installer's own output drives a cancellable progress bar, and a failure writes the shared
  `~/.mcp-steroid/markers/bootstrap-install.failed` marker (its agent-side readers — `/devrig:status`,
  the SessionStart hook — arrive with the unmerged `claude-plugin` branch). The install shares devrig's
  own updater machinery (`UpdateCoordination` and `installerCommands`/`InstallerHost` in
  `:devrig-common`; `DevrigVersion` in `:mcp-core`), so the two halves cannot start competing
  multi-hundred-megabyte downloads. Remaining:
  - **No funnel data**: `analyticsBeacon` records offered → install-ok, but nothing downstream, so
    every claim about conversion (including "the settings page converts better than a balloon") is
    still guesswork.
  - **When, if ever, to show the startup offer**: the status-bar widget is deleted; the startup
    notification is behind `mcp.steroid.devrig.widget.enabled` (default off; the key id predates the
    widget and stays stable) until we have run with it ourselves. "Every IDE run" was the wrong
    answer; we do not yet have a better one.
  - **Two `version.json` fetch/parse stacks remain**: `ij-plugin`'s `UpdateChecker` (its own private
    `VersionInfo` over `HttpRequests`) and `:npx-kt`'s `DevrigUpdateChecker`/`AutoUpdater`. Unifying
    means promoting a shared version.json model + fetch into `:devrig-common` — new downloader code
    there, deliberately NOT done as part of the onboarding collapse (out of scope). Follow-up only if
    the two ever need to agree on more than `version-base`.

- [ ] **dpaia/ee-dataset exporter strips trailing whitespace from patches (upstream fix)**: 11 of 304
  patches in the live `java-spring-ee-dataset.json` are damaged (blank context lines trimmed to empty,
  trailing context lines dropped) — the arena works because `repairTrimmedUnifiedDiff` repairs them at
  parse time (#447, `test-experiments/.../DpaiaDataset.kt`), but the exporter in `dpaia/ee-dataset`
  (READ permission only from here — needs a PR or owner access) should stop trimming so the dataset is
  valid for every consumer, not just ours.

- [ ] **TC Mac agents (icri-big-agent-eqx-\*) — residual infra asks** (from the 2026-08-05 Maven-429
  investigation; the in-repo cache-redirector fix already unblocks the lane): (a) persistent/warmed
  `~/.gradle/caches` for the ephemeral eqx pool (agent image bake or TC ephemeral-agent dependency
  cache) so cold resolution stops depending on any external host; (b) optionally point the TC-side
  Gradle wrapper distribution at `cache-redirector.jetbrains.com/services.gradle.org/...` (every cold
  agent re-downloads the dist; low priority — not throttled today; must NOT be changed in-repo, the
  wrapper properties cannot be conditional); (c) FYI to JB infra: Maven Central began throttling the
  Equinix Mac egress between 2026-08-02 and 2026-08-04.
- [ ] **KtBlock matrix has no GoLand/WebStorm/RubyMine/DataGrip lane** (noted in the #406 quorum
  review). Unannotated (all-IDE) prompt fences are compile-verified only against
  Idea/PyCharm/Rider/CLion (stable+EAP) yet render in GO/WS/RM/DB at runtime. Risk is low while
  fences stick to platform-level APIs, but if it ever matters, extend `KtBlockCompilationTestBase`
  with a GoLand or WebStorm distribution — repo-wide infra task, not specific to any one article.

- [ ] **ProcessAiAgentCliRunner follow-ups** (issue #407 quorum review, all minor, non-gating):
  - [ ] An `InterruptedException` from the FIRST `waitFor(timeout)` propagates without killing the
    child, so on Windows the temp output file stays locked and leaks (loudly logged after the
    bounded delete retries). Kill the process tree on that interrupt lane too, then re-interrupt.
  - [ ] `ProcessAiAgentCliRunnerTest."no temp output files are left behind"` scans the
    machine-global `java.io.tmpdir`, so a concurrent run of the same suite on one machine can
    flake it (observed once during the quorum review). Make the runner's temp-file parent
    injectable and point the test at `@TempDir` for an isolated scan.

- [ ] **Tool/resource counts drift across surfaces** (found during the 2026-07-31 plugin-description
  rewrite review). The MCP tool surface is **8** (`docs/PHILOSOPHY.md` Tenet 1, canonical; confirmed
  against live registrations), but root `CLAUDE.md` and `ij-plugin/CLAUDE.md` say "10 today", and
  `README.md` still carries "### 8 MCP Tools" plus a stale "### 58 MCP Resources" heading with
  per-category counts summing to 60 while there are 106 prompt articles. Reconcile the CLAUDE.md
  numbers with PHILOSOPHY.md and de-count the README sections (headings without volatile numbers),
  the way the Marketplace description now does.

- [ ] **KtBlock matrix ignores the production kotlinc language/api pin (drift).**
  `CodeEvalManager` compiles every `steroid_execute_code` script with the
  `mcp.steroid.kotlinc.parameters` registry extras (`-language-version 2.3 -api-version 2.3` since
  2026-07-31; was 2.2), but `KtBlockCompilationTestBase.compileAgainst` passes only
  `-Werror`/`-jvm-target`/`-no-stdlib` — despite its "must match what KotlincCommandLineBuilder
  produces" comment. A prompt fence using a language feature/stdlib API newer than the pin passes the
  matrix yet fails at runtime. Fix: add the same LV/api flags to both the kotlinc invocation and the
  `compilerOptions` cache-key list in `KtBlockCompilationTestBase`. NOTE: this invalidates the whole
  KtBlock compile cache (60–120 min recompile) — land it right after a `kotlincVersion` bump (which
  invalidates the cache anyway), never casually.

- [ ] **steroid_input / take_screenshot #309 follow-ups** (the three core defects — modal-EDT hang,
  window-relative click coordinates, wrong window_id echo — are fixed and guarded by
  `SteroidInputDialogIntegrationTest`; remaining hardening surfaced by the 2026-07-30 review):
  - [ ] Ghost-input hazard: an input step already parked on the EDT when its MCP request dies
    (client disconnect / handler timeout) still fires later against whatever UI is current.
    Consider a cancellation check inside each EDT step before dispatching.
  - [ ] `steroid_take_screenshot` has no handler-level timeout either (P1's `withTimeout` was added
    to `steroid_input` only); capture() uses `ModalityState.any()` so it does not hang under modals,
    but a wedged EDT would still stall it — consider the same safety net.
  - [ ] Update issue #309: causal theory (window_id → P1/P2) disproven; Breakpoints dialog is
    non-modal (`setModal(false)`); tool docs could state coordinates are window-relative including
    decorations, and that `screen:` targets exist.

- [ ] **devrig auto-update — follow-ups** (core shipped per `docs/updates-check/devrig-auto-update.md`,
  3×-quorum approved 2026-07-30; branch `auto-update-install-scripts`):
  - [ ] `:test-integration` lane: drive the auto-update path end-to-end against the nginx-served
    installer fixtures (real `install.sh`, real `devrig install devrig` verify + marker authority).
  - [ ] Windows process-level coverage on a Windows runner: `superviseInstallerProcess` with
    `powershell.exe -File`, atomic replacement of a RUNNING `devrig.cmd`, and the
    sharing-violation → non-zero → quiet-retry degradation (retries are uncapped by design; quorum
    nit; needs the per-OS GH matrix).
  - [ ] Transitional ping-pong hint: when the launcher version keeps regressing tick-over-tick
    (a pre-launcher agent registration pointing at an old tree), extend the restart notice with a
    "re-run `devrig install <agent>`" hint.
  - [ ] Weekly URL-liveness GH Action: also assert live version.json ↔ install-script VERSION
    agreement (the release process now gates the website advance via
    `release/scripts/verify-release-ready.sh` + Stage 9 agreement checks, but a scheduled
    assertion would catch late CDN/publish drift too).
  - [ ] Install-script transfer timeouts (`curl --max-time` / `Invoke-WebRequest -TimeoutSec`,
    generous, e.g. 1 h): bounds the unsupervised-orphan window (design Tradeoff 5) with zero
    protocol complexity; benefits manual installs too. Template change in `:installer-gen`.
  - [ ] `binaries/` auto-GC (design Tradeoff 7): after an update lands, sweep `devrig-*` trees not
    referenced by the current launcher (keep one previous) — auto-update makes disk accretion
    automatic (~50–200 MB/release). The v7 deployment-spec auto-GC sketch is the model.

- [ ] **Native MCP tools — implement per `docs/native-mcp-tools-design.md`** (spec landed first;
  research 3×-quorum validated + live-tested on IU-261.25134.95, 2026-07-22):
  - [ ] Scenario B (chosen first step): `IntelliJMcpServerProbe.listNativeTools()` (+ drop the
    banned `internal` on `IntelliJMcpServerProbeImpl`), `GET …/native-tools` bridge route,
    `mcp-steroid-server` DTOs (`available`/`unfiltered` on the wire, no `backend_name`),
    a redesigned top-level CLI route such as `devrig native_tools <project_name> [--json]`
    (`list_projects` is a generated leaf and `projects`/`project` are its aliases, so none can own nested actions),
    explicit 404="plugin too old" branch, WirePristinenessTest + contract pins,
    `:test-integration` canary (list + `find_files_by_glob` call), wire-table entry.
  - [ ] Scenario A follow-up: short static index `skill/native-mcp-tools.md` (guard + LIST
    fallback) with a live tool-index overlay, plus dynamic per-tool pages
    `mcp-steroid://skill/native-mcp-tools/<tool-name>` rendered fresh per fetch via a
    `NativeToolPagesHandler` seam in `FetchResourceToolHandler` (in-IDE: probe-backed; devrig:
    fed by the `/native-tools` bridge endpoint; shared renderer in `mcp-steroid-server`);
    one full KtBlock matrix run before merge; same PR fixes stale `required_plugins` in
    `coding-with-intellij-patterns.md` (3 sites).

- [ ] **runInspectionsDirectly follow-ups (#69 ask 1)** — deliberately deferred, not work-in-progress.
  - On IU-262/K2, `LoggingSimilarMessage` and `UnusedSymbol` can crash on a Kotlin file that references
    generated prompt articles with `Cannot compute containing PSI for unknown source kind
    KtFakeSourceElementKind.PluginGenerated`. Crash isolation preserves other findings and correctly
    populates `failedTools`, but the file check remains `check_failed`; add a focused reproducer and fix or
    filter the unsupported synthetic PSI path without hiding unrelated inspection failures.
  - *Deferred:* a `PsiFile`-accepting overload (and any richer per-file batch surface). It is a
    `McpScriptContext` surface growth — gated by PHILOSOPHY Tenet 3 / the 3-reviewer consensus, same
    as the explicit-`Project` overload (#94). Revisit only if that gate is cleared.
  - *Already shipped (2026-06, NOT part of this item):* per-tool crash isolation (#93), per-file
    PSI-invalid tolerance, and the additive `InspectionRunResult.failedTools` section — all without
    touching the argument list.

- [ ] Backend management follow-ups (deferred, surfaced during the design):
  - [x] Launch managed IntelliJ Ultimate 2026.2 as a native Remote Development backend and prove the
    clean-machine Claude/Codex Keycloak hierarchy flow described in
    `docs/devrig-remote-development-backend-e2e.md`.
  - Apply the secret-safe environment allowlist to standard managed launches too, while explicitly
    retaining `http_proxy` / `https_proxy` / `no_proxy` variants needed by IDE networking.
  - Replace hardcoded `/usr/bin/setsid` / `/bin/setsid` lookup with a portable executable search so
    detached managed launches work on non-FHS systems such as NixOS.
  - [x] Snapshot PID + start identity before launch instead of excluding raw PIDs; serialize
    download/start/stop with one operation lock and refuse to rewrite the plugin of a live target.
  - Make failed-start cleanup diagnostics distinguish a deliberate identity-change refusal from a
    termination failure.
  - Move legacy archive migration under the global backend-operation lock, or prove the current
    idempotent moves safe when two fresh `BackendManager` instances initialize concurrently.
  - Revalidate the native Remote Development launcher for baseline 263+, using cold-CI telemetry to
    tune the 180-second readiness bound and the caller-cancellation behavior before widening support.
  - Put the pure Remote Development NDJSON parser/workflow contracts on a normal CI-backed task; the
    experimental task's direct-invocation guard currently keeps them out of aggregate CI runs.
  - Redact Remote Development join-link fragments (`#jt=...`) from preserved managed-backend logs
    ([#448](https://github.com/jonnyzzz/mcp-steroid/issues/448)).
    The Codex artifact review found one after the backend had stopped; the current sanitizer and invariant
    cover `Authorization`/Bearer, `_ijt`, and `x-ijt` credentials only. Extend the pure sanitizer tests and
    keep the shell artifact scan aligned before treating those logs as generally safe to publish.
  - Stream download progress to the agent (downloads can take minutes; CLI is silent until done).
  - Add bounded retry-on-read-timeout to the shared IDE downloader. It already resumes a pre-existing
    `.tmp` with `Range`, but a socket stall currently waits 15 minutes and fails the whole Gradle test or
    backend download instead of reconnecting and resuming within the same invocation (observed 2026-08-03).
  - Consider enriching `backend --json` / `backend download --json` with release date + download channel so agents can reason about staleness; consider exposing `IdeProduct` metadata (license tier, launcher) for richer IDE choice.
  - Optional explicit `open_project` target (by managed-backend id / pid) for the case where the agent wants a specific backend even when several are running — today the global lock makes "prefer managed" sufficient.

- **plugins[] enumeration (follow-up to closed #88):** surface more IDE plugins on `BackendInfo.plugins[]`
  (e.g. the built-in IDE MCP server as `kind: "intellij-native-mcp"`). Needs an additive wire extension:
  optional `PidMarker.plugins: List<PluginInfo>? = null` (ij-plugin writes relevant plugins; old devrig
  ignores unknown key; new devrig falls back to the singular `plugin` field), devrig-side id→kind
  classification, PidMarker contract-test updates. Spec in the #88 closing comments.

- [ ] **devrig-naming.md id-scheme drift**: the naming-contract doc still specifies the old
  slug/bootHash exposed ids (`IntelliJ_IDEA_2025.3.3-AbC4Df01`) while the implementation has moved to
  `productCode-hash8` backend_names (`iu-9fk2a0xQ`) and pid-salted project names. The plugins[] section
  was fixed (2026-06-10); the id-scheme sections need their own reconciliation pass.
- [ ] **list_windows graceful degradation**: devrig's `steroid_list_windows` is all-or-nothing — one
  IDE failing its `/windows` fetch errors the whole call (`coroutineScope` + `error(...)`), unlike
  `list_projects` which degrades per-backend. Return partial windows + a per-backend error marker.
- [ ] **list_windows human presentation (#284 follow-up)**: console mode still prints the tool's
  JSON payload as pretty-printed JSON, but still lacks a purpose-built, colorful windows/tasks renderer.
  Add that renderer while preserving the current ANSI-free `--json` envelope for agents.

- [ ] **`--project_name` is not inferred from the current directory (#284)**: `resolveProjectFromCwd`
  in `npx-kt/.../devrig/server/CwdProjectResolver.kt` is fully written and unit-tested (`One` / `None` /
  `Ambiguous`) but has **zero production call sites** — confirmed by PSI `ReferencesSearch`, not grep.
  Because no inference runs, `project_name` is simply a mandatory parameter: `CommonToolParams.projectName()`
  drops `.cliOptional()`, so the command-line parser itself demands it and `devrig execute_code`
  (or `take_screenshot` / `input` / `execute_feedback` / `fetch_resource`) run without `--project_name`
  fail at **parse time** — exit 64 naming `project_name`, before any tool call. The generated usage line
  renders it un-bracketed to say so. The generated help used to promise the inference; that sentence was
  removed rather than left lying (Task 9). Wiring the inference needs two decisions the Phase B plan never
  settled: what `CwdProjectMatch.Ambiguous` should print, and whether inference applies to every tool
  declaring `project_name` or only some. When it lands, restore `.cliOptional()` on `projectName()` (so
  the parser stops demanding it), and these deliberate reminders flip back: `McpToolsCliHelpTest`'s
  `the footer promises no cwd inference…` (restore the footer line in the same commit),
  `CliFileSourceUsageTokenTest`'s `a plain required parameter renders bare, demanded` /
  `and the parser really does demand it` (re-bracket the token, and the parser must stop demanding it).

  *Not* to be confused with the separate defect this entry used to describe — that the failure came out as
  exit 69 `… Usually no IDE backend is reachable`, misdiagnosing a reachable IDE. That was a missing
  `ToolCallErrorException` arm in `GeneratedToolRuntime.kt`'s error pipeline, fixed independently and
  pinned by `CliErrorEnvelopeTest`. It affected EVERY tool-side argument rejection, not just an absent
  `project_name`, so it would have outlived the inference work.

- [ ] **Deferred, non-gating: agent harnesses must gate the first task turn on MCP initialization**:
  initialize instructions
  solve deferred-schema discovery only after the devrig MCP server reaches ready state. A Claude Code
  run can still begin while the server is `pending`, see no `steroid_*` names or instructions, and commit
  to shell text search before initialization completes. This is a client/harness readiness problem; add
  a regression in the agent launcher instead of another server prompt or MCP tool.

- [ ] **Repeat the solution-readiness pilot on a SECOND capture per arm, before any new task**
  ([RESIDUAL-DIFFICULTY.md](docs/ripple-checkpoint-pilot/RESIDUAL-DIFFICULTY.md)). The per-rollout re-read
  of the 97 probe builds settles the metric question: residual work (cumulative output tokens and tool
  calls of the successful probes) has within-cell CV 0.19–0.22 and is monotone in `editFraction`
  (Spearman −0.77 mcp / −0.90 shell), while the binary `V` needs `n ≈ 27` to separate 0.75 from 1.00 and
  saturates at 1.00 halfway through both arms. So do NOT buy more replicates — buy a second capture of
  each arm on the SAME case (~30 + ~40 probe cells, ≈ $56 and ≈ 70 build-hours at the measured $0.68 and
  ≈ 1 build-hour per probe) and check whether each arm puts its integration-layer action at the same
  phase twice. Pre-registered rule for going wider: mcp must land it ≥ 0.2 earlier in `editFraction`
  than shell AND the residual-work collapse must be ≥ 2× on output tokens with non-overlapping bootstrap
  intervals. Drop the partial verifier score from the design — it is saturated at both class and test
  granularity on this case.

- [ ] **Give `RippleCheckpointProbeTest` a TAMPERED outcome distinct from `Y=0`.** Five of the pilot's
  rollouts rewrote a FAIL_TO_PASS test file, so `DpaiaRunOutcome.objectiveSuccess` (correctly) voided
  their grade — but the verdict line has no way to say "void" and published them as failures to finish.
  Three of the five had all five classes green with zero regressions, and four sit at one checkpoint, so
  the confusion moved a published `V` cell from 0.75 to 0.60 and another from 0.50 to 0.20. Add
  `TAMPERED` next to `LOST` in `checkpointProbeVerdict`/`parseProbeVerdicts`, and keep it out of `V`'s
  denominator by default. Also record capture build ids in `RUN-IDS.md` when they are QUEUED — stage 3's
  had to be recovered from TeamCity afterwards.

- [ ] **Pin an exact semantic oracle for the Keycloak Authenticator hierarchy E2E**: the headless-agent
  discovery scenario currently gates the pinned checkout with a 70-FQN lower bound plus known indirect
  implementors. Capture the canonical full set (or query it independently after the agent run) so future
  Keycloak fixture changes can distinguish exact completeness from a strong workflow regression signal.

- [ ] **Harden the CLI tool-spec metadata layer (#284 follow-up)**: remaining review findings after
  PR #450. (1) `CliToolSpec.schema` exposes the mutable `ToolSchema` — any consumer can call
  `register()` after registration and change the advertised `inputSchema`; expose a read-only view
  (interface with `asMcpJson()`/`asCliParams()` only). (2) Collision checks now cover parameter,
  file-source, tool-extra, framework, command-token, and alias names at command construction, but strict
  option-name grammar still needs a declaration-time guard (for example, reject a bare `--`). (3) Wire
  bounds declared via the `extra {}` closure
  (`success_rating` 0..1) are invisible to `asCliParams()`, while `timeout` carries a CLI-only
  `cliMinimum` — the generated CLI cannot enforce the wire bound without parsing `asMcpJson()`; also
  `cliSynopsis` hardcodes "(default 600)" where the MCP description interpolates the constant, so the
  two can silently diverge.

- [ ] **Make agent-choice commands shell-safe in devrig output (#284 follow-up)**: `InstallCommand` and
  `InstallConfigCommand` still render `devrig install claude|codex|gemini`, which a shell interprets as a
  pipeline rather than as alternatives. Print separate concrete commands (or one concrete command plus
  prose naming the replacements) and pin that no copyable command uses shell alternation as notation.

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

## Test-infra consent/stub findings (from #412 T7, Android Studio ConsentDialog)

- [ ] **JetBrains user-home consent stub path is likely dead weight.** `ideUserStartupConfigFiles()`
  writes `.config/JetBrains/consentOptions/accepted`, but `ConsentOptions.getConfirmedConsentsFile()`
  resolves `PathManager.getCommonDataPath()` = `${XDG_DATA_HOME:-~/.local/share}/<vendor>` on Linux —
  so the platform never reads the `.config` copy. JetBrains-IDE consent dialogs are actually
  suppressed by `-Djb.consents.confirmation.enabled=false` in the vmoptions. Either move the stub to
  `.local/share/JetBrains/consentOptions/accepted` (careful: devrig's ManagedBackend writes this list
  into REAL user homes — macOS resolves to `~/Library/Application Support/JetBrains/...`) or drop it.
- [ ] **`writeFileInContainer` to container-local paths leaves files root-owned.** The `docker cp`
  branch creates files as `root:root` (mode from the host temp file, umask 0644): readable by the
  uid-1000 `agent` IDE but NOT rewritable. Anything the IDE must open read-write from `$HOME` cannot
  use it — `writeAndroidStudioConsentStubs` in `intelliJ.kt` had to shell-write as the `agent` user
  for exactly this reason (`AnalyticsSettings` opens its file with `RandomAccessFile(file, "rw")`).
  Audit the other `/home/agent/...` stubs (`.java/.userPrefs/...`) for the same read-write trap.
- [ ] **`AndroidStudioRuntimeCompatTest` KDoc claims AS 2026.1 bundles JBR 21 — it ships JBR 25.**
  Both #412 AS-lane runs log `JDK: 25.0.2` in idea.log for AI-261.26222.65 (2026.1.3). The
  bytecode-21 gate itself stays valuable (issue #157: older AS + minimum-supported baselines), but
  the KDoc's "AS 2026.1 bundles JBR 21" premise is stale and should be reworded against reality.
- [ ] **A `--tests '*FooTest*'` glob silently starts a Docker run when a Docker test's class name ENDS
  WITH the unit test's name.** Gradle's `--tests` patterns match the fully-qualified name, so
  `*RippleTargetSurveyTest*` selects both `RippleTargetSurveyTest` (pure parser unit tests, ~0.01 s)
  and `KeycloakRippleTargetSurveyTest` (a full IntelliJ container). A command documented as "the unit
  tests, seconds, no Docker" then burns ~6 minutes on a container, and the pattern is invisible in the
  Gradle output until a `[IDE]` line scrolls past — this was mistaken for deliberate re-runs when
  reading a session's history. Remedy: select the fully-qualified class,
  `--tests 'com.jonnyzzz.mcpSteroid.integration.arena.RippleTargetSurveyTest'`, whenever a
  same-suffix Docker sibling exists. The naming convention `<Project><Feature>Test` for the Docker
  case beside `<Feature>Test` for its unit test makes this collision the default rather than the
  exception, so it is worth either a naming rule (Docker cases get a distinct suffix) or a note in
  the per-module guides.

- [ ] **The change-signature ripple case gates a SUPERSET of its reference modules.**
  `RippleCases.changeSignatureWide.compileGateModules` lists the nine Maven modules whose sources
  name `org.keycloak.authorization.model.Resource` exactly and contain a `getId(` call, because the
  survey measured six IntelliJ modules without printing their names and the mapping back to
  artifactIds needs the index that produced it. A superset is safe for a gate but pays three extra
  modules of `test-compile` on every arm, and each extra module is one more way for the pre-agent
  gate to fail for reasons that have nothing to do with the task. Narrow it the next time a container has the
  project indexed: print `ModuleUtilCore.findModuleForFile` over the gold reference set, map those
  IntelliJ module names to artifactIds, and pin the six.
- [ ] **The ripple family's pinned gold counts are RAW readings and no case pins its graded one.**
  `expectedGoldReferences` / `expectedGoldFiles` are the survey's raw resolved-reference counts, and
  `SemanticGold.checkTripwires` still compares against them — deliberately, so the pins keep meaning what
  they meant when they were measured and no case needs re-measuring for the import exclusion to land. But
  the number every score is now computed over is `SemanticGold.countedReferences` (imports removed), and
  nothing pins it: an index change that turned five usages into five imports would keep the tripwire green
  while silently moving every recall denominator. Remedy on the next indexed container: print
  `GOLD_SITE` totals split by `IMPORT_SITE_DECLARATION` for all seven cases, add
  `expectedGoldUsageReferences` beside the raw pin, and tripwire both.
- [ ] **`KeycloakChangeSignatureWideRippleTest` has no TeamCity configuration yet.** The family's
  other two cases each have per-agent configs matching `-PtestFilter=*<Class>.<agent>*`; the third
  case needs the same in `~/Work/mcp-steroid-teamcity` before it runs anywhere.

- [ ] **Console mode prints a JSON payload as one minified line (#284)**: `devrig list_projects` (and any
  generated tool whose result is a single JSON text item) emits one long minified blob, because
  `Presentation.Console.render` prints a text content item verbatim. The fix does NOT need a per-tool
  renderer: pretty-printing a text payload that happens to parse as JSON is tool-agnostic, so it belongs in
  `Presentation.Console` in `CliToolSupport.kt`. Deliberately **console-only** — the `--json` envelope now
  unpacks a JSON text payload under a `json` key (`contentDataJson`, so `jq` reaches it in one parse); that
  path is settled and must not be reshaped again for a console concern. A richer per-tool table
  (`devrig project`-style columns for the listers) is a different, larger question: it would need declared
  rendering metadata, since a `when (toolName)` is exactly what #284 removes.

- [ ] **Owner decision: fold `PidMarker.markerDirectory` into `HomePaths`** (external review of PR #367,
  2026-08-06). Two structural residues survived the directive-A audit, both pre-existing and deliberate:
  (a) `HomePaths.markersDir` ignores the receiver's `home` and always resolves under the REAL
  `user.home` — the plugin↔devrig marker-discovery contract pins the location, but a member ignoring
  its own receiver is an API wart, and sandboxed-home tests need the `DevrigServices.markersDir`
  constructor seam solely because of it; (b) `PidMarker.markerDirectory` hand-joins
  `userHome/.mcp-steroid/markers` (it IS the named route and uses `DEVRIG_HOME_DIR_NAME`, but a strict
  "everything through HomePaths" reading would fold it in as `home.resolve("markers")` over the
  receiver). Folding changes marker discovery under explicit test homes (today it escapes the sandbox
  on purpose — see `GeneratedToolRuntimeTestSupport`), so it is a design decision, not a cleanup.

- [x] **`devrig mcp` log files all collapse onto `devrig-session.log`, and every line reads `[pid:?]`**
  — FIXED as #462: `configureLoggingSystemProperties` now publishes the properties as the first
  statement of `runDevrigMain`, before command-tree construction can reach SLF4J (the first getLogger
  came from `FetchResourceToolHandler`'s logger field, confirmed by class-load order).
- [ ] **Gradle test JVMs log into the developer's REAL `~/.mcp-steroid/logs`** (spotted while fixing
  #462, still open). `~/.mcp-steroid/logs/devrig-session.log` locally carries `[Test worker]` lines from
  Ktor/`:npx-kt:test` runs — unit tests should never write into the real devrig home. Find which test
  path initialises logback without redirecting `devrig.log.dir` (a `systemProperty` on the test task
  pointing at `build/` would do it) and pin it with a test.

- [ ] **stdio framing follow-ups noticed while fixing #461** (both pre-existing, neither blocking).
  (a) `FramingBuffer.append` grows without bound: a peer that never sends a newline (binary dump, a
  huge single line) makes devrig buffer it all. A cap — discard-and-report past N MiB, reusing the
  new `readNextUnparsableChunk` reporting path — would bound it.
  (b) A final NDJSON message with no trailing newline is now answered with `-32700` ("stdin ended
  mid-frame") rather than dispatched. That is spec-faithful (newlines delimit frames) and beats the
  old silence, but a tolerant reading would dispatch parseable EOF residue instead. Deliberate choice,
  worth revisiting only if a real client trips on it.

- [ ] **`--debug` inside a Clikt `@argfile` still does not enable logging** (residual after the #462 fix).
  Logging verbosity is now read straight off argv (`Array<String>.debugRequested()`) before Clikt runs,
  because logback pins its configuration during command-tree construction. Clikt's `expandArgumentFiles`
  defaults to on, so `devrig backend @args.txt` with `--debug` in the file parses to
  `DevrigCliInvocation.debug = true` while the argv scan sees only `@args.txt` — verified: 0 bytes on
  stderr. Left as is deliberately: the parsed flag has no other consumer, `@argfile` is not a documented
  devrig invocation form, and the alternative is a logback `reset()` + `JoranConfigurator` re-read after
  parsing. Pick that up only if a real client trips on it.

## KtBlock compilation on Rider EAP

- [ ] **Every prompt fence fails to compile against the Rider EAP distribution.** The fence classpath
  includes the plugin's own sources, and `ij-plugin/.../execution/InspectionCrashIsolation.kt` imports
  `com.intellij.codeInspection.LocalInspectionTool` / `LocalInspectionToolSession`, which do not resolve
  there. The failure is per-IDE, not per-fence: on `execute-code-tool-description.md` all fences pass on
  Clion/ClionEap/Idea/IdeaEap/Pycharm/PycharmEap/Rider and all fail on RiderEap, so `:prompts:test` cannot
  go green for any article regardless of its content. Either gate the file behind the availability of the
  Java inspection API or keep it off the fence classpath.

## Keycloak semantic-ripple family

- [x] **Every ripple round measured before `5ae147d29` is WITHDRAWN — do not publish those numbers.**
  Builds up to and including `1028521545` (branch `whitepaper/ide-access`, revision `20c233760`) sent the
  case through the dpaia track's wrapper instead of `buildRipplePrompt`: the brief opened with "You are
  working on a Java Spring project" (12 occurrences in that build's log, 0 in `1032465247`), printed the
  FAIL_TO_PASS class and the whole hidden-consumer patch (80 `FAIL_TO_PASS` lines), and twice ordered a
  reactor-wide test run Keycloak cannot complete, while every environment paragraph — `./mvnw`, the JDK
  path, `-pl <module>` — was dropped. The headline ratio from that series (shell arm $2.89 / 47 turns /
  39 bash calls against the mcp arm's $1.42 / 24 turns) is the cost of that broken brief, not of IDE
  access: once both arms got the reviewed prompt the mcp arm's cost did not move ($1.42 → $1.44) while
  the shell arm's collapsed ($2.89 → $1.06). The same defect produced the "446 against a gold of 445"
  conservation miss in both arms. Cite those runs only as evidence about the harness.

- [x] **Pin-verify of the retargeted `rename-method-wide` numbers is DONE.** Run
  `run-20260816-185913-ripple-target-survey` (`-Dripple.survey.phases=text-ambiguity,decoys,pins`,
  local Docker, 3m15s wall) printed `[PIN-VERIFY] org.keycloak.models.KeycloakContext#setRealm:
  measured 496 references over 109 files, 37 decoys, 0 declarations of 'bindRealm', 0 files with a
  string literal naming it` against the pinned 496 / 109 / 37 — `tripwires PASS`. The same run's
  `DECOY_VERIFY` matched both change-signature pins (`Resource#getId` 1018 of 1022,
  `Attributes#contains` 18 of 20). So 496/109/37 is no longer a formula.
- [ ] **The 14-module compile gate of `rename-method-wide` still has no proven offline `test-compile`.**
  A module that fails there voids the arm rather than grading it. Unchanged by the retarget decision
  below (the target was KEPT, so the gate list is the one already pinned) — but it is a Maven run on
  an untouched tree, not a PSI query, and it has still never been made.
- [ ] **Run all eight ripple configurations on one revision.** The matrix currently spans `.655`–`.658`,
  so cross-scenario comparison is not valid. The configurations have no VCS trigger — they must be
  started by hand after a push to `jb`.
- [ ] **Regressions are now reported as UNKNOWN for every ripple arm** (the synthetic baseline is gone).
  If the family ever needs a real regression number, it needs a real pre-agent `fullSuiteSnapshot`, and
  the whole-reactor Keycloak suite does not fit the harness timeout — scope it to the touched modules
  plus their reverse dependencies first.
- [ ] **Re-run the family with the strengthened brief and check the mcp arm now uses its tools.** In
  `1032465247` the mcp arm made 38 Bash calls against 6 tool calls, i.e. it ran as a shell arm carrying
  MCP overhead, which is why the two arms tied on six of seven cases. The brief now announces that the
  tools exist and what class of question they answer (`## Available Tools`, mcp arm only) and warns both
  arms that a name match is not an identification. The check is the tool-call split per arm, not just
  SUCCESS: if the mcp arm still barely calls its tools, the paragraph is not the lever and the case
  design is.
- [ ] **Three repeats per configuration per revision.** `rename-type-wide` separated the arms twice on
  `.658` and tied on `de26f1999`; one run per configuration cannot tell "the case does not separate"
  from "this run got lucky".
- [ ] **Series in flight on `6c35a0d8c`: all seven cases × Claude AND × Codex** (builds
  `1032490553`…`1032490573`, `1032503275`…`1032503279`, started by hand — these configurations have no
  VCS trigger). The Codex half has never run before, so a failure there is as likely to be
  configuration as it is to be the case. Read the tool-call split per arm first; SUCCESS alone repeats
  the mistake of the `de26f1999` round.
- [ ] **`RIPPLE_IDE_CALL_SHARE_THRESHOLD` is deliberately null until the `6c35a0d8c` series is read.**
  Every arm now prints and persists its tool split (`[RIPPLE]   tools:` / `tool errors:` /
  `comparable:`) and the run summary carries a `ripple.comparability` object, but no run is judged:
  the mcp arm reports `UNKNOWN` because the threshold has no honest source yet. Take the number from
  the distribution of IDE-call shares across the fourteen in-flight builds — NOT from `1032465247`
  (6 of 44), which is one build and the very run the gate was invented to describe — then record in
  the constant's KDoc which builds were read and where in that distribution it sits. `UNKNOWN` also
  covers a run whose decoded transcript is missing, which must never be conflated with an arm that
  did not call the IDE.
- [x] **The ripple run summary is published AND read.** `writeArenaRunSummary` writes a second copy
  into the per-run directory that `TeamCityArtifactPostProcess.buildPublishTree` bundles, so the JSON
  leaves a CI build, and the ripple grade (`ripple_success`, compile gate, all-predicates,
  f1/recall/precision, extra predicates, gold pins) lives under a `ripple` key. `:experiments-report`
  now routes `ripple__*` into its own `ScenarioBucket.RIPPLE` (the ids read `ripple__keycloak__…`, so
  without an explicit route they matched no prefix and fell into `OTHER`), parses the `ripple` object
  and its nested `comparability` block, and `AgentRun.succeeded()` prefers `ripple_success` over the
  shared `objective_success` — which is exactly the trap that would have published
  `change-signature-wide` as a baseline success on the run where the baseline fails `P5_ARITY`.
- [ ] **The n=3 series itself has NOT been run.** `rippleSeries` (`experiments-report`) aggregates
  repeats off `InputReader.readAll().allBuilds` — never `latest`, which keeps one build per leg and
  would silently turn three repeats into a median of one — and renders a section of its own with, per
  arm: attempts used/total, ripple SUCCESS count, median and observed min…max of cost/turns/agent
  time, the token split into fixed overhead (cache-read + input) and work (output), every excluded
  attempt with its build id and reason, and one paired statement per case. The statement refuses to
  name a difference below three usable pairs or one whose paired range straddles zero. What remains is
  operational and cannot be done from a workstation session: start 3 repeats per (case × agent) on ONE
  revision on TeamCity by hand (these configurations have no VCS trigger), not in parallel, and point
  the collector at the resulting builds. Until those builds exist, every ripple leg reports
  `UNKNOWN` comparability and the report will say `insufficient repeats` — by design, not as a bug.
- [ ] **The fourteen builds in flight on `6c35a0d8c` have not been read into the report yet.** They are
  the baseline sample the comparability threshold is supposed to come from (see the
  `RIPPLE_IDE_CALL_SHARE_THRESHOLD` item above) and the first real input for the new ripple section.
  Note they predate the summary-publication change if their agents ran before it landed — in that case
  the tool split must still be read out of the `[RIPPLE]` lines in the build log, and the JSON path
  starts with the next series.
- [ ] **`P7_RECEIVER` / `P8_NO_SHIM` have never fired on a real run.** Both rename kinds now contribute
  them (`RippleTarget.extraPredicates`, parsed fail-fast by `parseReceiverPredicate` /
  `parseNoShimPredicate`), and the post-condition script reports `POST_RECEIVER_CHECKED / FOREIGN /
  UNQUALIFIED / UNRESOLVED` plus every foreign owner and every surviving old-name declaration, printed
  by `rippleStructuralPredicateDetail`. Only the fixture tests have exercised them. Read the FIRST
  failure by hand before treating it as a signal: an owner with no qualified name is an artifact of the
  key shape (already excluded), but an unexpected foreign owner may still be one. `P8_NO_SHIM` is the
  weaker of the two on purpose — agents rarely leave a forwarder — so a series where only P7 ever moves
  is the expected shape, not a defect.
- [x] **All three rename cases now carry a MEASURED text-ambiguity reading, and none of them is
  retargeted.** From `run-20260816-185913-ripple-target-survey`
  (`-Dripple.survey.phases=text-ambiguity`), as `textual | resolved | foreign call sites`:
  `rename-method-wide` `KeycloakContext#setRealm` **696 | 496 | 151**; `rename-type-wide`
  `org.keycloak.validate.ValidationContext` **593 | 198 | 74**; `rename-type-narrow`
  `org.keycloak.tests.utils.KeyUtils` **496 | 12 | 287**. All three DISCRIMINATE (textual strictly
  above resolved) and all three have a non-zero foreign CALL-SITE trap, so the retarget rule fires on
  none of them — `KeycloakContext#setRealm`, the suspect, turns out to have 151 foreign call sites a
  textual replacement would rewrite. In every case `resolvedReferences` came back equal to the pinned
  `expectedGoldReferences` (496 / 198 / 12), i.e. an independent query reproduced the gold pin.
  `RippleTextAmbiguityTest` now REQUIRES a measured pin with a foreign trap for every rename case, so
  a future target cannot be admitted on argument again.
  **Consequence for the plan:** the `de26f1999` tie on `rename-method-wide` is NOT explained by a
  target a text tool cannot get wrong. The remaining explanations are the ones step 1 is measuring —
  the mcp arm barely calling the IDE — or the agent doing the semantic work by reading files.
  Why the metric exists, kept for the next reader: a case used to be admitted on FAN-OUT (references,
  files, modules) and on same-named DECLARATIONS, and neither says a textual solution must fail — a
  declaration nobody calls is never touched by a replacement of call sites. The metric
  (`RippleTargetSurveyScripts.textAmbiguity`, survey phase `text-ambiguity`, parser
  `parseTextAmbiguity`) and its tripwire in `RippleCase.init`
  (`textualOccurrences <= resolvedReferences` rejects the case, naming the run) are what replaced
  that argument with a measurement. Never transcribe a number no run printed.
- [ ] **The final ripple verdict is UNDECIDED, and the rule for reaching it is now written down.**
  The publication contract lives in two mirrored places — `TEAMCITY-WHITEPAPER.md` §9f (§9f.1 why the
  cost criterion currently points against us, §9f.2 comparability, §9f.3 the withdrawn series, §9f.4
  case admission, §9f.5 what the report may say, §9f.6 the four kill criteria, §9f.7 forbidden moves,
  §9f.8 what is pending) and
  `docs/superpowers/specs/2026-08-16-keycloak-ripple-kill-criteria-and-publication-contract.md`.
  Both were written **before** the deciding series on purpose, and both carry an explicitly EMPTY
  result slot. The four outcomes are: (1) IDE really used, quality equal, cost lower → publish the
  intended claim with spread and exclusion count; (2) IDE really used, quality equal, cost still
  higher → publish the NEGATIVE result and move the claim to a task class where a shell physically
  cannot answer (call hierarchy through a generic interface, implementors, runtime state in the
  debugger) — never to "cheaper"; (3) baseline stably fails `P7_RECEIVER` / `P8_NO_SHIM` → headline
  moves back to correctness; (4) the mcp arm still does not call its tools → fix the CASE, not the
  brief. Forbidden, written down so it cannot be rationalised later: breaking the baseline with the
  prompt, narrowing the decoy set, publishing n=1, restoring the withdrawn pre-`5ae147d29` series as
  anything but harness evidence, tuning `RIPPLE_IDE_CALL_SHARE_THRESHOLD` after seeing which side of
  it a run falls on, weakening the oracle, and naming a difference that lies inside the observed
  spread. **Numbers on the record so far, all measured:** `de26f1999` equal quality, mcp $12.13 vs
  none $7.98 (+52 %), output tokens +62 %, turns near equal; `1032465247` mcp arm 6 tool calls
  against 38 Bash. **What must happen before this item can be closed:** read the fourteen
  `6c35a0d8c` builds and take the threshold from their distribution; run 3 repeats per (case × agent)
  on ONE revision; prove the offline `test-compile` of the 14-module compile gate; read the first
  real `P7_RECEIVER` / `P8_NO_SHIM` failure by hand. Then fill the result slot in BOTH documents with
  the build ids, medians, spreads, exclusions and the selected outcome number — and change nothing
  above it. If outcome 2 fires, raise the replacement showcase as a SEPARATE task; do not start it
  in this track.
- [ ] **Re-queue the checkpoint-probe cells already published as a zero under a dropped connection.**
  The probe now prints `LOST reason=api-transport-error` and fails the cell instead of publishing
  `Y=0` (`extractApiTransportError` → `DpaiaRunOutcome.apiTransportError` → the probe seam), but the
  verdicts recorded BEFORE that are already in the aggregate. The one confirmed by hand is build
  `1035679682` (`arm=none checkpoint=5 step=33 replicate=1`, `Y=0 usd=0.0672 agentSeconds=26
  tokens=0`, 26 s, 9 Reads, 0 Edits, exit 1). Re-read every published probe log for a
  `"model":"<synthetic>"` + `API Error:` turn or a top-level `"error":"server_error"`, drop those
  verdicts and re-queue the cells — a group short of five replicates renders INCOMPLETE until it
  fills, which is the correct intermediate state.
- [ ] **The capture arm still ignores the same signal.** The capture seam
  (`DpaiaFeatureService125CheckpointCaptureTest.afterAgentRun`, and `RippleScenarioBaseTest`) now sees
  `DpaiaRunOutcome.apiTransportError` but hands `admitCapture` only its primitives, so nothing acts on
  it: a capture whose stream was cut mid-response would be judged on a trajectory it never finished,
  and every probe cell of that arm would then start from states nobody meant to record. Decide what an
  aborted capture is before the next capture round.
