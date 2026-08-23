# Downstream validation of `U`, round 2 — results

Pre-registration: [DESIGN-DOWNSTREAM-2.md](DESIGN-DOWNSTREAM-2.md) (with amendments 2 and 3, each
recorded before the wave it governs). Round 1, which could not answer the question:
[RESULTS-DOWNSTREAM.md](RESULTS-DOWNSTREAM.md). Per-cell numbers:
[data/downstream2-cells.csv](data/downstream2-cells.csv), recomputed by
`analysis/downstream2_validation.py`. Build ids: [RUN-IDS.md](RUN-IDS.md).

**No new research trajectory was bought.** The twelve notes are the same committed files round 1 used.

## Verdict

`U` is a functionally valid measure of actionable repository understanding, on this case, for this
weak solving agent, under a bounded interaction budget.

| | round 1 | round 2 |
|---|---|---|
| ρ(`U_obs`, obligations satisfied) | **−0.22** | **+0.668**, 90% CI [+0.23, +0.84] |
| ρ(`U_note`, obligations satisfied) | −0.06 | **+0.825** |
| ρ(`U_obs`, tool calls) | +0.03 | **−0.543** |
| ρ(`U_obs`, output tokens) | not read | **−0.459** |
| ρ(`U_obs`, calls refused after the wall) | no budget existed | **−0.446** |

The notes did not change between the rounds. The instrument did.

## What was broken, and what fixing it cost

**1. The solving agent had no budget, so the note was not load-bearing.** Round 1's floor anchor
reached 7 of 8 assertions with *no note at all*, spending 89 interactions: it simply performed the
research the note was meant to replace. Round 2 gives it **20 repository interactions** — reads,
searches, shell, builds all cost one; creating and editing files is free and stays available after the
wall, so the wall never truncates a patch. Under that rule the floor collapses to **0/9 in all four
anchor rollouts**, and a note becomes the difference between doing the task and not finding it.

**2. The oracle was a cascade.** All eight of round 1's assertions discovered the executor through one
line of profile JSON, so they failed together: the realisable scale was `{0} ∪ {5…8}`. The replacement
finds the implementation by scanning the module's compiled classes and grades nine independent
obligations. Measured on six trees before use: pristine 1, executor class only 7, plus the SPI line 8,
naive partial-update branch 8, gold 9, and gold-without-a-null-guard 9.

**3. Nobody had measured a cell twice.** They now have been, twice over — see the calibration wave and
amendment 3 below. It was the right worry: two rollouts of the same note differ by **2.25 assertions
on average, and by 5–7 for three of the twelve**.

## Calibration (stage 1)

Six cells at `B_down = 20`. Wave 1 (`1039274925`–`1039274935`) is published in RUN-IDS but gates
nothing: it found an instrument defect, which is what a calibration wave is for — both `oracle:gold`
cells lost the same assertion to a `NullPointerException` raised inside *the agent's own executor*,
because the oracle was calling it unconfigured, a state the runtime never produces (the shipped
profile entry always carries `{"auto-configure": true}`). The in-tree precedent the case is built
around dereferences its configuration unconditionally too, so the assertion punished an agent for
following the precedent. Fixed, re-verified on six trees, and the whole wave re-run rather than
patched up — one table, one grader.

Wave 2 (`1039289680`–`1039289690`) is the calibration of record:

| condition | obligations | budget used | denied | tool calls | $ |
|---|---|---|---|---|---|
| `baseline` ×4 | **0, 0, 0, 0** | 20/20 each | 8, 5, 4, 3 | 47, 39, 77, 30 | 1.53 total |
| `oracle:gold` ×2 | **9, 9** | 20/20 each | 1, 0 | 29, 29 | 0.51 total |

All four gates pass: floor 0.00, ceiling 9.00, gap **9.00** assertions, baseline sd **0.00**, no cell
lost to the harness. The anchors are as clean as this design can hope for: with a perfect note the weak
agent solves the task completely inside twenty interactions, and without one it never once got the
module to compile.

## The matrix (stage 2 + the amendment-3 replication)

Twelve notes × two rollouts, 24 cells, `B_down = 20`. Rollouts of one note are averaged before
analysis (`by_note`): two rollouts measure one knowledge state, and entering them separately would
claim twice the evidence about `U` that exists.

| trajectory | B | `U_obs` | `U_note` | obligations (r1, r3) | mean | recovery |
|---|---|---|---|---|---|---|
| mcp-r2 | 5 | .200 | .467 | 0, 7 | 3.5 | .39 |
| mcp-r2 | 10 | .800 | .667 | 6, 6 | 6.0 | .67 |
| mcp-r2 | 20 | .867 | .800 | 4, 4 | 4.0 | .44 |
| mcp-r3 | 5 | .533 | .667 | 7, 6 | 6.5 | .72 |
| mcp-r3 | 10 | .600 | .667 | 6, 6 | 6.0 | .67 |
| mcp-r3 | 20 | .733 | .667 | 7, 6 | 6.5 | .72 |
| shell-r1 | 5 | .200 | .133 | 0, 0 | 0.0 | .00 |
| shell-r1 | 10 | .600 | .467 | 0, 5 | 2.5 | .28 |
| shell-r1 | 20 | .733 | .733 | 7, 8 | 7.5 | .83 |
| shell-r3 | 5 | .133 | .333 | 0, 0 | 0.0 | .00 |
| shell-r3 | 10 | .200 | .333 | 7, 0 | 3.5 | .39 |
| shell-r3 | 20 | .267 | .400 | 0, 5 | 2.5 | .28 |

