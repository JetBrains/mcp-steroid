# TASKS

Open tasks only. Finished work lives in git history, the per-module `CLAUDE.md`/`AGENTS.md`, the PR
descriptions, and the auto-memory. The full historical running log (through 2026-06-19, ~4k lines across
the installer, npx-kt stabilization, prompts, apply-patch, managed-backend, EAP, and cleanup epics) was
moved to **[TASKS-archive.md](TASKS-archive.md)** — consult it (or `git log`) for the detail behind any
shipped item.

Discipline (standing rule): per change — design → implement → 0 WARNING+ IDE inspections
(`CodeSmellDetector` via `steroid_execute_code`) → 3× adversarial quorum before committing. Commits as
`Eugene Petrenko`, no AI co-author.

---

## Installer / website epic — remaining

Epic delivered via PRs #117–#125 (PR #113 closed as superseded). See the `installer-epic` memory for the
full delivered list. Still open:

1. **Cut a devrig release from `main`.** The LIVE installer is broken on all platforms until then: the
   published `v0.100` binary predates the `install devrig` command (mac/linux: "no such option
   --install-script") and the Windows pathing-jar fix (windows: "input line is too long"). The new binary
   is already proven working (Mac/Linux live-install + the Mac-hosted Windows e2e). Follow
   `release/release-instructions.md`.
2. **Advance the `website` branch to `main` after that release** (`release-instructions.md` → "Stage 7c").
   This is what publishes the new install scripts to the live site; do it only once the matching release
   exists.
3. **Block S — daily vendor-JDK drift detection.** A scheduled job to catch when Corretto/Azul publish new
   JDK 25 builds or change shas. ⚠️ The version on `docs/devrig-install-oneliner` /
   `deferred/website-devrig-install-cta` is OBSOLETE (uses the deleted `CoordinateResolver`/`ResolverMain`);
   reframe around the live `:installer-gen:generateJdkModel` (it PGP-verifies on every run).
4. **Install one-liner surfacing.** Document the `curl … | sh` / `irm … | iex` one-liner (docs) + a
   devrig-first homepage CTA (website). Salvage the CONTENT from `docs/devrig-install-oneliner` +
   `deferred/website-devrig-install-cta` — their code halves are stale; do NOT merge the branches.
5. **Synchronous beacon flush for short-lived `install` subcommands.** `DevrigBeacon.capture()` is async
   (`scope.launch`), so a fast-exiting `install` / `install devrig` can exit before the "install executed"
   event sends. Add a synchronous capture + flush for all `install` subcommands.

---

## test-integration / IDE

6. **test-integration JDK pre-config is too slow (likely sleeps).** The per-test IDE JDK setup
   (`mcpRegisterJdks` / `waitForProjectReady`) feels sleep-bound. Quantify the time in JDK pre-config first
   (log timestamps), then replace blocking fixed sleeps / coarse poll intervals with event-driven waits.
   Suspects: `mcp-steroid.kt:664/731/800` (`delay(2_000L)`), `:544` (`delay(500L)`),
   `IdeTestHelpers.kt:152/161` (`Thread.sleep(50)`), `intelliJ-container.kt:307/315/354/384/415` poll loops.

7. **DialogKiller hang on macOS/Docker (in progress).** `VisionService.capture` stalls while a modal
   `DialogWrapper` is showing (both the killer's pre-close screenshot and `closeDialog` go through
   `Dispatchers.EDT + ModalityState.any()`, never pumped → modality never resolved → downstream + MCP hang).
   Goal: the killer must reliably close all dialogs and restore `ModalityState.nonModal()`; the screenshot
   is secondary and must NEVER block that. Instrumentation in `2e26ec6a` + debug-port in `11bec066`. Next:
   read the screenshot-vs-close run log, make the screenshot best-effort/off the critical path, and/or fix
   `VisionService.capture` to not stall under a modal.

8. **devrig binary end-to-end console test (managed-backend self-install).** New console test exercising the
   whole flow an external user/agent hits — no pre-provisioned IDE: clone Keycloak (cached bare repo) →
   `devrig install claude` → run the agent with a deterministic find-usages task → devrig fetches + starts
   the managed IDE → opens the project → asserts the expected usage count. Each step visible in the console.
   Prereq: managed-backend path health — `DevrigManagedBackendGui` / `DevrigRealIdeBridge` / `DevrigAgent`
   currently FAIL (see `test-integration/TODO-stability-report.md`); devrig JVM must launch on Java 25.

---

## Prompts corpus

9. **IMPROVEMENTS.md harness rollout (separate PR).** The two-task prompt + per-agent reflection harness
   (landed on `FindDuplicatesPromptTest`) is generic; extend it across every `:test-integration` prompt-style
   test. Audit candidates (`ReferencesSearchPromptTest`, `FilenameIndexPromptTest`, `PsiClassLookupPromptTest`,
   `MavenRunnerAdoptionTest`, `ResourceReadingTest`, `WhatYouSeeTest.toolPreference`); add Task-2 reflection +
   snapshot the `<<<IMPROVEMENTS>>>` block; promote `extractImprovementsBlock`/`saveImprovements` to a shared
   helper; cadence 5×Claude→3×Codex→3×Claude→3×Codex; update CLAUDE.md when done (drop the
   "currently wired into FindDuplicatesPromptTest" caveat).

10. **Reflection audit follow-up (issue #33).** Replace the two reflection-using `kotlin` recipes with typed
    APIs (research in `~/Work/intellij` first, then land each as its own KtBlock + in-process tests):
    - `lsp/hover.md` (lines 88, 93) — `javaClass.methods.find{"getType"}?.invoke(...)`. Typed: Kotlin
      `KtProperty.typeReference?.text` / `KtParameter.typeReference?.text` / analysis-API
      `KaSession.getReturnKtType`; Java `PsiVariable.type.canonicalText`.
    - `lsp/signature-help.md` (lines 82, 88, 96, 112) — same pattern, 4 sites in one snippet wrapped in a
      failure-hiding `try/catch`. Typed: Kotlin `KtNamedFunction.valueParameters`/`.typeReference`; Java
      `PsiMethod.parameterList.parameters`/`.returnType`.

---

## Release / cross-cutting

11. **Release independence disclosure.** Every distributed artifact + public surface must state that MCP
    Steroid and Devrig are independent open-source projects, NOT made by / affiliated with JetBrains:
    website (footer/about), READMEs, docs, plugin (Marketplace + `plugin.xml` vendor + in-IDE notice), devrig
    `--version`/`--help` banner + dist `licenses/README` + package metadata, release notes, EULA. Add a
    release-checklist item + a build-time guard/test so a release can't ship without it. (Backlog; wording TBD.)

12. **devrig ↔ plugin protocol forward-compat.** Additive-only wire contract. Done: (1) contract test
    `DevrigToolBridgeClientTest`, (3) `ij-plugin/CLAUDE.md` "devrig ↔ plugin wire contract" rule, (4) audit of
    the shared `@Serializable` DTOs. Still open:
    - (2) **cross-version test** — devrig HEAD ↔ an older plugin build (and vice-versa); only meaningful once
      there is a baseline release to pin against.
    - (5) **devrig-owned DTOs** — give devrig its own copy of the marker + bridge request/result DTOs (today it
      reuses `mcp-steroid-server`'s classes via tolerant decode) so the two evolve independently and only the
      JSON wire shape is shared.
