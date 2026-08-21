#!/usr/bin/env python3
"""Round 3's milestone rule, as executable code — frozen before any round-3 build was queued.

Round 2 (`REPLICATION-2.md`) anchored its milestones in a SEVEN-layer taxonomy hand-written for one
case, `dpaia__feature__service-125`. That cannot generalize: a taxonomy authored per case is a free
parameter, and six of them would be six opportunities to choose the layer set that produces the answer
one wants.

So round 3 inverts the construction. ONE ordered, case-independent pattern list (`LAYER_RULES`) assigns
every production path to exactly one architectural layer. The layer set OF A CASE, `L_case`, is then
whatever layers that case's own GOLD PATCH touches — read out of the dpaia dataset, which is outside
both trajectories and cannot be influenced by either arm. Coverage is normalized by `|L_case|`, never by
the size of the global taxonomy, so a three-layer case is not permanently stuck at 3/11.

Nothing in this file may change once a round-3 probe verdict has been read. A change after that point is
a deviation and belongs in the deviations section of `RCW-GENERALIZATION.md`.

Usage:
    rcw_layers.py gold <dataset.json>          print `L_case` for every round-3 case (the frozen table)
    rcw_layers.py trace <dataset.json> <case> <arm-dir>
                                               per-step coverage + milestones of one committed capture
"""
import json
import os
import re
import sys

# The dataset the gold patches come from. Downloaded rather than vendored: it is the same URL
# `DpaiaCuratedCases` documents, and pinning a copy here would let the two drift apart silently.
DATASET_URL = (
    "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"
)

# The six cases of round 3, plus the pilot for control. `resourceDir` matches
# `test-experiments/src/test/resources/ripple-checkpoints/<dir>` and `RippleCheckpointCases.kt`.
ROUND3_CASES = {
    "petclinic-71": "dpaia__spring__petclinic-71",
    "petclinic-rest-37": "dpaia__spring__petclinic__rest-37",
    "petclinic-36": "dpaia__spring__petclinic-36",
    "springboot3-1": "dpaia__empty__maven__springboot3-1",
    "jhipster-3": "dpaia__jhipster__sample__app-3",
    "feature-service-25": "dpaia__feature__service-25",
}
PILOT_CASE = {"feature-service-125": "dpaia__feature__service-125"}

# Ordered, FIRST MATCH WINS. Patterns and not gold paths, because an agent that solves the same layer
# under its own file name must still count — round 1's shell arm renamed the migration and invented
# three classes a gold-path intersection would never have seen.
#
# The last two rules are the ones that make the list case-independent, and both are deliberate:
#
#  * `.java` that matched nothing above is `domain-model`. Petclinic keeps its entities as
#    `owner/Owner.java`, `owner/Pet.java`, `vet/Vet.java` — no `model` package anywhere — and without
#    this rule the whole domain layer of two of the six cases would be invisible.
#  * anything else under `src/main/` is `other`, so a state is never silently un-scored. `other` is a
#    real layer and enters `L_case` whenever the gold patch puts something there.
LAYER_RULES = [
    ("schema", r"/db/|/liquibase/|\.sql$|changelog"),
    ("config", r"/config/|application\.(properties|ya?ml)$|Config(uration)?\.java$|Properties\.java$"),
    ("domain-rules", r"Validator\.java$|/exceptions?/|Exception\.java$|Constants\.java$|Policy\.java$"),
    ("persistence", r"/repository/|Repository\w*\.java$|Dao\w*\.java$|Specifications?\.java$"),
    ("service", r"/service/|Service\w*\.java$|Commands\.java$|Manager\w*\.java$"),
    ("transport", r"/dtos?/|/api/models/|Mapper\.java$|Payload\.java$|Request\.java$|Response\.java$|Formatter\.java$"),
    ("api", r"/controllers?/|/rest/|Controller\.java$|Resource\.java$|ExceptionHandler\.java$"),
    ("security", r"/security/|/jwt/|Filter\.java$|TokenUtil\.java$"),
    ("view", r"/templates/|/webapp/|\.html$|\.tsx?$|\.js$|\.css$"),
    ("domain-model", r"\.java$"),
    ("other", r"."),
]
LAYERS = [name for name, _ in LAYER_RULES]

DIFF_HEADER = re.compile(r"^diff --git a/(\S+) b/(\S+)", re.M)
STEP_IN_NAME = re.compile(r"step-(\d+)")


