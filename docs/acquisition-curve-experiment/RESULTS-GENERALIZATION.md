# Generalization round: results

Pre-registered in `DESIGN-GENERALIZATION.md` before the first cell was queued. 24 research cells, four
cases, two arms, three independent trajectories per arm per case, all green, none rejected by the
degeneracy guard. Every number below is recomputed offline from `data/generalization-curves.csv`; the
build ids are in `RUN-IDS.md`.

## The headline: the shape of the curve, not the size of a p

The round asked whether round 2's result — *semantic access reaches a given level of architectural
understanding in fewer environment interactions, but not in fewer output tokens* — is a property of one
case or of the access. It replicates on two brand-new cases in untouched subsystems, and the replication
is worth reading as a **shape** rather than as a verdict. Pooled over all 24 trajectories:

| interactions spent | `U` mcp | `U` shell | advantage |
|---|---:|---:|---:|
| 5 | 0.585 | 0.310 | **1.89×** |
| 10 | 0.687 | 0.505 | 1.36× |
| 20 | 0.750 | 0.651 | 1.15× |
| 40 | 0.750 | 0.735 | **1.02×** |

Read down the last column: a large early gap, monotonically closing, ending in parity. That single
sequence is the round's actual finding, and it is a claim about a MECHANISM, not about a difference of
means. A tool that made the repository *comprehensible* would show a gap that persists at B=40 — the
shell arm would simply never get there. A tool that *accelerates acquisition* shows exactly this: the
same destination, reached earlier. The shell arm does reach 0.735; it just spends the entire allowance
doing it, and five of its twelve trajectories hit the wall at forty calls, while every semantic
trajectory stopped on its own, at a median of 16 (range 9–22).

The same shape holds inside each case, which is what makes it a shape rather than an average of four
unrelated things:

| case | B=5 | B=10 | B=20 | B=40 |
|---|---|---|---|---|
| `rename-method-wide` (navigational control) | +0.27 | +0.09 | +0.03 | +0.03 |
| `client-auth-method` (**new**) | +0.29 | +0.18 | +0.18 | +0.02 |
| `oauth-grant-type` (**new**) | +0.40 | +0.38 | +0.13 | −0.02 |
| `email-domain-mapper` (shallow control) | +0.14 | +0.08 | +0.06 | +0.03 |

Four cases, four monotone decays to approximately zero. No case ends with a gap; none of them widens.

*On the statistics, which are the supporting evidence and not the finding.* The pre-registered primary
test is a case-stratified exact permutation over trajectories (labels permuted within each case, the
four case-level differences summed, exhaustive over 20⁴ = 160 000 relabellings, so the floor is
p = 6·10⁻⁶ rather than the .05 a single 3-v-3 case is stuck with). It gives p = .0001 at B=5, .0009 at
B=10, .0065 at B=20 and **.63 at B=40**. The number worth quoting out of those four is the last one: it
is the only one that could have falsified the acceleration reading, and it did not. The three small
p-values say the early gap is not sampling noise; they say nothing at all about why it exists, and
quoting .0001 as the result would advertise the least informative thing the round measured.

## The prediction that failed

The round pre-registered an ordering: the navigational control first, the two architecture cases in the
middle, the shallow control last. Observed at B=10 the order is `oauth-grant-type` (+0.38) >
`client-auth-method` (+0.18) > `rename-method-wide` (+0.09) > `email-domain-mapper` (+0.08), a rank
correlation of ρ = +0.20 with the prediction. The bottom of the scale behaved (the shallow control is
last, as designed), but **the navigational control came third, not first**.

That is the round's most informative single number, and it says the mental model behind the prediction
was wrong. Renaming a widely-used method is the task semantic tooling is advertised for, yet the shell
arm reaches the same checklist there in fifteen to nineteen commands, because the knowledge is a
reference topology and `grep` enumerates references adequately when you know the name — and the control's
statement, uniquely, gives the name. The multi-stage architecture cases are where the arms separate,
because their knowledge is *which of several mechanisms is the one that applies*, and that is not a
question a text search can be pointed at.

