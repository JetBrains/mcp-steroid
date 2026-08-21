# TODO DPAIA

- [ ] Keep `TASKS.md` as the active task list (open tasks only).
- [ ] Record durable DPAIA findings in `docs/autoresearch-findings.md` (synthesized takeaways) — the old
  repo-root `MEMORY.md` running handoff was distilled into the docs + `git log` and removed.
- [ ] After each measured DPAIA iteration, record the hypothesis, changed files, validation command, and metric delta.
- [x] Measure the corrected `steroid_apply_patch` prompt on `DpaiaPetclinicRest37Test.claude with mcp` and compare native Edit count against the 2026-04-26 baseline of 2.
- [x] Tighten DPAIA verification guidance to reduce duplicate Maven/Bash runs while preserving 184/184 pass behavior.
- [x] Measure the DPAIA verification-guidance tweak on `DpaiaPetclinicRest37Test.claude with mcp`; target Bash <=2, Edit 0, apply_patch true, 184/184 tests.
- [x] Add a prompt regression test for the DPAIA arena MCP block after the verification-guidance measurement.
- [x] Run 3-agent review for the next low-hanging fruit after arena prompt regression; consensus is to fix global apply-patch prompt-resource routing before Gradle-resource work.
- [x] Measure the dedicated apply-patch routing resource change on `DpaiaPetclinicRest37Test.claude with mcp`.
- [x] Add disk-persistence integration tests for `steroid_apply_patch` success/failure cases.
- [x] Pick the next Gradle DPAIA scenario and measure it before changing Gradle guidance.
- [x] Add a real IntelliJ Ultimate monorepo `thisLogger` lookup regression test using `Observation.awaitConfiguration` plus `smartReadAction`.
- [x] Update MCP server/resource indexing guidance to use `Observation.awaitConfiguration(project)` plus `smartReadAction { }` instead of treating `waitForSmartMode()` as a stable handoff.
- [x] Tighten Gradle/JDK prompt guidance so DPAIA agents use the configured JDK path before the first Bash Gradle call.
- [x] Fix the `ExceptionCaptureService` null-parameters crash observed during the green IntelliJ monorepo `thisLogger` lookup.
- [x] Review IntelliJ checkout ZIP/cache precedence so `MCP_STEROID_INTELLIJ_CHECKOUT_DIR` does not silently lose to an older cached TeamCity ZIP when a local checkout was explicitly configured.
- [x] Investigate the remaining severe Kotlin FIR resolve logs observed during the green IntelliJ monorepo `thisLogger` lookup.
- [x] Add decoded-log regression coverage so Microshop-2 MCP runs do not use Java 21 or wildcard JAVA_HOME assignments for Gradle.
- [x] Add a Gradle-focused MCP prompt resource modeled after the Maven patterns.
- [x] Measure the Gradle-focused MCP prompt resource on `DpaiaMicroshop2Test.claude with mcp` against the 136s JDK-fixed baseline.
- [x] Review the Microshop-2 measurement and improve Gradle resource discovery/routing so agents actually fetch or receive `mcp-steroid://skill/execute-code-gradle` before falling back to Bash Gradle.
- [x] Rerun `DpaiaMicroshop2Test.claude with mcp` after Gradle resource routing; target full-suite pass and `fetch_resource_calls >= 1` for `mcp-steroid://skill/execute-code-gradle`.
- [x] Add result-boundary guidance for `steroid_execute_code` build results with `errors=false, aborted=true`, because prompt-only routing still produced 0 `steroid_fetch_resource` calls.
- [x] Rerun `DpaiaMicroshop2Test.claude with mcp` after result-boundary guidance; target full-suite pass and `fetch_resource_calls >= 1`.
- [x] Run 3-agent review of the failed fetch-resource boundary measurement and select the next low-hanging correction.
- [x] Make aborted-build guidance name Claude's exact `mcp__mcp-steroid__steroid_fetch_resource` tool and render on its own line.
- [x] Rerun `DpaiaMicroshop2Test.claude with mcp` after the explicit boundary hint; target `fetch_resource_calls >= 1`.
- [x] Run 3-agent review of the explicit-hint failure and choose inline Gradle sync guidance versus removing/replacing the failed fetch-only hint.
- [x] Update Gradle arena/resource guidance so agents use the now-working IDE-native Gradle build/sync path instead of Bash compile fallback.
- [x] Rerun `DpaiaMicroshop2Test.claude with mcp` after the Gradle guidance update; result: fewer native Read/Glob/Bash calls, no build abort, and no tool errors.
- [x] Run 3-agent review of the Gradle guidance measurement and choose the next low-hanging correction.
- [ ] Reduce native source discovery/read calls in Gradle DPAIA prompts/resources with a batched IDE/VFS `steroid_execute_code` recipe.
- [ ] Cap the size of build output an arena agent carries in its context. Measured on
  `dpaia__spring__petclinic-71` pass 3 (Codex, mcp): a single unfiltered `./mvnw test` returned **410 KB**
  in one tool result, a targeted run another 240 KB, and re-sending those blobs on every later request
  drove 21.5M input tokens against 32.6k output — ~$14 for one arm, versus $2.06 for the same scenario's
  pass 2. Cost here is context replay, not work done, and it is not MCP-specific (the `none` arms of the
  same scenario cost $11.92 and $11.66). Prompt-side fix, deliberately deferred: the whitepaper's
  Round 3 data is pinned against the current prompt, so changing it splits the dataset across two prompts.
