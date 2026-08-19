# Keycloak ripple — kill criteria and publication contract

Date: 2026-08-16. Status: **decision framework fixed, outcome UNDECIDED**.
Not committed to git, per the repo rule against committing process artifacts.

Predecessors: `2026-08-11-keycloak-semantic-ripple-pilot-design.md` (the pilot),
`2026-08-13-keycloak-ripple-case-family-design.md` (the seven-case family). Read both first — this
document does not restate the design, only what may be **concluded** from it and under which rules.
Its mirror in the working notes is `TEAMCITY-WHITEPAPER.md` §9f, which carries the same numbers.

## Why this document exists now, before the results

The deciding series (3 repeats × 7 cases × 2 agents, one revision) has **not been run**. Writing the
decision rule after seeing the numbers is how an experiment turns into an illustration. So the four
possible outcomes, the exclusion rules and the forbidden moves are fixed here first; when the series
lands, exactly one outcome is selected and this file records which, with build ids — nothing else in
it changes.

**Nothing below reports a result. The result section is deliberately empty.**

## The criterion, and the problem with it

The chosen headline is **cost and step count at equal quality** (not correctness). The last valid
measurement points against the product: on `de26f1999`, at equal quality, the mcp arm cost **$12.13**
against the none arm's **$7.98** (+52 %), with output tokens +62 % and turns near equal.

Arm cost decomposes as **fixed MCP overhead + work**. Overhead — tool schemas in every request, the
extra prompt section, `execute_code` results occupying context — is paid whether or not a tool is
called. Work is saved only when the tools replace shell searching. In build `1032465247` the mcp arm
made **6 tool calls against 38 Bash calls**: it ran as a shell arm carrying MCP overhead, so the
+52 % is essentially pure overhead and the same design would reproduce it.

The operative consequence, and the reason comparability exists at all:

> A cost figure from a run whose mcp arm did not use the IDE measures the price of *having* MCP
> installed, not the value of IDE *access*. It may not be published as the latter.

## Validity rules

### Comparability

`usedMcpSteroid` (≥ 1 call) is too weak — the 6-of-44 run passes it. Each arm now prints and persists
its tool split (`[RIPPLE]   tools:` / `tool errors:` / `comparable:`) and the run summary JSON carries
`ripple.comparability`.

1. Non-comparable is a **measurement defect, not an agent failure**: it never fails the build, never
   counts as a loss, and removes the run from the **cost** aggregate only, with a printed reason.
   Quality remains readable for such a run.
2. `UNKNOWN` is a **third state**, never `false`. A missing or unparsable transcript (the usual Codex
   shape) must not be reported as "did not call the IDE".
3. `RIPPLE_IDE_CALL_SHARE_THRESHOLD` is **deliberately unset**. It is taken once, from the
   *distribution* of IDE-call shares across the fourteen builds in flight on `6c35a0d8c`
   (`1032490553`…`1032490573`, `1032503275`…`1032503279`), and the constant's KDoc records which
   builds were read and where in that distribution the number sits. It is **not** taken from
   `1032465247` (n=1, and the very run the gate was invented to describe).

### Withdrawn and discarded runs

- Every round before `5ae147d29` (through build `1028521545`, rev `20c233760`) is **WITHDRAWN**: the
  case went through the dpaia wrapper instead of `buildRipplePrompt`. Its attractive ratio (none
  $2.89 / 47 turns / 39 bash against mcp $1.42 / 24 turns) is the cost of a broken brief — with the
  reviewed prompt the mcp arm barely moved ($1.42 → $1.44) while the none arm collapsed
  ($2.89 → $1.06). Citable only as evidence about the harness.
- `1031230755` (fixture literal trap) and `1031488960` (modal race in setup) are **discarded for
  stated cause**, not counted as agent failures.

### Case admission

