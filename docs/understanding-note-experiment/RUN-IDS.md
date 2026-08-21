# Run ledger — fixed-budget repository understanding

Every TeamCity build this experiment spends money on, in the order it was queued, with the cell it
measured. A cell that is not in this table did not happen: the analysis reads this file to know what the
denominator of a rate is, and a run recorded only in a chat log is a run nobody can subtract later.

Server: `buildserver.labs.intellij.net`, project `mcp_steroid` → *Integration & Experiment Tests*.
Build configurations (both manually triggered, no VCS trigger, in no composite):

| stage | build configuration id |
|:---|:---|
| research | `mcp_steroid_IntegrationTests_UnderstandingResearch` |
| downstream | `mcp_steroid_IntegrationTests_UnderstandingDownstream` |

All runs are on the branch `worktree-semantic-ripple-pilot` of the `jb` mirror, which is what TeamCity
pulls from; a run started on `main` would be running a tree without this harness.

## Phase 0 — calibration

Six downstream cells, no research phase: three with no note at all and three with the hand-written
`oracle:gold` note. They answer the two questions that decide whether the expensive matrix is worth
starting, and the decision rule was fixed before they were queued:

| observation | verdict |
|:---|:---|
| `baseline` 3/3 | the task is too easy — the note cannot show an effect, pick another task |
| `oracle:gold` 0/3 | the downstream agent cannot do this task even when told how — pick another task |
| `baseline` ≤ 1/3 **and** `oracle:gold` ≥ 2/3 | the understanding band — proceed to phase 1 |

Queued 2026-08-21 18:38 UTC, all six in parallel on separate agents.

| build id | condition | replicate | Y | oracle test | agent s | output tokens | usd |
|---:|:---|---:|:---:|:---|---:|---:|---:|
| 1038334501 | `baseline` | 1 | **0** | 3 errors: e-mail read from the session, not the token | 596 | 17 154 | 0.5765 |
| 1038334503 | `baseline` | 2 | **0** | same 3 errors **+** not offered as a built-in | 2 534 | 10 544 | 0.5989 |
| 1038334505 | `baseline` | 3 | **0** | same 3 errors **+** not offered as a built-in | 341 | 12 309 | 0.3767 |
| 1038334507 | `oracle:gold` | 1 | **1** | 7/7 | 418 | 8 841 | 0.2776 |
| 1038334509 | `oracle:gold` | 2 | **1** | 7/7 | 967 | 17 548 | 0.5335 |
| 1038334511 | `oracle:gold` | 3 | **1** | 7/7 | 418 | 6 691 | 0.2605 |

**Verdict: baseline 0/3, `oracle:gold` 3/3 — the understanding band, phase 1 is authorised.** Total spend
$2.62 of agent time plus the six builds' machine time.

What the failures were is the more informative half of the result, because both of them are failures of
understanding rather than of coding:

- All three baseline runs implemented the claim by reading the e-mail off the **user session**
  (`userSession.getUser().getEmail()`) instead of off the token being issued, and threw
  `NullPointerException` on the oracle's session-free call path. The statement says "the e-mail address
  carried by the token that is being issued"; the precedent that shows this — `HardcodedClaim` and the
  `AbstractOIDCProtocolMapper.transformIDToken` contract — is exactly what a research note can point at.
- Two of the three also missed the built-in registration entirely, which is the second, differently
  located integration point the case was chosen for (`OIDCLoginProtocolFactory.initBuiltIns`). The third
  found it and still failed on the e-mail source, so the two failure modes are independent — a note that
  fixes only one of them still loses the cell.

With the `oracle:gold` note the same agent passes all seven assertions in three runs out of three, at
roughly half the cost and a third of the wall time of the baseline runs. Nothing about the task is beyond
the downstream agent; what it lacks is the model of the repository.

