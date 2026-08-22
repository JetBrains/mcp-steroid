# Acquisition curves: what the pilot measured

Pre-registered design: [DESIGN.md](DESIGN.md). Why a new case and not an existing one:
[CASE-SELECTION.md](CASE-SELECTION.md). Per-build ledger: [RUN-IDS.md](RUN-IDS.md).

Every number below is recomputed from the published transcripts, offline, for nothing:

```
./gradlew :test-experiments:test --tests '*AcquisitionRecomputeTest*' \
    -Dacquisition.recompute.dir=<dir with one folder per trajectory>
```

## The question and the instrument

The understanding-note round answered "does semantic access produce a better note" with a no (8/15
against 5/15 at the only clean note length, note-level permutation p = 0.4). The question here is
different: does it produce the same architectural model in FEWER environment interactions?

`U(B)` is the share of a pre-registered fifteen-fact checklist whose evidence appears in the tool
results of the first `B` budgeted interactions. A fact counts only when several literals occur
**together inside one tool result** — a `find` that prints the right filename among a hundred paths
scores nothing. One trajectory runs to forty interactions and is sliced offline at 5/10/20/40, so the
shape of a curve is a within-run quantity and the unit of replication is the trajectory, not the slice.

Case: `acquisition__keycloak__cc-refresh-token` — a client-policy executor that must fire on client
create **and** update and must ship enabled under the strict security profile, which is a three-file
JSON indirection the behavioural statement never hints at. Agent: opus, both arms, pinned Keycloak tree,
budget 40, tree verified pristine after every run.

## Round 1 — voided: the treatment arm had no treatment

Three mcp cells came back `{Bash=25}` and `{Bash=26, Read=1}`. The transcripts say why:

```
"type":"system","subtype":"init", … "mcp_servers":[{"name":"mcp-steroid","status":"pending"}]
"tools":["Task","Bash","Glob","Grep","ExitPlanMode","Read","Edit","Write", …]   ← no mcp__ entry
```

Claude Code defers MCP tool schemas behind a `ToolSearch` call. An agent told it has forty environment
interactions — and not told that discovery is free — does not spend one on a tool it cannot see. Those
cells were control cells wearing the treatment label. **No number from round 1 is admissible.**

| defect | how it looked | fix |
|---|---|---|
| MCP schemas deferred | model never saw a `mcp__…` tool | `ENABLE_TOOL_SEARCH=false` in the settings `env` block **and** the session environment |
| written as top-level `enable_tool_search` | accepted by the file, ignored by the CLI: three more trajectories reported the flag installed and still came back `semantic=0` | the key belongs inside `env`; pinned by `UnderstandingHarnessTest` |
| budget charged for the bootstrap | `list_projects` + `fetch_resource` cost the mcp arm 2–3 of its interactions; the shell arm's first call is already research | both exempt, and the brief now says so |

The exemption cannot flatter the mcp curve: neither exempt call can carry a checklist fact — one returns
a routing key, the other returns documentation — while every evidence bundle is a set of literals from
Keycloak sources.

## Round 2 — the measurement

Nine trajectories bought, eight admitted. `mcp r4` was rejected by the harness itself: `{Bash=14}`,
`semantic=0`, i.e. the fix does not bind in every session and the guard exists precisely for that.

### How the two arms behave

| trajectory | tools | budgeted calls | refused after the wall | output tokens |
|---|---|---:|---:|---:|
| `mcp-r1` | `execute_code` ×19 | 19 | **0** | 21 873 |
| `mcp-r2` | `execute_code` ×11, `Read` ×5 | 16 | **0** | 15 023 |
| `mcp-r3` | `execute_code` ×18 | 18 | **0** | 22 343 |
| `mcp-r10` | `execute_code` ×18 | 18 | **0** | 20 851 |
| `shell-r1` | `Bash` ×31 | 31 | **0** | 15 555 |
| `shell-r2` | `Bash` ×15, `Read` ×23, `Agent` ×2 | 40 | 4 | 3 635 |
| `shell-r3` | `Bash` ×20, `Read` ×18, `Agent` ×2 | 40 | 4 | 3 843 |
| `shell-r4` | `Bash` ×21, `Read` ×17, `Agent` ×2 | 40 | 5 | 4 751 |
| ~~`mcp-r4`~~ | ~~`Bash` ×14~~ | — | — | rejected: no semantic call |

Every admitted mcp trajectory **stopped by itself** at 16–19 interactions with zero refusals. Three of
four shell trajectories ran into the wall at forty and kept pushing, and delegated most of their work to
a sub-agent (38–40 of their tool uses are nested).

### `U` against environment interactions