- [x] Run the solution-readiness checkpoint pilot on TeamCity — done over two rounds, 137 probe builds
  and 4 measured captures. Round 1: `docs/ripple-checkpoint-pilot/RESULTS.md` +
  `RESIDUAL-DIFFICULTY.md`. Round 2 (independent replication, per-step upstream work instrumented):
  `docs/ripple-checkpoint-pilot/REPLICATION-2.md`. Provenance for every build id: `RUN-IDS.md`.
- [x] Decide the scale-up — **decided against it**, by the pre-registered rule. The 500-run design was
  meant to test `V_MCP(x) > V_shell(x)`; round 1 already showed `V` saturates at 1.00 and cannot rank
  states at `n = 5`, and round 2 showed the residual-work metric that CAN rank them puts the two arms in
  the same place once the denominator is the model's own output tokens (mcp 40 175 vs shell 25 176 to the
  same milestone; indistinguishable residual work afterwards, `p = 0.56`). Spending ≈ $340 on 500 runs of
  a comparison whose sign flips with the choice of x-axis is not worth it. The follow-ups that ARE worth
  it are in `TODO.md` (a round-trip-limited comparison, and reusing `C(s)` as an instrument).
- [x] Decide what to do with two statements in `RCW-GENERALIZATION.md` that its own frozen code does not
  reproduce — **both corrected in the document as deviation D1**, before any round-3 verdict was read.
  The `T` column was withdrawn from the validation table rather than restated (it is an anchor only in
  the fallback branch, and on a trajectory that adds one layer per write it degenerates to the first
  write), and the retrospective-selection claim now reads "recovers round 2's states, plus the new
  positional `C2`" instead of "precisely". `Mlast`, the quantity the rule consumes, reproduced exactly on
  all four pilot trajectories and is unchanged.
- [x] **Round 3 — does RCW generalize across DPAIA cases? Answered: NO at this sample, and the branch
  stops by its own pre-registered rule.** Six cases selected for diversity of change shape AND of prior
  mcp-vs-shell outcome, pre-registered in `docs/ripple-checkpoint-pilot/RCW-GENERALIZATION.md`; 12
  captures, 120 probe cells, ≈ $55. One of five measured cases (`springboot3-1`, on BOTH arms) meets all
  six criteria against a bar of four of six. The pilot's *shape* — one large collapse at a decisive step
  — did not transfer: `feature-service-25` is a clean monotone 2.97× defeated by the censoring rule,
  `petclinic-36` is directionally right but underpowered at `n = 5`, `jhipster-3` is flat, and
  `petclinic-rest-37` **inverts** (a weak agent finishes that small task more cheaply from the pristine
  tree than from Opus's half-built endpoint — an inherited-context cost, not progress). Q2 is the durable
  result: `V` = 1.00 in 23 of 24 cells while RCW separates 21 of 43 `V`-tied state pairs.
- [ ] Fix the `petclinic-71` capture artifact publication. Both round-3 captures (1037547376 /
  1037547378) ran correctly — `n = 37`, admitted, 37/37 hook records, transcript, 37 step patches — but
  the build published only the `video` directory, so the `checkpoints` directory never left the agent and
  the case could not be probed. Specific to the 5 400-second case; the other ten published normally.
  It is the ONLY case in the catalog with a measured strong mcp advantage (0.64×), so it is the one whose
  absence most limits any future arm comparison.
- [ ] Before any further RCW round, fix the two design faults round 3 exposed, both cheap relative to
  another six-case sweep. (1) **Power**: `n = 5` was calibrated on the pilot's 3× effect; the effects that
  actually occur are 1.5–2×, and `petclinic-36` fails on variance rather than direction. (2) **Case
  admissibility**: three of five mcp arms had too few distinct work trees to measure at all —
  `gate1_r3.py` already computes this and should gate case SELECTION, not just spending. Related: mcp
  trajectories are systematically COARSER (2–9 distinct trees against shell's 3–18), which is a real
  finding about MCP and a structural obstacle to instrumenting mcp runs with any work-tree metric.
- [ ] Generalize the arena prompt's Docker escape hatch from a whitelist of literal error strings
  (`Could not find a valid Docker environment`, `BadRequestException`, `HTTP 400`, `docker.sock`,
  `DockerClientException`) to any Docker/Testcontainers infrastructure failure.
  `DockerProcessStartException` (Docker Compose absent) fell outside the list on
  `dpaia__spring__petclinic-27`, so the agent withheld the success marker with all 38 targeted tests
  green. Harness-side this is now covered — such tests are red in the pre-agent baseline too, so they no
  longer count against the agent — but the prompt still sends it hunting. Deferred with the item above.
