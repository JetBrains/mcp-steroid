#!/usr/bin/env python3
"""Read the round-8 floor probe against the rule registered in DESIGN-DOWNSTREAM-8.md.

The probe varies the allowance and the solver, not the note: one note (`mcp-b40-l2000-r3@20`) and
one no-note condition per setting. So the readout is a GAP, `g = mean(note) - mean(no note)` per
setting, and the two adoption conditions are the ones written down before any cell was queued -- the
ceiling has to move and the floor has to hold.

The parsing is round 7's, imported rather than copied: the same marker line, the same two-rendering
surefire reconstruction, the same refusal to average a cell whose two sources disagree. What is new
here is the scale rule, and it is applied to the probe's own cells rather than asserted -- the helper
grouping is read out of `oracle-v2.patch`, and the constant axes are whatever the probe finds
constant, not whatever round 7 expected.
"""

import argparse
import csv
import pathlib
import re
import statistics
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from downstream7_collect import (  # noqa: E402
    CASE, MARKER, RETAINED_AXES, axes_from_surefire, fetch, marker_fields,
)

# The ledger records what was TRIGGERED (`checkpoint:<trajectory>@<checkpoint>`); the harness echoes
# the condition's own label, which drops the `checkpoint:` prefix. Normalising one onto the other is
# not the same as relaxing the check -- everything after the prefix still has to match exactly, which
# is what catches a cell that ran a different note than the one queued.
TRIGGER_PREFIX = "checkpoint:"
NOTE_LABEL = "mcp-b40-l2000-r3@20"
FLOOR_LABEL = "baseline"
# The floor half of the adoption rule, in obligations of six. Registered, not chosen here: the
# no-note arm stays at or below 3 of 6. The design states the item-scale form as a parenthetical
# EQUIVALENCE -- "does not discharge more than one of the three items" -- so it is written out as 1,
# not derived as a proportion of 3/6 (which would give 1.5 and quietly admit a floor of 1 item that
# the design does not admit). The equivalence was stated for a three-item scale; if the probe's own
# cells yield some other number of items, the parenthetical no longer has a referent and the binding
# rule is the six-axis one it parenthesises.
FLOOR_CEILING_SIX = 3
FLOOR_CEILING_ITEMS = 1
REGISTERED_ITEM_TOTAL = 3
REFUSAL_HELPER = "assertRefusedBeforeIssuing"
ORACLE = pathlib.Path("test-experiments/src/test/resources/acquisition-cases") / CASE / "oracle-v2.patch"
TEST_DECL = re.compile(r"^\+\s*public void (\w+)\(\)")
HELPER_DECL = re.compile(r"^\+\s*private static \w[\w<>\[\], .]* (\w+)\(")
# Only helpers that carry the VERDICT group two axes into one item. The rule says two axes are one
# obligation when they "reach their verdict through the same helper", and a fixture builder is not a
# verdict: `anOrdinary...` and `anUnreadable...` both assert through assertRefusedBeforeIssuing but
# only one of them also calls refreshTokenOfType(), so keying on every private helper would leave the
# pair ungrouped -- which is the double-count the rule exists to remove. Fixed before any round-8
# cell had produced an outcome to read.
ASSERTION_HELPER = re.compile(r"^assert[A-Z]")
HELPER_CALL = re.compile(r"\b(\w+)\s*\(")


def helper_groups() -> dict:
    """Which axes reach their verdict through the same private assertion helper.

    Derived from the oracle patch rather than listed here: a hand-written grouping is a place for
    the scale to drift once someone edits the oracle, and the whole point of the rule is that the
    scale stops being a choice.
    """
    lines = ORACLE.read_text().splitlines()
    declared = {m.group(1) for line in lines if (m := HELPER_DECL.match(line))}
    helpers = {name for name in declared if ASSERTION_HELPER.match(name)}
    if "assertRefusedBeforeIssuing" not in helpers:
        raise SystemExit(f"{ORACLE}: no private assertion helpers found -- the parser is stale")
    calls, current = {}, None
    for line in lines:
        declared = TEST_DECL.match(line)
        if declared:
            current = declared.group(1)
            calls[current] = set()
            continue
        if current and HELPER_DECL.match(line):
            current = None
            continue
        if current:
            calls[current] |= {name for name in HELPER_CALL.findall(line) if name in helpers}
    groups = {}
    for axis in RETAINED_AXES:
        key = tuple(sorted(calls.get(axis, ()))) or (axis,)
        groups.setdefault(key, []).append(axis)
    return groups


def load_ids(path: str) -> list:
    cells = []
    for raw in open(path):
        line = raw.split("#", 1)[0].split()
        if not line:
            continue
        setting, allowance, condition, replicate, build_id = line
        cells.append({
            "setting": setting,
            "allowance": int(allowance),
            "condition": condition.removeprefix(TRIGGER_PREFIX),
            "triggered": condition,
            "replicate": int(replicate.lstrip("r")),
            "build": build_id,
        })
    return cells


