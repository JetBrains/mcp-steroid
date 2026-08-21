# `petclinic-rest-37` / `pcr37-mcp` — committed checkpoint states

Round 3 of the residual-completion-work study. Exported from capture build `1037545772` and cut down to the
states the pre-registered rule selected; see
[RCW-GENERALIZATION.md](../../../../../../../docs/ripple-checkpoint-pilot/RCW-GENERALIZATION.md).

`index` is the probe coordinate (`-P ripple.checkpoint.index=`), `step` is the position in the original
trajectory, and the checkpoint id is the role the rule assigned:

| index | step | checkpoint | layerCov | patch chars | layers |
|---:|---:|:---|---:|---:|:---|
| 1 | 7 | C3 | 0.000 | 0 |  |
| 2 | 8 | C1+C2+C4 | 0.333 | 2479 | api |
| 3 | 20 | C5 | 0.333 | 4182 | api |

The patch of a step is the whole-tree diff against the pristine revision, so a state is restored by
applying one file. Patches and `checkpoints.json` must be committed together: an index whose patch is
missing fails the probe at restore time, and a patch no index names is never read.
