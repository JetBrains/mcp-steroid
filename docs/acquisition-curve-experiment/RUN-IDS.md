# Acquisition-curve builds: the ledger

Every paid run of the experiment, in order, with what it produced and whether it entered the curve.
Build configuration `mcp_steroid_IntegrationTests_AcquisitionResearch`, branch
`acquisition-curve-experiment`, TeamCity `buildserver.labs.intellij.net/build/<id>`.

The transcripts of every run that produced one are committed under
[`data/trajectories/`](data/trajectories) (gzipped NDJSON, one folder per trajectory), so nothing here
needs the TeamCity artifact server to be reproducible:

```
gunzip -kr docs/acquisition-curve-experiment/data/trajectories
./gradlew :test-experiments:test --tests '*AcquisitionRecomputeTest*' \
    -Dacquisition.recompute.dir=$PWD/docs/acquisition-curve-experiment/data/trajectories
```

## Round 1 — voided (the treatment arm had no treatment)

| build | cell | outcome |
|---|---|---|
| 1038838581 | mcp r1 | infra: `mcp.testIntegration.lane` unset on a new config → whole suite queued |
| 1038845517 | mcp r1 | infra: agent image build timeout on a cold agent |
| 1038853135 | mcp r1 | **completed**, but `{Bash=25}` — no semantic call |
| 1038871413 | none r1 | completed, control arm |
| 1038878215 | mcp r2 | **completed**, but `{Bash=26, Read=1}` — no semantic call |
| 1038886661 | mcp r3 | completed, semantic calls present |
| 1038898783 | none r2 | completed, control arm |
| 1038906157 | none r3 | completed, control arm |

Diagnosis in [RESULTS.md](RESULTS.md) § "Round 1". Nothing from this round is used.

## Between rounds — the three fixes, verified one cell at a time

| build | cell | outcome |
|---|---|---|
| 1038915019 | mcp r5 | top-level `enable_tool_search` — ignored by the CLI, `semantic=0` |
| 1038921301 | mcp r6 | same, `semantic=0` |
| 1038927496 | mcp r7 | same, `semantic=0` |
| 1038933471 | mcp r8 | `ENABLE_TOOL_SEARCH` in `env` + bootstrap exempt — **first semantic trajectory** |
| 1038939031 | none r1 | rejected by the count cross-check (reader 43, hook 40); transcript lost |

## Round 2 — the measurement

| build | trajectory | calls | refused | tokens | U(40) | admitted |
|---|---|---:|---:|---:|---:|---|
| 1038933471 | `mcp-b40-l2000-r1` | 19 | 0 | 21 873 | .87 | yes |
| 1038942337 | `mcp-b40-l2000-r2` | 16 | 0 | 15 023 | .87 | yes |
| 1038945729 | `mcp-b40-l2000-r3` | 18 | 0 | 22 343 | .73 | yes |
| 1038948995 | `mcp-b40-l2000-r10` | 18 | 0 | 20 851 | .87 | yes |
| 1038953685 | `none-b40-l2000-r1` | 31 | 0 | 15 555 | .80 | yes |
| 1038946951 | `none-b40-l2000-r2` | 40 | 4 | 3 635 | .53 | yes |
| 1038950281 | `none-b40-l2000-r3` | 40 | 4 | 3 843 | .40 | yes |
| 1038953687 | `none-b40-l2000-r4` | 40 | 5 | 4 751 | .67 | yes |
| 1038953689 | `mcp-b40-l2000-r4` | 14 | 0 | 10 976 | — | **no**: `ARM DEGENERATE`, `{Bash=14}` |

Nine trajectories bought, eight admitted, `n` = 4 per arm.

## Cost

Twenty-two builds in total; nine produced a trajectory. Research trajectories run 2–5 minutes of agent
time each on top of ~20 minutes of IDE start-up and indexing, and the whole experiment — including the
voided round and the three verification cells — is roughly $25 of Opus.

## Reproducing one cell

