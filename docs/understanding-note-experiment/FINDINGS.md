# What the understanding-note experiment actually found

One file, every round, including the rounds that turned out to measure the wrong thing. Numbers come
from the builds' own `[UNDERSTANDING-NOTE]` / `[UNDERSTANDING-DOWN]` log lines; build ids are in
[RUN-IDS.md](RUN-IDS.md), the pre-registered design in [DESIGN.md](DESIGN.md), the per-round narrative
in [RESULTS.md](RESULTS.md). Everything below can be recomputed:

```
python3 docs/understanding-note-experiment/analysis/verify_notes.py \
  --notes test-experiments/src/test/resources/understanding-notes/understanding__keycloak__email-domain-mapper/raw \
  --runs  docs/understanding-note-experiment/data/downstream-runs.csv \
  --kc    <keycloak checkout at 60c4d5e9>
```

## The setup, in one paragraph

A strong agent (opus) explores a pinned Keycloak tree under a hard budget of environment interactions,
is forbidden to touch the tree, and hands back a note of at most L characters. A weak agent (haiku,
shell only) then gets the pristine tree, the same task and that note, and its patch is graded by a
hidden JUnit oracle. The task: contribute an `email_domain` claim from the e-mail *carried by the token
being issued*, honour the four administrator switches, and be offered out of the box in a fresh realm.
Two integration points in two different mechanisms — a `META-INF/services` entry and a model put into
`OIDCLoginProtocolFactory.initBuiltIns()` — and the statement names neither.

## The headline: what decides whether the weak agent succeeds

**Not the arm. The exemplar the note tells it to copy.**

Every mapper in `services/.../protocol/oidc/mappers/` overrides the same
`setClaim(IDToken, ProtocolMapperModel, UserSessionModel)`. Eleven of them read the user out of that
session; eleven do not. The oracle passes a **null** session — the statement says the domain comes from
the token — so an agent that copies a session-reading exemplar throws
`NullPointerException: Cannot invoke "UserSessionModel.getUser()" because "userSession" is null`
regardless of how correct the prose around the class name was.

At L = 500, where each note can name at most one or two exemplars, the correspondence is total:

| note | exemplar named | reads the session? | downstream |
|---|---|---|---:|
| `none-b10-l500-r1` | `HardcodedClaim` | no | **5/5** |
| `mcp-b10-l500-r2` | `HardcodedClaim` | no | 4/5 |
| `mcp-b10-l500-r3` | `AllowedWebOriginsProtocolMapper` | no (has no `setClaim` at all) | 3/5 |
| `mcp-b10-l500-r1` | — ("copy a sibling") | — | 1/5 |
| `none-b10-l500-r2` | `FullNameMapper` | **yes** | 0/5 |
| `none-b10-l500-r3` | `FullNameMapper`, `AddressMapper` | **yes** | 0/5 |

Token-only or unnamed exemplar: 13/20. Session-reading exemplar: **0/10**.

Failure signatures across the 17 failed runs of this round: **16 × the null-session NPE**, 1 other. Not
one failure was the built-in registration.

### The finding that surprised me

**All six notes say to read the e-mail off the token** (five say `token.getEmail()` verbatim; the sixth
says "off the token"). The weak agent still went to the user session in 10 of those runs — every time
the note had named a session-reading class. It follows the *name*, not the *prose*. A note is not read
as instructions; it is read as a pointer to code to imitate, and the imitation overrides the sentence
next to the pointer.

That is the mechanism behind every earlier round's numbers, and it is worth more than the arm
comparison: **for a weak downstream agent, the single most valuable thing a note can contain is the
name of a precedent whose shape matches the task — and naming a near-miss precedent is worse than
naming none at all.**

## All rounds, all cells

`Y` = hidden oracle verdict, five downstream runs per note. Each row is ONE note.

| limit | budget | arm | note | Y |
|---:|---:|---|---|---:|
| — | — | — | baseline (no note) | **0/5** |
| — | — | — | `oracle:gold` (hand-written) | **3/3** |
| 500 | 10 | mcp | r1 / r2 / r3 | 1/5 · 4/5 · 3/5 → **8/15** |
| 500 | 10 | shell | r1 / r2 / r3 | 5/5 · 0/5 · 0/5 → **5/15** |
| 1 000 | 10 | mcp | r1 / r2 / r3 | 5/5 · 4/5 · 0/5 → **9/15** |
| 1 000 | 10 | shell | r1 / r2 / r3 | 0/5 · 0/5 · 0/5 → **0/15** |
| 1 000 | 5 | mcp / shell | r1 | 1/5 · 3/5 |
| 2 000 | 5 | mcp / shell | r1 | 3/5 · 5/5 |
| 2 000 | 10 | mcp / shell | r1 | 5/5 · 5/5 |
| 3 000 | 5 | mcp / shell | r1 | 5/5 · 5/5 |
| 3 000 | 10 | mcp / shell | r1 | 4/5 · 3/5 |
| 5 000 | 5, 10 | mcp / shell | r1 | 5/5 in all four cells |

Roughly 180 agent runs in total (research plus downstream, including the cells voided by the instrument
defects listed at the end) for about $60. The authoritative per-build ledger is [RUN-IDS.md](RUN-IDS.md);
the machine-readable downstream outcomes are `data/downstream-runs.csv`, which currently holds the 112
rows whose build logs were still retrievable.

## Round by round: what each one actually measured

### L = 5 000 — saturated
20/20. With five thousand characters both arms write down everything that matters, and the note carries
the task. This length cannot separate anything and should never be run again.

