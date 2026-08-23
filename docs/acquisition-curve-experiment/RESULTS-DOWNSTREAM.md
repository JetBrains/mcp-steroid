# Downstream validation of `U` — results

Design and both amendments: [DESIGN-DOWNSTREAM.md](DESIGN-DOWNSTREAM.md). Raw cells:
[data/downstream-cells.csv](data/downstream-cells.csv). Notes: `data/notes/`, and the twelve the wave
used are committed under `test-experiments/src/test/resources/acquisition-notes/`.

## Verdict

**`U` is not validated as a functional measure by this wave, and the wave cannot validate it.** Not
because the answer came out negative — because the instrument it was measured with has a resolution of
roughly one bit, and the noise between two identical cells spans the whole scale.

Both pre-registered failure conditions fired, and they fired for reasons that are properties of the
downstream *measurement*, not of `U`:

| pre-registered rule | required | observed |
|---|---|---|
| floor anchor | `baseline` ≤ 2/8 | **7/8** and **0/8** (n=2) |
| primary ρ(`U_obs`, `toolCalls`) | ≤ −0.5 | **+0.03** |
| secondary ρ(`U_obs`, passed) | — | **−0.22**, p = .88 |
| ρ(`U_note`, passed) | — | **−0.06** |
| effort falls with `U` within a trajectory | 3–4 of 4 | **1 of 4** |

## What was actually run

15 cells, one rollout each, all on `acquisition__keycloak__cc-refresh-token` with the weak agent,
shell-only, pristine tree: 12 notes (4 trajectories × B ∈ {5,10,20}, two trajectories per arm), 2
`baseline`, 1 `oracle:gold`. A second `oracle:gold` was lost to a Docker image build failure and not
re-run — the ceiling was already established by the first. Total ≈ $10.

| trajectory | B | `U_obs` | `U_note` | passed | tool calls | tokens | usd |
|---|---:|---:|---:|---:|---:|---:|---:|
| mcp r2 | 5 | .20 | .47 | 5/8 | 84 | 25 515 | 0.80 |
| mcp r2 | 10 | .80 | .67 | 5/8 | 60 | 17 762 | 0.53 |
| mcp r2 | 20 | .87 | .80 | **0/8** | 99 | 20 064 | 0.75 |
| mcp r3 | 5 | .53 | .67 | 6/8 | 44 | 13 694 | 0.35 |
| mcp r3 | 10 | .60 | .67 | 5/8 | 72 | 21 378 | 0.62 |
| mcp r3 | 20 | .73 | .67 | 5/8 | 93 | 23 246 | 0.91 |
| shell r1 | 5 | .20 | .13 | **0/8** | 72 | 16 712 | 0.70 |
| shell r1 | 10 | .60 | .47 | 6/8 | 78 | 24 926 | 0.76 |
| shell r1 | 20 | .73 | .73 | 7/8 | 67 | 25 112 | 0.68 |
| shell r3 | 5 | .13 | .33 | 7/8 | 61 | 13 527 | 0.49 |
| shell r3 | 10 | .20 | .33 | 5/8 | 126 | 32 082 | 1.30 |
| shell r3 | 20 | .27 | .40 | 7/8 | 77 | 21 523 | 0.69 |
| baseline | — | — | — | 7/8 | 89 | 19 551 | 0.97 |
| baseline | — | — | — | **0/8** | 62 | 15 568 | 0.50 |
| oracle-gold | — | — | — | 8/8 | 70 | — | — |

## Why the wave cannot answer the question — three findings, in order of importance

### 1. The downstream agent is not budget-limited, so the note is not load-bearing

The floor anchor solved seven of the eight assertions **with no note at all**, spending 89 environment
interactions. The research agent in the acquisition round works under a budget of 5–40 interactions;
the downstream agent has none. Given an unlimited allowance on a pristine tree it simply performs the
research itself, and arrives where a good note would have put it.

This is not a flaw in the case — the case's shell audit, which admitted it, measured exactly this and
said so: three optimal commands reach `U = .07`, ten reach `.80`. Ten commands is well inside 89. The
experiment that follows from that audit is "does the note reduce the WORK", and answering it needs the
downstream agent to be budgeted too. It was not.