```
POST /app/rest/buildQueue
{"buildType":{"id":"mcp_steroid_IntegrationTests_AcquisitionResearch"},
 "branchName":"acquisition-curve-experiment",
 "properties":{"property":[
   {"name":"understanding.case","value":"acquisition__keycloak__cc-refresh-token"},
   {"name":"understanding.arm","value":"mcp"},
   {"name":"understanding.replicate","value":"11"}]}}
```

One build is one independent trajectory at budget 40; the four checkpoints are slices of it. Never queue
two at once — each starts a full Docker IntelliJ.

## Downstream validation wave (2026-08-23)

Build `mcp_steroid_IntegrationTests_AcquisitionDownstream`, branch `acquisition-curve-experiment`, all
on `acquisition__keycloak__cc-refresh-token`, weak agent, one rollout per cell. Results in
[RESULTS-DOWNSTREAM.md](RESULTS-DOWNSTREAM.md), per-cell numbers in
[data/downstream-cells.csv](data/downstream-cells.csv).

| build | condition | verdict |
|---|---|---|
| 1039175445 | `baseline` r1 | 7/8, 89 calls |
| 1039182951 | `baseline` r2 | 0/8, 62 calls |
| 1039175447 | `oracle:gold` r1 | 8/8, 70 calls |
| 1039182953 | `oracle:gold` r2 | LOST — Docker image build failed; not re-run |
| 1039177308/310/312 | `checkpoint:mcp-b40-l2000-r2@{5,10,20}` | 5/8, 5/8, 0/8 |
| 1039177314/316/318 | `checkpoint:mcp-b40-l2000-r3@{5,10,20}` | 6/8, 5/8, 5/8 |
| 1039177320/322/324 | `checkpoint:none-b40-l2000-r1@{5,10,20}` | 0/8, 6/8, 7/8 |
| 1039177326/328/330 | `checkpoint:none-b40-l2000-r3@{5,10,20}` | 7/8, 5/8, 7/8 |

The notes were distilled by build `mcp_steroid_IntegrationTests_AcquisitionDistill` (1039166835 and its
re-run), offline from the committed transcripts — no research trajectory was bought for this wave.

Queue one cell like this:

```
POST /app/rest/buildQueue
{"buildType":{"id":"mcp_steroid_IntegrationTests_AcquisitionDownstream"},
 "branchName":"acquisition-curve-experiment",
 "properties":{"property":[
   {"name":"understanding.case","value":"acquisition__keycloak__cc-refresh-token"},
   {"name":"understanding.condition","value":"checkpoint:mcp-b40-l2000-r2@10"},
   {"name":"understanding.replicate","value":"1"}]}}
```

## Downstream round 2 — calibration wave 1, DISCARDED (2026-08-23)

Build `mcp_steroid_IntegrationTests_AcquisitionDownstream`, branch `acquisition-curve-experiment`,
`acquisition__keycloak__cc-refresh-token`, weak agent, **`understanding.budget=20`**, the de-cascaded
nine-assertion oracle. Design: [DESIGN-DOWNSTREAM-2.md](DESIGN-DOWNSTREAM-2.md). Per-cell numbers:
[data/downstream2-calibration-wave1.csv](data/downstream2-calibration-wave1.csv).

 build | condition | passed | budget | denied | toolCalls | usd |
---|---|---|---|---|---|---|
 1039274925 | `baseline` r1 | 0/9 (module left non-compiling) | 20/20 | 7 | 61 | 0.53 |
 1039274927 | `baseline` r2 | 0/9 (module left non-compiling) | 20/20 | 3 | 32 | 0.22 |
 1039274929 | `baseline` r3 | 2/9 | 20/20 | 4 | 41 | 0.29 |
 1039274931 | `baseline` r4 | 1/9 | 20/20 | 5 | 38 | 0.25 |
 1039274933 | `oracle:gold` r1 | 8/9 | 20/20 | 2 | 32 | 0.27 |
 1039274935 | `oracle:gold` r2 | 8/9 | 20/20 | 2 | 34 | 0.31 |

