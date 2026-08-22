# Fixed-budget repository understanding — results

What was run, what it says, and what it does not say. Every number here comes from a build's own
`[UNDERSTANDING-*]` log line; the build ids are in [RUN-IDS.md](RUN-IDS.md), the design and the
pre-registered rules in [DESIGN.md](DESIGN.md).

The hypothesis under test:

> With a fixed exploration budget, semantic repository access lets a strong agent acquire and distil a
> more useful actionable model of a large unfamiliar codebase, as measured by the downstream performance
> of a weaker agent receiving only that distilled note.

Case: `understanding__keycloak__email-domain-mapper` — add an OIDC mapper that contributes an
`email_domain` claim, honour the four administrator switches, and make it available out of the box in a
fresh realm. Two integration points in two different mechanisms (a `META-INF/services` entry and
`OIDCLoginProtocolFactory.initBuiltIns`), and the statement names neither.

## The headline table

`Y` is the hidden oracle's verdict, five downstream runs per note. Each cell is ONE note; where a
condition has several notes they are listed separately, because five runs of one note measure the note.

| research budget | note limit | mcp arm | shell arm |
|---:|---:|:---|:---|
| 5 | 1 000 | 1/5 | 2/4 |
| **10** | **1 000** | **5/5, 4/5, 0/5 → 9/15** | **0/5, 0/5, 0/5 → 0/15** |
| 5 | 2 000 | 3/5 | 5/5 |
| 10 | 2 000 | 5/5 | 5/5 |
| 5 | 3 000 | 5/5 | 5/5 |
| 10 | 3 000 | 4/5 | 3/5 |
| 5 | 5 000 | 5/5 | 5/5 |
| 10 | 5 000 | 5/5 | 5/5 |

Controls: **baseline 0/5** (no note), **`oracle:gold` 3/3** (a hand-written note). The instrument has
range: the same weak agent goes from never solving the task to always solving it, and the only thing that
changes is the note.

## What the data supports

**1. A note is worth the whole task.** Baseline 0/5 against 45/50 across all note conditions at 2 000
characters and above. Whatever the arms disagree about, they agree that a distilled model of the
repository is what the weak agent lacked — it was never a capability bottleneck. The failure mode without
a note was always the same and always about understanding: the e-mail was read off the user session
instead of the token being issued (a null path in the oracle), and the built-in registration was missed
entirely.

**2. The arms separate only under a hard information bottleneck.** At 1 000 characters and a budget of
ten interactions: mcp 9/15, shell 0/15. At 2 000 and above the difference vanishes — 19/20 versus 18/20
pooled. The interpretation that fits: with enough characters both arms can afford to write down
everything that matters; when the note must be cut to a thousand characters, WHAT gets cut decides the
outcome, and that is a question about what the researcher understood to be essential.

**3. The two arms behave differently while researching, in a way no downstream number shows.** At a
budget of ten the mcp arm stopped early — 8, 9 and 9 calls used, zero refused. The shell arm spent all
ten in every cell and then kept asking: four refusals typically, ten in one cell, i.e. it wanted twice
its budget. It also produced consistently cheaper notes (4 101–8 799 output tokens versus 7 132–9 002),
so the mcp arm buys its earlier stop with model compute — the same sign flip round 2 of the checkpoint
pilot found. **Per environment interaction the mcp arm is ahead; per output token it is not.** The two
denominators are reported separately and must stay that way.

## What the data does NOT support

**The effect is not established as an arm effect.** Three mcp notes at (10, 1 000) scored 5/5, 4/5 and
**0/5**. The between-note spread is as large as the arm difference. A permutation test at the level that
matters — the note, not the downstream run — gives one-sided **p = 0.2** (four of twenty labellings of
six notes are at least as extreme). The run-level p of 0.0001 is not admissible: it treats five runs of
one note as five independent observations.

So the honest statement is: **at (10, 1 000) the mcp arm produced the only notes that worked, but with
three notes per arm this is a promising asymmetry, not a demonstrated effect.**

**One case, one downstream model.** Everything above is `email-domain-mapper` with a haiku downstream.
Nothing here generalises to another repository or another weak agent without being measured.

**The 1 000-character condition partly measures prioritisation.** Both arms overran the limit in every
cell and were cut. `mcp-b5-l1000-r1` ends mid-sentence exactly where it was about to name the second
registration; `none-b5-l1000-r1` had named both earlier and kept them. Under a hard cut, the order in
which an agent writes things down matters as much as what it knows — which is a real skill, but not the
one the hypothesis is about.

## The instrument defects that were found on the way

Both were silent, both produced plausible numbers, and both invalidated everything measured before them
(fixed in `f3b0a1e2c`):

- **The in-container request builder is immutable**, and its calls were written as separate statements,
  so only the last survived and the container ran `bash -c ''`. The budget counters therefore read
  `calls=0`, and — much worse — the pristine check certified every tree as untouched off a `git status`
  that never ran. It now builds the command as one expression, prints a completion marker and refuses to
  certify a tree on silence.
- **The research phase read the console-filtered `stdout`.** The filter drops the terminal `result`
  event, which is where the note travels. Twenty paid Opus runs across two waves reported "no final
  message" while their notes sat complete in the build log. `ArenaTestResult.agentResult` is now typed
  as `AiProcessResult` so `rawStdout` is reachable, and a unit test forbids the wrong spelling.

## What would settle it

The design is now cheap where it counts: a note costs ≈ $0.3–0.6 and 100 seconds; a downstream cell
costs ≈ $0.3 and 4 minutes. The decisive experiment is therefore **more notes, not more downstream runs
per note**:

1. **Ten notes per arm at (10, 1 000)**, three downstream runs each. Same total spend as this wave
   (≈ $25), but the unit of replication becomes the note, and a permutation test over twenty notes can
   reach p ≈ 0.01 if the asymmetry is real.
2. **A second case** with the same shape (a behaviour plus a registration in a different mechanism), to
   check that the bottleneck is not specific to `initBuiltIns`.
3. **No more cells at 5 000 characters.** That condition is saturated at 20/20 and cannot separate
   anything.

## Spend

| wave | runs | usd |
|:---|---:|---:|
| calibration (baseline, oracle) | 6 | 2.62 |
| research notes, all waves incl. voided | 32 | ≈ 13 |
| downstream, 5 000 and 1 000 | 42 | ≈ 13 |
| downstream, 2 000 / 3 000 / replicates | 60 | 18.89 |
| **total** | **140** | **≈ 47** |
