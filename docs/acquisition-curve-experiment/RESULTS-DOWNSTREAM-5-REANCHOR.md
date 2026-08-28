# Round 5 — the raised allowance, read on its own anchors

Seventeen cells on revision `098771a4c`, $5.29. Ledger: [RUN-IDS.md](RUN-IDS.md). Per-cell numbers:
[data/downstream5-anchors.csv](data/downstream5-anchors.csv).

The round was bought to answer two questions that the previous one could not answer honestly, because
its repair turn ran before the scratch-test discard and its loop did not await the agent. Both are
answered, and both answers are negative.

## 1. The floor rises with the allowance, and it rises past the rule

| case | allowance | gold note | no note |
|---|---|---|---|
| `cc-refresh-token` | 25 | 9/9, 9/9, 9/9 | **4/9**, did not build, **7/9** |
| `client-auth-method` | 25 | 9/9, 9/9, 9/9 | did not build, **6/9**, did not build |
| `oauth-grant-type` | 30 | 10/10, 8/10, 10/10 | did not build, did not build |

`BASELINE_SLACK` allows a no-note cell one obligation above the pristine floor — two of nine on the
first two cases. The readings are four, seven and six. The gap the wave would be measured on collapses
from nine obligations to two on `cc-refresh-token` and to three on `client-auth-method`, where the
admission rule asks for at least half the scale.

This is the risk the allowance change was committed with, and it is now measured: every extra
interaction is also handed to the agent with NO note, and on these two cases the extra interactions are
worth more to it than the note is. `client-auth-method` had already shown a 6-of-9 no-note cell at an
allowance of 20 and was tightened to 15 for exactly that reason; at 25 it does it again, and
`cc-refresh-token` joins it.

**The floor readings are a lower bound.** Five of the eight no-note cells left a tree that did not
build, and those cells are scored zero. They are the cells the repair turn was supposed to rescue, and
it could not — see below. A working repair can only move them up.

## 2. The repair turn cannot edit anything, and the reason is mechanical

Every cell that needed the repair loop used all three rounds and still did not compile. Read line by
line, build `1044601757` shows the loop itself is now correct — the right file of the graded scope, the
agent awaited, 20 s, 17 s and 26 s of real work — and shows why nothing came of it:

- the agent made 20 `Edit`/`Write` attempts across the three rounds;
- all 20 were refused with `File has not been read yet. Read it first before writing to it.`;
- because `Read` is walled by the budget gate, which is exhausted by then: 12 `PreToolUse:Read`
  refusals in the same window.

`Write`, `Edit` and `MultiEdit` are deliberately free, so a note's value is priced in discovery rather
than in keystrokes, and `Read` is deliberately NOT free, because reading is discovery. The interaction
between that rule and the CLI's own precondition — a file may not be edited before it is read in the
same session — leaves the repair turn, which starts a fresh session, with no way to touch a file.

So `repairRounds=3` on those cells does not mean the repair was tried and failed. It means it was
never able to run, and the column published a number for a mechanism that was inert. The earlier
finding that repair rescued zero cells of seventy-three stands as an observation and falls as an
explanation: nothing about the agent's ability was measured.

The harness already hands the agent the full contents of every file `javac` named, so letting the same
paths through `Read` free — by path, exactly as the CLI's own background-task output is already let
through — grants no discovery the cell does not already have.

## 3. What this does to the case family

The two questions interact, and the interaction is the round's actual result.

The allowance was raised because notes could not reach the ceiling inside 15 or 20 interactions. But a
large part of what an agent spends interactions on is getting its own code to build, which is what the
repair turn exists to absorb without spending them. With the repair turn inert, the allowance was
raised to pay for compilation — and the raise handed the same interactions to the no-note arm, which
spent them on discovery and closed the gap.

So the raise was withdrawn on the same day it was measured: `ACQUISITION_DOWNSTREAM_BUDGETS` is three
values again, every case runs at the allowance its own floor and ceiling were calibrated at (15, 15,
25), and the repair turn was given the one thing it lacked — permission to read back the files it is
handed, by path, free, for the duration of that turn only.

