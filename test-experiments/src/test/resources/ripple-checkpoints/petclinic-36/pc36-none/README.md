# `petclinic-36` / `pc36-none` — committed checkpoint states

Round 3 of the residual-completion-work study. Exported from capture build `1037545758` and cut down to the
states the pre-registered rule selected; see
[RCW-GENERALIZATION.md](../../../../../../../docs/ripple-checkpoint-pilot/RCW-GENERALIZATION.md).

`index` is the probe coordinate (`-P ripple.checkpoint.index=`), `step` is the position in the original
trajectory, and the checkpoint id is the role the rule assigned:

| index | step | checkpoint | layerCov | patch chars | layers |
|---:|---:|:---|---:|---:|:---|
| 1 | 11 | C1 | 0.333 | 2199 | domain-model |
| 2 | 12 | C3 | 0.667 | 17581 | schema, domain-model |
| 3 | 13 | C2+C4+C5 | 1.000 | 23264 | schema, view, domain-model |

The patch of a step is the whole-tree diff against the pristine revision, so a state is restored by
applying one file. Patches and `checkpoints.json` must be committed together: an index whose patch is
missing fails the probe at restore time, and a patch no index names is never read.
