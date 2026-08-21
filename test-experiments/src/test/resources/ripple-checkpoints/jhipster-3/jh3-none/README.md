# `jhipster-3` / `jh3-none` — committed checkpoint states

Round 3 of the residual-completion-work study. Exported from capture build `1037545770` and cut down to the
states the pre-registered rule selected; see
[RCW-GENERALIZATION.md](../../../../../../../docs/ripple-checkpoint-pilot/RCW-GENERALIZATION.md).

`index` is the probe coordinate (`-P ripple.checkpoint.index=`), `step` is the position in the original
trajectory, and the checkpoint id is the role the rule assigned:

| index | step | checkpoint | layerCov | patch chars | layers |
|---:|---:|:---|---:|---:|:---|
| 1 | 13 | C1 | 0.200 | 638 | domain-rules |
| 2 | 15 | C2 | 0.800 | 5753 | config, domain-rules, api, view |
| 3 | 16 | C3 | 0.800 | 6726 | config, domain-rules, api, view |
| 4 | 17 | C4 | 1.000 | 9020 | schema, config, domain-rules, api, view |
| 5 | 18 | C5 | 1.000 | 9968 | schema, config, domain-rules, api, view |

The patch of a step is the whole-tree diff against the pristine revision, so a state is restored by
applying one file. Patches and `checkpoints.json` must be committed together: an index whose patch is
missing fails the probe at restore time, and a patch no index names is never read.