The next reading is therefore the anchors of all three cases at their own allowances, with a repair
turn that can act. If the gold note reaches its ceiling at 15 while the no-note cell stays at or below
two, the family is measurable at the number it was calibrated at, and the raise stays withdrawn as what
it was: a payment for a defect.

## 4. One cell is not a reading

`oauth-grant-type` `baseline` r53 (1044601769) died in setup, before the agent started:
`Process failed to register JDKs`, caused by the `syncDocuments` modal guard firing while the EDT was
in `BuildTreeView.rebuildCache` — import progress, not a dialog. The harness's own retry hit it again.
Known open defect (the `waitForProjectReady` race); the cell owes a re-buy and no agent money was
spent on it.

---

# Round 6 — the same anchors, with a repair turn that works

Eighteen cells, revision `3e39f1348`, every case at the allowance its own readings calibrated. $5.18.
Per-cell numbers: [data/downstream6-anchors.csv](data/downstream6-anchors.csv).

## The repair turn was verified in the container, not assumed

Build `1044788462` publishes `reads allowed free for this turn:` with the paths `javac` named, once per
round. Across its three rounds the agent attempted ten reads and two were refused — both of a file that
round's compiler output had not named, which is the exemption behaving as written. Where the same cell
shape previously produced 20 edit attempts and 20 `File has not been read yet` refusals, the agent now
reads what it was handed and edits it.

The mechanism's first measured rescues follow immediately: `1044788472` and `1044803876` (one repair
round each) and `1044788456` (three) carried no-note trees to a build that would otherwise have scored
zero for failing to compile.

## What the anchors say

| case | allowance | gold note | no note |
|---|---|---|---|
| `cc-refresh-token` | 15 | 9/9, 9/9, 9/9 | did not build, **1/9**, did not build |
| `client-auth-method` | 15 | **6/9**, **7/9**, 9/9 | **5/9**, did not build, did not build |
| `oauth-grant-type` | 25 | 10/10, 10/10, 10/10 | **6/10**, did not build, **6/10** |

`cc-refresh-token` is admitted, and it is the only one. Its gold note reaches the ceiling three times of
three without a single repair round — a note that says where the change belongs leaves the weak agent
nothing to be rescued from — while the one no-note tree the repair did carry to a build scored 1 of 9,
the obligation an untouched tree already satisfies. Compilation and understanding separate on this case,
which is precisely what makes it measurable.

`client-auth-method` is blocked twice over: one gold rollout fell under the reachability floor (6 of 9,
where 7 is asked), and a rescued no-note tree read 5 of 9. Its ceiling now reads 9, 7, 6 across three
replicates of the same condition — the case has form here, having produced 9 and then 0, 0, 0 in an
earlier round, and three rollouts is the minimum for exactly that reason.

`oauth-grant-type` is blocked on the floor alone, and reproducibly: both no-note trees that built read
6 of 10. Its gold note is perfect three times of three, so the ceiling is not in doubt — the unaided
solver simply knows most of this change already.

## The repair turn subtracts from the gap, and that is not a defect in it

It is given to both arms and only one arm needs it. Every gold rollout of round 6 that reached its
ceiling did so with `repairRounds=0`; every rescue the repair performed was of a no-note cell. So on a
case where the unaided solver knows the architecture and merely fails to compile, repair converts a zero
into most of the scale, and the measured value of the note falls accordingly.

That is the correct behaviour for an instrument whose endpoint is "did the change get made". It is also
the clearest statement yet of the limit found in round 4: about four fifths of every oracle here grades
implementation, so anything that helps implementation moves the floor more than it moves the ceiling.
Two cases of three now fail on that, not because the notes are bad but because the endpoint prices what
the note does not carry.

## What was bought on this reading

The wave on `cc-refresh-token`: 18 distilled notes × 2 replicates, at 15, listed in
[RUN-IDS.md](RUN-IDS.md). It was queued while the case's last three anchors were still running, on the
first three; the remaining three agreed. Nothing was bought on the two blocked cases, and nothing may
be until a reading changes their admission — for `oauth-grant-type` that means an endpoint the unaided
solver does not already satisfy, which is the architecture-weighted oracle argued for in round 4,
step 6, not another allowance.
