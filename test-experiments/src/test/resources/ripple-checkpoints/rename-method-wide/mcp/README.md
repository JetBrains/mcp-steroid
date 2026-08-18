# `rename-method-wide` — mcp arm checkpoints

The states `RippleCheckpointProbeTest.probe` restarts a bare Haiku from, captured by
`KeycloakRenameMethodWideCheckpointCaptureTest.claude with mcp`.

Expected contents once the capture has run and been admitted:

- `step-2.patch`, `step-6.patch`, `step-11.patch`, `step-17.patch`, `step-24.patch` —
  `git diff step-0 step-<a_i>` of the recorded trajectory. The positions come from
  `rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS["mcp"] = 32)`, so the file names are known before the
  run and a probe cell addresses one of them by index.
- `checkpoints.json` — `RippleCheckpointRecorder.exportMetadata`, which carries the MEASURED step
  count `n`. The probe reports `position = a_i/n` from it; without the file no probe can say where on
  the trajectory its readiness value belongs.

Copy all six files out of the admitted capture build's run-directory artifact (`checkpoints/`) in one
commit — patches from one run and metadata from another describe a trajectory that never existed.

This README is what keeps the directory in git before the capture lands: an empty directory cannot be
committed, and `RippleCheckpointProbeTest` asserts the layout exists because a probe cell must fail on
a missing STATE rather than on a missing folder.
