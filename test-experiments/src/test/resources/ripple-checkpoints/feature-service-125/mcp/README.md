# `dpaia__feature__service-125` — mcp arm checkpoints

The states `RippleCheckpointProbeTest.probe` restarts a bare Haiku from, captured by
`DpaiaFeatureService125CheckpointCaptureTest.captureMcpArm`.

Expected contents once the capture has run and been admitted:

- `step-<a_i>.patch`, one per planned checkpoint (at most `RIPPLE_CHECKPOINT_COUNT` = 5) —
  `git diff step-0 step-<a_i>` of the recorded trajectory. **The file names are NOT known before the
  run.** The positions are `round(n·(i/6)^1.5)` of the MEASURED tool-call count `n`, moved forward
  where a nominal step held a state already probed and dropped where no differing state exists, so
  only `RippleCheckpointRecorder.plan` — running after the capture — can say which steps they are. A
  probe cell addresses a checkpoint by its ORDINAL (1..5) and resolves the step through
  `checkpoints.json`.
- `checkpoints.json` — `RippleCheckpointRecorder.exportMetadata`. It carries the measured `n`, and for
  every checkpoint its ordinal (`index`), its `nominalStep`, the `step` really probed, the `position`
  = `step/n`, the `patch` file name, and every correction the selection had to make. Without this file
  no probe can say where on the trajectory its readiness value belongs, and
  `RippleCheckpointProbeTest` refuses a directory whose patches and metadata do not match.

Copy the patches and `checkpoints.json` out of the admitted capture build's run-directory artifact
(`checkpoints/`) in ONE commit — patches from one run and metadata from another describe a trajectory
that never existed, and the probe would normalize its position by the wrong `n`.

The two arms' curves are compared at equal NORMALIZED positions `a_i/n`, not at equal tool-call counts:
each capture has its own `n`, so the step numbers here and in `../none/` are expected to differ.

This README is what keeps the directory in git before the capture lands: an empty directory cannot be
committed, and `RippleCheckpointProbeTest` asserts the layout exists because a probe cell must fail on
a missing STATE rather than on a missing folder.