def collect(cells: list, solver_of: dict) -> tuple:
    rows, problems = [], []
    for cell in cells:
        text = fetch(cell["build"])
        if not MARKER.search(text):
            problems.append(f"{cell['build']}: no [ACQUISITION-DOWN] line -- the cell never graded")
            continue
        fields = marker_fields(text, cell["build"])
        # The ledger is checked against the harness's own echo, because a misrouted parameter is
        # exactly what voided this round's first batch and it was invisible in the trigger's reply.
        expected = {"case": CASE, "condition": cell["condition"], "replicate": str(cell["replicate"])}
        for key, want in expected.items():
            if fields.get(key) != want:
                raise SystemExit(f"{cell['build']}: log says {key}={fields.get(key)!r}, "
                                 f"ledger says {want!r} -- the cell that ran is not the cell queued")
        budget_total = fields.get("budget", "?/?").split("/")[-1]
        if budget_total != str(cell["allowance"]):
            raise SystemExit(f"{cell['build']}: ran at allowance {budget_total}, "
                             f"ledger says {cell['allowance']}")
        raw_passed, raw_total = fields["oraclePassed"].split("/")
        if int(raw_total) != len(RETAINED_AXES):
            raise SystemExit(f"{cell['build']}: graded {raw_total} axes -- that is not oracle-v2")
        compiled = int(fields["compiled"])
        passed = None if raw_passed == "unmeasured" else int(raw_passed)
        if passed is None and compiled:
            raise SystemExit(f"{cell['build']}: says compiled=1 yet the oracle was never measured")
        axes = axes_from_surefire(text, cell["build"])
        if axes and passed is not None:
            if len(axes) != len(RETAINED_AXES):
                problems.append(f"{cell['build']}: surefire carries {len(axes)}/{len(RETAINED_AXES)} axes")
            elif sum(v == "PASS" for v in axes.values()) != passed:
                problems.append(f"{cell['build']}: marker says {passed}, surefire says "
                                f"{sum(v == 'PASS' for v in axes.values())}")
        elif compiled and not axes:
            problems.append(f"{cell['build']}: compiled but no surefire block found")
        # A tree that did not build discharged nothing -- round 6's convention, kept so every round's
        # table means the same thing. Its axes are all FAIL for the same reason.
        if not compiled:
            axes = {axis: "FAIL" for axis in RETAINED_AXES}
        rows.append({**cell, "solver": solver_of.get(cell["setting"], "?"), "compiled": compiled,
                     "six": passed if compiled else 0, "axes": axes,
                     "usd": fields.get("usd", ""), "denied": fields.get("denied", ""),
                     "repairRounds": fields.get("repairRounds", ""),
                     "used": fields.get("budget", "?/?").split("/")[0]})
        print(f"  {cell['setting']} b{cell['allowance']:<3} {cell['condition']:<32} "
              f"r{cell['replicate']:<3} {cell['build']}  "
              f"{'compiled' if compiled else 'NO BUILD'}  {passed}/{raw_total}", flush=True)
    return rows, problems


def apply_scale(rows: list, gold_axes: list) -> tuple:
    """The registered scale rule, applied to the probe's cells (plus the free gold anchors).

    Constancy is judged over every cell the probe produced AND the anchors, exactly as registered:
    an axis that no condition ever moves is measuring nothing here whatever it does elsewhere.
    """
    universe = [row["axes"] for row in rows] + gold_axes
    constant = [axis for axis in RETAINED_AXES
                if len({verdicts.get(axis) for verdicts in universe if axis in verdicts}) == 1]
    items, notes, refusal = [], [], []
    for key, group in sorted(helper_groups().items(), key=lambda kv: kv[1]):
        kept = [axis for axis in group if axis not in constant]
        if not kept:
            notes.append(f"  dropped (constant across the probe): {', '.join(group)}")
            continue
        items.append(kept)
        if REFUSAL_HELPER in key:
            refusal = kept
        if len(kept) > 1:
            notes.append(f"  collapsed to one item via {key[0]}(): {', '.join(kept)}")
    for row in rows:
        # A group is discharged only when EVERY axis in it passes -- the conservative direction,
        # registered so the round's one remaining obligation cannot be scored as half-met.
        row["items"] = sum(1 for group in items if all(row["axes"].get(a) == "PASS" for a in group))
        row["item_total"] = len(items)
    if not refusal:
        raise SystemExit(f"no surviving item asserts through {REFUSAL_HELPER}() -- the ceiling half of "
                         "the rule has nothing to test, and that is a stale parser, not a result")
    return items, constant, notes, refusal