def layer_of(path):
    """The single layer a production path belongs to. Never None — `other` is the catch-all."""
    for name, pattern in LAYER_RULES:
        if re.search(pattern, path):
            return name
    return "other"


def production_files(paths):
    """Only what ships. Tests, build output and everything outside a source root are dropped.

    `/src/main/` and not `src/main/` alone, because three of the six repos are multi-module Maven
    builds whose paths start with the module directory.
    """
    return {p for p in paths if p.startswith("src/main/") or "/src/main/" in p}


def touched_files(patch_text):
    """Production files a whole-tree patch touches, de-duplicated (rest-37's gold patch repeats them)."""
    return production_files({b for _, b in DIFF_HEADER.findall(patch_text)})


def gold_layers(entry):
    """`L_case` — the layers the dataset's own gold patch touches, in taxonomy order."""
    files = touched_files(entry["patch"])
    layers = {layer_of(p) for p in files}
    return sorted(layers, key=LAYERS.index), sorted(files)


def coverage(patch_text, case_layers, gold_files):
    """`layerCov` (the milestone axis) and `fileCov` (the taxonomy-free control) of one state."""
    files = touched_files(patch_text)
    hit = {layer_of(p) for p in files} & set(case_layers)
    return dict(
        files=sorted(files),
        layers=sorted(hit, key=LAYERS.index),
        layerCov=len(hit) / len(case_layers),
        fileCov=(len(files & set(gold_files)) / len(gold_files)) if gold_files else 0.0,
    )


def transition_step(steps):
    """`T` — the largest single-step increase in `layerCov`; ties resolve to the earliest step."""
    best, best_delta, previous = None, None, 0.0
    for step, cov in steps:
        delta = cov - previous
        if best_delta is None or delta > best_delta + 1e-12:
            best, best_delta = step, delta
        previous = cov
    return best, best_delta


def milestones(steps, first_write=None):
    """`M0`, `Mmid`, `Mlast` over an ordered `(step, layerCov)` sequence.

    `M0` is the first WRITE and is passed in rather than derived: a step whose only change lies outside
    a source root leaves `layerCov` at zero while still being a recorded step.
    """
    out = {"M0": first_write, "Mmid": None, "Mlast": None}
    for step, cov in steps:
        if out["Mmid"] is None and cov >= 0.5:
            out["Mmid"] = step
        if out["Mlast"] is None and cov >= 1.0:
            out["Mlast"] = step
    return out


def select_checkpoints(steps, first_write, last_distinct):
    """The five pre-registered states of one trajectory, exactly as `RCW-GENERALIZATION.md` fixes them.

    `steps` is the ordered `(step, layerCov)` sequence over EVERY recorded step of the capture;
    `last_distinct` is the last step whose work tree differs from the final one. Returns a list of
    `(id, step)` with collisions PRESERVED — two ids landing on one step is data about the trajectory's
    shape, and the aggregator folds it through `sameStateAs` exactly as rounds 1 and 2 did.

    The rule uses coverage and position only. No downstream probe result may enter it; that is what
    makes the round a pre-registration rather than a search for a nice-looking drop.
    """
    ordered = [s for s, _ in steps]
    marks = milestones(steps, first_write)
    mlast = marks["Mlast"] if marks["Mlast"] is not None else _peak_coverage_step(steps)

    # C2 — a POSITIONAL anchor, deliberately independent of coverage. Without it a trajectory whose
    # layers all land in one batched write (round 2's mcp2 reached 6 of 7 layers at its first write)
    # would spend four of its five states inside a two-step window and measure nothing in between.
    midpoint = first_write + (last_distinct - first_write) / 2.0
    c2 = min((s for s in ordered if first_write <= s <= last_distinct),
             key=lambda s: (abs(s - midpoint), s), default=first_write)

    # C3 — the state immediately BEFORE the last layer lands. When the transition is adjacent to the
    # first write, "before it" is the pre-write state, which is what round 2 probed as mcp2 step 13.
    before_mlast = _before(ordered, mlast, first_write)
    if before_mlast == first_write:
        before_mlast = _before(ordered, first_write, first_write)

    return [
        ("C1", first_write),
        ("C2", c2),
        ("C3", before_mlast),
        ("C4", mlast),
        ("C5", last_distinct),
    ]


def _before(ordered, step, fallback):
    """The recorded step immediately before `step`, or `fallback` when none exists."""
    return max((s for s in ordered if s < step), default=fallback)


