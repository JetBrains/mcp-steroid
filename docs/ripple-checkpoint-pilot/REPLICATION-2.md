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

Baseline environment recorded from the round-1 capture logs, for the drift comparison:

| | capture 1 (round 1) | capture 2 (round 2) |
|:---|:---|:---|
| Agent CLI | `claude-code/2.1.197` | recorded at capture time |
| MCP Steroid plugin | `0.101.672-jb-4e6c047` | recorded at capture time |
| IDE | `2026.2.1` | recorded at capture time |
| Model (capture) | `claude-opus-5` | `claude-opus-5` |
| Model (probe) | `claude-haiku-4-5` | `claude-haiku-4-5` |
| Capture build ids | `1035363501` (mcp), `1035363503` (shell) | see [RUN-IDS.md](RUN-IDS.md) stage 4 |

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

*Appended after the captures and probes; empty by design at pre-registration time.*

### Key comparison table (schema fixed in advance)

| capture | arm | upstream tokens to `T` | tool calls to `T` | step | `editFraction` | milestone | residual before | residual after |
|--:|:---|--:|--:|--:|--:|:---|--:|--:|
| 1 | mcp | `NA` (unrecoverable) | 19 | 19 | 0.4 | last layer | 24 190 | 4 017 |
| 1 | shell | `NA` (unrecoverable) | 49 | 49 | 0.8 | last layer | 13 386 | 5 234 |
| 2 | mcp2 | | | | | | | |
| 2 | none2 | | | | | | | |

Unknown cells stay `NA`. No value in this table is filled by assumption.
