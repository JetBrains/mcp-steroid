# TODO — TeamCity coverage audit follow-ups (0.102 release validation, 2026-07-31)

Source: adversarially-verified TC-coverage audit run during the 0.102 release validation
(10 confirmed gaps, 0 refuted). Fixed since: GAP-1 (Windows devrig-test leg) and GAP-6
(VCS triggers on the gate configs) in `mcp-steroid-teamcity@7b7ec80`; validation-run test
failures [#406](https://github.com/jonnyzzz/mcp-steroid/issues/406)–
[#410](https://github.com/jonnyzzz/mcp-steroid/issues/410) all fixed and closed 2026-08-01;
the IDE-matrix debt they unblocked was burned down via
[#412](https://github.com/jonnyzzz/mcp-steroid/issues/412) (2026-08-03/04: CompatTests lane
9/9 green, main matrix down to flake-classification — see the issue for the full history).

## Remaining gaps (product repo)

- **GAP-2 (important, PARTIALLY addressed) — rolling IDE channels slide silently.** Every
  "stable"/"eap" surface (prompts KtBlock matrix via `ideDownloadSpecs`, the 8 TC PromptTest
  legs via `-Pmcp.prompts.ide.filter`, test-integration's `IdeDistribution.Latest`) resolves
  the products-API channel with no expected-major assertion; the downloader CLI does not even
  accept a version flag. Already fired twice: "stable" slid 2026.1 → 2026.2 on 2026-07-16
  (broke #409 and the 262 classpath allowlist), and the post-release eap gap served expired
  EAP builds ("PyCharm EAP Build Expired", #412). The expiry half is FIXED structurally —
  `IdeReleaseLookup` now resolves EAP as the newest available build across eap+release types —
  but the original ask stands: an optional `expectedMajorPrefix` on the channel-resolution
  path, failing loudly when the resolved major changes, so a channel cut becomes a deliberate
  single-place edit. This WILL recur at 263 (also budget a `UNLOADED_CONTENT_MODULES_IU_263`
  set in `ContentModuleClasspathTest` — see the symptom table in `ij-plugin/CLAUDE.md`).
- **GAP-3 (important) — buildSrc tests never run.** 7 test files (incl.
  `ClassFileVersionScannerTest`, which guards the `verifyClassFileVersions` release gate)
  are never compiled nor run by any CI. Fix: wire
  `gradle.includedBuild("buildSrc").task(":test")` into `ciBuildPluginTests` and buildSrc
  `testClasses` into `compileAllClasses`.
- **GAP-8 (minor) — `:npx` has zero real CI coverage.** Its `test` script is just a build.
  Fix: add `:npx:check` to `ciDevrigTestTaskPaths`; longer term a real smoke test.
- **GAP-9 (minor) — `:test-integration-agent-launch:test` double-covered.** Swept into all
  3 per-OS `ciBuildPluginTests` legs (downloads the real Claude build inside the unit
  matrix) while also having dedicated AgentLaunchTests coverage. Fix: add the module to
  `nonPluginTestSubprojects`, or document the duplication with an explicit `require()`.
- **GAP-10 (minor) — release smoke matrix runs only from laptops.**
  `testReleaseSmokeMatrix` / `testManagedBackendsTart` have no TC config. Optional:
  manual-trigger TC config mirroring `_06`'s scaffolding; evaluate tart on the Mac agent.

## Remaining gaps (mcp-steroid-teamcity repo)

- **GAP-4 (important) — `TestExperimentsBuild` has an empty `steps{}`.** 21 of 57
  test-experiments classes appear in no TC config. Fix: run the deterministic subset
  (ArenaPromptContract, DpaiaConfig, Extract*Metrics, …) in the placeholder; keep heavy
  manual experiment classes out but list them in a SettingsTest known-uncovered whitelist.
- **GAP-5 (important) — SettingsTest drift checks cover only 4 of 9 spec families.**
  Keycloak (18 configs), SSR (4), YouTrackDb (4), IdePower (8), AndroidStudio (2) have no
  existence/drift validation. Fix: extend `main-test.kt` mirroring
  `testBrightScenarioTestClassesExist`.
- **GAP-7 (minor, product half DONE) — live-network feed tests invoked by nothing on TC.**
  The opt-in tasks now exist and are documented in the root `CLAUDE.md` CI section
  (`:npx-kt:liveNetworkTest`, `:npx-kt:liveDownloadSmokeTest`,
  `:intellij-downloader:liveNetworkTest`), with the offline `AllIdeProductsDownloadTest`
  running by default. Remaining: a small scheduled TC config (weekly, Linux) that runs the
  cheap live-network pair so vendor-feed drift is caught between releases.

## Infra notes from the 0.102 validation runs (updated 2026-08-04)

- **Mac agent: Maven Central 429 (Too Many Requests)** killed one `ij-plugin test
  (Mac aarc64)` leg before tests started — same "Gradle exception" signature as the May
  0.96-era failures; the very next run passed once the dependency cache warmed. If it
  recurs, consider a repository mirror or a BuildFetch cache warm-up for the Mac agent.
- ~~TestIntegrationBuild can never show SUCCESS while Gemini skips count as failures~~ —
  FIXED (#408, `JUnit38AssumeSupportRunner`): the Gemini legs now report as ignored.
- Scheduling landscape (implemented in `mcp-steroid-teamcity`, see its `CLAUDE.md`): gate
  configs run per-push via VCS triggers; the heavy matrices run in the Sunday-03:00
  `WeeklyAllTests` composite (PromptTest, TestIntegrationBuild lane=main at 480 min,
  CompatTests lane on a fresh agent). Nothing heavy fires per-push by design.