def _peak_coverage_step(steps):
    """The earliest step holding the highest `layerCov` — `Mlast`'s stand-in when 1.0 is never reached."""
    best = max(cov for _, cov in steps)
    return next(step for step, cov in steps if cov >= best - 1e-12)


def _load(dataset_path):
    with open(dataset_path) as fh:
        return {c["instance_id"]: c for c in json.load(fh)}


def _print_gold(dataset_path):
    by_id = _load(dataset_path)
    for resource_dir, instance_id in {**PILOT_CASE, **ROUND3_CASES}.items():
        entry = by_id.get(instance_id)
        if entry is None:
            raise SystemExit(f"{instance_id} is not in {dataset_path}")
        layers, files = gold_layers(entry)
        print(
            f"{resource_dir:20s} {instance_id:36s} base={entry['base_commit'][:10]} "
            f"patch={len(entry['patch']):6d} ftp={len(entry['FAIL_TO_PASS']):4d} "
            f"prod={len(files):3d} |L|={len(layers)} {layers}"
        )


def read_steps(arm_dir):
    """`{step: patch text}` of one capture's exported per-step patches.

    A step's patch is the WHOLE-TREE diff against the pristine revision, so patch text is a faithful
    identity for the work-tree state: two steps with equal text are the same state, and an empty one is
    an untouched tree. That is what lets [first_write_step] and [last_distinct_step] be read off here
    instead of trusting a recorder field or re-hashing trees.
    """
    steps = {}
    for name in os.listdir(arm_dir):
        if not name.endswith(".patch"):
            continue
        match = STEP_IN_NAME.search(name)
        if match is None:
            continue
        with open(os.path.join(arm_dir, name), errors="replace") as fh:
            steps[int(match.group(1))] = fh.read()
    if not steps:
        raise SystemExit(f"{arm_dir} holds no step-<n>.patch files")
    return steps


def first_write_step(steps):
    """`M0` — the first step whose tree differs from the pristine one.

    ANY change counts, not just one under a source root. `springboot3-1`'s shell arm opens by adding the
    web and security starters to `pom.xml`: that is the first substantive act of the trajectory, it is
    what the recorder's own `firstWriteStep` reports, and scoring it as "not yet started" because a POM
    is not production Java would put `M0` two steps late and silently drop the state the pre-registration
    calls the early anchor.
    """
    return min((step for step, patch in steps.items() if patch.strip()), default=min(steps))


def last_distinct_step(steps):
    """`C5` — the last step whose tree differs from the FINAL tree.

    Captures end with verification: `springboot3-1`'s mcp arm runs the build at steps 14, 15 and 16 and
    all three carry byte-identical patches. Probing any of them measures the finished solution, which is
    the one state whose residual work is known in advance to be near zero.
    """
    final = steps[max(steps)]
    return max((step for step, patch in steps.items() if patch != final), default=min(steps))


def _print_trace(dataset_path, resource_dir, arm_dir):
    by_id = _load(dataset_path)
    instance_id = {**PILOT_CASE, **ROUND3_CASES}[resource_dir]
    case_layers, gold_files = gold_layers(by_id[instance_id])
    steps = read_steps(arm_dir)
    rows, previous = [], 0.0
    for step in sorted(steps):
        cov = coverage(steps[step], case_layers, gold_files)
        rows.append((step, cov["layerCov"]))
        print(
            f"  step {step:3d}  layerCov {cov['layerCov']:.3f}  d {cov['layerCov'] - previous:+.3f}"
            f"  fileCov {cov['fileCov']:.2f}  chars {len(steps[step]):6d}  {cov['layers']}"
        )
        previous = cov["layerCov"]
    first_write = first_write_step(steps)
    last_distinct = last_distinct_step(steps)
    t, delta = transition_step(rows)
    print(f"  L_case={case_layers}")
    print(f"  M0={first_write} lastDistinct={last_distinct} T=step {t} ({delta:+.3f}) "
          f"{milestones(rows, first_write)}")
    picked = select_checkpoints(rows, first_write, last_distinct)
    print(f"  checkpoints {[(i, s) for i, s in picked]} -> distinct {sorted({s for _, s in picked})}")


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "gold":
        _print_gold(sys.argv[2])
    elif len(sys.argv) >= 5 and sys.argv[1] == "trace":
        _print_trace(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        raise SystemExit(__doc__)
