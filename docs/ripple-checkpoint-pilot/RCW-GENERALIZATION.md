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

The generalized taxonomy was run, blind, over the four committed trajectories of the pilot. It
reproduces the hand-authored round-2 table exactly, and it recovers the two states at which round 2
measured the collapse **without being told where they are**:

| trajectory | `T` (Δ`layerCov`) | `Mlast` | round 2 published | agreement |
|:---|:---|--:|:---|:---|
| capture 1 `mcp` | 19 (+0.571) | 19 | `T` = 19, `Mfull` = 19 | exact |
| capture 1 `none` | 25 (+0.286) | 45 | `T` = 25, `Mfull` = 45 | exact |
| capture 2 `mcp2` | 14 (+0.857) | **15** | `Mapi` = 15 — the 3.02× collapse is 14 → 15 | exact |
| capture 2 `none2` | 40 (+0.714) | **41** | `Mapi` = 41 — the 2.87× collapse is 40 → 41 | exact |

`|L_case| = 7` for the pilot under the general rule, the same count its bespoke taxonomy had.

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
RCW value for that case is known. Applied retrospectively to round 2 the rule selects
`{13, 14, 15, 23}` for `mcp2` and `{16, 40, 41, 44}` for `none2` — precisely the states round 2 probed.

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

*(none yet — appended as they occur, before the affected numbers are read wherever possible)*

## Results

*(appended after the builds; nothing above this line changes)*
