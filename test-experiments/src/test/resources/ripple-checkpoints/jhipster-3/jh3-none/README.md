# `dpaia__jhipster__sample__app-3` — `jh3-none` arm checkpoints

The states `RippleCheckpointProbeTest.probe` restarts a bare Haiku from, captured by
`DpaiaJhipster3CheckpointCaptureTest.captureShellArm`. A probe cell addresses them as
`-Dripple.checkpoint.arm=jh3-none`: the token is what names the case, because a probe build forwards
only `arm`, `index` and `replicate` — see `RippleCheckpointCaseSpec`.

Expected contents once the capture has run and been admitted:

- `step-<n>.patch`, one per checkpoint of the ten-fraction grid — `git diff step-0 step-<n>` of the
  recorded trajectory. **The file names are NOT known before the run.** The steps are the even
  fractions of the edit phase that actually happened, so only `RippleCheckpointRecorder.plan`, running
  after the capture, can say which they are. A probe cell addresses a checkpoint by its ORDINAL and
  resolves the step through `checkpoints.json`.
- `checkpoints.json` — `RippleCheckpointRecorder.exportMetadata`: the measured `n`, the first write the
  edit phase is counted from, and per checkpoint its ordinal, step, `editFraction`, `position` and the
  `sameStateAs` marker of a repeated state.

Copy the patches and `checkpoints.json` out of the admitted capture build's run-directory artifact
(`checkpoints/`) in ONE commit — patches from one run next to metadata from another describe a
trajectory that never existed, and the probe would normalize its position by the wrong `n`.
`RippleCheckpointProbeTest` refuses a directory holding either one without the other.

The two arms' curves are compared at equal fractions of the EDIT PHASE, not at equal tool-call counts:
each capture has its own `n` and its own first write, so the step numbers here and in `../jh3-mcp/`
are expected to differ.

This README is what keeps the directory in git before the capture lands: an empty directory cannot be
committed, and `RippleCheckpointProbeTest` asserts the layout exists because a probe cell must fail on
a missing STATE rather than on a missing folder.
