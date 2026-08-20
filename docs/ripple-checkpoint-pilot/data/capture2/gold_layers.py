#!/usr/bin/env python3
"""The frozen milestone rule of round 2, as executable code.

Pre-registered in `REPLICATION-2.md` before any capture-2 build was queued. Nothing here may change
once a capture-2 verdict has been read; a change would be a deviation and must be recorded as one.

Run without arguments to reproduce the "rule validated on capture 1" table of `REPLICATION-2.md` from
the committed round-1 states.
"""
import os
import re
import sys

# The gold patch's production files, from the dataset entry `dpaia__feature__service-125`
# (base_commit e5a4623e8aa7b7d41485b0095e0ae9c38133d7a2, patch 44 568 chars).
GOLD_FILES = [
    "src/main/resources/db/migration/V5__enhance_releases_for_advanced_query.sql",
    "src/main/java/com/sivalabs/ft/features/domain/entities/Release.java",
    "src/main/java/com/sivalabs/ft/features/domain/models/ReleaseStatus.java",
    "src/main/java/com/sivalabs/ft/features/domain/ReleaseStatusTransitionValidator.java",
    "src/main/java/com/sivalabs/ft/features/domain/exceptions/InvalidStatusTransitionException.java",
    "src/main/java/com/sivalabs/ft/features/domain/ReleaseRepository.java",
    "src/main/java/com/sivalabs/ft/features/domain/ReleaseService.java",
    "src/main/java/com/sivalabs/ft/features/domain/Commands.java",
    "src/main/java/com/sivalabs/ft/features/domain/dtos/ReleaseDto.java",
    "src/main/java/com/sivalabs/ft/features/api/models/CreateReleasePayload.java",
    "src/main/java/com/sivalabs/ft/features/api/models/UpdateReleasePayload.java",
    "src/main/java/com/sivalabs/ft/features/api/controllers/ReleaseController.java",
    "src/main/java/com/sivalabs/ft/features/api/GlobalExceptionHandler.java",
]

# Ordered, first match wins. Patterns rather than gold paths, because an agent that solves the same
# layer under its own name must count: the round-1 shell run wrote `V5__add_release_planning_columns.sql`
# and invented `ReleaseSpecifications.java` / `ReleaseMapper.java` / `ReleaseSearchCriteria.java`.
LAYER_RULES = [
    ("schema", r"^src/main/resources/db/migration/"),
    ("domain-model", r"/domain/entities/|/domain/models/"),
    ("domain-rules", r"Validator\.java$|/domain/exceptions/"),
    ("persistence", r"Repository\.java$|Specifications?\.java$"),
    ("service", r"Service\.java$|/domain/Commands\.java$"),
    ("transport", r"/domain/dtos/|/api/models/|Mapper\.java$"),
    ("api", r"/api/controllers/|ExceptionHandler\.java$"),
]
LAYERS = [name for name, _ in LAYER_RULES]

DIFF_HEADER = re.compile(r"^diff --git a/(\S+) b/(\S+)", re.M)


def layer_of(path):
    """The single layer a production path belongs to, or None when it is outside the taxonomy."""
    for name, pattern in LAYER_RULES:
        if re.search(pattern, path):
            return name
    return None


def touched_files(patch_text):
    """Production files a state's whole-tree patch touches. Tests and build output never count."""
    return {b for _, b in DIFF_HEADER.findall(patch_text) if b.startswith("src/main/")}


def coverage(patch_text):
    """`fileCov` (taxonomy-free control) and `layerCov` (the pre-registered milestone axis)."""
    files = touched_files(patch_text)
    layers = {layer_of(p) for p in files} - {None}
    return dict(
        files=sorted(files),
        layers=sorted(layers, key=LAYERS.index),
        fileCov=len(files & set(GOLD_FILES)) / len(GOLD_FILES),
        layerCov=len(layers) / len(LAYERS),
    )


def transition_step(steps):
    """`T` — the step with the largest single-step increase in `layerCov`; ties resolve to the earliest.

    `steps` is an ordered sequence of `(step, layerCov)`, one entry per recorded step.
    """
    best, best_delta, previous = None, None, 0.0
    for step, cov in steps:
        delta = cov - previous
        if best_delta is None or delta > best_delta + 1e-12:
            best, best_delta = step, delta
        previous = cov
    return best, best_delta


def milestones(steps, first_write=None):
    """`M0`, `M50`, `Mfull` and `Mapi` over an ordered `(step, layerCov, layers)` sequence.

    `M0` is the first WRITE, and it is passed in rather than derived here: coverage cannot see it. A
    step that reads a file, or one whose only change is outside `src/main/`, leaves `layerCov` at zero
    while still being a recorded step, so taking the first entry of the sequence would place `M0` before
    the agent had written anything at all.
    """
    out = {"M0": first_write, "M50": None, "Mfull": None, "Mapi": None}
    for step, cov, layers in steps:
        if out["M50"] is None and cov >= 0.5:
            out["M50"] = step
        if out["Mfull"] is None and cov >= 1.0:
            out["Mfull"] = step
        if out["Mapi"] is None and "api" in layers:
            out["Mapi"] = step
    return out


def _capture1_table(root):
    """Reproduce the capture-1 validation table from the committed round-1 states."""
    base = os.path.join(root, "test-experiments/src/test/resources/ripple-checkpoints/feature-service-125")
    unmapped = [g for g in GOLD_FILES if layer_of(g) is None]
    if unmapped:
        raise SystemExit(f"taxonomy does not cover every gold file: {unmapped}")
    for arm in ("mcp", "none"):
        print(f"== {arm}")
        rows, previous = [], 0.0
        names = sorted(
            (f for f in os.listdir(os.path.join(base, arm)) if f.endswith(".patch")),
            key=lambda s: int(re.search(r"step-(\d+)", s).group(1)),
        )
        for name in names:
            step = int(re.search(r"step-(\d+)", name).group(1))
            with open(os.path.join(base, arm, name), errors="replace") as fh:
                cov = coverage(fh.read())
            rows.append((step, cov["layerCov"], cov["layers"]))
            print(
                f"  step {step:3d}  fileCov {cov['fileCov']:.2f}  layerCov {cov['layerCov']:.3f}"
                f"  d {cov['layerCov'] - previous:+.3f}  {cov['layers']}"
            )
            previous = cov["layerCov"]
        t, delta = transition_step([(s, c) for s, c, _ in rows])
        first_write = next((s for s, _, layers in rows if layers), None)
        print(f"  T = step {t} (delta {delta:+.3f}); milestones {milestones(rows, first_write)}")


if __name__ == "__main__":
    repo_root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "..")
    _capture1_table(sys.argv[1] if len(sys.argv) > 1 else repo_root)