### L = 1 000 — measured the harness, not the agents
mcp 9/15 versus shell 0/15 looked like the result of the experiment. It was not: **every note of both
arms overran the limit** — by 151 characters at best and by 1 020 at worst — so the harness cut them,
and the arms differed by where the knife landed. `mcp-b5-l1000-r1` ends mid-sentence exactly where it
was about to name the second registration point; `none-b5-l1000-r1` had named both earlier and kept
them. What the round measured is the order in which an agent writes things down.

The `verify_notes.py` output makes this quantitative: notes whose built-in-registration instruction
survived the cut solved **54/75**; notes where it was cut solved **7/35**. The arm split over the same
runs is 35/55 versus 26/55 — the cut is the stronger predictor by a wide margin.

### L = 2 000 and 3 000 — no arm difference
17/20 against 18/20. Enough room for both arms to say everything; nothing to separate.

### L = 500 — the honest round
The brief was rewritten to make the limit the agent's own budget ("count the characters before you send
it, and rewrite until it fits"), and a note that overruns is now **re-run, not truncated** — the harness
prints a loud `OVERRUN` line and the cell is repeated. Three cells needed a retry (raw 866, 501 and 695
characters); the six notes that entered the comparison are 479–499 characters, none cut.

Result: mcp 8/15, shell 5/15, and the best note of the whole experiment is a **shell** note (5/5).
A note-level permutation test on the arm gives one-sided **p = 0.4** — no arm effect. The same test on
the exemplar classification gives **p = 0.067** with a much larger effect size (+3.25 solved per note).

## Research-phase behaviour (the other denominator)

Accepted 500-character cells:

| note | calls used | refused after budget | opus output tokens | seconds | USD |
|---|---:|---:|---:|---:|---:|
| `mcp-r1` | 10 | 6 | 7 518 | 106 | 0.41 |
| `mcp-r2` | 10 | 4 | 7 056 | 101 | 0.35 |
| `mcp-r3` | **6** | **0** | 7 450 | 91 | 0.51 |
| `none-r1` | 10 | 5 | 6 712 | 100 | 0.35 |
| `none-r2` | 10 | 4 | 4 596 | 70 | 0.26 |
| `none-r3` | 10 | 3 | 5 532 | 83 | 0.27 |

The pattern from the 1 000 round holds: the mcp arm sometimes stops before its budget is spent
(`mcp-r3`: six calls, zero refusals) and the shell arm never does; the shell arm's notes are cheaper in
output tokens. **Ahead per environment interaction, not ahead per model token.** The two denominators
stay separate — this is the same sign flip round 2 of the checkpoint pilot found, and it is a real
finding, not a wash.

## What the data supports

1. **A note is worth the whole task.** Baseline 0/5; with any note of 2 000+ characters, 45/50. This was
   never a capability bottleneck for the weak agent — it was an understanding bottleneck, which is what
   the case was selected to be.
2. **The content that matters is a well-matched precedent.** Naming `HardcodedClaim` is worth more than
   any amount of correct prose; naming `FullNameMapper` is worth less than naming nothing.
3. **Semantic access changes research behaviour** (earlier stop, fewer refused calls) at a higher token
   cost. Measured, reproducible, and independent of the downstream outcome.

## What the data does NOT support

1. **No arm effect on downstream success.** Pooled over the two clean lengths: mcp 8/15 + shell 5/15 at
   500, and parity at 2 000/3 000. The 1 000-character advantage was an artefact of truncation. Anyone
   quoting "mcp 9/15 vs shell 0/15" without the truncation caveat is quoting a measurement of the knife.
2. **Between-note variance dominates.** Three notes from the same arm, same budget, same limit scored
   5/5, 4/5 and 0/5. Any design whose unit of replication is the downstream run — not the note —
   will manufacture significance out of this.
3. **One case, one downstream model.** `email-domain-mapper` with a haiku. Nothing here transfers to
   another repository or another weak agent without being measured there.

## Instrument defects found and fixed (all of them silent)

Each of these produced plausible-looking data before it was caught. Listed because the next round will
have its own.

| defect | what it looked like | fix |
|---|---|---|
| Immutable container-command builder used as if mutable | `git status` ran as an empty command, so the tree "verified" as pristine and budget counters read zero | `understandingExecRequest`, unit-tested |
| Parsers fed the console-filtered stdout | 20 paid research runs reported "no final message" while their notes sat in the log | `resolveAgentRawOutput`, `AiProcessResult.rawStdout`, test forbidding `agentResult.stdout` |
| Hard truncation treated as a way to shorten a note | the entire 1 000-character result | limit is now the agent's own budget; overrun ⇒ re-run, loud `OVERRUN` line |
| Image build timeout sized for a warm agent | 25 paid downstream cells failed as "broken Dockerfile" on cold agents | `AGENT_IMAGE_BUILD_TIMEOUT_SECONDS = 1800` |

## If this is continued

The arm question is answered for this case: **no**. The interesting question the data raised is a
different one, and it is cheap to test on the artefacts already committed:

> Does downstream success depend on the *precedent named* rather than on who wrote the note?

A clean design: hold the note format fixed and vary only the exemplar named — one cell naming
`HardcodedClaim`, one naming `FullNameMapper`, one naming none, all written by the same hand, five runs
each. Fifteen downstream runs, ≈ $5, and the prediction is already registered here: ≈ 5/5, ≈ 0/5,
≈ 1/5. If that holds, the actionable statement for the product is about what a note (or an agent's
context) should contain, not about which arm produced it.
