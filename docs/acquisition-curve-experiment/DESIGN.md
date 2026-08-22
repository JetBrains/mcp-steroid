# Acquisition curves: how fast does an agent build an actionable model of a large repository?

Pre-registered before the first trajectory of either arm was run. Written down in this order on
purpose: the checklist and the scoring rules exist in git before any transcript does, so nothing in
them can have been chosen to fit what one arm turned out to produce.

## The claim under test

> Semantic repository access lets an agent acquire an actionable architectural model of a large
> unfamiliar repository in **fewer environment interactions**, even when the model-side reasoning cost
> is comparable or larger.

This is deliberately not the claim the previous round tested. That round asked whether the mcp arm
writes *better* notes and the answer, once the truncation defect was removed, was no: 8/15 against
5/15 at 500 characters, note-level permutation p = 0.4, and the best note of the whole experiment was
written by the shell arm. What survived two independent experiments is a different regularity — the
mcp arm reaches its stopping point in fewer environment interactions while spending as many or more
model output tokens. This round measures that directly instead of inferring it from a downstream
proxy.

Two things follow and both are load-bearing:

- **The primary endpoint is not downstream success.** It is `U(B)`, the share of a pre-registered
  architecture checklist an agent has acquired after `B` environment interactions. Downstream success
  becomes what it should always have been — a functional validation that a higher `U` means something
  real — and not the instrument.
- **The unit of replication is the trajectory.** Never the downstream rollout. The last round's three
  notes from one arm, one budget and one limit scored 5/5, 4/5 and 0/5; any design that treats the
  rollouts as independent observations manufactures significance out of that variance.

## The case

`acquisition__keycloak__cc-refresh-token`, on `keycloak/keycloak` at `60c4d5e9`. Why this one, and why
no existing case would do, is [CASE-SELECTION.md](CASE-SELECTION.md). In one line: under the `strict`
security profile a confidential OpenID Connect client must never obtain a refresh token from the client
credentials grant, and the correct implementation is four files and 215 lines that the statement gives
no way to find.

Its qualifying numbers, all measured:

| criterion | measurement |
|---|---|
| no leakage | every statement phrase matches 23–592 java files; "refresh token" ∧ "client credentials" = 31 files, **none** under `clientpolicy/` or `securityprofile/` |
| research depth | 3 optimal shell commands ⇒ `U_obs` = **0.07** |
| reachable ceiling | 10 optimal shell commands ⇒ `U_obs` = **0.80** |
| implementation tractability | gold ⇒ hidden oracle 8/8 in 1.6 s; pristine ⇒ 0/8 |
| the invariant bites | naive copy of the obvious neighbour ⇒ exactly 1 of 8 fails |

## The measurement

### Arms

Two, differing in one thing. Same model (Opus), same statement, same pinned tree, same budget, same
prompt skeleton, same pristine-tree enforcement.

- `mcp` — shell plus the IDE-backed resolved-program tools, and the paragraph that tells the agent they
  exist. (Without that paragraph the arm labelled "with IDE access" spends its budget on the shell and
  the comparison measures a coin flip about tool choice.)
- `shell` — the same, without them. Called `none` in the harness's own vocabulary.

The research agent may not modify the tree, may not implement anything, and the tree is verified
byte-identical afterwards; a run that changed anything is discarded rather than reported.

### One trajectory, four checkpoints

Every trajectory is run once, to a hard budget of **40** environment interactions enforced by the same
`PreToolUse` hook the previous round used. The curve is then read off **slices of that one transcript**
at `B ∈ {5, 10, 20, 40}`.

This is the main methodological change and it is worth being precise about what it does and does not
buy. It buys: the shape of the curve is a *within-trajectory* quantity, so the between-trajectory
variance that dominated the last round does not leak into it, and every dollar of replication buys
another independent trajectory instead of another point on the same one. It does not buy: any claim
that the agent would have *stopped* at call ten and written what we distil from its first ten calls.
The prefix is exactly what the environment had told the agent by then, and nothing more is claimed.

### Two curves, and why both