### 2. The oracle is a cascade, not a graded scale

The eight assertions are not eight independent chances. Every one of them first resolves the executor
through the shipped profile JSON, so until that one line exists **all eight fail together**. Both 0/8
cells that compiled show `Tests run: 8, Failures: 8`, and one further zero (shell r1 @ 5) is a plain
`COMPILATION ERROR`.

So the endpoint is effectively `{0} ∪ {5…8}`: one boolean about a JSON entry, plus a narrow band above
it. The residual count was chosen precisely to be finer-grained than pass/fail, and it is not. This was
visible before the wave — the report that built the oracle wrote "the suite gives one bit of
information until the profile JSON entry exists" — and it was not carried into the endpoint choice.
That is an instrument defect of this round, recorded as such.

### 3. Cell-level noise equals the full range of the scale

Two runs of the *same* condition, `baseline`, returned 7/8 and 0/8. With the between-cell standard
deviation that large, twelve one-rollout cells cannot resolve any effect the design was looking for. A
rough reading of the same variance in the note cells (three zeros in twelve, the rest 5–7) puts the
catastrophic-outcome rate near 20 %, and it is what drives every correlation above.

The p-value of the primary test, .0417, must not be read as a result. Four clusters admit 24
arrangements, so .042 is the *smallest attainable* p, and it was attained by an effect of +0.03 in the
direction opposite to the prediction. It illustrates the design's own warning about small cluster
counts rather than anything about `U`.

## What the wave nevertheless shows

- **The ceiling is real.** `oracle:gold` — the hand-written, solution-derived note — is the only cell of
  fifteen that reached 8/8. Handing over complete understanding does finish this task; the assertions
  are satisfiable by this agent.
- **The one invariant is where the work actually is.** In the cells that got past the JSON entry, the
  assertion that fails most often is the partial-update trap, and the single failure of the 7/8 cells is
  `registerWithoutTheSettingIsAcceptedAndTurnedOff` — the auto-configure branch. The two facts the
  acquisition checklist calls `H1` and `G1` are precisely the ones the downstream agent does not
  reconstruct on its own. That is consistent with `U` measuring something real; it is not evidence for
  it.
- **No note leaked its arm.** Twelve notes, none of them mentioning a tool, a search or the IDE — pinned
  by a test — so the blind judge and the downstream agent both stayed blind. The blinding machinery
  works and is reusable.

## What the arms did, as a secondary reading only

`mcp` 4.33/8 mean against `shell` 5.33/8, and the three matched pairs at equal `U_obs` go 5 vs 0, 5 vs
6, 5 vs 7. Four cells an arm, one rollout each, on an endpoint with this variance: this says nothing,
and it is reported only so that nobody has to re-derive it later.

## What would have to change before buying this again

1. **Budget the downstream agent.** The same interaction gate the research phase already has, at maybe
   15–25 interactions. Without it the note competes against the agent's own unlimited research and the
   comparison is uninformative by construction.
2. **Replace the cascading oracle with independent assertions.** Each one must be reachable without the
   profile JSON entry — e.g. exercise the executor directly once it exists at all, and score the
   registration separately — or the endpoint stays one boolean wearing eight names.
3. **Measure the per-cell variance before designing around it.** Four `baseline` rollouts cost $4 and
   would have shown the 7-vs-0 spread before twelve note cells were bought.
4. **Then, and only then, decide the matrix.** With a catastrophic-outcome rate near 20 % and a
   three-point usable band, twelve cells is far too few; the honest number follows from the variance
   measured in step 3.

None of this is being done in this round. Per the pre-registration's own rule, an inconclusive result
buys no further cells without a new pre-registration.

## Effect on the acquisition round's conclusions

Unchanged and unstrengthened. The acquisition curves — `U_mcp` .40/.70/.83/.83 against `U_shell`
.18/.35/.43/.60 by interactions, no advantage by output tokens — stand exactly as
[RESULTS.md](RESULTS.md) reports them, and the causal chain they support still stops at

> semantic access reaches a given level of the pre-registered architecture checklist in fewer
> environment interactions.

The step "and that level is functionally useful to a downstream agent" remains **unproven**. It was not
disproven either: this wave failed to test it.
