# Solution-readiness pilot — measured result

Case `dpaia__feature__service-125`, source agent `claude-opus-5`, probe agent bare `claude-haiku-4-5`
with no MCP and no prior knowledge of the trajectory. Every probe is graded by the same
`ArenaVerifier.verify` the source run was graded by: 5 FAIL_TO_PASS classes green, no regression against
the measured baseline, no tamper. `Y = 1` only then.

## What the axis is, and why it is not the obvious one

Three axes were tried, and the first two are wrong for a reason worth keeping written down.

**Position within the run (`step/n`) is wrong** because it divides by the very quantity under
measurement. The mcp arm solved this task in 26 tool calls, the shell arm in 57; normalising each by its
own length makes the shorter run look slower. On that axis the shell arm's AUC came out HIGHER
(0.568 vs 0.387), which is an artefact, not a finding.

**Absolute turn index is wrong** because the two arms spend nearly the same effort READING before they
write anything — mcp's first tree change is at turn 15 of 26, shell's at turn 17 of 57 — and then their
edit phases differ fourfold: 11 turns against 40. Turn 4 of 11 is not turn 4 of 40.

**The axis used here is the fraction of the EDIT phase**:
`editFraction = (step − firstWriteStep) / (n − firstWriteStep)`, sampled at `k/10`, `k = 0…9`. `k = 0` is
the first write; the final state is never a checkpoint, because the source run's own outcome is judged
separately. A fraction whose tree repeats an earlier one is kept in the metadata (it records that the
agent wrote nothing in that window) but is not probed twice — mcp buys 6 distinct states out of its 10
fractions, shell 8.

## V(editFraction)

`V` = fraction of probes that finished the task from that state. Median cost columns are over the
SUCCESSFUL probes only — an unsuccessful probe usually burns its whole 30-minute budget, so mixing them
in would measure the failure rate twice.

| arm | editFraction | step | runs | successes | V | median $ | median s | median turns |
|:---|---:|---:|---:|---:|---:|---:|---:|---:|
| mcp | 0.000 | 15 | 5 | 2 | 0.40 | n/a | n/a | n/a |
| mcp | 0.091 | 16 | 5 | 3 | 0.60 | 1.035 | 1553 | 100 |
| mcp | 0.182 | 17 | 5 | 4 | 0.80 | n/a | 1800 | n/a |
| mcp | 0.273 | 18 | 5 | 3 | 0.60 | 0.877 | 1385 | 89 |
| mcp | 0.364 | 19 | 5 | 5 | **1.00** | 0.197 | 703 | 25 |
| mcp | 0.818 | 24 | 5 | 5 | **1.00** | 0.220 | 486 | 30 |
| none | 0.000 | 17 | 5 | 3 | 0.60 | 0.745 | 1199 | 75 |
| none | 0.200 | 25 | 4 | 3 | 0.75 | 0.686 | 1800 | 71 |
| none | 0.300 | 29 | 5 | 1 | 0.20 | 0.913 | 1448 | 94 |
| none | 0.400 | 33 | 4 | 4 | **1.00** | 0.658 | 1447 | 71 |
| none | 0.500 | 37 | 5 | 2 | 0.40 | 0.597 | 864 | 64 |
| none | 0.600 | 41 | 5 | 5 | **1.00** | 0.764 | 1428 | 80 |
| none | 0.700 | 45 | 5 | 4 | 0.80 | 0.520 | 910 | 58 |
| none | 0.800 | 49 | 5 | 5 | **1.00** | 0.338 | 778 | 37 |

Rows with 4 runs are cells whose fifth replicate was lost to the instrument and re-queued; rows whose
cost columns say `n/a` are the ones whose verdicts predate the cost-carrying verdict line.

A probe that spent its whole 30-minute budget is a **zero**, not a loss: exhausting the budget is exactly
what failing to finish the task looks like, and withholding such a cell would silently shrink the sample
of the hardest states — which is where `V` is most informative. `agentRunTimedOut` reads that from the
runner's own report (`PROCESS_TIMEOUT_EXIT_CODE` plus the timeout marker on stderr), not from the wall
clock, so a container that died just before the limit still withholds a verdict instead of donating a
false zero. Only three things are withheld as `LOST`: the checkpoint patch failing to apply, the
container or harness producing no grade at all, and a transport abort of the agent's API connection.