**`U_observed(B)`** — mechanical, no model in the loop, recomputable from the committed transcript.
A fact counts as observed when some *single tool result* in the prefix contains every literal of one of
its evidence bundles. Single result and all literals, because the failure this defends against is the
cheap one: a `find` that prints a hundred paths, one of them the right file, is not knowledge of what
is in that file. `AcquisitionHarnessTest` pins exactly that — a listing naming all three profile JSONs
scores zero on `G1`; quoting the enabled policy inside one of them scores the point.

**`U_actionable(B)`** — judged. Each prefix is distilled, by the same model with no tools and a fixed
prompt, into a hand-off note of at most 2 000 characters that may use nothing but the prefix and may
not mention how anything was found. A judge then answers the checklist's fifteen yes/no questions
against that note alone.

The judge is blind to the arm, and the blinding is structural rather than promised: the note is prose
about the repository with every tool name forbidden by the distiller's brief, so there is nothing in it
to identify an arm by. The judge is not blind to the checkpoint — a note distilled from five calls is
visibly thinner than one from forty — and that is accepted, because the comparison of interest is
between arms at the same checkpoint.

Both curves are published. They answer different questions and they are allowed to disagree: the gap
between them is exactly "was shown it" minus "made something of it", which is a result in itself.

### Two denominators, never combined

1. **`U(environment interactions)`** — the primary axis, `B ∈ {5, 10, 20, 40}`.
2. **`U(cumulative model output tokens)`** — the same four points, re-plotted against the tokens the
   model had emitted by then, sampled as a step function (`observedAtTokenBudget`).
3. `U(wall-clock seconds)` — reported as an operational metric only.

They are never summed into a score. The result this experiment most expects to see — and has already
seen twice, in the checkpoint pilot and in the note round — is `U_mcp(calls) > U_shell(calls)` together
with `U_mcp(tokens) ≈ U_shell(tokens)` or worse. That is a finding about *where* the saving is, not a
wash, and a combined score would destroy it.

Token accounting is honest about its own weakness: when the transcript's per-message `usage` sums to
the run total it is used directly (`PER_MESSAGE`); when the streaming events carry partial counts — as
they do in real captures — the run total from the terminal `result` event is attributed across turns in
proportion to the characters each emitted (`PROPORTIONAL`). Exact at the end of the run, monotone,
identical in both arms, and labelled as an estimator wherever it appears.

### `P` — the precedent, measured separately

The previous round's strongest finding was that a weak downstream agent imitates the *named precedent*
and ignores prose that contradicts it: notes naming a session-reading exemplar scored 0/10, notes
naming a token-only one 13/20. So `A1` — "the right precedent is an executor that consults the stored
client on update" — is tracked as its own series `P(B)` alongside `U(B)`, and `A2` records whether the
agent noticed that the two name-adjacent `reject-*-grant` executors are the wrong shape. The analysis
will report `P → U → downstream` as a chain, but the experiment is not only about `P`: it is one of
fifteen facts and the design will say whether it is the first milestone or merely one of several.

## Downstream functional validation

Not the primary endpoint, and not a source of independent observations for the arm comparison.

Pre-registered checkpoints: **`B = 5` and `B = 20`**, plus the two anchors the last round established
(`baseline`, no note; `oracle:gold`, a note written from the gold change). The distilled note from each
selected checkpoint is handed to a Haiku agent with the pristine tree, the original statement and shell
access only, and graded by the hidden oracle. Two checkpoints and not four, because a downstream cell
costs a container and a reactor install, and the question it answers is only "does a higher `U`
correspond to a functionally useful understanding" — a correlation across ~12 cells, not a significance
test.

## Statistics

- Unit of replication: **one research trajectory**. `n` per arm is the sample size; downstream runs are
  never counted as observations of the arm.
- Primary test: two-sample permutation on `U_observed(10)` over trajectory labels, one-sided in the
  direction of the hypothesis, 10 000 relabelings. The same test is reported for `U_observed(5)`,
  `U_observed(20)`, `U_actionable(·)` and for `U` at matched token budgets, with the multiplicity stated
  rather than corrected away — the primary endpoint is named here in advance.
- Effect size: difference in mean `U` at each checkpoint, with the per-arm spread shown.

