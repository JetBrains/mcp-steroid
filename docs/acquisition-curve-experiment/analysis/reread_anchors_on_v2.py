#!/usr/bin/env python3
"""Re-read the round-5/6 oauth anchors on the WAVE's instrument, for free.

The six axes oracle-v2 retains are byte-identical to the same six in the ten-axis oracle the
anchors were graded on -- shared helper included -- so the anchor build logs already contain the
v2 reading. It was never extracted because the anchors were published as x/10. Nothing here is a
re-run: it is the same surefire XML, scored against the smaller axis set.
"""
import pathlib
import sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from downstream7_collect import surefire_roots, RETAINED_AXES

ANCHORS = [
    ("1044788472", "baseline", 25, 61), ("1044788474", "baseline", 25, 62),
    ("1044803876", "baseline", 25, 63),
    ("1044788466", "gold", 25, 61), ("1044788468", "gold", 25, 62),
    ("1044788470", "gold", 25, 63),
    ("1044601765", "baseline", 30, 51), ("1044601767", "baseline", 30, 52),
]

print(f"{'build':<12}{'cond':<10}{'B':<4}{'rep':<5}{'v2':<7}{'old':<7} failing v2 axes")
for build, cond, budget, rep in ANCHORS:
    text = open(f"/tmp/anchor-logs/{build}.log", errors="replace").read()
    bad = []
    verdicts, total_seen = None, 0
    for root in surefire_roots(text, bad):
        seen = {}
        for case in root.iter("testcase"):
            name = case.get("name", "").split("(")[0]
            failed = any(c.tag in ("failure", "error") for c in case)
            seen[name] = "FAIL" if failed else "PASS"
        if not seen:
            continue
        if verdicts is None or len(seen) > len(verdicts):
            verdicts, total_seen = seen, len(seen)
    if verdicts is None:
        print(f"{build:<12}{cond:<10}{budget:<4}{rep:<5}{'-':<7}{'-':<7} no surefire block (tree never built)")
        continue
    v2 = {a: verdicts.get(a) for a in RETAINED_AXES}
    missing = [a for a, v in v2.items() if v is None]
    passed = sum(1 for v in v2.values() if v == "PASS")
    old_passed = sum(1 for v in verdicts.values() if v == "PASS")
    fails = [a for a, v in v2.items() if v == "FAIL"]
    note = f" MISSING:{missing}" if missing else ""
    print(f"{build:<12}{cond:<10}{budget:<4}{rep:<5}{passed}/6    {old_passed}/{total_seen}   "
          + ", ".join(f.replace('AndHandedToTheShippedRenewalPath','') for f in fails) + note)
