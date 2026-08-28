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
