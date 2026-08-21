# Round 3 — does Residual Completion Work generalize across DPAIA Arena tasks?

**Everything above [Results](#results) is a PRE-REGISTRATION.** It was written and committed before any
round-3 TeamCity build was queued, and before a single round-3 probe verdict existed. The case list, the
primary metric, the layer taxonomy, the checkpoint-selection rule and the confirmation / refutation
thresholds are fixed here so that none of them can be chosen after the numbers are read. The results half
is appended later; anything that departs from this text is recorded in
[Deviations](#deviations-from-the-pre-registration) rather than silently applied.

## What rounds 1 and 2 settled, and what they did not

| | established | on what |
|:---|:---|:---|
| Round 1 ([RESIDUAL-DIFFICULTY.md](RESIDUAL-DIFFICULTY.md)) | `C(s)` = residual completion work is a usable progress metric: within-cell CV 0.19, monotone where `V` is not, still moving after `V` saturates. One decisive action per trajectory, after which finishing is ≈ 6× cheaper (24 190 → 4 017 output tokens) | one case, one capture per arm |
| Round 2 ([REPLICATION-2.md](REPLICATION-2.md)) | The collapse **replicates** on two fresh trajectories — 3.02× (mcp) and 2.87× (shell), `p ≈ 0.008`, disjoint bootstrap intervals, corroborated by tool calls (≈ 2×) and edits (≈ 18×) — while `V` sits at 1.00 everywhere and sees nothing | the same one case |
| Round 2 | The **causal** reading of the mcp advantage is **refuted**: measured in the model's own output tokens the ordering reverses (mcp 40 175 vs shell 25 176 to reach the decisive state), and the two states leave successors indistinguishable work (`p = 0.56`) | the same one case |

So the metric has four trajectories behind it and **one task**. Round 3 asks whether that is a property
of Residual Completion Work or a property of `dpaia__feature__service-125`.

### The three questions this round must answer separately

- **Q1 — metric validity.** Does RCW reproduce as a measure of semantic progress across different
  DPAIA Arena tasks?
- **Q2 — added information.** Does RCW carry information about the solution state that binary
  success does not?
- **Q3 — MCP trajectory effect.** Read through RCW, where do mcp and shell trajectories systematically
  differ: model compute, environment interactions, wall clock, or nowhere?

**Q3 is explicitly not the point of the round.** Round 2 refuted the model-compute claim on its own
pre-registered denominator; this round does not re-litigate it and does not select cases to recover it.
A round in which RCW behaves identically on both arms is a **positive** result for the metric, which is
what Q1 is about.

## Inherited unchanged from round 2

Nothing in the instrument is redesigned. What changes is the number of cases it addresses.

| piece | where | change in round 3 |
|:---|:---|:---|
| per-tool-call hook record, transcript JSONL, exact cumulative output tokens, a patch for EVERY step | `RippleCheckpointRecorder.kt` | none |
| capture admission | `RippleCaptureAdmission.kt` | none (`reference = null`, as in round 2) |
| probe cell: bare haiku, no MCP, blind continuation prompt, `ArenaVerifier.verify` grading, LOST handling | `RippleCheckpointProbeTest.kt`, `RippleCheckpointProbePrompt.kt` | the probed CASE is now resolved from the arm token |
| verdict line `[CHECKPOINT-PROBE] arm=… checkpoint=… step=… editFraction=… replicate=… Y=… usd=… agentSeconds=… tokens=…` | `RippleCheckpointProbeTest.kt` | **format unchanged** — it is the analysis pipeline's input |
| probe model | `claude-haiku-4-5`, asserted before the run is paid for | none |
| capture model | `claude-opus-5` (`DockerClaudeSession.DEFAULT_MODEL`) | none |
| case registry | **new** `RippleCheckpointCases.kt` | eight cases instead of a hardcoded one |

The probe build addresses a cell by three coordinates only — `ripple.checkpoint.arm`, `.index`,
`.replicate` — all declared in the separate `mcp-steroid-teamcity` DSL. Round 2 encoded the capture ROUND
into the arm token to avoid a cross-repo DSL commit; round 3 encodes the CASE the same way, for the same
reason. TeamCity forwards a prompted value that is not among its `select` options (round 1 proved it),
the verdict regex is `arm=(\S+)`, and round 1's and round 2's committed states stay byte-identical.

## The cases

Six, none of them the pilot. Selected before any round-3 build, on three axes at once: the **shape of the
change**, the **prior measured mcp-vs-shell outcome**, and **feasibility** (a case whose oracle cannot be
graded, or which no agent ever solves, cannot carry an RCW curve — that is why
`dpaia__spring__boot__microshop-18` is excluded below).

| # | case | shape of the change | `L_case` (gold layers) | gold patch | FTP | prior mcp/none | JDK | agent budget |
|--:|:---|:---|:---|--:|--:|:---|--:|--:|
| 1 | `dpaia__spring__petclinic-71` | repository-wide **architectural** refactor: JPA → R2DBC, every repository and entity in the tree | 5: config, persistence, transport, api, domain-model | 38 624 | 286 | **HIGH, mcp 0.64×** (3 passes) | 21 | 5 400 s |
| 2 | `dpaia__spring__petclinic__rest-37` | repository-wide **API/signature** change: `Pageable` through 6 REST controllers, the service interface and 3 repository implementations | 3: persistence, service, api | 38 789 | 352 | unknown | 21 | 1 800 s † |
| 3 | `dpaia__spring__petclinic-36` | **multi-layer feature**, small: add `Owner.email` through entity, 4 DB schemas and 3 templates | 3: schema, view, domain-model | 6 324 | 343 | **parity, 1.04×** (3 passes) | 21 | 900 s |
| 4 | `dpaia__empty__maven__springboot3-1` | **implementation from scratch**: whole JWT auth subsystem in a greenfield project, nothing to navigate | 7: config, persistence, service, transport, api, security, domain-model | 28 390 | 43 | **LOW, mcp 1.76×** — the worst mcp case in the set | 21 | 900 s |
| 5 | `dpaia__jhipster__sample__app-3` | repository-wide **rename with a large localization phase**, and the only cross-stack one: Java + liquibase CSV + YAML + Angular TS/HTML | 5: schema, config, domain-rules, api, view | 7 176 | 225 | **LOW, mcp 1.30×** | 21 | 900 s |
| 6 | `dpaia__feature__service-25` | **multi-layer feature** in the pilot's OWN repository, different task: self-referential release hierarchy | 6: schema, persistence, service, transport, api, domain-model | 17 400 | 94 | unknown | 24 | 900 s |

† `petclinic__rest-37` is the one case-config change this round makes: its `agentTimeoutSeconds` goes from
the 900 s default to 1 800 s. 23 files and 38 KB cannot land in 15 minutes, and a capture that times out
in both arms has no edit phase to cut checkpoints from. Recorded here because it is a deviation from the
configuration every earlier arena measurement of that case used, so its numbers are not comparable with
those. No other case config is touched, and no `mcpBenefit` value is changed anywhere.

### Why this set, and what it deliberately does not contain

**Diversity of the prior arm outcome is a selection criterion, in both directions.** The set holds one
case with a measured strong mcp advantage (#1, 0.64×), one at parity (#3, 1.04×), two where the shell arm
was measurably better (#4 at 1.76× — the worst mcp result ever recorded here — and #5 at 1.30×), and two
never run as a pair (#2, #6). Selecting only cases where mcp had won would make Q3 unanswerable and would
contaminate Q1, because "the states mcp passes through" would then be a biased sample of states.

**Diversity of change shape** covers the axes the round was asked for: repository-wide change (#1, #2,
#5), API/signature change (#2, and #1 at the repository-interface level), feature implementation (#3, #6),
several implementation layers (#1 five, #4 seven, #6 six), and a substantial localization/research phase
(#5, where the work is finding all 9 call sites across four languages, and #1, where it is finding every
blocking call site).

**Two requested axes are NOT covered, and pretending otherwise would be the easiest way to make this
round look better than it is:**

- **No bugfix.** The dpaia `java-spring-ee-dataset` has no bugfix category; all 154 entries are feature
  or refactoring tasks. This round therefore says nothing about RCW on defect repair.
- **No case with a genuinely low success rate.** Every candidate that historically fails is degenerate
  for a different reason (see below), so all six cases are ones a strong agent usually solves. If RCW
  turns out to work only on solvable tasks, this set cannot detect it.

**Excluded, with the reason recorded so the exclusion is auditable:**

| excluded | why |
|:---|:---|
| `dpaia__feature__service-125` | the pilot itself — it is the hypothesis under test, not evidence for generalization |
| `ripple__keycloak__rename-method-wide` | its solution is ATOMIC: the round-1 capture went from untouched tree to all 111 files renamed in one step, so it has no intermediate states to measure ([RippleCheckpointCase] documents the rejection) |
| `dpaia__spring__boot__microshop-18` | 1 success in 6 recorded runs; probes would return zero everywhere and a flat zero cannot be told apart from "readiness does not grow" |
| `dpaia__train__ticket-1` / `-31` | JDK 11, a toolchain no other case in the set uses; a capture failure there would be confounded with the toolchain |
| `dpaia__spring__petclinic__rest-3`, `dpaia__piggymetrics-6`, `dpaia__spring__petclinic__microservices-5`, `dpaia__spring__boot__microshop-2`, `dpaia__spring__petclinic-27`, `dpaia__spring__petclinic__rest-14` | viable, not selected — they duplicate a shape or an arm outcome already covered, and six cases is the budget |

## Metrics

### Primary outcome

`RCW_tokens(s)` = **cumulative output tokens** a downstream probe rollout emits between the restored
state `s` and its own termination, averaged over the rollouts that **finished**.

Read off the CLI's terminal `result` event, exactly as round 2 read it. Explicitly NOT
`endContextTokens`: that is the size of the conversation at the end of the run, it is dominated by the
cached prompt prefix, and on the pilot it moved 1.5× where the real work moved 6×.

### Secondary outcomes

| symbol | definition | role |
|:---|:---|:---|
| `RCW_tools(s)` | tool calls of the rollout | censoring-immune corroboration |
| `RCW_edits(s)` | Edit + Write calls | censoring-immune corroboration |
| `RCW_usd(s)`, `RCW_sec(s)` | cost and agent seconds | operational |
| `V(s)` | solved / (solved + failed), Wilson 95 % | **separate coordinate** |
| exit reason | solved / not-solved / budget-exhausted / tampered / LOST | censoring bookkeeping |

`V(s)` and `RCW(s)` are never combined into one number by formula. They are reported as a
two-dimensional point per state, which is what makes Q2 answerable at all.

### Censoring — fixed in advance

A CLI killed at the case's budget emits no terminal `result` event, so `usd` and the token counters are
`NA` for exactly the SLOWEST successful runs. The missingness is informative and biased against the
metric of interest, so:

1. Every cell reports how many of its successes are censored.
2. Every headline drop is recomputed with each censored success imputed at the **maximum** output-token
   count observed anywhere in the round; the claim must survive that substitution.
3. `RCW_tools` and `RCW_edits` are decoded from the transcript and exist even for killed runs — that is
   why they carry the corroboration role.
4. A timeout is **never** silently converted into a large numeric observation of `RCW_tokens`. It is a
   failure for `V` and a censored value for `RCW`, and the two are reported separately.
5. `LOST` — patch failed to apply, no grade produced at all, or an API transport abort — is withheld from
   both `V` and `RCW`, never published as a zero.

## The milestone rule

Anchored **outside both trajectories**, in the dataset's own gold `patch`, and computed by
[`data/round3/rcw_layers.py`](data/round3/rcw_layers.py). Neither arm's behaviour informs it.

Round 2 used a seven-layer taxonomy hand-written for one case. Six hand-written taxonomies would be six
free parameters, so round 3 inverts the construction:

1. ONE ordered, case-independent pattern list assigns every production path (anything under a
   `src/main/` root; tests and build output never count) to exactly one of eleven layers —
   `schema, config, domain-rules, persistence, service, transport, api, security, view, domain-model,
   other`, first match wins.
2. `L_case` = the layers that **that case's gold patch** touches. It is listed per case in the table
   above and is frozen by this document.
3. `layerCov(k) = |layers touched by state k ∩ L_case| / |L_case|`.
4. `fileCov(k) = |files touched ∩ gold files| / |gold files|` — the taxonomy-free control, reported
   alongside every `layerCov` statistic.

Patterns rather than gold paths, because an agent that solves a layer under its own file names must
count: round 1's shell arm renamed the migration and invented three classes a gold-path intersection
would never have seen.

Milestones, computed identically in both arms:

- `M0` — the first step whose tree differs from the pristine one (the first write);
- `Mmid` — the first step with `layerCov ≥ 0.5`;
- `Mlast` — the first step with `layerCov = 1.0`, i.e. **the step at which the last missing layer lands**.
  This is round 2's `Mapi` generalized: on the pilot the last layer to arrive was the api layer, which is
  not a fact that transfers, whereas "the last missing layer" is;
- `T` — the largest single-step increase in `layerCov`, ties to the earliest step. Reported everywhere,
  used as an anchor only when `layerCov` never reaches 1.0.

### The rule validated against rounds 1 and 2 before round 3 runs

The generalized taxonomy was run, blind, over the committed trajectories of the pilot. It recovers the
two states at which round 2 measured the collapse **without being told where they are**:

| trajectory | `Mlast` | round 2 published | agreement |
|:---|--:|:---|:---|
| capture 1 `mcp` | 19 | `Mfull` = 19 | exact |
| capture 1 `none` | 45 | `Mfull` = 45 | exact |
| capture 2 `mcp2` | **15** | `Mapi` = 15 — the 3.02× collapse is 14 → 15 | exact |
| capture 2 `none2` | **41** | `Mapi` = 41 — the 2.87× collapse is 40 → 41 | exact |

`|L_case| = 7` for the pilot under the general rule, the same count its bespoke taxonomy had.

`Mlast` is the quantity the checkpoint rule actually consumes, and it reproduces on every trajectory.
`T` is deliberately NOT tabulated here: it is an anchor only in the fallback branch where `layerCov`
never reaches 1.0, and on a trajectory that adds exactly one layer per write — which is what the pilot's
shell arm does — "largest single-step increase, ties to the earliest" degenerates to "the first write".
See [Deviations](#deviations-from-the-pre-registration) for the erratum this replaces.

That is a real check and not a self-congratulation: the taxonomy was written to cover six new repositories
whose layouts differ (petclinic keeps entities as `owner/Owner.java` with no `model` package; jhipster
puts half the change in Angular sources), and it was NOT tuned against any round-3 patch, none of which
exists yet.

## Checkpoint selection

Five states per trajectory, the same rule in both arms, computed from the capture's per-step patches
**before any probe of that case is queued**, and executable as `select_checkpoints()` in
[`rcw_layers.py`](data/round3/rcw_layers.py).

| id | state | what it is for |
|:---|:---|:---|
| `C1` | `M0` — the first write | early anchor, start of substantive work |
| `C2` | the recorded step nearest the MIDPOINT of the edit phase `[M0, last distinct]` | a positional anchor, deliberately independent of coverage — without it a trajectory that batches every layer into one write spends four of its five states in a two-step window |
| `C3` | the recorded step immediately before `Mlast`; if that is `M0` itself, the last PRE-WRITE state | immediately before the last layer lands |
| `C4` | `Mlast` (or, if `layerCov` never reaches 1.0, the earliest step holding the maximum coverage) | immediately after it |
| `C5` | the last step whose tree differs from the final tree | near-done anchor |

Two ids landing on the same step is **data** about the trajectory's shape, published as a collision and
probed once; the aggregator folds the shared verdict through `sameStateAs`, exactly as rounds 1 and 2 did.

**No downstream probe result may enter this rule**, and no checkpoint may be added or moved after any
RCW value for that case is known.

Applied retrospectively to round 2's full step artifacts, the rule selects `{13, 14, 15, 18, 23}` for
`mcp2` and `{16, 30, 40, 41, 44}` for `none2`. That is **every state round 2 probed, plus one**: round 2
measured four states per arm, and the extra one is the new positional anchor `C2` (step 18 and step 30),
which round 2 had no equivalent of. The rule therefore RECOVERS round 2's selection rather than
reproducing it exactly, and the difference is in the direction of measuring more of the trajectory.

## Probe protocol

Per checkpoint: **`n = 5`** rollouts of a bare `claude-haiku-4-5`, no MCP in either arm's probe, the same
blind continuation prompt round 2 used, graded by the same `ArenaVerifier.verify` the capture was graded
by. The probe never learns which arm the state came from.

Five and not twenty: round 1 measured within-cell CV 0.19 on `RCW_tokens`, so five rollouts already detect
a 30 % shift, while the binary `V` would need `n ≈ 27` to separate 0.75 from 1.00. **The replicate count
is not raised automatically.** If a case shows a small effect with large variance, the required additional
`n` is COSTED and reported, and the decision to spend it is taken separately.

## Analysis plan

Per case, per trajectory:

1. `step → RCW`, `cumulative upstream output tokens → RCW`, `upstream tool calls → RCW`,
   `semantic milestone → RCW`. Monotonicity is not required — `RCW(s_{t+1}) > RCW(s_t)` after a harmful
   edit is a valid observation and potentially strong evidence FOR the metric.
2. `ΔRCW_i = RCW(s_i) − RCW(s_{i+1})` between adjacent measured states, with bootstrap intervals. For
   every large `|ΔRCW|`, the corresponding stretch of the source transcript is opened and **described
   from what it actually shows**. No explanation is written that the trace does not support; a large
   `ΔRCW` whose trace shows nothing structural is reported as unexplained.
3. Construct validity: for every pair of states with `V(s_i) ≈ V(s_j)` (both Wilson intervals
   overlapping, in practice both saturated at 1.00), count how often `RCW(s_i)` and `RCW(s_j)` differ by
   more than their bootstrap intervals. This is the direct measurement of Q2.
4. Arms, only after 1–3: `upstream output tokens → RCW`, `upstream tool calls → RCW`,
   `upstream seconds → RCW`, per case and pooled.
5. Cross-case summary answering Q1, Q2, Q3 separately.

Statistics as in round 2: two-sided permutation tests on the difference of means, 100 000 relabelings,
bootstrap 10 000 resamples, Wilson 95 % for `V`, seed `20260821`.

Every individual rollout is published as a row (`data/round3/rollouts-r3.csv`), every checkpoint as a row
(`checkpoints-r3.csv`), every upstream step as a row (`upstream-r3.csv`). Round 1 and round 2 datasets are
**not** modified.

## Pre-registered decision rule

### Q1 — the main hypothesis is SUPPORTED if, on a MAJORITY of the six cases (≥ 4 of 6), all of:

1. `RCW` changes substantially over the trajectory — the largest measured ratio between two of a
   trajectory's states is **≥ 2×** with non-overlapping bootstrap intervals;
2. the largest `|ΔRCW|` of the trajectory coincides with a structural change in the solution state that
   the source trace independently shows (layer landing, integration, a harmful edit) — coincides meaning
   the same step, or the adjacent step with the trace showing why;
3. later / more complete states generally have lower `RCW`: Spearman of cell-mean `RCW_tokens` against
   `layerCov` is **negative** on both arms of the case;
4. `RCW` distinguishes at least one pair of states that binary success calls equal (both `V` saturated,
   bootstrap intervals of `RCW` disjoint);
5. the effect appears in at least one censoring-immune proxy (`RCW_tools` or `RCW_edits`) in the same
   direction;
6. the effect survives the worst-case censoring imputation of the metrics section.

The result is called **especially strong** if 1–6 hold on both the mcp and the shell trajectory of the
same case, because that is evidence about the metric rather than about the tool.

### Q1 — the hypothesis is REFUTED, and the branch stops, if ANY of:

- `RCW` fails to track semantic progress on a majority of the new cases;
- the whole result is carried by one case;
- `RCW_tokens` agrees with NO independent work proxy (neither tools nor edits nor USD nor seconds);
- between-rollout variance within a cell is comparable to or larger than the progress being measured
  (median within-cell CV ≥ the median between-state relative difference);
- censoring makes more than half the checkpoints incomparable;
- the checkpoint rule cannot be applied arm-independently on a majority of cases.

A negative result is written up in this document with the same care as a positive one, and the round
stops rather than scaling.

### Q3 — reported, never optimized for

No threshold, no pass/fail. The three efficiency readings (model output tokens, tool calls, wall clock)
are tabulated per case with their sign. Round 2's finding — mcp behind on output tokens, far ahead on tool
calls and wall clock — is the prior; a repetition of it across cases is an interesting result in its own
right, and a reversal is equally publishable. **No case may be dropped, and no metric added, because of
which way Q3 comes out.**

## Budget and gates

| item | unit cost | count | cost | build-hours |
|:---|--:|--:|--:|--:|
| `hookPreflight` (instrument re-proof, once) | ≈ $0 | 1 | ≈ $0 | ≈ 1 |
| Opus captures, 2 per case | ≈ $2–8 | 12 | ≈ $50 | ≈ 20 |
| Haiku probes, ≤ 5 states × 5 replicates × 2 arms | ≈ $0.7 | ≤ 300 | ≈ $200 | ≈ 250 |
| **total** | | **≤ 313** | **≈ $250** | **≈ 270** |

- **Gate 0 — comparability.** The two arms of one case must differ ONLY in MCP access: same model, same
  prompt, same dataset revision, same verifier, same budget, same image build. Verified per case before
  its probes are queued.
- **Gate 1 — after each case's two captures, before that case's probes.** Both captures must be admitted,
  have an edit phase (`M0 < n`), produce per-step hook records and a transcript, and yield at least three
  DISTINCT states under the checkpoint rule. A case that fails Gate 1 is reported as a failed capture and
  its ≈ $27 of probes are not spent.
- **Gate 2 — after the first two cases complete their probes.** If neither shows criterion 1 of the Q1
  rule, the round stops there rather than buying four more cases of the same answer.

Cases are probed in a fixed order so that a partial round is still interpretable, and the order is set
before any result exists: **#3 `petclinic-36`, #6 `feature-service-25`, #4 `springboot3-1`,
#5 `jhipster-3`, #2 `petclinic-rest-37`, #1 `petclinic-71`** — cheapest and most different first, the
5 400-second case last.

## Addressing

Arm tokens, globally unique, so a probe cell needs no fourth coordinate:

| case | resource dir | mcp arm | shell arm |
|:---|:---|:---|:---|
| `dpaia__feature__service-125` (rounds 1–2, untouched) | `feature-service-125` | `mcp`, `mcp2` | `none`, `none2` |
| `dpaia__spring__petclinic-71` | `petclinic-71` | `pc71-mcp` | `pc71-none` |
| `dpaia__spring__petclinic__rest-37` | `petclinic-rest-37` | `pcr37-mcp` | `pcr37-none` |
| `dpaia__spring__petclinic-36` | `petclinic-36` | `pc36-mcp` | `pc36-none` |
| `dpaia__empty__maven__springboot3-1` | `springboot3-1` | `sb31-mcp` | `sb31-none` |
| `dpaia__jhipster__sample__app-3` | `jhipster-3` | `jh3-mcp` | `jh3-none` |
| `dpaia__feature__service-25` | `feature-service-25` | `fs25-mcp` | `fs25-none` |

Every build id is recorded in [RUN-IDS.md](RUN-IDS.md) as it is queued — the token has no
`TAG_BUILD`/`COMMENT_BUILD` permission, so that file is the only link between a build and the cell it
measured.

## Deviations from the pre-registration

**D1 — erratum in the round-1/2 validation table, corrected before any round-3 verdict was read.**
As first committed, the "rule validated" table carried a `T` column reading `mcp` 19 (+0.571),
`none` 25 (+0.286), `mcp2` 14 (+0.857), `none2` 40 (+0.714), and the checkpoint section claimed the rule
reproduces round 2's probed sets exactly. Both were computed against the **committed checkpoint
directories**, which hold only the 4–10 states round 2 chose to probe — not the full per-step artifacts.
Re-run against the complete captures:

- `T` for `none2` is step 16 (+0.143), not step 40. Every write in that arm adds exactly one of seven
  layers, so "largest increase, earliest tie" lands on the first write. This matches `REPLICATION-2.md`'s
  own text and changes no selected state, because `T` is only an anchor when `layerCov` never reaches
  1.0. The `T` column has been withdrawn from the table rather than restated.
- the retrospective selection is `{13, 14, 15, 18, 23}` and `{16, 30, 40, 41, 44}` — round 2's states plus
  the new `C2` positional anchor, which round 2 did not have. The text now says "recovers, plus one"
  instead of "precisely".

`Mlast`, the quantity the rule consumes, reproduced exactly on all four trajectories and is unchanged.
No case, metric, checkpoint rule or decision threshold moved.

**D2 — verdict-parsing regex widened, before any round-3 probe ran.** Round 2's extractor matched the
arm token with `arm=(\w+)`, which cannot match a hyphen. Every round-3 token contains one (`pc36-mcp`,
`sb31-none`, …), so the round-2 script would have silently found zero verdict lines in all ~300 round-3
probe logs and reported an empty dataset rather than an error. The round-3 extractor uses `arm=(\S+)`,
which is what the format section of this document already specified. No Kotlin change: the plugin never
parses the token back.

**D3 — the case coordinate did not reach the capture builds; twelve captures re-run.** The first batch
was queued with `-P ripple.checkpoint.capture.case=<case>`, which the build configuration does not
forward: its Gradle step is templated as `-Pripple.checkpoint.capture.method=%…%` and nothing else, so
TeamCity accepted the parameter, dropped it, and every build fell back to the default case. All thirteen
were green with complete, well-formed artifacts; they had simply captured `feature-service-125`. The
`[ARENA]` summary is what exposed it — a build queued as `petclinic-36` reported implementing a release
status machine. Nine were cancelled, six had finished; ≈ $25 and ≈ 4 build-hours lost, and none of those
six is used, because a pilot trajectory stored under an arm token that means another case would corrupt
the pilot's own dataset.

The fix keeps the selection in this repository rather than in the TeamCity DSL repo: the case travels
inside the forwarded parameter as `<case>:<method>`, the same device the arm token already uses for the
probe side. Two regression tests pin it. **This is an infrastructure fix, not a design change** — no
case, metric, milestone rule, checkpoint rule, replicate count or decision threshold moved, and the
correction was made before any round-3 checkpoint was selected or any probe queued.

The general lesson is recorded because it will recur: **on this harness a green build is not evidence
that the intended thing was measured.** Every capture is now checked against the case it was queued for
by reading the agent's own summary and the arena instance id, before its probes are bought.

**D4 — Gate 1 applied per ARM, not per CASE.** As written, Gate 1 fails a case unless BOTH of its
captures yield three distinct states, and a failed case buys no probes. Applied literally, four of the
five cheap cases would have been dropped whole. The reason they fail is one-sided and is itself the
round's first finding: the mcp arm lands the entire change in one or two writes, while the shell arm of
the same case walks through five to eighteen distinct trees. Dropping those cases would discard four
perfectly measurable shell trajectories to punish their mcp partners.

So the gate is applied per arm: an arm with ≥ 3 distinct states is probed, an arm below it is not. The
decision was taken from the capture artifacts alone, before any probe of those arms was queued, and it
is one-directional — it can only ADD arms relative to the literal rule, never remove one that would
have qualified. The cost is to Q3, not Q1: on `petclinic-36`, `feature-service-25`, `jhipster-3` and
`petclinic-rest-37` only one arm is measured, so those cases cannot contribute a within-case mcp-vs-shell
RCW comparison. That restriction is stated again wherever Q3 is answered.

**D5 — "distinct" resolved as distinct TREE, not distinct step number.** The pre-registration says
collisions collapse through `sameStateAs` but does not say when. Round 3 forced the question: `jh3-mcp`
records steps 7, 8 and 9 with byte-identical patches — one write followed by three build runs — and
`fs25-mcp` does the same at 12 and 13. Counting step numbers would have called those four and three
states and bought 15–20 probe cells to measure one or two trees. Distinctness is therefore evaluated on
patch text at selection time, in `gate1_r3.py` and `commit_checkpoints_r3.py` alike, with the earliest
step carrying a tree as its representative. This only ever reduces spend and never changes which STATES
are measured.

**D6 — criterion 6 was implemented against the wrong quantity; fixed after the numbers were read, and
the fix is reported here because of that.** The analyzer evaluated "the effect survives the worst-case
censoring imputation" by applying criterion 1's 2× threshold to the `Mlast` TRANSITION ratio. That is a
different claim, and it produced a reading that cannot be right: three arms with **zero** censored
successes were marked as not surviving censoring — `sb31-none` among them, whose ratio after imputation
is arithmetically identical to before it, because there is nothing to impute. A criterion that fails on
data it does not apply to is measuring the threshold, not the censoring.

Corrected to: not applicable when criterion 1 did not hold (no effect to survive, and a second failure
for one cause would double-count it); trivially true when no success was imputed; otherwise the SAME
pair of states criterion 1 named must still clear the threshold under substituted means.

**This changed the verdict, so the direction of the change matters.** Before: 0 of 5 cases met all six.
After: 1 of 5 (`springboot3-1`). The pre-registered bar is 4 of 6, so **the round's conclusion is
unaffected either way** — the fix makes the negative result less severe, not more convincing, and it
leaves every genuinely censored case (`feature-service-25` 2.97 → 1.85) failing exactly as before. The
bug and its correction are recorded rather than quietly patched because the fix was made with the data
in hand, which is precisely the situation pre-registration exists to police.

## Results

### Stage 1 — captures and Gate 1

Twelve captures on `6cf948cc8`, one per arm, each verified against the case it was queued for by reading
the arena instance id and the agent's own summary of what it implemented. All twelve solved their task,
so `V` at the final state is 1.00 everywhere and every trajectory is a SUCCESSFUL one — the same
condition rounds 1 and 2 measured under.

| arm | steps | distinct trees | `M0` | last distinct | states selected | Gate 1 |
|:---|--:|--:|--:|--:|:---|:---|
| `sb31-mcp` | 16 | 9 | 7 | 13 | 7, 10, 12, 13 | PASS |
| `sb31-none` | 16 | 10 | 5 | 13 | 5, 9, 12, 13 | PASS |
| `pc36-mcp` | 17 | **2** | 12 | 11 | 11, 12 | **FAIL** |
| `pc36-none` | 20 | 5 | 11 | 15 | 11, 12, 13 | PASS |
| `fs25-mcp` | 16 | **3** | 12 | 13 | 11, 12 | **FAIL** |
| `fs25-none` | 42 | 18 | 10 | 38 | 10, 24, 30, 31, 38 | PASS |
| `jh3-mcp` | 12 | **3** | 7 | 9 | 6, 7 | **FAIL** |
| `jh3-none` | 25 | 8 | 13 | 18 | 13, 15, 16, 17, 18 | PASS |
| `pcr37-mcp` | 24 | 5 | 8 | 20 | 7, 8, 20 | PASS |
| `pcr37-none` | 10 | **3** | 7 | 7 | 6, 7 | **FAIL** |
| `pc71-mcp`, `pc71-none` | | | | | | pending |

**The first result of round 3 is about trajectories, not about RCW: three of five mcp arms have no
measurable interior, against one of five shell arms.** The `distinct trees` column is the evidence and
it is not a selection artifact — it counts every tree the whole capture passed through, independent of
which states the rule picked. With semantic IDE access Opus batches the change into one or two writes
(`jh3-mcp`: 12 steps, 3 trees, the complete cross-stack rename landing at step 7); the same model on
shell tools walks the same task through 8 to 18 trees.

This is consistent with, and sharpens, round 2's `Q3` finding. "MCP reduces environment interactions"
also means **MCP produces coarser trajectories**, and a coarser trajectory has fewer intermediate states
to measure — which is a limitation of what can be observed about an mcp run, not a defect of the metric.
It also means the mcp arm is structurally harder to instrument for any progress metric that reads the
work tree, RCW included.

Two further observations from the captures, both recorded before any probe verdict:

- **`petclinic-rest-37`'s gold patch is not a faithful description of its task.** The dataset entry is
  38 KB across 23 file headers, but the file list repeats — the real change is one paginated endpoint,
  and both arms solved it with a ≈ 2.7 KB patch touching only the `api` layer. `layerCov` therefore
  never exceeds 0.333 in either arm and `Mlast` is unreachable, so `C4` falls to the coverage-peak
  fallback. The case is retained with its shell arm failing Gate 1, and its `L_case` is flagged as
  overstated wherever it is used.
- **`petclinic-36` and `jhipster-3` have short edit phases** (5 and 8 trees on the measured arm), so
  their curves have three to five points rather than the pilot's ten.

### Stage 2 — probes

120 rollouts across 24 cells, 6 arms, 5 cases. 115 solved, 1 budget-exhausted, 4 tampered, **0 LOST**;
15 rollouts censored (a success with no terminal `result` event). Raw data:
[`data/round3/rollouts-r3.csv`](data/round3/rollouts-r3.csv) (one row per rollout),
[`checkpoints-r3.csv`](data/round3/checkpoints-r3.csv) (one row per cell),
[`upstream-r3.csv`](data/round3/upstream-r3.csv) (one row per captured step),
[`summary-r3.json`](data/round3/summary-r3.json) (the criteria, evaluated mechanically).

| case | arm | state | `layerCov` | `V` | `RCW_tokens` mean | bootstrap 95 % | tools | edits |
|:---|:---|--:|--:|--:|--:|:---|--:|--:|
| `springboot3-1` | `sb31-mcp` | 7 | 0.14 | 1.00 | 11 049 | 7 484 – 15 982 | 33.2 | 13.8 |
| | | 10 | 0.57 | 1.00 | 5 870 | 5 272 – 6 634 | 25.4 | 7.2 |
| | | 12 | 0.86 | 1.00 | 7 934 | 6 319 – 9 747 | 30.8 | 6.0 |
| | | 13 | **1.00** | 1.00 | **4 155** | 3 705 – 4 650 | 27.2 | 2.8 |
| | `sb31-none` | 5 | 0.00 | 1.00 | 12 971 | 8 950 – 16 992 | 36.8 | 15.0 |
| | | 9 | 0.29 | 1.00 | 7 955 | 5 455 – 11 232 | 31.8 | 7.8 |
| | | 12 | 0.57 | 1.00 | 3 825 | 3 470 – 4 255 | 23.2 | 2.4 |
| | | 13 | 0.71 | 1.00 | **3 191** | 2 703 – 3 621 | 22.2 | 1.0 |
| `feature-service-25` | `fs25-none` | 10 | 0.17 | 1.00 | 11 266 | 10 614 – 11 918 | 53.4 | 13.2 |
| | | 24 | 0.67 | 1.00 | 9 728 | 8 571 – 10 699 | 44.6 | 7.8 |
| | | 30 | 0.83 | 1.00 | 8 186 | 7 174 – 9 199 | 40.2 | 5.6 |
| | | 31 | **1.00** | 1.00 | 6 167 | 5 260 – 6 660 | 35.8 | 4.0 |
| | | 38 | 1.00 | 1.00 | **3 794** | 3 374 – 4 163 | 26.0 | 0.0 |
| `petclinic-36` | `pc36-none` | 11 | 0.33 | 1.00 | 8 715 | 3 071 – 11 959 | 47.0 | 10.0 |
| | | 12 | 0.67 | 1.00 | 5 520 | 3 422 – 7 693 | 37.0 | 4.6 |
| | | 13 | **1.00** | 1.00 | 4 197 | 3 715 – 5 030 | 30.0 | 0.0 |
| `jhipster-3` | `jh3-none` | 13 | 0.20 | 0.80 | 6 647 | *n = 1* | 36.3 | 6.5 |
| | | 15 | 0.80 | 1.00 | 5 098 | 4 585 – 5 611 | 28.0 | 0.2 |
| | | 16 | 0.80 | 1.00 | 4 744 | 4 108 – 5 380 | 27.2 | 0.0 |
| | | 17 | **1.00** | 1.00 | 4 446 | 3 700 – 5 192 | 29.4 | 0.4 |
| | | 18 | 1.00 | 1.00 | 5 549 | 4 165 – 7 362 | 40.8 | 0.4 |
| `petclinic-rest-37` | `pcr37-mcp` | 7 | 0.00 | 1.00 | **4 023** | 3 594 – 4 479 | 16.0 | 2.2 |
| | | 8 | 0.33 | 1.00 | 6 563 | 4 452 – 10 451 | 24.8 | 4.0 |
| | | 20 | 0.33 | 1.00 | **8 600** | 8 415 – 8 784 | 33.0 | 5.0 |

### The pre-registered decision

| case | 1 ≥ 2× disjoint | 2 Δ at milestone | 3 Spearman < 0 | 4 separates `V`-ties | 5 proxy agrees | 6 survives censoring | all six |
|:---|:--|:--|:--|:--|:--|:--|:--|
| `springboot3-1` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | **YES**, both arms |
| `feature-service-25` | ✅ 2.97× | ❌ | ✅ −0.97 | ✅ 8/10 pairs | ✅ | ❌ 2.97 → 1.85 | no |
| `petclinic-rest-37` | ✅ 2.14× | ✅ | ❌ **+0.87** | ✅ | ✅ | ✅ | no |
| `petclinic-36` | ❌ 2.08×, CIs overlap | ✅ | ✅ −1.00 | ❌ | ✅ | — | no |
| `jhipster-3` | ❌ 1.49× | ✅ | ✅ −0.53 | ❌ | ❌ | — | no |

**`petclinic-71` could not be measured.** Both captures ran correctly inside the container — `n = 37`,
admitted, 37/37 hook records, transcript published, per-step patches exported — but TeamCity published
only the `video` directory as a build artifact; the `checkpoints` directory never left the agent. This is
an artifact-publication failure specific to the 5 400-second case, not a capture failure, and it means
the round's one strong-mcp-advantage case has no data. Recorded as an open item, not as a null result.

**Q1 is therefore NOT supported.** The rule required all six criteria on ≥ 4 of 6 cases. One case of five
measured meets them. Even a perfect `petclinic-71` could not have reached four. **The pre-registered
stopping criterion applies and the branch stops here rather than scaling to more cases.**

### What actually happened, case by case

Reading the shape rather than the verdict, because the verdict compresses five different behaviours into
one word.

**`springboot3-1` — the pilot's result, reproduced on a different task and on both arms.** The mcp
trajectory falls 11 049 → 4 155 (2.66×, disjoint intervals), the shell trajectory 12 971 → 3 191 (4.07×).
The mcp arm's largest single drop, 7 934 → 4 155 (1.91×, `p = 0.008`), lands exactly on the step where
`layerCov` reaches 1.0, and the trace says what that step is: a `Write` adding `application.properties`,
the last of the seven gold layers. Edits corroborate at 2.14× (`p = 0.016`). `V = 1.00` in all eight
cells — binary success sees nothing at all across a 2.7-to-4-fold change in the work remaining.

**`feature-service-25` — the cleanest monotone curve of the round, defeated by censoring.** Five states,
`RCW` 11 266 → 9 728 → 8 186 → 6 167 → 3 794, Spearman −0.97, tools 53.4 → 26.0, edits 13.2 → 0.0. The
step-31 `Edit` that takes coverage to 1.0 adds the controller layer. But 5 of its 21 rollouts are
censored and they concentrate in the LATE cells (2 of 3 successes at step 31), so worst-case imputation
pulls the 2.97× down to 1.85× and criterion 6 fails as written. The effect is probably real; the
pre-registration's own conservative rule says this dataset cannot establish it, and that ruling stands.

**`petclinic-rest-37` — `RCW` increases along the trajectory, and this is the round's most interesting
observation.** From the PRISTINE tree a probe finishes in 4 023 tokens; from the state after Opus writes
the endpoint, 6 563; from twelve steps later, 8 600 — a 2.14× increase with disjoint intervals. The task
is genuinely small (one paginated endpoint, a 2.7 KB final patch), so a weak agent solves it from scratch
more cheaply than it can read, trust and finish someone else's half-built version. The trace supports
this directly: after step 8 the tree does not change for thirteen consecutive steps while Opus runs the
test suite. **`RCW` is measuring something real here — an inherited-context cost — and it is not
"progress".** The pre-registration anticipated `RCW(s_{t+1}) > RCW(s_t)` as valid data, and this is the
first case where it dominates a whole trajectory.

**`petclinic-36` — right direction, insufficient power.** 8 715 → 5 520 → 4 197, Spearman −1.00, but the
first cell's bootstrap interval is 3 071 – 11 959. Three states and `n = 5` cannot separate a 2.08×
effect against that variance. This is a power failure, not a contradiction.

**`jhipster-3` — nearly flat, and the one case where the trace does not support the metric.** 6 647 →
5 098 → 4 744 → 4 446 → 5 549, a 1.49× total swing that ends by going back UP. The task is a
repository-wide rename: the agent's step-17 `Write` adds the Liquibase changelog and completes coverage,
but a rename has no "integration layer" whose arrival changes how hard finishing is — the remaining work
after any of these states is to run the build and fix stragglers, which costs roughly the same
everywhere. `RCW` correctly reports that there is no transition, which is a defensible reading, but the
case contributes no evidence that `RCW` tracks progress.

### Q2 — construct validity, the round's strongest result

`V = 1.00` in **23 of 24 cells**. The single exception, `jh3-none` step 13, is 0.80 on one graded
rollout. Binary success is saturated on every case, exactly as it was in rounds 1 and 2.

Against that, `RCW` separates **21 of 43** pairs of `V`-tied states at disjoint bootstrap intervals:
8/10 on `feature-service-25`, 6/6 on `sb31-mcp`, 5/6 on `sb31-none`, 2/3 on `pcr37-mcp`, 0/3 on
`petclinic-36`, 0/10 on `jhipster-3`. On the two cases with clean curves it separates nearly every pair
that pass/fail calls identical — including states 3.0× apart in the work a downstream agent needs.

**This holds regardless of the Q1 verdict**: whatever `RCW` is measuring, it is not what the binary
verifier measures, and the binary verifier is blind on all five tasks.

### Q3 — mcp versus shell, reported without a thesis

Only `springboot3-1` has both arms measured (D4), so this is one case, not five.

| | mcp | shell |
|:---|--:|--:|
| upstream output tokens at `layerCov` = 1.0 | 16 382 | never reached (0.71 at end) |
| upstream tool calls at that point | 13 | 13 |
| upstream wall clock | 181 s | 134 s |
| distinct trees over the capture | 9 | 10 |
| `RCW` at the last measured state | 4 155 | 3 191 |

On this greenfield task the mcp arm is **behind on every axis** — more output tokens, the same tool
count, more wall clock — which is what its historical `mcpBenefit = LOW` (1.76×) predicted and what
round 2's refutation of the model-compute claim would lead one to expect. There is no navigation to
amortize when the repository is empty.

The Gate-1 table is the more general Q3 observation, and it points the other way: across five cases the
mcp arm needed 12–17 steps where the shell arm needed 16–42, and **produced 2–9 distinct trees where the
shell arm produced 3–18**. MCP compresses the trajectory. For an operator that is a win; for anyone
measuring internal progress it is a problem, because the states simply are not there to measure.

The two arms also solved `springboot3-1` **differently**: mcp built the gold architecture (`UserRepository`
+ JPA entity), shell used an `InMemoryUserDetailsService` and never touched two of the seven gold layers
while still passing the verifier. That is a real architectural divergence, and it exposes a limitation of
the layer taxonomy — first-match-wins put shell's `security/User.java` in `security` rather than
`domain-model`, so its `layerCov` caps at 0.71 for a solution that is complete.

### Threats to validity, stated plainly

1. **Five cases, one arm each on four of them.** D4 bought coverage at the cost of within-case arm
   comparison.
2. **`n = 5`.** Adequate for a 3× effect (the pilot's), underpowered for the 1.5–2× effects that turned
   out to be typical. `petclinic-36` fails on variance, not on direction.
3. **Censoring is not random.** 15 of 120 rollouts, concentrated in late cells on `fs25-none` and
   `jh3-none`, i.e. exactly where the metric is smallest. The worst-case rule is deliberately brutal and
   it broke the one otherwise-clean case.
4. **`layerCov` depends on a taxonomy.** It reproduced round 2 exactly and needed no per-case tuning, but
   `sb31-none` and `pcr37` both show it can cap below 1.0 for a complete solution.
5. **Gold patches are not always the task.** `petclinic-rest-37`'s 38 KB entry describes a 2.7 KB change.
6. **`petclinic-71` is missing**, and it is the one case selected for a strong mcp advantage.

### Answers

**Q1 — does RCW reproduce as a measure of semantic progress across tasks? Partially, and not enough to
call it general.** It reproduces convincingly on 2 of 5 tasks (`springboot3-1` on both arms,
`feature-service-25` up to a censoring rule), is directionally right but underpowered on a third, is flat
on a fourth, and **inverts on a fifth**. The pre-registered bar of 4 of 6 is not met and the branch stops.
The direction is more robust than the magnitude: Spearman against layer coverage is negative on 4 of 5
cases and exactly −1.00 on three of them. What does not generalize is the pilot's *shape* — a single
large collapse at one decisive step. That shape appeared on `springboot3-1` and nowhere else.

**Q2 — does RCW add information over binary success? Yes, unambiguously, and this is the durable
result.** `V` is saturated at 1.00 in 23 of 24 cells across all five tasks; `RCW` separates 21 of 43
`V`-tied state pairs at disjoint intervals, with ratios up to 3.0×. Every task in this round is one where
pass/fail says all states are equally good and RCW says they are not. That claim does not depend on Q1:
even the inverted `petclinic-rest-37` distinguishes states that `V` calls identical.

**Q3 — how do mcp and shell differ through RCW? On the one case with both arms, mcp is behind on model
compute and wall clock and level on tool calls — no advantage, on a task chosen because it never had
one.** The systematic difference visible across all five cases is not efficiency but **granularity**: mcp
trajectories are shorter and pass through far fewer distinct states, to the point where three of five mcp
arms had no measurable interior at all. Round 2's finding that MCP does not reduce model-output compute
is not contradicted here, and nothing in this round supports the reverse.

### What follows

Per the pre-registered stopping rule, **do not scale this design to more cases.** Two specific things
would have to change first, and both are cheap relative to another six-case round:

- **Power.** `n = 5` was calibrated on a 3× effect. The effects that occur are 1.5–2×. Either raise `n`
  on a small number of states or stop treating the 2× bar as the definition of "substantial".
- **Case admissibility.** Three of five mcp arms and one shell arm had too few distinct states to
  measure, and one case's gold patch misdescribed its task. A cheap pre-screen — capture first, count
  distinct trees, only then buy probes — is already implemented as `gate1_r3.py` and should gate case
  SELECTION, not just spending.

The `petclinic-71` artifact-publication failure should be fixed regardless: it is the only case in the
catalog with a measured strong mcp advantage, and it is currently unmeasurable.
