# `springboot3-1` / `sb31-none` — committed checkpoint states

Round 3 of the residual-completion-work study. Exported from capture build `1037545766` and cut down to the
states the pre-registered rule selected; see
[RCW-GENERALIZATION.md](../../../../../../../docs/ripple-checkpoint-pilot/RCW-GENERALIZATION.md).

`index` is the probe coordinate (`-P ripple.checkpoint.index=`), `step` is the position in the original
trajectory, and the checkpoint id is the role the rule assigned:

| index | step | checkpoint | layerCov | patch chars | layers |
|---:|---:|:---|---:|---:|:---|
| 1 | 5 | C1 | 0.000 | 1169 |  |
| 2 | 9 | C2 | 0.286 | 10084 | service, security |
| 3 | 12 | C3 | 0.571 | 16021 | config, service, transport, security |
| 4 | 13 | C4+C5 | 0.714 | 19587 | config, service, transport, api, security |

The patch of a step is the whole-tree diff against the pristine revision, so a state is restored by
applying one file. Patches and `checkpoints.json` must be committed together: an index whose patch is
missing fails the probe at restore time, and a patch no index names is never read.