| B | mcp r1 · r2 · r3 · r10 | mean | shell r1 · r2 · r3 · r4 | mean | diff | one-sided p |
|---:|---|---:|---|---:|---:|---:|
| 5 | .27 · .20 · .53 · .60 | **.40** | .20 · .20 · .13 · .20 | **.18** | +.22 | .057 |
| 10 | .60 · .80 · .60 · .80 | **.70** | .60 · .27 · .20 · .33 | **.35** | +.35 | **.043** |
| 20 | .87 · .87 · .73 · .87 | **.83** | .73 · .33 · .27 · .40 | **.43** | +.40 | **.029** |
| 40 | .87 · .87 · .73 · .87 | **.83** | .80 · .53 · .40 · .67 | **.60** | +.23 | **.029** |

Exact permutation over trajectories, 70 assignments, minimum attainable p = .014.

**The mcp arm reaches at ten interactions (.70) what the shell arm has not reached at forty (.60).**
Both plateau below 1.0 for opposite reasons: mcp because it stops, shell because it runs out.

### `U` against cumulative model output tokens

| trajectory | tokens at B=10 | tokens at plateau | U at plateau |
|---|---:|---:|---:|
| `mcp-r1` | 8 708 | 18 695 | .87 |
| `mcp-r2` | 7 190 | 11 973 | .87 |
| `mcp-r3` | 11 656 | 19 472 | .73 |
| `mcp-r10` | 9 283 | 17 806 | .87 |
| `shell-r1` | 2 836 | 11 588 | .80 |
| `shell-r2` | 1 221 | 2 605 | .53 |
| `shell-r3` | 1 018 | 2 167 | .40 |
| `shell-r4` | 1 473 | 3 312 | .67 |

**The ordering does not survive the change of denominator.** `shell-r1` reaches .80 for 11 588 tokens;
the median mcp trajectory reaches .87 for 18 250. Per token the two arms are comparable at best, and
three shell trajectories are far cheaper while knowing far less. This is the third independent
observation of the same sign flip (checkpoint pilot, understanding-note round, here): **semantic access
buys interactions, not thinking.**

### Which knowledge arrives, and which never does

Facts by arm at B=40 (statements in `AcquisitionCase.kt`):

| fact | what it is | mcp | shell |
|---|---|---:|---:|
| `A1` | the correct architectural precedent | **4/4** | **0/4** |
| `H1` | the partial-update invariant | **4/4** | **0/4** |
| `E2` | the profile→executor resolution step | 3/4 | 4/4 |
| `B2` | the dynamic-registration entry point | 2/4 | 1/4 |
| `B1` | the admin entry point | 0/4 | 1/4 |
| `E1` | the manager's event dispatch | 0/4 | 2/4 |

`A1` alone: 4/4 against 0/4, exact p = **.014** — the smallest value 4 against 4 can produce. The
previous experiment identified the named precedent as the single strongest predictor of downstream
success; here the control arm never finds it, in four independent trajectories, with a budget four
times what the treatment arm actually used.

The reverse also happens and is worth keeping: `E1` and `B1` were reached only by shell trajectories.
Reading `DefaultClientPolicyManager` top to bottom is something the shell arm does and the mcp arm,
querying targeted structure, skips. The checklist is not a list of things one tool is good at.

### One shell trajectory behaved like an mcp trajectory

`shell-r1` stopped itself at 31 calls, spent 15 555 tokens and reached .80 — the only control run that
neither hit the wall nor delegated. It is the strongest single argument that this case does not make the
control arm structurally incapable: with a different search strategy, shell gets most of the way there.
It still did not find `A1`.

## The rejected cells, and why rejection is the point

| cell | rejected by | reason |
|---|---|---|
| `mcp r4` | `ARM DEGENERATE` | 14 calls, none semantic — the fix does not bind every time |
| `none r1` (first attempt) | count cross-check | reader charged 43 budgeted interactions, in-container hook charged 40 |

The cross-check compares two independent counts of the quantity every checkpoint is a position in; a
silent drift of three would have moved all four points while the report looked identical. That cell cost
more than it should have — the check ran *before* the transcript was written, so the evidence went with
it. Reversed since.

## Answers to the five questions the design asked

1. **Does MCP acquire architecture knowledge in fewer environment interactions?** Yes. .70 at ten
   against .35, p = .043; .83 at twenty against .43, p = .029.
2. **Is it faster per output token?** No. Comparable at best, worse than `shell-r1`.
3. **Does shell catch up at a large budget?** Partly — .60 at forty against .83 — and never on the two
   facts that matter most (`A1`, `H1`).
4. **Which kinds of knowledge arrive earlier?** Precedent and invariant, i.e. the "which existing thing
   is this like, and what will a naive copy break" pair. Sequential-reading facts (`E1`, `B1`) arrive
   earlier in the control arm.
5. **Does it transfer downstream?** Unmeasured. The distil prompts are published per checkpoint; the
   Haiku validation is the next purchase.

## Limits

`n` = 4 per arm on one case with one model. The gap at B=10/20 is significant at the trajectory level,
but four trajectories cannot bound it. The shell plateau at .60 is a property of this checklist as much
as of the arm. Nothing here says an agent with semantic access writes better code — only that it builds
the same architectural picture in half to a third of the environment interactions, and pays for that in
model output tokens.
