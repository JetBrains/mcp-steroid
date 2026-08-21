# Replication 2 — is the early residual-work collapse an effect of semantic IDE access?

**This document's design half is written and committed BEFORE any round-2 build is queued.** Everything
above [Results](#results) is a pre-registration: hypotheses, metrics, the milestone rule, the checkpoint
selection rule and the decision thresholds are fixed here so that they cannot be chosen after the
verdicts are read. The results half is appended later, and any deviation from this text is recorded as a
deviation rather than silently applied.

Round 1 ([RESIDUAL-DIFFICULTY.md](RESIDUAL-DIFFICULTY.md)) established two things on the case
`dpaia__feature__service-125`:

- **residual completion work** `C(s) = E[downstream work | s]` is a usable progress metric — within-cell
  CV 0.19 against a binary `V` that 5 replicates quantise in steps of 0.2, monotone where `V` is not,
  and still moving after `V` saturates at 1.00;
- each trajectory contains ONE decisive action after which finishing becomes ≈ 6× cheaper: the mcp
  capture reached it at `editFraction` 0.4 / tool call 19, the shell capture at 0.8 / tool call 49, and
  in both arms the action is the same kind of action — the last missing implementation layer landing.

It established nothing about causation. `n = 1` trajectory per arm, and the mcp trajectory happened to
write its layer in one batched `steroid_execute_code`. Round 2 answers exactly one question:

> Does semantic IDE access cause the agent to reach low-residual-work states earlier, or did round 1
> observe two unusually different trajectories?

The unit of replication therefore changes: it is now the **source Opus trajectory**, not another Haiku
rollout from a checkpoint that already exists.

## Hypotheses

| | statement | what would show it |
|:---|:---|:---|
| **H0** | The round-1 gap is variance between independent Opus trajectories, or a property of one run's action style (a single giant batched write). | capture 2 reverses or erases the ordering; or the two mcp captures disagree with each other as much as the two arms disagree |
| **H1** | mcp systematically reaches a low-residual-work state earlier, under several honest upstream denominators. | both mcp traces collapse earlier than both shell traces on tool calls **and** cumulative Opus output tokens, with the collapse coinciding with the same semantic milestone |

`V(s) = P(solve | s)` stays a **separate coordinate**. It is reported with Wilson intervals and is never
merged with `C(s)` by formula — round 1 showed it cannot separate 0.75 from 1.00 at `n = 5`
(`n ≈ 27` would be needed), so it carries no weight in the decision.

## Gate 0 — comparability audit

Run before anything was queued. A replication that silently changes the model, the prompt or the grading
is not a replication, and mixing such captures would be worse than not running them.

| check | method | result |
|:---|:---|:---|
| Model | `DockerClaudeSession.DEFAULT_MODEL` | `claude-opus-5` — unchanged from round 1 |
| Revision | `git log` in `worktree-semantic-ripple-pilot` | `HEAD = cd646b679`, the round-1 capture revision; branch NOT rebased |
| Working tree | `git status` | only round-1 documentation and data are modified; no production or harness Kotlin file differs from `cd646b679` |
| Prompt | `DpaiaScenarioBaseTest` / `DpaiaRunSeams` prompt path | untouched at `cd646b679` (implied by the previous row, verified by file list) |
| Grading | `ArenaVerification.kt`, `arena-overlays/dpaia__feature__service-125.patch` | untouched; the overlay adds `ReleaseApiSecuritySliceTest` to FAIL_TO_PASS, which is why the verifier grades **5** classes while the upstream dataset lists 4 |
| Dataset entry | `raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json` | `base_commit = e5a4623e8aa7b7d41485b0095e0ae9c38133d7a2`, identical to the commit the round-1 capture logs record; gold `patch` 44 568 chars over 13 production files |
| Case config | `DpaiaCuratedCases.CASE_CONFIGS["dpaia__feature__service-125"]` | `agentTimeoutSeconds = 1800`, `projectJdkVersion = "24"`, `dockerOracleWorks = true` — unchanged |

**Verdict: Gate 0 passes.** The one deviation that cannot be prevented is recorded rather than hidden:

> **Known deviation — agent CLI version.** The Claude CLI is installed by `npm install -g` behind a daily
> cache-bust in the Docker image build, so round 2 will run a NEWER CLI than round 1's
> `claude-code/2.1.197`. Both round-2 arms share ONE image build, which keeps the within-round contrast
> clean; the cross-round contrast (capture 1 vs capture 2) therefore carries a CLI difference and is
> quantified, not assumed away, by three drift-control probes that re-run a capture-1 state now.

*Annotation added after the captures, the pre-registered text above left as written:* the deviation did
not materialise. Both round-2 captures resolved `claude-code/2.1.197` — the very version round 1 ran — so
the rounds are comparable on the agent CLI after all. The drift-control probes were consequently not
queued; see [Deviations](#deviations-from-the-pre-registration).

Baseline environment recorded from the round-1 capture logs, for the drift comparison:

| | capture 1 (round 1) | capture 2 (round 2) |
|:---|:---|:---|
| Agent CLI | `claude-code/2.1.197` | `claude-code/2.1.197` — **identical**, the feared drift did not happen |
| MCP Steroid plugin | `0.101.672-jb-4e6c047` | `0.101.679-jb-9e5a174` |
| IDE | `2026.2.1` | `2026.2.1` |
| Model (capture) | `claude-opus-5` | `claude-opus-5` |
| Model (probe) | `claude-haiku-4-5` | `claude-haiku-4-5` |
| Capture build ids | `1035363501` (mcp), `1035363503` (shell) | `1037157415` (mcp2), `1037157425` (none2) |

### What capture 1 cannot tell us, and why round 2 is instrumented

The honest upstream denominator this round needs — cumulative Opus **output** tokens before a state — is
**not recoverable from capture 1**. Its streamed log carries `assistant` events whose `usage` objects are
partial: summing `output_tokens` over all 111 assistant events of build `1035363501` gives **642**
tokens, while the terminal `result` event reports **59 164** for the same run. The per-message usage in
the stream is a running partial, not a per-step increment.

Consequences, both pre-registered:

1. Round 2's captures record per-step upstream work at capture time (hook stdin record per tool call,
   the CLI transcript JSONL published as an artifact, and a patch for EVERY step, not only the grid).
2. Capture 1's denominator **B** stays `NA` unless a character-based proxy — computed on capture 2
   alongside the exact per-message usage — correlates well enough per step to be applied backwards. If
   it does not, capture 1 keeps `NA`. No imputation, in either direction.

## Metrics

### Downstream — the outcome

Over probe rollouts that **finished**; failures are never converted into numeric completion costs.

| symbol | definition | role |
|:---|:---|:---|
| `C_tokens(s)` | mean cumulative **output** tokens of a successful probe rollout (`[ARENA] Tokens in/out`) | **primary** |
| `C_tools(s)` | total tool calls of the rollout | censoring-immune corroboration |
| `C_edits(s)` | Edit + Write calls | censoring-immune corroboration |
| `C_usd`, `C_sec` | cost and agent seconds | secondary, operational |
| `V(s)` | solved / (solved + failed), Wilson 95 % | separate coordinate, not a decision input |

`endContextTokens` — the column `RESULTS.md` originally published as `tokens` — is reported ONLY as the
round-1 comparison column. It is the size of the conversation at the end of the run, not work done.

### Upstream — the denominator

The methodological core of this round. `editFraction` alone is not enough: one semantic operation can do
in a single tool call what shell does in several, so a comparison on phase fraction alone flatters
whichever arm takes fewer, larger steps.

| id | quantity | capture 1 | capture 2 |
|:---|:---|:---|:---|
| A | tool calls before the state | ✔ | ✔ |
| B | cumulative Opus **output** tokens | proxy only, else `NA` | ✔ exact |
| C | agent seconds | ✔ | ✔ |
| D | edit operations | ✔ | ✔ |
| E | `editFraction` = `(step − firstWriteStep) / (n − firstWriteStep)` | ✔ | ✔ |

Every upstream → downstream curve is drawn against all of A, B, C and E. The claim of interest is
whether the mcp curve is systematically shifted left and down — not whether it is on any single axis.

## Pre-registered milestone rule

The layer taxonomy is anchored **outside both trajectories**, in the dataset's own gold `patch`. Neither
arm's behaviour informs it.

`G` = the 13 production files of the gold patch (44 568 chars). `L` = 7 layers. A touched file is
assigned to a layer by the FIRST matching path pattern, so that a state is classified by the SHAPE of
what it touched, not by whether the agent guessed the reference filenames:

| # | layer | pattern | gold members |
|--:|:---|:---|:---|
| 1 | `schema` | `src/main/resources/db/migration/` | `V5__enhance_releases_for_advanced_query.sql` |
| 2 | `domain-model` | `/domain/entities/` or `/domain/models/` | `Release.java`, `ReleaseStatus.java` |
| 3 | `domain-rules` | `*Validator.java` or `/domain/exceptions/` | `ReleaseStatusTransitionValidator.java`, `InvalidStatusTransitionException.java` |
| 4 | `persistence` | `*Repository.java` or `*Specification(s).java` | `ReleaseRepository.java` |
| 5 | `service` | `*Service.java` or `/domain/Commands.java` | `ReleaseService.java`, `Commands.java` |
| 6 | `transport` | `/domain/dtos/`, `/api/models/` or `*Mapper.java` | `ReleaseDto.java`, `CreateReleasePayload.java`, `UpdateReleasePayload.java` |
| 7 | `api` | `/api/controllers/` or `*ExceptionHandler.java` | `ReleaseController.java`, `GlobalExceptionHandler.java` |

Only paths under `src/main/` count; test files, build output and everything outside the source tree are
ignored. Each of the 13 gold files falls in exactly one layer (verified), and the patterns also catch the
agent-invented files that do the same job under different names — which is exactly why the rule is
pattern-based: the round-1 shell run named its migration `V5__add_release_planning_columns.sql` and
invented `ReleaseSpecifications.java`, `ReleaseMapper.java` and `ReleaseSearchCriteria.java`, none of
which a gold-path intersection would see.

For the state `s_k` after step `k`:

- `layerCov(k)` = |layers with at least one touched file| / 7
- `fileCov(k)` = |touched ∩ `G`| / 13 — the **taxonomy-free control**, reported alongside every
  `layerCov` statistic as a sensitivity. Its known blind spot is the renamed migration.
- **`T`**, the transition = the step with the LARGEST single-step increase in `layerCov`; ties → the
  earliest step.
- Milestones: `M0` = first write; `M50` = first `layerCov ≥ 0.5`; `Mfull` = first `layerCov = 1.0`;
  `Mapi` = first step touching the `api` layer — the integration layer whose arrival round 1 identified
  as the mechanism.

Coverage is a statement about WHICH PARTS of the solution the state addresses, not about correctness.
A file counts as touched whatever its content.

The rule is frozen as executable code in [`data/capture2/gold_layers.py`](data/capture2/gold_layers.py)
— `GOLD_FILES`, `LAYER_RULES`, `coverage()`, `transition_step()` and `milestones()`. Running it with no
argument reproduces the capture-1 table below from the committed round-1 states.

### The rule validated on capture 1

Applied blind to the round-1 committed states — the only tuning was the choice to match by path pattern
instead of by gold path, made because the shell run renamed its migration, not because of any outcome:

| arm | step | `editFraction` | `fileCov` | `layerCov` | Δ`layerCov` |
|:---|--:|--:|--:|--:|--:|
| mcp | 15 | 0.0 | 0.08 | 0.143 | +0.143 |
| mcp | 16 | 0.1 | 0.15 | 0.143 | 0.000 |
| mcp | 17 | 0.2 | 0.15 | 0.286 | +0.143 |
| mcp | 18 | 0.3 | 0.15 | 0.429 | +0.143 |
| **mcp** | **19** | **0.4** | **0.85** | **1.000** | **+0.571** |
| mcp | 21–25 | 0.5–1.0 | 0.85 | 1.000 | 0.000 |
| none | 17 | 0.0 | 0.00 | 0.143 | +0.143 |
| none | 21 | 0.1 | 0.00 | 0.143 | 0.000 |
| **none** | **25** | **0.2** | 0.23 | 0.429 | **+0.286** |
| none | 29 | 0.3 | 0.38 | 0.571 | +0.143 |
| none | 33 | 0.4 | 0.38 | 0.714 | +0.143 |
| none | 37 | 0.5 | 0.54 | 0.857 | +0.143 |
| none | 41 | 0.6 | 0.62 | 0.857 | 0.000 |
| **none** | **45** | **0.7** | 0.85 | **1.000** | +0.143 |
| none | 49–53 | 0.8–1.0 | 0.92 | 1.000 | 0.000 |

Three honest observations, all recorded before capture 2 exists:

1. `T` on the mcp trajectory is **step 19** — precisely the step round 1 identified from the downstream
   data alone. The rule recovers the known answer without being told it.
2. `T` on the shell trajectory is **step 25**, which is NOT where round 1 saw the residual-work collapse.
   The largest single coverage jump and the largest residual-work drop need not coincide in an arm that
   works in small increments; that is a property of the metric, not a defect, and it is why `Mapi` /
   `Mfull` are pre-registered as separate milestones alongside `T`.
3. `Mfull` = mcp 19 (`editFraction` 0.4) against shell 45 (0.7); `Mapi` is the same pair, because in both
   arms the `api` layer is the last one to arrive. Round 1 named step 49 for shell because it tracked
   `ReleaseController` specifically; the layer rule sees `GlobalExceptionHandler` complete the layer four
   steps earlier. Both readings are reported in round 2.

Capture-1 rows are computed on the COMMITTED GRID only (every shell step is not available — the round-1
recorder exported patches for grid steps alone), so shell's step numbers there are upper bounds at a
resolution of 4 steps. Capture 2 exports a patch per step and has no such limitation.

## Pre-registered checkpoint selection

Four states per new trajectory, chosen by the identical rule in both arms, computed and committed BEFORE
any probe is queued:

| id | state | why |
|:---|:---|:---|
| `C1` | `M0` — the first write | early anchor, comparable with round 1's `editFraction` 0.0 |
| `C2` | `T − 1` | immediately before the transition |
| `C3` | `T` | immediately after the transition |
| `C4` | the last step whose tree differs from the final tree | near-done anchor |

If `T = M0` the pair collapses; in that case `C2` is the state before `T` in the recorded step sequence
and the collision is reported. Duplicate trees are probed once and reported as `sameStateAs`, exactly as
round 1 did.

Five Haiku replicates per checkpoint. Round 1's variance budget justifies the number: within-cell CV of
0.19 on `C_tokens` means 5 rollouts already detect a 30 % shift, and the effect under test is 6×.

## Pre-registered decision rule

Inherited verbatim from `RESIDUAL-DIFFICULTY.md` → "Minimal next experiment", with the refutation and
ambiguity branches added here:

1. **Confirmation** — continue to a second CASE only if, in the round-2 pair, ALL of:
   - the integration-layer milestone (`Mapi`) lands at an `editFraction` at least **0.2 earlier** in mcp
     than in shell, **and**
   - the residual-work collapse at the transition is **≥ 2×** on cumulative output tokens with
     **non-overlapping bootstrap intervals**, **and**
   - the same ordering holds on at least one **censoring-immune** metric (`C_tools` or `C_edits`).
2. **Refutation / stop the branch** — record the negative result and stop if ANY of:
   - the ordering reverses (shell reaches the low-residual state earlier);
   - the collapse disappears after normalising by upstream Opus output tokens;
   - the transition is fully explained by the byte size of a single batched write;
   - the milestone rule cannot be applied arm-independently.
3. **Ambiguity** — direction right, margin under threshold: report inconclusive and **cost out** (do not
   launch) the smallest experiment that would settle it.

## Censoring

Unchanged from round 1: a CLI killed at the 1800 s budget emits no terminal `result` event, so USD and
token fields are `NA` exactly for the SLOWEST successful runs — the missingness is informative and
biased against the metric of interest. Every cell reports how many of its successes are censored, and
the headline drop is recomputed with every censored success imputed at the **maximum** output-token
count observed anywhere in the round. The conclusion must survive that substitution. `C_tools` and
`C_edits` are decoded from the transcript and exist even for killed runs, which is why they carry the
corroboration role.

## Budget and gates

| item | unit | count | cost | build-hours |
|:---|--:|--:|--:|--:|
| `hookPreflight` (instrument re-proof) | ≈ $0 | 1 | ≈ $0 | ≈ 1 |
| Opus captures (`mcp2`, `none2`) | ≈ $3.8 | 2 | ≈ $7.6 | ≈ 2.5 |
| Haiku probes (4 checkpoints × 5 replicates × 2 arms) | $0.68 | 40 | ≈ $27 | ≈ 40 |
| drift-control probes on a capture-1 state | $0.68 | 3 | ≈ $2 | ≈ 3 |
| **total** | | **46** | **≈ $37** | **≈ 47** |

- **Gate 0 — comparability.** Passed, see above.
- **Gate 1 — after the two captures, before the $27 of probes.** Both captures must be admitted, have an
  edit phase, and produce complete per-step records. If a capture is degenerate, or the two arms'
  transitions already coincide under every denominator, the round STOPS here having spent ≈ $8.
- **Gate 2 — after the sweep.** The decision rule above, evaluated verbatim.

## Addressing: how capture 2 is named

The probe build addresses a cell by three coordinates only — `ripple.checkpoint.arm`, `.index`,
`.replicate` — declared in the separate `mcp-steroid-teamcity` DSL. Capture 2 is therefore addressed by
**new arm tokens** `mcp2` and `none2`, with states under
`ripple-checkpoints/feature-service-125/mcp2|none2/`. This needs no cross-repo DSL commit (round 1
already proved TeamCity accepts a prompted value outside its `select` options — it ran indices 6..10
against options 1..5), the verdict regex `arm=(\S+)` parses the new tokens unchanged, and every
capture-1 path stays byte-identical.

## Results

Everything below was produced after the design above was committed. Datasets:
[`data/capture2/upstream-r2.csv`](data/capture2/upstream-r2.csv) (73 source steps),
[`rollouts-r2.csv`](data/capture2/rollouts-r2.csv) (40 probe rollouts),
[`checkpoints-r2.csv`](data/capture2/checkpoints-r2.csv),
[`summary-r2.json`](data/capture2/summary-r2.json). Provenance: [RUN-IDS.md](RUN-IDS.md) stage 4.

### Deviations from the pre-registration

Three, all recorded before any verdict was read except the third, which is a parser fix:

1. **The transition anchor `T` degenerated and was replaced by `Mapi`.** `T` = "largest single-step
   increase in `layerCov`, ties → earliest" returns the FIRST WRITE on both round-2 trajectories: the
   mcp run's first write already covers 6 of 7 layers (+0.857 in one step), and every shell increase is
   exactly one layer, so the tie rule picks its first write too. Probing `T−1`/`T` would have measured
   the first write twice and never approached the state the round is about. The substitute `Mapi` —
   first step touching the `api` layer — is itself pre-registered in this document, is computed
   arm-independently from the gold taxonomy, and is the milestone the confirmation rule is written in.
   `T` is still reported everywhere. The mcp arm's fourth state is `T−1` (its pristine tree), because
   `M0` and `Mapi` are adjacent there.
2. **Drift-control probes were not run.** Three cells were budgeted to re-measure a capture-1 state
   under the round-2 CLI. Their purpose was to price a CLI change that turned out not to exist — both
   captures resolved the same `claude-code/2.1.197` as round 1 — so they were not queued. The probe-side
   model snapshot is still unverified between rounds, and every cross-round number below is reported
   with that caveat; the within-round contrast, which carries the verdict, is unaffected.
3. **The verdict parser was fixed after the sweep**: `editFraction` was matched unsigned, and the mcp
   arm's pre-write state legitimately has `editFraction = −0.091`, so five real verdicts were being
   dropped as "no probe line". The fix (`-?[\d.]+`) changes no round-1 row.

### The two round-2 trajectories

| | mcp2 | none2 |
|:---|--:|--:|
| build | `1037157415` | `1037157425` |
| tool calls `n` | 25 | 48 |
| turns | 27 | 55 |
| first write | step 14, at 388 s | step 16, at 744 s |
| own output tokens | 45 702 | 41 528 |
| agent seconds | 970 | 1366 |
| cost | $3.33 | $4.21 |
| admitted | true | true |
| `Mapi` (all 7 layers) | **step 15** (`editFraction` 0.09) | **step 41** (`editFraction` 0.78) |
| tool at `Mapi` | `steroid_execute_code` | `Write` |
| final `fileCov` / `layerCov` | 0.85 / 1.00 | 0.92 / 1.00 |

The round-1 pattern reappears on the phase axis: the mcp arm completes the layer set at 9 % of its edit
phase, the shell arm at 78 %. It also reappears in kind — mcp's decisive step is again ONE batched
`steroid_execute_code`, writing 19 828 chars at step 14 and 36 107 by step 15, while the shell arm gets
there through 25 further tool calls.

### Residual completion work per probed state

40 rollouts, 5 per cell, `C_tokens` = output tokens of a successful rollout, bootstrap 95 % CI over the
individual observations, `V` with Wilson 95 %.

| arm | step | milestone | `editFr` | upstream tok | upstream calls | `V` (Wilson) | `C_tokens` | 95 % CI | `C_tools` | `C_edits` |
|:---|--:|:---|--:|--:|--:|:---|--:|:---|--:|--:|
| mcp2 | 13 | pristine (`T−1`) | −0.09 | 12 246 | 13 | 0.40 (0.12–0.77) | `NA` (both successes censored) | — | 115.0 | 33.5 |
| mcp2 | 14 | `M0` | 0.00 | 33 574 | 14 | 1.00 (0.51–1.00) | 17 175 | 14 225–21 733 | 69.3 | 15.5 |
| **mcp2** | **15** | **`Mapi`** | **0.09** | **40 175** | **15** | 1.00 (0.57–1.00) | **5 685** | 4 105–7 265 | 34.8 | 0.8 |
| mcp2 | 23 | last distinct | 0.82 | 43 013 | 23 | 1.00 (0.57–1.00) | 4 639 | 3 880–5 399 | 33.0 | 0.6 |
| none2 | 16 | `M0` | 0.00 | 17 430 | 16 | 0.80 (0.38–0.96) | 28 220 | 25 419–31 404 | 101.8 | 31.5 |
| none2 | 40 | `Mapi−1` | 0.75 | 25 087 | 40 | 1.00 (0.57–1.00) | 14 254 | 13 032–15 544 | 63.2 | 10.6 |
| **none2** | **41** | **`Mapi`** | **0.78** | **25 176** | **41** | 1.00 (0.57–1.00) | **4 964** | 3 418–6 510 | 32.2 | 0.6 |
| none2 | 44 | last distinct | 0.88 | 29 508 | 44 | 1.00 (0.57–1.00) | 5 673 | 5 114–6 231 | 35.6 | 0.6 |

Raw observations are in the dataset and drawn as individual points in every figure; e.g. mcp2 `Mapi` =
3 308 / 4 460 / 4 987 / 7 664 / 8 006 and none2 `Mapi` = 2 877 / 3 170 / 4 998 / 6 346 / 7 431.

### What replicated

**The metric.** Residual completion work collapses at the integration layer, in BOTH arms, by almost the
same factor, and it does so on three independent measurements:

| arm | transition | `C_tokens` | `p` | `C_tools` | `p` | `C_edits` | `p` |
|:---|:---|:---|--:|:---|--:|:---|--:|
| mcp2 | 14 → 15 | 17 175 → 5 685 (**3.02×**) | 0.008 | 69.3 → 34.8 (1.99×) | 0.016 | 15.5 → 0.8 (19.4×) | 0.008 |
| none2 | 40 → 41 | 14 254 → 4 964 (**2.87×**) | 0.008 | 63.2 → 32.2 (1.96×) | 0.016 | 10.6 → 0.6 (17.7×) | 0.008 |

Two-sided permutation tests on the difference of means, 100 000 relabelings, seed 20260820. The
bootstrap intervals do not overlap in either arm. Round 1's headline — a residual-work collapse when the
last missing implementation layer lands — is therefore **confirmed on two fresh trajectories**, and the
pre-registered milestone rule located it without being told where to look.

`V` again saturates and again decides nothing: every state from the first write onwards has `V = 1.00`
with a Wilson interval reaching down to 0.51, so the binary metric cannot tell a 17 175-token state from
a 4 964-token one. That is the round's second replication.

### What did NOT replicate: the arm difference

The round exists to test causation, and here the answer is negative under the honest denominator.

| | mcp2 | none2 | who is earlier |
|:---|--:|--:|:---|
| `Mapi` at edit fraction | 0.09 | 0.78 | **mcp** by 0.69 |
| `Mapi` at tool calls | 15 | 41 | **mcp** by 26 |
| `Mapi` at agent seconds | 451 | 877 | **mcp** by 426 s |
| `Mapi` at cumulative Opus OUTPUT tokens | **40 175** | **25 176** | **shell**, by 15 000 |
| residual work after `Mapi` | 5 685 | 4 964 | indistinguishable, `p = 0.56` |
| **total model tokens to a low-residual state** | **45 860** | **30 140** | **shell**, 34 % cheaper |

Read on tool calls, wall clock or phase fraction, mcp reaches the decisive state far earlier — round 1's
picture. Read on the model's own output tokens — the denominator this round was built to measure, and
the one that actually prices the work — **the ordering reverses**. The same is true of the state that
each arm's transition produces: after `Mapi` the two arms leave their successors statistically identical
amounts of work (`p = 0.56` on tokens, `p = 0.77` on tool calls). The mcp arm buys its earlier arrival
with its own tokens, roughly 1.6 upstream tokens per tool call saved, and hands over a state that is not
measurably better.

This is not a small-margin miss. It is a sign flip on the pre-registered primary denominator.

### Leverage, and where it actually appears

The exploratory `η = ΔC / ΔU` (downstream tokens removed per upstream token spent):

| arm | step pair | ΔU | ΔC | η |
|:---|:---|--:|--:|--:|
| mcp2 | 13 → 14 | 21 328 | `NA` (censored cell) | `NA` |
| mcp2 | 14 → 15 | 6 601 | 11 490 | **1.74** |
| mcp2 | 15 → 23 | 2 838 | 1 046 | 0.37 |
| none2 | 16 → 40 | 7 657 | 13 965 | 1.82 |
| none2 | 40 → 41 | **89** | **9 290** | **104.4** |
| none2 | 41 → 44 | 4 332 | −708 | −0.16 |

The single most leveraged action in round 2 is a SHELL action: a 89-token `Write` that adds the
controller and removes 9 290 downstream tokens. Leverage turns out to be a property of *where in the
solution structure* an action lands, not of the tool that performs it — and the last row shows η going
negative, which is the noise the pre-registration warned about. `η` is reported and not promoted.

### Censoring

Six of the 35 successes hit the 1800 s budget; a killed CLI emits no terminal `result` event, so their
token and cost fields are `NA`. The distribution is informative: **both** successes of the pristine mcp2
state are censored, which is why its `C_tokens` is `NA` rather than a number, and one of the five at mcp2
step 23. Worst-case imputation at the maximum observed value (32 876 output tokens):

| cell | successes | imputed | worst-case mean | vs measured |
|:---|--:|--:|--:|:---|
| mcp2 13 | 2 | 2 | 32 876 | no measured value exists |
| mcp2 15 (`Mapi`) | 5 | 0 | 5 685 | unchanged |
| mcp2 23 | 5 | 1 | 10 287 | still below step 14's 17 175 |
| none2 41 (`Mapi`) | 5 | 0 | 4 964 | unchanged |

Neither transition depends on a censored value: the four cells that carry the two collapses have zero
censored successes. The conclusion survives the substitution.

### The batching confound, tested

Round 1 could not separate "semantic access" from "one giant batched write". Round 2 can, and the answer
is that batching is the mechanism: the mcp arm's `Mapi` is again a single `steroid_execute_code`
(36 107 chars of state by step 15), and normalising by the bytes it wrote removes the arm difference
entirely — at `Mapi` the two arms hold 36 107 and 37 711 chars of patch, within 4 % of each other, and
leave 5 685 and 4 964 residual tokens, within their overlapping intervals. **Same state, same residual
work, reached by a different number of tool calls at a different token price.** What differs between the
arms is the granularity of the trajectory, not the usefulness of the state it passes through.

### Figures

| figure | what it shows |
|:---|:---|
| [`fig-r2-a-residual-vs-upstream.png`](fig-r2-a-residual-vs-upstream.png) | the sign flip: mcp is left of shell on tool calls, seconds and edit fraction, and RIGHT of it on cumulative output tokens |
| [`fig-r2-b-capture1-vs-capture2.png`](fig-r2-b-capture1-vs-capture2.png) | both rounds on one axis; round 1 redrawn from its untouched dataset |
| [`fig-r2-c-state-space.png`](fig-r2-c-state-space.png) | `V` against residual work — the vertical collapse at saturated `V` |
| [`fig-r2-d-coverage-trajectory.png`](fig-r2-d-coverage-trajectory.png) | `layerCov` / `fileCov` per step with the milestones and the probed states |
| [`fig-r2-e-delta-efficiency.png`](fig-r2-e-delta-efficiency.png) | ΔC and η, including the negative bar |

### Gate 2: the pre-registered rule, evaluated verbatim

**Confirmation** required all three of:

| clause | threshold | measured | verdict |
|:---|:---|:---|:---|
| `Mapi` at least 0.2 earlier in mcp | Δ`editFraction` ≥ 0.2 | 0.09 vs 0.78 → **0.69** | ✅ met |
| collapse ≥ 2× on output tokens, non-overlapping CIs | ≥ 2× | 3.02× (mcp2), 2.87× (none2), CIs disjoint | ✅ met |
| same ordering on a censoring-immune metric | tools or edits | tools 1.99×/1.96×, edits 19×/18× | ✅ met |

**Refutation** required any one of four. One fires:

| clause | measured | verdict |
|:---|:---|:---|
| ordering reverses | **yes, on denominator B**: shell reaches its low-residual state at 25 176 upstream output tokens, mcp at 40 175 | ❌ triggered |
| collapse disappears after normalising by upstream tokens | no — it survives in both arms | not triggered |
| transition explained by the size of one batched write | partially: at `Mapi` both arms hold ≈ 36–38 k chars and leave indistinguishable residual work | ❌ triggered |
| milestone rule not applicable arm-independently | no — `Mapi` applied identically; `T` degenerated and was replaced, also identically | not triggered |

**Verdict: refutation of H1 as stated, confirmation of the metric.**

- The **causal claim is not supported.** Semantic IDE access does not make the agent reach a
  low-residual-work state at a lower cost in model work; on this case it costs 34 % more total output
  tokens to get there, and the state it produces is indistinguishable from the shell arm's.
- The **weaker operational claim survives and is worth stating separately**: mcp reaches that state in
  15 tool calls and 449 seconds against 41 and 869. If the scarce resource is round trips or wall clock
  — which it is for an interactive agent — that is a real advantage. If the scarce resource is model
  tokens, it is not.
- The **progress metric replicated cleanly** and is the durable result of this branch: `C(s)` collapses
  at an identifiable structural event, on four independent trajectories now, with `p ≈ 0.008` at `n = 5`,
  while `V(s)` saturates and sees nothing.

Per the pre-registered rule, the branch **stops here** rather than scaling to more cases. There is no
point buying a second case to test a hypothesis whose sign has flipped on its own primary denominator.

### What would be worth doing instead (costed, not launched)

1. **Round-trip-limited comparison — ≈ $14, ≈ 20 build-hours.** If the operational claim is the one worth
   having, measure it directly: cap both arms at the same number of tool calls and compare what they
   deliver, instead of measuring how quickly each arrives at a state.
2. **Drift controls — ≈ $2, 3 builds.** The three cells this round skipped, needed before any capture-1
   number is compared with a capture-2 number.
3. **The metric on its own merits — ≈ $27 per case.** `C(s)` is now a validated progress measure; its
   next use is as an instrument for OTHER questions (curriculum construction, reward shaping, early
   stopping), not as another attempt to make one arm win.

### Key comparison table (schema fixed in advance)

| capture | arm | upstream tokens to transition | tool calls | step | `editFraction` | milestone | residual before | residual after |
|--:|:---|--:|--:|--:|--:|:---|--:|--:|
| 1 | mcp | `NA` (unrecoverable) | 19 | 19 | 0.40 | last layer | 24 190 | 4 017 |
| 1 | shell | `NA` (unrecoverable) | 49 | 49 | 0.80 | last layer | 13 386 | 5 234 |
| 2 | mcp2 | 40 175 | 15 | 15 | 0.09 | `Mapi` | 17 175 | 5 685 |
| 2 | none2 | 25 176 | 41 | 41 | 0.78 | `Mapi` | 14 254 | 4 964 |

Unknown cells stay `NA`. No value in this table is filled by assumption.
