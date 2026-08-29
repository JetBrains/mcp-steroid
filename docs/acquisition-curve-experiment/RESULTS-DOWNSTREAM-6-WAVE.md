# Round 6, the wave — what a note is worth, measured on an instrument that works

Thirty-six cells on `cc-refresh-token`, revision `3e39f1348`, allowance 15, eighteen distilled notes ×
two replicates, $7.39. All 36 green. Per-cell numbers, with the `U` of each note beside its outcome:
[data/downstream6-wave-cc.csv](data/downstream6-wave-cc.csv). Anchors and their reading:
[RESULTS-DOWNSTREAM-5-REANCHOR.md](RESULTS-DOWNSTREAM-5-REANCHOR.md).

## The instrument first, because the last three rounds could not get past it

The repair turn's pre-registered prediction, written before this wave was bought:

| quantity | before | predicted | measured |
|---|---|---|---|
| cells producing a gradable tree | ~60 % | > 90 % | **86 %** (31 of 36) |
| within-note SD | 2.6–3.1 | 0.4–1.3 | **0.61** (13 notes whose replicates both built) |
| between-note SD | 2.2 | — | **1.89** |

The compile rate missed its predicted number and the noise beat its predicted range. What matters is the
ratio the round exists for: noise inside one note is now **0.61 against a signal of 1.89 between
notes**, where in round 4 it was 2.96 against 2.30 — noise larger than signal. Six of the seven
replicate pairs that both built differ by 0 or 1 obligation. A note's score is nearly deterministic once
its tree compiles, which is what the repair turn was bought to expose.

And the wave sits between the anchors instead of on the floor: scores run 2.0 to 8.0 against a floor of
1 and a ceiling of 9. The previous wave, on `oauth-grant-type`, produced three gradable trees out of
twenty-four.

## The primary reading: what the note SAYS predicts the outcome

`U_note` is the checklist score a judge gives the note's own text; `U_obs` is what the trajectory's
transcript shows the agent had seen. Both were computed in earlier rounds, offline, from committed
artifacts — neither was recomputed for this wave, and neither could have been tuned to it.

| | ρ(U, downstream) | permutation p |
|---|---|---|
| `U_note`, all 18 notes | **+0.62** | **0.0074** |
| `U_note`, the 13 notes whose two replicates both built | **+0.85** | **0.0005** |
| `U_obs`, all 18 notes | +0.12 | 0.63 |
| `U_obs`, the same 13 | +0.58 | 0.039 |

### Why this is not the correlation that was withdrawn

Round 2's ρ = +0.67 was withdrawn because it was computed across cells that had failed to compile, so it
could have been measuring the compile flip rather than the note. That specific confound is now testable,
and it is absent:

- **ρ(`U_note`, whether the tree compiled) = +0.09.** A better note does not make compilation more
  likely. So the five non-compiling pairs add noise to the outcome independent of `U` — which
  attenuates the correlation rather than manufacturing it, and is why the 13-note figure is the larger
  one rather than the smaller.
- **ρ(checkpoint, downstream) = +0.14.** The relation is not "a later checkpoint scores better", even
  though `U_note` does rise with the checkpoint (+0.55). Within a single checkpoint the relation
  survives: +0.60 at 5, +0.14 at 10, +0.93 at 20.
- **Within each arm separately**: +0.68 for the semantic arm's notes, +0.25 for the shell arm's.

### What it does not say

One case. The design reads the `U`-to-outcome relation within a case, and `cc-refresh-token` is the only
one its own anchors admit, so there is no across-case sign test behind this number — three cases were
anchored and two were refused by the floor rule, which is a fact about the endpoint, not about the
notes. Eighteen notes, thirty-six cells, one weak solver model.

## The arm difference is visible and not significant

| arm of the trajectory the note came from | mean downstream | notes |
|---|---|---|
| semantic (`mcp`) | **5.44** | 9 |
| shell (`none`) | **4.00** | 9 |

Mann-Whitney over the 18 note means, exact two-sided **p = 0.114**. A 1.4-obligation difference on a
9-point scale, in the direction the project predicts, at a sample size that cannot establish it. The
honest summary is that this wave was powered to relate `U` to outcome, and it did; it was not powered to
separate the arms, and it did not.

Note also what the two readings say together: `U_note` predicts the outcome at ρ = +0.62 while `U_obs`
does not (+0.12). What the agent saw is not what carries — what the note manages to say is. An arm that
looks at more of the repository buys nothing downstream unless its note ends up carrying more.
