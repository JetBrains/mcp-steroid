# `feature-service-25` / `fs25-none` — committed checkpoint states

Round 3 of the residual-completion-work study. Exported from capture build `1037545762` and cut down to the
states the pre-registered rule selected; see
[RCW-GENERALIZATION.md](../../../../../../../docs/ripple-checkpoint-pilot/RCW-GENERALIZATION.md).

`index` is the probe coordinate (`-P ripple.checkpoint.index=`), `step` is the position in the original
trajectory, and the checkpoint id is the role the rule assigned:

| index | step | checkpoint | layerCov | patch chars | layers |
|---:|---:|:---|---:|---:|:---|
| 1 | 10 | C1 | 0.167 | 516 | schema |
| 2 | 24 | C2 | 0.667 | 5696 | schema, service, transport, domain-model |
| 3 | 30 | C3 | 0.833 | 9249 | schema, persistence, service, transport, domain-model |
| 4 | 31 | C4 | 1.000 | 10292 | schema, persistence, service, transport, api, domain-model |
| 5 | 38 | C5 | 1.000 | 15365 | schema, persistence, service, transport, api, domain-model |

The patch of a step is the whole-tree diff against the pristine revision, so a state is restored by
applying one file. Patches and `checkpoints.json` must be committed together: an index whose patch is
missing fails the probe at restore time, and a patch no index names is never read.