Every cell exhausted its allowance, so the budget binds in all conditions. The wave is **discarded and
gated on nothing**: both ceiling cells lost the same assertion to an oracle defect it exposed —
`registerWithTheSettingOnIsRejected` exercised an executor that had never been configured, a state the
shipped profile never produces — see amendment 2 in the design. It is kept here because it is what the
repair is evidence for, and because its floor (0, 0, 2, 1 with every cell hitting the wall) is the
first demonstration that the budget does what round 1 lacked.

### Calibration wave 2 (2026-08-24) — the fixed oracle, and the gates

Same six cells, same `B_down = 20`, the only change being the amendment-2a repair. All four gates
pass; per-cell numbers in [data/downstream2-cells.csv](data/downstream2-cells.csv).

 build | condition | passed | budget | denied | toolCalls | usd |
---|---|---|---|---|---|---|
 1039289680 | `baseline` r5 | 0/9 | 20/20 | 8 | 47 | 0.32 |
 1039289682 | `baseline` r6 | 0/9 | 20/20 | 5 | 39 | 0.27 |
 1039289684 | `baseline` r7 | 0/9 | 20/20 | 4 | 77 | 0.70 |
 1039289686 | `baseline` r8 | 0/9 | 20/20 | 3 | 30 | 0.23 |
 1039289688 | `oracle:gold` r5 | **9/9** | 20/20 | 1 | 29 | 0.27 |
 1039289690 | `oracle:gold` r6 | **9/9** | 20/20 | 0 | 29 | 0.25 |

Floor 0.00 (sd 0.00), ceiling 9.00, gap 9.00 assertions, nothing lost. The floor is zero because all
four unaided cells left `services` non-compiling — twenty interactions are not enough to find the
chain AND get the code to build — while both cells that were told the chain finished it, one of them
without ever reaching the wall.

## Downstream validation round 2 (2026-08-24)

Build `mcp_steroid_IntegrationTests_AcquisitionDownstream`, branch `acquisition-curve-experiment`,
case `acquisition__keycloak__cc-refresh-token`, weak agent, **20 repository interactions**, oracle
`oracle-v2.patch` (nine independent assertions). Results in
[RESULTS-DOWNSTREAM-2.md](RESULTS-DOWNSTREAM-2.md), per-cell numbers in
[data/downstream2-cells.csv](data/downstream2-cells.csv).

 build | cell | obligations |
---|---|---|
 1039274925/927/929/931 | calibration wave 1, `baseline` r1–r4 | 0, 0, 2, 1 |
 1039274933/935 | calibration wave 1, `oracle:gold` r1–r2 | 8, 8 |
 1039289680/682/684/686 | calibration wave 2, `baseline` r1–r4 | 0, 0, 0, 0 |
 1039289688/690 | calibration wave 2, `oracle:gold` r1–r2 | 9, 9 |
 1039300811/813/815 | `checkpoint:mcp-b40-l2000-r2@{5,10,20}` r1 | 0, 6, 4 |
 1039300817/819/821 | `checkpoint:mcp-b40-l2000-r3@{5,10,20}` r1 | 7, 6, 7 |
 1039300823/825/827 | `checkpoint:none-b40-l2000-r1@{5,10,20}` r1 | 0, 0, 7 |
 1039300829/831/833 | `checkpoint:none-b40-l2000-r3@{5,10,20}` r1 | 0, 7, 0 |
 1039310876/878/880 | `checkpoint:mcp-b40-l2000-r2@{5,10,20}` r3 | 7, 6, 4 |
 1039310882/884/886 | `checkpoint:mcp-b40-l2000-r3@{5,10,20}` r3 | 6, 6, 6 |
 1039310888/890/892 | `checkpoint:none-b40-l2000-r1@{5,10,20}` r3 | 0, 5, 8 |
 1039310894/896/898 | `checkpoint:none-b40-l2000-r3@{5,10,20}` r3 | 0, 0, 5 |

