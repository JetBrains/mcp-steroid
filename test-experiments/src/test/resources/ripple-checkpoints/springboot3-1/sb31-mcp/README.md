# `springboot3-1` / `sb31-mcp` — committed checkpoint states

Round 3 of the residual-completion-work study. Exported from capture build `1037545764` and cut down to the
states the pre-registered rule selected; see
[RCW-GENERALIZATION.md](../../../../../../../docs/ripple-checkpoint-pilot/RCW-GENERALIZATION.md).

`index` is the probe coordinate (`-P ripple.checkpoint.index=`), `step` is the position in the original
trajectory, and the checkpoint id is the role the rule assigned:

| index | step | checkpoint | layerCov | patch chars | layers |
|---:|---:|:---|---:|---:|:---|
| 1 | 7 | C1 | 0.143 | 701 | domain-model |
| 2 | 10 | C2 | 0.571 | 7887 | persistence, service, transport, domain-model |
| 3 | 12 | C3 | 0.857 | 14123 | persistence, service, transport, api, security, domain-model |
| 4 | 13 | C4+C5 | 1.000 | 17847 | config, persistence, service, transport, api, security, domain-model |

The patch of a step is the whole-tree diff against the pristine revision, so a state is restored by
applying one file. Patches and `checkpoints.json` must be committed together: an index whose patch is
missing fails the probe at restore time, and a patch no index names is never read.
