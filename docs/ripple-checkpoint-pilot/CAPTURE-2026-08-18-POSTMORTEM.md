# Checkpoint pilot — what the first two captures measured, and why they were thrown away

Two Opus capture runs of `ripple__keycloak__rename-method-wide` finished on 2026-08-18. Both agents
solved the task; both captures were **rejected**, and the instrument they exposed had to be rebuilt
before any probe build could be queued. This file is the evidence, so the same money is not spent twice.

| | mcp arm | shell arm |
|:---|:---|:---|
| TeamCity build | 1034576458 (crash), 1034618405 (preflight), **1034656372** | **1034656374** |
| SUCCESS / f1 | true / 1.0000 | true / 1.0000 |
| tool calls `n` (hook counter) | **23** | **51** |
| `tool_use` events in the raw NDJSON | 23 | 53 |
| agent wall time | 990 s | 751 s |
| cost | $1.12 | $3.31 |
| tools reported by `[RIPPLE]` | 21 (7 steroid / 13 bash) | 50 (0 steroid / 44 bash / 2 edit) |
| admitted | false | false |

The schedule both arms were snapshotted at was `2, 6, 11, 17, 24`, precomputed from an assumed
`n̂ = 32`.

## Defect 1 — a schedule computed before the run cannot be the schedule of the run

The pilot's positions are defined as `round(n·(i/6)^1.5)`, i.e. ≈7/19/35/54/76% **of the trajectory's
own length**. `n` is not knowable until the run ends, so the first implementation guessed it from the v3
mean and snapshotted five fixed steps. The guess missed in both directions at once:

- **mcp: 23 steps.** `a_5 = 24` was never reached, so that arm has **no fifth state at all** — and the
  four states it does have sit at 8.7 / 26 / 48 / 74% instead of 7 / 19 / 35 / 54%.
- **shell: 51 steps.** Its deepest checkpoint landed at **47%** of the trajectory, not 76%. The curve
  would have stopped less than halfway into the run and still been labelled as reaching 76%.

Two arms with `n` differing by a factor of 2.2 cannot share one absolute schedule *and* satisfy the
specification's normalized positions. Curves are therefore compared at equal `a_i/n`, not at equal
tool-call counts.

## Defect 2 — two checkpoints of one state

`step-11.patch` and `step-17.patch` of the mcp arm are **byte-identical** (298 762 chars, 111 files,
`setRealm` → `bindRealm`). The agent completed the whole rename at step 11 and never touched a file
again; steps 12–17 were verification. Probing both would have published one measured state as two
points of a readiness curve — and, with five replicates each, spent ten probe builds to measure one
number.

The shell arm shows the same shape one checkpoint later: `step-2/6/11/17` are all empty, `step-24`
holds the complete solution.

## Defect 3 — the token criterion compared two different quantities

The gate rejected `end-context tokens 17496 are outside the v3 mcp mean±1sd 63249.2..86890.0`. The v3
band was measured as the **last assistant message's** `input + cache_read + cache_creation + output`
(cumulative context). The value fed to the gate was `TokenUsage.totalTokens` — `input + output` of the
terminal `result` event, a third quantity again (that event's own cache-read counter was 969 851, the
sum over every request of the run). No run could ever have satisfied that comparison.

## Defect 4 — the case itself is unsuitable for a readiness curve

Even with a perfect instrument, `rename-method-wide` cannot produce a rising `V(x)`: its solution is
one atomic edit, so disk state jumps from "nothing" to "solved". A readiness curve on it is a step
function whose position measures *when the agent happened to run the rename*, not how readiness grows.
The pilot therefore moves to a case whose solution is assembled incrementally and graded by a test
suite — `dpaia__spring__boot__microshop-18` (RestTemplate → WebClient across 3 modules, 8
FAIL_TO_PASS classes).

## What changed as a result

| Defect | Fix |
|:---|:---|
| fixed schedule | the hook snapshots and tags EVERY tool call; positions are selected afterwards from the measured `n` (`RippleCheckpointRecorder.plan`) |
| duplicate states | `selectCheckpoints` compares git **tree ids**: a checkpoint repeating the previous state moves to the next differing step, and is dropped with a stated correction when none exists |
| token criterion | `extractEndContextTokens` measures context the way v3 did; `ArenaRunMetrics.endContextTokens` carries it; `admitCapture` takes `contextTokens` |
| `n > a_5` criterion | deleted — impossible by construction now; replaced by "the run must be longer than the checkpoint count" |
| no sample for a new case | `admitCapture(reference = null)` admits the capture and reports the missing sample as a NOTE instead of judging against invented numbers |

## Cost of the discarded stage

$4.43 of API spend (2 captures) plus ~2.5 h of TeamCity agent time, and one crashed build
(1034576458, `mkdir /checkpoints` as an unprivileged container user). The preflight (1034618405) was
worth its ~35 min: it proved the `PostToolUse` hook fires in `claude-code/2.1.159` and that the
snapshot sees the agent's writes.

The recorded artifacts are kept — `mcp/checkpoints/` and `none/checkpoints/` of those two builds still
hold the shadow repositories, so the 23-step and 51-step trajectories remain re-analysable offline.