`recovery` is the calibrated reading the anchors exist for: `(obligations − floor) / (ceiling −
floor)`, i.e. the share of a perfect note's advantage this knowledge state delivered.

**Primary**: ρ(`U_obs`, obligations) = **+0.668**, 90% cluster-bootstrap CI **[+0.23, +0.84]**,
cluster-permutation p = .0417. That p is the *floor* attainable with four clusters and is reported as
such: it says the observed ordering is the best of twenty-four whole-trajectory relabellings, not that
the effect is established to any particular precision. The estimate and its interval are the finding.

**Effort falls as understanding rises**, on all three denominators at once — tool calls −0.543, output
tokens −0.459, refused calls −0.446. Every one of the twelve notes exhausted the allowance, so
`budgetUsed` is a constant 20 and carries no information; the gradient lives in what the agent did
with those twenty and in how hard it pushed against the wall afterwards.

**The blind judge's reading is the stronger predictor**: ρ(`U_note`, obligations) = **+0.825** against
+0.668 for the observation-based `U_obs`. `U_note` scores what the distilled note actually *states*,
`U_obs` what the transcript prefix *contained*. That ordering is the expected one — a fact observed
and not connected does not help a reader — and it is the first evidence in this project that the
`observed` / `actionable` distinction the acquisition design insisted on is doing work.

**Arm, descriptive only**: mcp notes 5.42/9, shell notes 2.67/9. This is not an arm comparison: the two
arms' notes sit at different `U`, which is the whole point of the curve, and four trajectories cannot
separate arm from `U`.

## Two concrete pairs

- **shell-r1 at 5 vs at 20 — the same trajectory, fifteen interactions apart.** At `U_obs = .20` the
  note has found the security-profile SPI and asserts of the profile names: *"Those names are strings
  only — no file in the repo is named after them, so the actual profiles/policies are assembled in
  code."* They are not; they are JSON resources in the same directory. It then sends the reader to
  wire the rule into `keycloak-strict-client-policies` — the policy file, not the profile file. Both
  rollouts scored **0/9**. At `U_obs = .73` the same trajectory's note names
  `keycloak-default-client-profiles.json`, the policy that binds `oauth-2-1-for-confidential-client`,
  the `META-INF/services` line, and — under a heading "Easy to miss" — that an update omitting the
  attribute must still consult `ClientCRUDContext.getTargetClient()`. **7/9 and 8/9**, the best pair in
  the wave.
- **mcp-r2 at 20 — the counter-example, and it is not noise.** Highest `U_obs` in the wave (.867), yet
  **4/9 in both rollouts, failing the identical five assertions**: the profile entry, the blast
  radius, and all three of REGISTER/UPDATE/partial-update. The note is not wrong; it is
  *disarming*. It states correctly that "`oauth-2-1-for-confidential-client` is shared by the **lax**
  policies too; if the rule must be strict-only, do not simply extend that profile" — a real fact,
  discovered by exactly the deep reading `U` rewards, and the agent duly wired the rule somewhere
  else. A correct architectural caveat with no resolution attached is worse for a weak reader than
  silence. This is the same failure mode the note-bottleneck round found from the other side: the weak
  agent follows the structure a note names, and the higher-`U` note names more structure.

## Limits

- One case, one repository, one weak solving agent, four research trajectories. Four clusters.
- Per-cell dispersion is large (2.25 assertions mean spread, up to 7). Two rollouts per note reduce it;
  they do not remove it, and a third would move some of these means.
- `successWithinBudget` is 0/12: no note let the agent finish the whole task without hitting the wall.
  Twenty interactions is a hard budget; the gradient is entirely in residual work, not in completions.
- Amendment 3 was written after seeing stage 2's +0.42. It is a replication of *all twelve* notes, run
  once, with the analysis fixed in advance — but it is still an addition made after seeing a result,
  and stage 2's own estimate stays published beside the pooled one for exactly that reason.

## What the two rounds now support together

The acquisition round established, on the same case and the same four trajectories:

> semantic repository access reaches a given level of the pre-registered architecture checklist in
> **fewer environment interactions** than shell-only access, while spending **no fewer model output
> tokens**.

This round adds the missing link:

> that checklist level is not bookkeeping. A note distilled from a higher-`U` knowledge state leaves a
> weak agent measurably less residual work on the same task, under the same interaction budget, and
> costs it fewer interactions and fewer tokens to get there.

Chained: **semantic access → the same actionable architectural model in fewer environment interactions
→ a downstream agent that finishes more of the change with less work.** Both links are measured on one
Keycloak case with four independent research trajectories, and neither says anything about model-side
token cost, which remains the sign that does *not* favour semantic access.