Admission is a measurement, not an argument: `textualOccurrences`, `resolvedReferences`,
`foreignSameNameCallSites`, with `RippleCase.init` rejecting `textualOccurrences <= resolvedReferences`.
Measured on `run-20260816-185913-ripple-target-survey` (*textual | resolved | foreign call sites*):
`rename-method-wide` `KeycloakContext#setRealm` 696 | 496 | 151; `rename-type-wide`
`ValidationContext` 593 | 198 | 74; `rename-type-narrow` `KeyUtils` 496 | 12 | 287. All three
discriminate; none was retargeted; `resolvedReferences` reproduced each pinned gold count.

So the `de26f1999` tie is **not** explained by an easy target. `P7_RECEIVER` and `P8_NO_SHIM` exist
for the error a text tool makes, but have never fired on a real run — the first failure of either is
read by hand before it is called a signal.

### Statistics

Repeats aggregate off `allBuilds` (never latest-only). Per arm: attempts used/total, SUCCESS count,
unweighted median and observed min…max of cost/turns/agent time, the cost split into fixed overhead
(cache-read + input) and work (output), and every exclusion with build id and reason. Arms pair **by
build id**, never by list order, and paired differences are computed per pair before any median. The
report refuses to name a difference below three usable pairs or when the paired range straddles zero;
until the series exists it prints `insufficient repeats` and `UNKNOWN` — by design. Quality inclusion
and cost inclusion are separate. Regressions are `UNKNOWN` for every arm (no real pre-agent full-suite
snapshot) — a stated limitation, not a zero.

## Kill criteria — the four outcomes

| # | What the series shows | What is published |
|---|---|---|
| 1 | mcp arm really calls the IDE, quality equal, **cost lower** | The intended claim, with spread and exclusion count beside it |
| 2 | mcp arm really calls the IDE, quality equal, **cost still higher** | The **negative** result, plainly: on refactorings of this class, IDE access does not pay for itself in tokens. The claim moves to a task class where a shell physically cannot answer (call hierarchy through a generic interface, implementors, runtime state in the debugger) — **not** to "cheaper" |
| 3 | baseline **stably fails** `P7_RECEIVER` / `P8_NO_SHIM` | Headline returns to **correctness** (as `P5_ARITY` already does for change-signature); cost becomes secondary |
| 4 | mcp arm **still does not call its tools** | The brief is not the lever — the case design is. Fix the case (a target whose file list cannot be assembled without resolution), or admit ripple is the wrong showcase |

If outcome 2 fires, the replacement showcase is raised as **one separate task** and is not started
inside this track.

## Forbidden moves

- Do not break the baseline with the prompt. Both arms get the same brief except `## Available Tools`.
- Do not narrow the decoy set to make a grade come out.
- Do not publish n=1.
- Do not restore the withdrawn pre-`5ae147d29` series except as evidence about the harness.
- Do not tune `RIPPLE_IDE_CALL_SHARE_THRESHOLD` after seeing which side of it a run falls on.
- Do not weaken the oracle — no relaxed predicate, removed assertion, or shrunk gold set.
- Do not report a difference lying inside the observed spread.

## Pending before any headline can be fixed

1. Read the fourteen `6c35a0d8c` builds; derive the comparability threshold from their distribution.
   (If those agents predate the summary-publication change, read the split from the `[RIPPLE]` log
   lines; the JSON path begins with the next series.)
2. Run 3 repeats per (case × agent) on one revision, by hand (no VCS trigger), never in parallel.
3. Prove the offline `test-compile` of the 14-module compile gate of `rename-method-wide`.
4. Read the first real `P7_RECEIVER` / `P8_NO_SHIM` failure by hand.

## Result

**EMPTY — UNDECIDED.** No outcome has been selected. When the series exists, record here: the revision,
the build ids per (case × agent × arm), the medians and spreads, the number and reasons of exclusions,
the threshold actually used, and the selected outcome number. Do not edit any section above while
doing so.