**Power.** Taking the previous rounds' within-arm spread of note quality as a guide, `σ ≈ 0.15` on a
0–1 scale. To detect Δ = 0.25 at α = 0.05 one-sided with 80 % power needs `n ≈ 5` per arm; the
pre-registered main wave is therefore **n = 6 per arm**, which also keeps the exact permutation floor
(1/924) far below α. The pilot is **n = 3 per arm**, whose permutation floor is 1/20 = 0.05: a pilot can
at best reach the significance threshold with perfect separation, and it is run to find out whether the
instrument moves at all, not to decide the question.

**Cost.** A research trajectory is one container: Keycloak clone, index, an Opus run of at most 40
interactions. Measured at budget 10 in the previous round: 70–106 s of agent time, $0.26–0.51. Budget 40
with larger results scales to roughly **$2 and ~45 minutes of machine time** per trajectory. Distillation
and judging are offline API calls, ≈ $2 per trajectory for all four checkpoints and well under $5 in
total for the judge.

| wave | cells | agent cost | machine time |
|---|---:|---:|---:|
| pilot, n = 3/arm | 6 research | ≈ $12 | ≈ 4.5 h |
| main, n = 6/arm | 12 research | ≈ $25 | ≈ 9 h |
| downstream validation | ≈ 12 + 4 anchors | ≈ $10 | ≈ 13 h |

Containers are never run in parallel: two IntelliJ containers on one machine exhaust RAM and OOM-kill
both.

## Stopping rules, written down before the data

- **Stop and report negative** if, over the pilot's three trajectories per arm, the `U_observed(B)`
  curves are within one checklist item of each other at every checkpoint. Before closing the direction,
  re-check the case against the research-depth criterion — that the case did not collapse into a
  filename search — using the recorded calibration numbers.
- **Stop and report the case as unusable** if both arms exceed `U = 0.8` by `B = 5` (too easy) or if
  neither arm passes `U = 0.5` by `B = 40` (the checklist is not reachable and measures its own
  detectors).
- **Stop and report as navigational** if the entire difference sits in facts `B1`, `B2`, `C1`, `C2` and
  `F1` — the ones a reference query answers — with `E`, `G` and `H` equal between arms.
- **Scale to n = 6/arm** if the pilot separates by ≥ 0.15 at any of `B = 5, 10, 20` in the hypothesised
  direction.

## Threats this design accepts

1. **One case, one repository, one model.** Nothing here transfers to another tree without being
   measured there. The registry (`AcquisitionCases`) exists so the second case is cheap to add.
2. **The distiller is a second model in the loop.** It removes "who writes better prose" as a confound,
   which is what we want, but it also removes any part of the arm effect that lives in the agent's own
   synthesis. `U_observed` has no such contamination, which is why both are published.
3. **The detectors are literal.** An agent that learns a fact from source we did not anticipate quoting
   scores zero on `U_observed`. This under-counts both arms, but not necessarily equally — a semantic
   tool may return a normalised form. The judge curve is the check on that, and any fact where the two
   curves disagree systematically will be reported.
4. **`B = 40` is a real ceiling.** A trajectory that would have kept going is truncated by the budget,
   and the last checkpoint is therefore the least trustworthy of the four.

## Runbook

```bash
# the whole offline instrument — 13 tests, seconds, no container and no money
./gradlew :test-experiments:test \
  --tests '*AcquisitionHarnessTest*' --tests '*AcquisitionCalibrationTest*'

# one research trajectory (Opus, 40 interactions, one arm, one replicate)
./gradlew :test-experiments:test --tests '*AcquisitionResearchTest*' \
  -Dunderstanding.case=acquisition__keycloak__cc-refresh-token \
  -Dunderstanding.arm=mcp \
  -Dunderstanding.replicate=1
```

The cell prints `[ACQUISITION] …` for the trajectory's accounting and one `[ACQUISITION-CURVE] …` CSV
row per checkpoint, and writes `<noteId>.ndjson`, `<noteId>.md` and `<noteId>.json` into the run
directory. `arm` is `mcp` or `none`; the budget and note limit default to the pre-registered 40 and
2 000 and the cell refuses any other value, so a build cannot quietly produce a curve with three of its
four points missing.

A cell queued without coordinates fails with a readable message. That is intended and matches the
understanding family: a build must never silently run a default cell.
