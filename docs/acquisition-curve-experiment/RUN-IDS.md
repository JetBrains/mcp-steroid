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
