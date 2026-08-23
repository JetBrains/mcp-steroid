# Distilled checkpoint notes

One file per (trajectory, checkpoint): `<trajectoryId>-at<B>.md`, addressed by a downstream cell as
`-Dunderstanding.condition=checkpoint:<trajectoryId>@<B>`.

Each note is a read-out of what one research trajectory had observed after `B` environment
interactions, and nothing else. It is produced offline, by the strong model with **no tools and no
repository**, from the prompt `AcquisitionRecomputeTest` writes out of the committed transcript:

```
./gradlew :test-experiments:test --tests '*AcquisitionRecomputeTest*' \
    -Dacquisition.recompute.dir=docs/acquisition-curve-experiment/data/trajectories \
    -Dacquisition.recompute.out=/tmp/acq-distill
python3 docs/acquisition-curve-experiment/analysis/distill_and_judge.py \
    --artifacts /tmp/acq-distill --out /tmp/acq-distill
```

Two rules that make these notes evidence rather than decoration:

- **Nothing but the prefix goes in.** No gold, no oracle, no hindsight. A note that stated something
  the trajectory never observed would break the only link this round tests.
- **The distiller never mentions how anything was found.** That is what keeps a note untraceable to
  its arm, and therefore keeps the blind judge blind.

The hand-written `oracle-gold` note is NOT here — it lives beside the other family's notes under
`understanding-notes/`, because it is the opposite kind of object: written by someone who had already
seen the solution, and admissible only as the calibration ceiling.
