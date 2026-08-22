#!/usr/bin/env python3
"""What the research agents actually WROTE, and what of it survived to the downstream agent.

Read-only. Nothing here queues a build or spends an API token; every number comes from two committed
inputs plus, optionally, a Keycloak clone at the pinned base commit:

  --notes  test-experiments/src/test/resources/understanding-notes/<case>/raw/*.raw.md
           the notes as the agents emitted them, BEFORE the harness applied the character limit
  --runs   docs/understanding-note-experiment/data/downstream-runs.csv
           one row per downstream cell: condition, replicate, solved, failing oracle methods
  --kc     a Keycloak checkout at 60c4d5e9321ff5462a772ceb896f8cb2e639e04b (optional)
           used ONLY to decide whether an identifier a note names really exists in the tree

It answers three questions, in this order:

1. Did the notes fit the limit they were given? (they did not, at 1000 — that is the finding that
   invalidated the first comparison: the harness, not the model, chose what each arm had said)
2. Does the ONE actionable sentence about the second registration point survive the cut, and does
   its survival predict downstream success better than the arm does?
3. Are the two arms' notes factually different — unknown identifiers, hedging, and the specific
   wrong claim that the built-in mapper map is filled by a static initializer?

Usage:
  python3 verify_notes.py --notes <dir> --runs <csv> [--kc <keycloak-checkout>]
"""

import argparse
import collections
import json
import os
import re
import statistics
import subprocess
import sys

# The instruction the downstream agent cannot derive from the task statement: the mapper is offered
# out of the box by a map inside the protocol factory, which is a DIFFERENT mechanism from the
# ServiceLoader file every note mentions. Both spellings occur; a note that has neither leaves the
# weak agent to guess, and the oracle has a test method for exactly that omission.
BUILTIN_ANCHORS = [
    re.compile(r"initBuiltIns", re.I),
    re.compile(r"builtin[s]?\s+mapper", re.I),
    re.compile(r"getBuiltinMappers", re.I),
    re.compile(r"OIDCLoginProtocolFactory", re.I),
]

# The ServiceLoader half of the registration, which is the part both arms always find.
SERVICE_LOADER_ANCHOR = re.compile(r"META-INF/services", re.I)

# "I could not check this" — the honest gap the brief explicitly asks for.
HEDGE = re.compile(
    r"I (?:didn't|did not|couldn't|could not|haven't|have not|am unsure|'m unsure)"
    r"|unverified|not verified|I believe|probably|I'm not sure"
    r"|could not (?:verify|enumerate|resolve)|budget (?:ran out|exhausted)"
    r"|double-check|check (?:the )?(?:exact|actual)",
    re.I,
)

# Factually wrong at the base commit: `builtins` is an instance field filled by initBuiltIns() from
# init(), not a static initializer. A downstream agent following this looks in the wrong place.
WRONG_STATIC = re.compile(r"static[^.\n]{0,40}(builtins|initializer|init block)"
                          r"|`?builtins`? map[^.\n]{0,30}static", re.I)

CAMEL = re.compile(r"\b[A-Za-z][A-Za-z0-9]*[a-z][A-Z][A-Za-z0-9]*\b")
CONST = re.compile(r"\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b")
# Names the agent is INVENTING for the class to be written cannot be checked against the tree.
INVENTED = re.compile(r"EmailDomain|YourMapper|YourClass|SomeMapper|Xxx")

NOTE_ID = re.compile(r"^(mcp|none)-b(\d+)-l(\d+)-r(\d+)$")


def read_notes(directory):
    """Every raw note in the directory, keyed by note id, with its coordinates parsed back."""
    notes = {}
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".raw.md"):
            continue
        note_id = name[: -len(".raw.md")]
        match = NOTE_ID.match(note_id)
        if not match:
            print(f"skipping {name}: not a note id", file=sys.stderr)
            continue
        arm, budget, limit, replicate = match.groups()
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            text = handle.read().strip()
        notes[note_id] = dict(arm=arm, budget=int(budget), limit=int(limit),
                              replicate=int(replicate), text=text)
    return notes