def read_rule(rows: list, scale: str, total: int, refusal: list) -> None:
    print(f"\n===== the registered rule, on the {scale} scale (out of {total}) =====")
    settings = sorted({(r["setting"], r["allowance"], r["solver"]) for r in rows})
    for setting, allowance, solver in settings:
        note = [r for r in rows if r["setting"] == setting and r["condition"] == NOTE_LABEL]
        floor = [r for r in rows if r["setting"] == setting and r["condition"] == FLOOR_LABEL]
        note_scores = [r[scale] for r in note]
        floor_scores = [r[scale] for r in floor]
        head = f"{setting} allowance={allowance} solver={solver}"
        if not note_scores:
            print(f"\n{head}\n  no-note {floor_scores}  (floor-only setting, no gap to compute)")
            continue
        gap = statistics.fmean(note_scores) - statistics.fmean(floor_scores) if floor_scores else None
        # "both refusal axes pass in the same cell", with the pair read out of the oracle so an
        # edit to the oracle cannot leave this testing an axis that no longer exists.
        ceiling = [r for r in note if all(r["axes"].get(a) == "PASS" for a in refusal)]
        if scale == "six":
            threshold, binding = FLOOR_CEILING_SIX, True
        else:
            threshold, binding = FLOOR_CEILING_ITEMS, total == REGISTERED_ITEM_TOTAL
        floor_ok = bool(floor_scores) and max(floor_scores) <= threshold
        print(f"\n{head}")
        print(f"  note    {note_scores}  mean {statistics.fmean(note_scores):.2f}")
        print(f"  no-note {floor_scores}" + (f"  mean {statistics.fmean(floor_scores):.2f}" if floor_scores else ""))
        if gap is not None:
            print(f"  g = {gap:+.2f}")
        print(f"  [1] ceiling moves (a note cell discharges refusal): "
              f"{'YES -- ' + ', '.join(r['build'] for r in ceiling) if ceiling else 'no'}")
        print(f"  [2] floor holds (max no-note <= {threshold}): {'YES' if floor_ok else 'NO'}"
              + (f" (max no-note {max(floor_scores)})" if floor_scores else " (no floor cells)"))
        if not binding:
            print(f"  the design's item-scale threshold was stated for {REGISTERED_ITEM_TOTAL} items "
                  f"and the probe yielded {total}: the six-axis reading above is the binding one")
        if floor_scores:
            print(f"  ADOPTED: {'yes' if (ceiling and floor_ok) else 'no'}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ids", default="/tmp/probe8_ids.txt")
    parser.add_argument("--out", required=True)
    parser.add_argument("--solvers", default="S0=claude-haiku-4-5,S1=claude-haiku-4-5,"
                                             "S2=claude-haiku-4-5,S3=claude-sonnet-5")
    parser.add_argument("--expect", type=int, default=10,
                        help="how many cells the ledger names. The run fails rather than reads a "
                             "short table, because a probe missing a floor cell still prints a gap.")
    parser.add_argument("--gold-axes", default="",
                        help="build ids of the free oracle-gold anchors, whose surefire is read for "
                             "the constancy check only -- no anchor contributes to any mean")
    args = parser.parse_args()

    solver_of = dict(pair.split("=") for pair in args.solvers.split(","))
    rows, problems = collect(load_ids(args.ids), solver_of)
    gold_axes = []
    for build_id in (b.strip() for b in args.gold_axes.split(",") if b.strip()):
        gold_axes.append(axes_from_surefire(fetch(build_id), build_id))

    if len(rows) != args.expect:
        raise SystemExit(f"the ledger names {args.expect} cells and {len(rows)} reported. A cell that "
                         "never graded is a hole in the probe, not a row to read around: name what "
                         "happened to it and pass --expect explicitly.")
    items, constant, notes, refusal = apply_scale(rows, gold_axes)
    print(f"\n===== the scale rule, applied to these {len(rows)} cells "
          f"+ {len(gold_axes)} gold anchor(s) =====")
    for note in notes:
        print(note)
    print(f"  {len(RETAINED_AXES)} axes -> {len(items)} items: "
          + "; ".join("+".join(g) for g in items))

    print(f"  the ceiling half of the rule tests: {' + '.join(refusal)}")
    read_rule(rows, "six", len(RETAINED_AXES), refusal)
    read_rule(rows, "items", len(items), refusal)

    with open(args.out, "w") as handle:
        writer = csv.DictWriter(handle, fieldnames=[
            "setting", "allowance", "solver", "condition", "triggered", "replicate", "build",
            "compiled",
            "six", "items", "item_total", "used", "denied", "repairRounds", "usd", *RETAINED_AXES])
        writer.writeheader()
        for row in rows:
            writer.writerow({k: v for k, v in row.items() if k != "axes"} | row["axes"])
    print(f"\nwrote {args.out}")

    if problems:
        print(f"\n{len(problems)} cross-check problem(s) -- resolve before reading the table:",
              file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    print("marker and surefire agree on every cell")
    return 0


if __name__ == "__main__":
    sys.exit(main())
