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

`V` = fraction of probes that finished the task from that state, over all 276 verdicts recovered from the
74 probe builds. The cost columns come in two flavours because they answer different questions: over the
SUCCESSFUL probes they are the PRICE OF FINISHING from that state, while over all probes they mostly
report how long a failure burns before its budget runs out — which is the same information `V` already
carries, counted twice.

| arm | editFraction | step | patch chars | runs | successes | V | $ succ | s succ | tokens succ | $ all | s all |
|:---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| mcp | 0.000 | 15 | 2436 | 4 | 1 | 0.25 | n/a | n/a | n/a | n/a | n/a |
| mcp | 0.100 | 16 | 3951 | 4 | 3 | 0.75 | 1.035 | 1552 | 128432 | 1.035 | 1552 |
| mcp | 0.200 | 17 | 4408 | 5 | 4 | 0.80 | n/a | 1800 | 114793 | n/a | 1800 |
| mcp | 0.300 | 18 | 6639 | 5 | 3 | 0.60 | 0.830 | 1473 | 113313 | 0.830 | 1604 |
| mcp | 0.400 | 19 | 37498 | 5 | 5 | **1.00** | 0.190 | 1004 | 75103 | 0.190 | 1004 |
| mcp | 0.800 | 24 | 37288 | 5 | 5 | **1.00** | 0.290 | 784 | 72829 | 0.290 | 784 |
| none | 0.000 | 17 | 712 | 5 | 3 | 0.60 | 0.745 | 1199 | 108263 | 1.101 | 1458 |
| none | 0.200 | 25 | 3511 | 5 | 4 | 0.80 | 0.760 | 1536 | 119686 | 0.760 | 1589 |
| none | 0.300 | 29 | 8752 | 5 | 1 | 0.20 | 0.913 | 1448 | 113987 | 0.953 | 1569 |
| none | 0.400 | 33 | 12669 | 5 | 5 | **1.00** | 0.784 | 1265 | 110276 | 0.784 | 1265 |
| none | 0.500 | 37 | 21078 | 5 | 2 | 0.40 | 0.597 | 864 | 109412 | 0.716 | 1316 |
| none | 0.600 | 41 | 22484 | 5 | 5 | **1.00** | 0.772 | 1393 | 114264 | 0.772 | 1393 |
| none | 0.700 | 45 | 25972 | 5 | 4 | 0.80 | 0.537 | 946 | 102290 | 0.542 | 954 |
| none | 0.800 | 49 | 40196 | 5 | 5 | **1.00** | 0.319 | 812 | 82655 | 0.319 | 812 |

Cost figures are means, not medians, because with two to five successes a median just picks a single
run. `patch chars` is the size of the inherited state — the diff the probe starts on top of. The two mcp
rows with `n/a` money carry verdicts recorded before the verdict line grew its cost fields; mcp 0.000 has
four runs because its fifth cell was lost to the instrument and is the one gap left in the grid.

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
| mcp | 0.678 | 0.000 … 0.800 | **0.847** | 6 |
| none | 0.570 | 0.000 … 0.800 | **0.713** | 8 |

The ranges are identical, so the two numbers are directly comparable: over its edit phase the mcp
trajectory spends more of its length in states a weak agent can finish from. The gap is 0.13 in
normalised AUC, which on 6 and 8 points of five replicates each is suggestive and not conclusive — see
the threats below.

## Baseline — what the number has to beat

The pristine tree (identical in both arms, so it belongs to neither curve) was probed 9 times on the
first grid — mcp step 2 and none step 4, whose trees are bit-identical — and finished 6 times: **0.67**.
A bare Haiku solves this task unaided two thirds of the time, which bounds everything above: the
interesting range of `V` is 0.67…1.0, and four of the fourteen cells fall BELOW that no-information
baseline.

That is the pilot's most useful negative finding. A half-written state is not monotonically better than a
clean one: mcp at 0.000 (`V = 0.25`) and 0.300 (`V = 0.60`), and shell at 0.300 (`V = 0.20`) and 0.500
(`V = 0.40`), are all worse than starting from scratch. Inheriting someone's unfinished edit costs the
probe the effort of understanding it, and on this task that cost is real enough to dominate the head
start. Both arms' worst cell sits at 0.3 of the edit phase — the point where enough has been written to
constrain the design but not enough to be self-explanatory.

## The cost of finishing

This is what still moves after `V` saturates. Both arms show the same shape: early in the edit phase a
successful probe spends 20–30 minutes, 110–130 k context tokens and $0.75–1.04; at 0.8 of the edit phase
it spends 13 minutes, 73–83 k tokens and $0.29–0.32. A state deep in the trajectory is not just more
often finishable — it is three times cheaper to finish. mcp reaches the cheap regime earlier: $0.19 /
1004 s / 75 k already at 0.400, where shell is still at $0.78 / 1265 s / 110 k at the same fraction, and
only gets to $0.32 / 812 s / 83 k at 0.800.

## Method verification (the pilot's actual question)

1. **States restore correctly.** Every committed patch applied to a freshly deployed clone; the only
   application failure in 95 cells was one empty-patch case, fixed before the grid ran.
2. **The probe can continue from them.** 55 of 57 new cells produced a graded verdict on the first
   attempt; two were re-queued and both then passed, leaving one gap in 57.
3. **`V` moves meaningfully** — 0.20 … 1.00 across states of one task, and it is NOT monotone, which is
   information about the task rather than noise in the instrument.
4. **Readiness does rise along the trajectory**, but only in trend: both arms end at 1.00 and start
   below it, with a dip in between.
5. **Five replicates are not enough** to separate 0.60 from 0.80 (Wilson 68 % intervals overlap heavily);
   they are enough to separate 0.20 from 1.00. A scale-up should either raise the replicate count or
   lean on the cost-of-finishing metric, whose spread is much narrower. A cheaper third option the logs
   already support: grade PARTIALLY — the verifier reports per-class results, so "104 of 107 tests green"
   and "61 of 107" are distinguishable where a binary `Y` calls both a zero.
6. **Cost of the pilot**: 2 Opus captures $7.51, 95 Haiku probes ≈ $34, one preflight ≈ $0, and about
   90 TeamCity build-hours. Per curve point: ≈ $2.4 and ≈ 5 build-hours.

## Threats to this result

- **One task, one capture per arm.** The mcp arm's edit phase is 11 turns, so its curve rests on 6
  points, two of which (0.100, 0.200) carry verdicts recorded before the cost fields existed. A second
  capture of the same arm would tell how much of the difference is the arm and how much is the run.
- **The mcp arm has a gap between 0.400 and 0.800** because steps 20–23 held trees identical to step 19.
  The curve is flat there by construction, not by measurement, and that flat stretch is 0.4 of the
  integration range — half of the mcp AUC rests on an interpolation between two points.
- **Transport aborts are a real error source.** One cell (`none/5/1`) was published as `Y = 0` after
  Anthropic closed the connection 26 s in, with 9 reads and 0 edits. It is now detected
  (`extractApiTransportError`) and reported as `LOST`, not as a zero — but every earlier grid predates
  that detector, so old zeros carry an unknown share of such aborts.
- **`dockerOracleWorks = true` for this case only.** The result does not transfer to a case whose oracle
  cannot run in the container.