def read_runs(path):
    """Downstream outcomes grouped by condition; conditions that are not note ids are kept apart."""
    import csv

    by_condition = collections.defaultdict(list)
    with open(path, encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            by_condition[row["condition"]].append(row)
    return by_condition


def keycloak_index(checkout):
    """Identifiers and file base names that really exist in the pinned tree."""
    if not checkout:
        return None
    files = subprocess.run(["git", "-C", checkout, "ls-files"],
                           capture_output=True, text=True, check=True).stdout.split()
    basenames = {os.path.basename(f).rsplit(".", 1)[0] for f in files}
    tokens = set()
    for path in files:
        if not path.endswith((".java", ".xml", ".properties")):
            continue
        try:
            with open(os.path.join(checkout, path), encoding="utf-8", errors="replace") as handle:
                body = handle.read()
        except OSError:
            continue
        tokens.update(CAMEL.findall(body))
        tokens.update(CONST.findall(body))
    return dict(tokens=tokens, basenames=basenames)


SET_CLAIM = re.compile(r"protected void setClaim\(IDToken.*?\n    \}", re.S)


def classify_exemplars(checkout):
    """Split the OIDC mappers into those whose setClaim reads the user session and those that do not.

    Computed from the tree rather than listed by hand, because the whole claim being tested is that
    the note's choice of exemplar decides the downstream outcome — a hand-written list would let the
    conclusion be smuggled into its own evidence.
    """
    directory = os.path.join(checkout, "services", "src", "main", "java", "org", "keycloak",
                             "protocol", "oidc", "mappers")
    session_based, token_based = set(), set()
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".java"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8", errors="replace") as handle:
            body = handle.read()
        match = SET_CLAIM.search(body)
        if not match:
            continue
        simple = name[: -len(".java")]
        (session_based if "userSession." in match.group(0) else token_based).add(simple)
    return session_based, token_based


def anchor_position(text):
    """Where the built-in-registration instruction starts, or None when the note never gives it."""
    positions = [m.start() for pattern in BUILTIN_ANCHORS for m in pattern.finditer(text)]
    return min(positions) if positions else None


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--notes", required=True)
    parser.add_argument("--runs", required=True)
    parser.add_argument("--kc", default=None)
    args = parser.parse_args()

    notes = read_notes(args.notes)
    runs = read_runs(args.runs)
    index = keycloak_index(args.kc)

    print("== 1. did the note fit the limit it was told? ==")
    print(f"{'note':22s} {'limit':>6s} {'raw':>6s} {'over':>6s}  anchor@   kept  solved")
    kept_tally = collections.Counter()
    for note_id, note in sorted(notes.items(), key=lambda kv: (kv[1]["limit"], kv[0])):
        raw = len(note["text"])
        over = raw - note["limit"]
        position = anchor_position(note["text"])
        kept = position is not None and position < note["limit"]
        outcomes = runs.get(note_id, [])
        solved = sum(int(r["solved"]) for r in outcomes)
        if outcomes:
            kept_tally[("kept" if kept else "cut", "solved")] += solved
            kept_tally[("kept" if kept else "cut", "runs")] += len(outcomes)
        print(f"{note_id:22s} {note['limit']:6d} {raw:6d} {over:+6d}  "
              f"{'-' if position is None else position:>7} {str(kept):>6s}  "
              f"{solved}/{len(outcomes) if outcomes else 0}")

    print("\n== 2. does the surviving instruction predict success better than the arm? ==")
    for state in ("kept", "cut"):
        runs_n = kept_tally[(state, "runs")]
        if runs_n:
            print(f"  instruction {state:4s}: {kept_tally[(state, 'solved')]}/{runs_n} solved")
    by_arm = collections.Counter()
    for note_id, note in notes.items():
        for row in runs.get(note_id, []):
            by_arm[(note["arm"], "runs")] += 1
            by_arm[(note["arm"], "solved")] += int(row["solved"])
    for arm in ("mcp", "none"):
        if by_arm[(arm, "runs")]:
            print(f"  arm {arm:4s}         : {by_arm[(arm, 'solved')]}/{by_arm[(arm, 'runs')]} solved")

    print("\n== 3. are the arms factually different? ==")
    print(f"{'note':22s} {'sym':>4s} {'unknown':>7s} {'hedges':>6s} {'names initBuiltIns':>18s} "
          f"{'static claim':>12s}  unknown identifiers")
    per_arm = collections.defaultdict(collections.Counter)
    for note_id, note in sorted(notes.items(), key=lambda kv: (kv[1]["limit"], kv[0])):
        text = note["text"]
        symbols = {s for s in set(CAMEL.findall(text)) | set(CONST.findall(text))
                   if not INVENTED.search(s)}
        unknown = sorted(s for s in symbols
                         if index and s not in index["tokens"] and s not in index["basenames"])
        hedges = len(HEDGE.findall(text))
        names_builtins = "initBuiltIns" in text
        wrong = bool(WRONG_STATIC.search(text))
        counter = per_arm[note["arm"]]
        counter["notes"] += 1
        counter["symbols"] += len(symbols)
        counter["unknown"] += len(unknown)
        counter["hedges"] += hedges
        counter["names"] += names_builtins
        counter["wrong"] += wrong
        counter["chars"] += len(text)
        print(f"{note_id:22s} {len(symbols):4d} {len(unknown):7d} {hedges:6d} "
              f"{str(names_builtins):>18s} {str(wrong):>12s}  {','.join(unknown)[:52]}")
    for arm, counter in per_arm.items():
        share = 100 * counter["unknown"] / counter["symbols"] if counter["symbols"] else 0
        print(f"  {arm:5s} n={counter['notes']} chars={counter['chars']} "
              f"identifiers={counter['symbols']} unknown={counter['unknown']} ({share:.1f}%) "
              f"hedges={counter['hedges']} namesInitBuiltIns={counter['names']}/{counter['notes']} "
              f"wrongStaticClaim={counter['wrong']}/{counter['notes']}")
        if not index:
            print("  (no --kc given: the unknown-identifier column is not measured)")

    print("\n== 3b. which exemplar the note told the weak agent to copy ==")
    # The 500-character round's whole outcome. Every mapper in the package overrides the same
    # setClaim(IDToken, ProtocolMapperModel, UserSessionModel); some read the user out of that
    # session and some do not. The oracle passes a null session — the statement says the domain
    # comes from the token being issued — so an agent that copies a session-reading exemplar
    # throws NullPointerException no matter how correct the prose around the name was.
    if index is None:
        print("  (no --kc given: exemplars are not classified)")
    else:
        session_based, token_based = classify_exemplars(args.kc)
        print(f"  session-reading exemplars in the tree: {len(session_based)}, "
              f"token-only: {len(token_based)}")
        for note_id, note in sorted(notes.items(), key=lambda kv: (kv[1]["limit"], kv[0])):
            named = sorted({name for name in session_based | token_based if name in note["text"]})
            risky = [n for n in named if n in session_based]
            outcomes = runs.get(note_id, [])
            solved = sum(int(r["solved"]) for r in outcomes)
            print(f"  {note_id:22s} solved={solved}/{len(outcomes)}  "
                  f"named={','.join(named) if named else '(none)'}  "
                  f"sessionReading={','.join(risky) if risky else '-'}")

    print("\n== 4. which oracle method fails when a cell fails ==")
    failing = collections.Counter()
    for condition, rows in runs.items():
        for row in rows:
            if int(row["solved"]):
                continue
            for method in filter(None, row["failingOracleMethods"].split(";")):
                failing[method] += 1
    for method, count in failing.most_common():
        print(f"  {count:3d}  {method}")

    lengths = [len(n["text"]) for n in notes.values()]
    if lengths:
        print(f"\nnotes={len(notes)} median raw length={int(statistics.median(lengths))}")


if __name__ == "__main__":
    main()