**Calibration wave 1 gates nothing.** It found the instrument defect described in amendment 2a — the
oracle called the executor unconfigured, a state the runtime never produces — and the whole wave was
re-run rather than patched up. Its numbers are kept here so the discarded wave stays auditable.

Queue one cell (`understanding.budget` accepts only 15, 20 or 25):

```
POST /app/rest/buildQueue
{"buildType":{"id":"mcp_steroid_IntegrationTests_AcquisitionDownstream"},
 "branchName":"acquisition-curve-experiment",
 "properties":{"property":[
   {"name":"understanding.case","value":"acquisition__keycloak__cc-refresh-token"},
   {"name":"understanding.condition","value":"checkpoint:mcp-b40-l2000-r2@10"},
   {"name":"understanding.replicate","value":"1"},
   {"name":"understanding.budget","value":"20"}]}}
```

## Generalization round (2026-08-24) — 24 research cells, all green

Branch `acquisition-curve-experiment`, product revision `ca505f88`, DSL revision with the four-case
selector. Build configuration `mcp_steroid_IntegrationTests_AcquisitionResearch`. Two waves, both run
in parallel across agents: 10 cells in 25 min, 12 cells in 26 min. No cell was rejected by the
degeneracy guard, and no cell was discarded.

| build | case | arm |
|---|---|---|
| [1039514843](https://buildserver.labs.intellij.net/build/1039514843) | `client-auth-method` | mcp |
| [1039540757](https://buildserver.labs.intellij.net/build/1039540757) | `client-auth-method` | mcp |
| [1039566520](https://buildserver.labs.intellij.net/build/1039566520) | `client-auth-method` | mcp |
| [1039540753](https://buildserver.labs.intellij.net/build/1039540753) | `client-auth-method` | shell |
| [1039540755](https://buildserver.labs.intellij.net/build/1039540755) | `client-auth-method` | shell |
| [1039566522](https://buildserver.labs.intellij.net/build/1039566522) | `client-auth-method` | shell |
| [1039540769](https://buildserver.labs.intellij.net/build/1039540769) | `email-domain-mapper` | mcp |
| [1039566795](https://buildserver.labs.intellij.net/build/1039566795) | `email-domain-mapper` | mcp |
| [1039566799](https://buildserver.labs.intellij.net/build/1039566799) | `email-domain-mapper` | mcp |
| [1039540771](https://buildserver.labs.intellij.net/build/1039540771) | `email-domain-mapper` | shell |
| [1039566797](https://buildserver.labs.intellij.net/build/1039566797) | `email-domain-mapper` | shell |
| [1039566801](https://buildserver.labs.intellij.net/build/1039566801) | `email-domain-mapper` | shell |
| [1039514845](https://buildserver.labs.intellij.net/build/1039514845) | `oauth-grant-type` | mcp |
| [1039540763](https://buildserver.labs.intellij.net/build/1039540763) | `oauth-grant-type` | mcp |
| [1039566524](https://buildserver.labs.intellij.net/build/1039566524) | `oauth-grant-type` | mcp |
| [1039540759](https://buildserver.labs.intellij.net/build/1039540759) | `oauth-grant-type` | shell |
| [1039540761](https://buildserver.labs.intellij.net/build/1039540761) | `oauth-grant-type` | shell |
| [1039566526](https://buildserver.labs.intellij.net/build/1039566526) | `oauth-grant-type` | shell |
| [1039540765](https://buildserver.labs.intellij.net/build/1039540765) | `rename-method-wide` | mcp |
| [1039566663](https://buildserver.labs.intellij.net/build/1039566663) | `rename-method-wide` | mcp |
| [1039566791](https://buildserver.labs.intellij.net/build/1039566791) | `rename-method-wide` | mcp |
| [1039540767](https://buildserver.labs.intellij.net/build/1039540767) | `rename-method-wide` | shell |
| [1039566789](https://buildserver.labs.intellij.net/build/1039566789) | `rename-method-wide` | shell |
| [1039566793](https://buildserver.labs.intellij.net/build/1039566793) | `rename-method-wide` | shell |

## Downstream replication round 3 (2026-08-24) + the Dubbo probe

Branch `acquisition-curve-experiment`. Cells: `mcp_steroid_IntegrationTests_AcquisitionDownstream`,
distillation: `...AcquisitionDistill`, probe: `...AcquisitionDubboProbe`. Raw per-cell numbers in
`data/downstream3-cells.csv`; the reading is `RESULTS-DOWNSTREAM-3.md`.

| build | what |
|---|---|
| [1039697519](https://buildserver.labs.intellij.net/build/1039697519) | dubbo prewarm probe — PASSED, 116 modules, ready in 660 s |
| [1039700670](https://buildserver.labs.intellij.net/build/1039700670) | distillation of the 24 notes (re-run, seeded from the repository) |
| [1039700643](https://buildserver.labs.intellij.net/build/1039700643) | anchor cell (`baseline` / `oracle:gold`, both cases) |
| [1039700645](https://buildserver.labs.intellij.net/build/1039700645) | anchor cell (`baseline` / `oracle:gold`, both cases) |
| [1039700647](https://buildserver.labs.intellij.net/build/1039700647) | anchor cell (`baseline` / `oracle:gold`, both cases) |
| [1039700649](https://buildserver.labs.intellij.net/build/1039700649) | anchor cell (`baseline` / `oracle:gold`, both cases) |
| [1039700651](https://buildserver.labs.intellij.net/build/1039700651) | anchor cell (`baseline` / `oracle:gold`, both cases) |
| [1039700653](https://buildserver.labs.intellij.net/build/1039700653) | anchor cell (`baseline` / `oracle:gold`, both cases) |
| [1039700655](https://buildserver.labs.intellij.net/build/1039700655) | anchor cell (`baseline` / `oracle:gold`, both cases) |
| [1039700657](https://buildserver.labs.intellij.net/build/1039700657) | anchor cell (`baseline` / `oracle:gold`, both cases) |
| [1039759218](https://buildserver.labs.intellij.net/build/1039759218) | `oauth-grant-type` note cell — 0/10 |
| [1039759250](https://buildserver.labs.intellij.net/build/1039759250) | `oauth-grant-type` note cell — 0/10 |
| [1039759273](https://buildserver.labs.intellij.net/build/1039759273) | `oauth-grant-type` note cell — 0/10 |
| [1039759503](https://buildserver.labs.intellij.net/build/1039759503) | `oauth-grant-type` note cell — 0/10 |
| [1039759505](https://buildserver.labs.intellij.net/build/1039759505) | `oauth-grant-type` note cell — 0/10 |
| [1039760195](https://buildserver.labs.intellij.net/build/1039760195) | `oauth-grant-type` note cell — 0/10 |
| [1039760636](https://buildserver.labs.intellij.net/build/1039760636) | `oauth-grant-type` note cell — 0/10 |
| [1039760681](https://buildserver.labs.intellij.net/build/1039760681) | `oauth-grant-type` note cell — 0/10 |
| [1039760683](https://buildserver.labs.intellij.net/build/1039760683) | `oauth-grant-type` note cell — 0/10 |
| [1039760685](https://buildserver.labs.intellij.net/build/1039760685) | `oauth-grant-type` note cell — 0/10 |
| [1039760697](https://buildserver.labs.intellij.net/build/1039760697) | `oauth-grant-type` note cell — 0/10 |
| [1039760699](https://buildserver.labs.intellij.net/build/1039760699) | `oauth-grant-type` note cell — 0/10 |
| [1039761044](https://buildserver.labs.intellij.net/build/1039761044) | `client-auth-method` extra `oracle:gold` replicate — 0/9 |
| [1039761157](https://buildserver.labs.intellij.net/build/1039761157) | `client-auth-method` extra `oracle:gold` replicate — 0/9 |