So the effect is not "semantic tools win navigation tasks". It is closer to: **semantic tools win when
the next question depends on the answer to the previous one.**

## What kind of knowledge arrives early

Facts held after five interactions, summed over all twelve trajectories per arm, by category:

| category | mcp | shell | Δ |
|---|---:|---:|---:|
| invariant | 12 | 3 | **+9** |
| flow | 10 | 2 | **+8** |
| precedent | 19 | 11 | **+8** |
| secondary integration | 9 | 2 | **+7** |
| abstraction | 16 | 11 | +5 |
| implementation | 14 | 10 | +4 |
| wiring | 8 | 4 | +4 |
| verification | 3 | 3 | 0 |

This answers the standing objection that `U` might be a find-usages benchmark in disguise. The four
categories where the gap is widest are precisely the four that no reference query answers: what breaks a
naive implementation, how data moves at run time, which precedent is the right analogue, and which
second mechanism also has to be touched. The categories a reference query *does* answer — wiring,
implementation — separate the arms least.

The precedent milestone reproduces round 2's finding in a stronger form: at five interactions the
semantic arm had identified the correct architectural precedent in **12 of 12** trajectories, the shell
arm in 7 of 12.

## The token axis: still the other way round

| case | mcp tokens per unit of `U` | shell |
|---|---:|---:|
| `rename-method-wide` | 15 354 | 8 375 |
| `client-auth-method` | 24 073 | 17 440 |
| `oauth-grant-type` | 22 994 | 10 120 |
| `email-domain-mapper` | 15 942 | 13 141 |

The semantic arm is 1.2–2.3× more expensive per unit of understanding on every case, and mean output is
14 338 tokens against 8 799. The sign flip that three rounds have now found is not an artefact of one
case: **what semantic access buys is interactions, not thinking.** Wall-clock goes the third way again
(197 s against 98 s) — three denominators, three answers, which is why this family never merges them into
one score.

This round's token counts are the first that include delegated sub-agent output (`modelUsage`, all
models). Round 2's published axis read only the top model and undercounted three delegating shell
trajectories by 60–70 %.

## Trajectory examples

- `oauth-grant-type`, B=5. Semantic: 0.47–0.60, all three arms holding the shortcut invariant and the
  encoder that enforces it — a fact living two packages away from the change. Shell at the same budget:
  0.00, 0.20, 0.20, still listing the grants directory. The shell arm reaches the same 0.73–0.80, but at
  forty calls, having spent its whole budget.
- `client-auth-method`, `mcp-0757` is the round's counter-example and is left in: it plateaus at 0.53 and
  never moves, because it settled on the trust-validating precedent and stopped asking. Two of three
  semantic trajectories are not a rule, and the case's mean (0.64 at B=40) sits below the shell arm's
  best trajectory (0.67).
- `rename-method-wide`: all three semantic trajectories stopped at nine or ten calls with 0.73–0.82 and
  nothing left to ask. The shell arm needed fifteen to nineteen. Same destination, twice the road.

## What this does and does not license

Supported now, on four cases and 24 trajectories:

> Semantic repository access ACCELERATES the acquisition of an actionable architectural model of a
> large unfamiliar repository: the advantage is 1.89× at five environment interactions, 1.36× at ten,
> 1.15× at twenty and 1.02× at forty. It is a curve that closes, not a capability the other arm lacks.
> The advantage is concentrated in the facts a reference query cannot produce — invariants, runtime
> flow, the correct precedent, the second integration mechanism — and it costs more model output, not
> less.

Not supported: any claim about a second repository (deferred with measurements — Kill Bill is TestNG and
database-bound, Camel is 1 124 modules against Keycloak's 189, Dubbo is the viable candidate and needs a
prewarm probe first), and any claim about downstream implementation on these two new cases, which have
no hidden oracle by design. The downstream link remains the one measured in `RESULTS-DOWNSTREAM-2.md` on
the original case: ρ(U, obligations met) = +0.668.
