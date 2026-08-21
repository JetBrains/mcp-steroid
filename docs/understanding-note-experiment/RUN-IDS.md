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

Not queued. Requires the phase-0 verdict and a separate authorisation.

| build id | arm | budget | note limit | replicate | note id | calls | denied | output tokens | pristine |
|---:|:---|---:|---:|---:|:---|---:|---:|---:|:---|

## Phase 1 — downstream cells

Not queued.

| build id | condition | replicate | outcome | graded | downstream tokens | usd |
|---:|:---|---:|:---|:---|---:|---:|
