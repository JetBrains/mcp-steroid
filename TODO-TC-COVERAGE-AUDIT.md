# TODO — TeamCity coverage audit follow-ups (0.102 release validation, 2026-07-31)

Source: adversarially-verified TC-coverage audit run during the 0.102 release validation
(10 confirmed gaps, 0 refuted). Two gaps were fixed immediately in `mcp-steroid-teamcity`
commit `7b7ec80` (Windows devrig-test leg = GAP-1; VCS triggers on the gate configs = GAP-6).
Test failures found by the validation runs are tracked as issues
[#406](https://github.com/jonnyzzz/mcp-steroid/issues/406)
[#407](https://github.com/jonnyzzz/mcp-steroid/issues/407)
[#408](https://github.com/jonnyzzz/mcp-steroid/issues/408)
[#409](https://github.com/jonnyzzz/mcp-steroid/issues/409)
[#410](https://github.com/jonnyzzz/mcp-steroid/issues/410).

## Remaining gaps (product repo)

- **GAP-2 (important) — rolling IDE channels slide silently.** Every "stable"/"eap" surface
  (prompts KtBlock matrix via `ideDownloadSpecs`, the 8 TC PromptTest legs via
  `-Pmcp.prompts.ide.filter`, test-integration's `IdeDistribution.Latest`) resolves the
  products-API channel with no expected-major assertion; the downloader CLI does not even
  accept a version flag. Already fired once: "stable" slid 2026.1 → 2026.2 on 2026-07-16,
  which is what broke #409. Only `McpSteroidIdeTargets` pins majors (`262-EAP-SNAPSHOT`) and
  rejects rolling tags. Fix: optional `expectedMajorPrefix` on the channel-resolution path,
  failing loudly when the resolved build's major changes, so a channel cut becomes a
  deliberate single-place edit. Then update `docs/262-EAP-PLAN.md` remaining-gaps note.
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
- **GAP-7 (minor) — `:intellij-downloader:liveNetworkTest` invoked by nothing.** Fix: small
  scheduled TC config (weekly, Linux) hitting the live JetBrains products API.

## Infra notes from the 0.102 validation runs

- **Mac agent: Maven Central 429 (Too Many Requests)** killed the
  `ij-plugin test (Mac aarc64)` leg before tests started — same "Gradle exception" signature
  as the May 0.96-era failures; the agent's dependency cache is cold. Consider a repository
  mirror / `--refresh-dependencies` retry policy for the Mac agent, or the BuildFetch cache
  warm-up.
- **TestIntegrationBuild can never show SUCCESS on TC** while the Gemini skip contract
  surfaces as 6 failures — see #408. 108/108 real tests passed on `bf19795c`.
- PromptTest heavy matrix (60–120 min) and TestIntegrationBuild stay manually triggered on
  purpose; consider a nightly `schedule` trigger once #406/#409 are fixed so history builds
  up without cost surprises.