The `oracle:gold` note is committed at
`test-experiments/src/test/resources/understanding-notes/understanding__keycloak__email-domain-mapper/oracle-gold.md`
(4 144 bytes, so it also exercises the 5 000-character condition's ceiling). It is calibration only and
never appears in an mcp-versus-shell comparison.

## Phase 1 — research notes

Queued 2026-08-21 20:18 UTC, eight cells in parallel: both arms × budgets {5, 10} × note limits
{1000, 5000}, one replicate each.

| build id | arm | budget | limit | note id | calls | denied | turns | output tokens | usd | s | note chars |
|---:|:---|---:|---:|:---|---:|---:|---:|---:|---:|---:|---:|
| 1038399360 | mcp | 5 | 1000 | `mcp-b5-l1000-r1` | 5 | 6 | 4 | 4 593 | 0.227 | 59 | 1 348 → 1 000 |
| 1038399362 | mcp | 5 | 5000 | `mcp-b5-l5000-r1` | 5 | 5 | 5 | 12 178 | 0.461 | 154 | 6 053 → 5 000 |
| 1038399364 | mcp | 10 | 1000 | `mcp-b10-l1000-r1` | 10 | 2 | 5 | 9 598 | 0.514 | 147 | 1 151 → 1 000 |
| 1038399366 | mcp | 10 | 5000 | `mcp-b10-l5000-r1` | 10 | 5 | 4 | 11 165 | 0.428 | 148 | 6 090 → 5 000 |
| 1038399368 | none | 5 | 1000 | `none-b5-l1000-r1` | 5 | 3 | 6 | 10 115 | 0.414 | 126 | 1 276 → 1 000 |
| 1038399370 | none | 5 | 5000 | `none-b5-l5000-r1` | 5 | 4 | 3 | 9 996 | 0.360 | 125 | 6 176 → 5 000 |
| 1038399372 | none | 10 | 1000 | `none-b10-l1000-r1` | 10 | 2 | 4 | 6 422 | 0.332 | 107 | 1 307 → 1 000 |
| 1038399374 | none | 10 | 5000 | `none-b10-l5000-r1` | 10 | 3 | 3 | 8 439 | 0.330 | 126 | 5 962 → 5 000 |

The budget hook did exactly what it is for: `calls` equals the budget in all eight cells, and `denied`
counts how hard the agent pushed against the wall afterwards (2 to 6 refusals). No cell left the work
tree dirty.

**All eight builds are red, and every one of them nevertheless produced its note.** The research phase
read the agent's final message off the captured process stdout, which is console-filtered; the filter
removes the terminal `result` event, and that event is where the note travels. The harness reported "the
research run produced no final message" and threw after the note had already been written. Fixed in
commit `3a03b7ad` (`resolveAgentRawOutput` — the same source `collectRunMetrics` has always used); the
notes above were recovered from the build logs rather than re-purchased, and the numbers in the table
come from the same `result` events plus the `understanding/` artifacts.

Both arms overran the limit in every cell and were cut, by 15–35 % at 1000 characters and by ~20 % at
5000. The rule is symmetric and was fixed before the runs, but it has a consequence worth naming before
reading any downstream result: at 1000 characters the cut deletes whatever the agent left for last.
`mcp-b5-l1000-r1` ends mid-word at "Two registrations, easy to forget (I could not ve", i.e. the cut
removed precisely the integration point the case is built around, while `none-b5-l1000-r1` had named
both registrations earlier in its text and kept them. The 1000-character condition therefore measures
prioritisation-under-a-limit at least as much as it measures understanding; the 5000-character condition
is the cleaner test of the hypothesis.

## Phase 1 — downstream cells

**A green build is NOT a solved task.** The cell's JUnit test passes whenever the instrument worked; the
verdict lives in the run's own log line, `[UNDERSTANDING-DOWN] … Y=0|1 …`, and every number below was
read from there. Build 1038465462 is the standing counter-example: `SUCCESS`, `Tests passed: 1`, `Y=0`.

### Note limit 5000 — queued 2026-08-21 23:45 UTC, 20 cells

| condition | Y | downstream usd (median) | agent s (median) | output tokens (median) |
|:---|:---:|---:|---:|---:|
| `mcp-b5-l5000-r1` | **5/5** | 0.359 | 239 | 9 417 |
| `none-b5-l5000-r1` | **5/5** | 0.269 | 209 | 7 568 |
| `mcp-b10-l5000-r1` | **5/5** | 0.272 | 189 | 8 609 |
| `none-b10-l5000-r1` | **5/5** | 0.257 | 166 | 8 418 |

Build ids 1038427554…592 in the order (mcp-b5 r1..r5, none-b5 r1..r5, mcp-b10 r1..r5, none-b10 r1..r5).

Twenty cells out of twenty. At this length the condition is saturated and the two arms are indis­tinguish­able
by construction — no amount of replication can separate 5/5 from 5/5. Against a baseline of 0/5 this is
still the experiment's largest single effect: **the note itself is worth the whole task.**

### Note limit 1000 — queued 2026-08-22 00:52 UTC, 20 cells + 2 baseline

| condition | Y | downstream usd (median) | agent s (median) | output tokens (median) |
|:---|:---:|---:|---:|---:|
| `mcp-b5-l1000-r1` | 1/5 | 0.331 | 246 | 10 522 |
| `none-b5-l1000-r1` | 2/4 | 0.330 | 206 | 9 025 |
| **`mcp-b10-l1000-r1`** | **5/5** | 0.397 | 328 | 10 801 |
| **`none-b10-l1000-r1`** | **0/5** | 0.258 | 199 | 8 003 |
| `baseline` (r4, r5) | 0/2 | 0.449 | 913 | 9 526 |

Build ids 1038465422…464. One cell of `none-b5-l1000-r1` (1038465434, replicate 2) was still running when
this table was written and is excluded from its denominator.

This is the separating condition. At a budget of ten interactions and a note of a thousand characters the
mcp arm's note carries the weak agent to **5/5** and the shell arm's note to **0/5**, against a baseline
of 0/5 — a one-sided Fisher exact p = 1/252 ≈ 0.004 for that single comparison. Both notes were produced
under the same budget, the same limit, the same model and the same prompt except for the tool paragraph;
both were cut by the limit; the downstream agent, tree and grading are identical.

Two things this table does **not** yet establish, and the next wave is aimed at both:

- **One note per condition is pseudo-replication.** Five downstream runs of ONE note measure the note,
  not the arm. Replicates r2 and r3 of the research cell are queued for both arms at (10, 1000).
- **The budget-5 row is noise at this n.** 1/5 versus 2/4 says nothing; if the curve really rises between
  five and ten interactions, that is what the intermediate note lengths have to show.

With baseline now at 0/5 and the 5000 condition at 20/20, the instrument's dynamic range is confirmed:
the outcome is neither floored nor ceilinged by the task.

### Note limits 2000 and 3000, plus the (10, 1000) research replicates

Queued 2026-08-22 02:31 UTC after the transcript-parsing fix (`c897a99f2`): eight research cells at the
two intermediate lengths and four replicate notes at the separating point.

| build id | arm | budget | limit | replicate |
|---:|:---|---:|---:|---:|
| 1038522104 | mcp | 5 | 2000 | 1 |
| 1038522106 | mcp | 10 | 2000 | 1 |
| 1038522108 | none | 5 | 2000 | 1 |
| 1038522110 | none | 10 | 2000 | 1 |
| 1038522112 | mcp | 5 | 3000 | 1 |
| 1038522114 | mcp | 10 | 3000 | 1 |
| 1038522116 | none | 5 | 3000 | 1 |
| 1038522118 | none | 10 | 3000 | 1 |
| 1038522120 | mcp | 10 | 1000 | 2 |
| 1038522122 | mcp | 10 | 1000 | 3 |
| 1038522124 | none | 10 | 1000 | 2 |
| 1038522126 | none | 10 | 1000 | 3 |

An earlier attempt at the same eight cells (1038466374…488) is void: four were refused before spending
anything because 2000 was not yet a registered limit, and four spent an Opus run each and then threw on
the note extraction. Their notes are recoverable from the build logs but are NOT used — they were
produced by the same prompt, so re-running costs less than arguing about whether a recovered note is the
same artifact.