## AUC

Trapezoidal over the measured range only, nothing extrapolated:

| arm | AUC | range | width-normalised | points |
|:---|---:|:---|---:|---:|
| mcp | 0.700 | 0.000 … 0.818 | **0.855** | 6 |
| none | 0.563 | 0.000 … 0.800 | **0.703** | 8 |

The ranges are almost the same width, so the normalised numbers are comparable: over its edit phase the
mcp trajectory spends more of its length in states a weak agent can finish from.

## Baseline — what the number has to beat

The pristine tree (identical in both arms, so it belongs to neither curve) was probed 9 times and
finished 6 times: **0.67**. A bare Haiku solves this task unaided two thirds of the time, which bounds
everything above: `V` can only move inside 0.67…1.0, and a single `V = 0.40` cell is BELOW the
no-information baseline.

That is the pilot's most useful negative finding. A half-written state is not monotonically better than
a clean one: mcp at 0.091 and 0.273 (`V = 0.60`) and shell at 0.300 (`V = 0.20`) and 0.500 (`V = 0.40`)
are all worse than starting from scratch. Inheriting someone's unfinished edit costs the probe the effort
of understanding it, and on this task that cost is real enough to dominate the head start.

## The cost of finishing

This is what still moves after `V` saturates. Both arms show the same shape: at the start of the edit
phase a successful probe needs 60–100 turns and 20–30 minutes and about $0.7–1.0; at 0.8 of the edit
phase it needs 30–37 turns and 8–13 minutes and $0.22–0.34. A state deep in the trajectory is not just
more often finishable — it is three to four times cheaper to finish. mcp reaches the cheap regime
earlier: $0.197 / 703 s already at 0.364, where shell is still at $0.658 / 1447 s at 0.400.

## Method verification (the pilot's actual question)

1. **States restore correctly.** Every committed patch applied to a freshly deployed clone; the only
   application failure in 95 cells was one empty-patch case, fixed before the grid ran.
2. **The probe can continue from them.** 55 of 57 new cells produced a graded verdict.
3. **`V` moves meaningfully** — 0.20 … 1.00 across states of one task, and it is NOT monotone, which is
   information about the task rather than noise in the instrument.
4. **Readiness does rise along the trajectory**, but only in trend: both arms end at 1.00 and start
   below it, with a dip in between.
5. **Five replicates are not enough** to separate 0.60 from 0.80 (Wilson 68 % intervals overlap heavily);
   they are enough to separate 0.20 from 1.00. A scale-up should either raise the replicate count or
   lean on the cost-of-finishing metric, whose spread is much narrower.
6. **Cost of the pilot**: 2 Opus captures $7.51, 95 Haiku probes ≈ $34, one preflight ≈ $0, and about
   90 TeamCity build-hours. Per curve point: ≈ $2.4 and ≈ 5 build-hours.

## Threats to this result

- **One task, one capture per arm.** The mcp arm's edit phase is 11 turns, so its curve rests on 6
  points, two of which (0.091, 0.182) carry verdicts recorded before the cost fields existed. A second
  capture of the same arm would tell how much of the difference is the arm and how much is the run.
- **The mcp arm has a gap between 0.364 and 0.818** because steps 20–23 held trees identical to step 19.
  The curve is flat there by construction, not by measurement.
- **Transport aborts are a real error source.** One cell (`none/5/1`) was published as `Y = 0` after
  Anthropic closed the connection 26 s in, with 9 reads and 0 edits. It is now detected
  (`extractApiTransportError`) and reported as `LOST`, not as a zero — but every earlier grid predates
  that detector, so old zeros carry an unknown share of such aborts.
- **`dockerOracleWorks = true` for this case only.** The result does not transfer to a case whose oracle
  cannot run in the container.
