#!/usr/bin/env python3
"""Build the round-7 wave table from the cells' own build logs.

The harness prints one structured [ACQUISITION-DOWN] line per cell carrying every number the table
needs, and that line reproduced all nine published anchor readings exactly -- so it is the source,
and the surefire XML is parsed only as an independent cross-check. A cell whose two sources disagree
is a defect in this script or in the log, never something to average.
"""

import argparse
import csv
import os
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

# The oracle-v2 axis set, fixed in DESIGN-DOWNSTREAM-7.md before the wave was queued.
RETAINED_AXES = [
    "theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt",
    "theGrantAppearsInThePublishedGrantTypesSupported",
    "anOrdinaryInteractiveCredentialIsRefusedBeforeAnyTokenIsMinted",
    "anUnreadableCredentialIsAProtocolErrorNotAServerError",
    "theTokenContextShortCodeIsGloballyUnique",
    "theShippedGrantsAreUnchanged",
]
CASE = "acquisition__keycloak__oauth-grant-type"
MARKER = re.compile(r"\[ACQUISITION-DOWN\][^\n]*")
# The transport failure that kills a cell AFTER the agent has spent its budget: the repair prompt
# rode on the docker exec command line, which Linux caps at 128 KiB per single argv element.
E2BIG = "Argument list too long"
# TeamCity renders the same surefire XML TWICE in one log -- once as timestamp-prefixed build-log
# lines, once raw -- and the two renderings interleave line by line. Reading the whole log as a
# single document made `(.*?)</testcase>` stop at the other rendering's closing tag, which attributed
# one axis's <failure> to a different axis's name. The prefix is what separates them.
TS_LINE = re.compile(r"^\[\d\d:\d\d:\d\d\]")
TS_PREFIX = re.compile(r"^\[\d\d:\d\d:\d\d\].*?\[IDE OUT\] ?")
IDE_ONLY = re.compile(r"^\[IDE OUT\] ?")
SUITE_OPEN = "<testsuite "
SUITE_CLOSE = "</testsuite>"


def fetch(build_id: str) -> str:
    log = pathlib.Path(f"/tmp/cell{build_id}.log")
    if not log.exists():
        subprocess.run(
            ["jb", "tc", "builds", "log", build_id, "-o", str(log)],
            check=True, env={**os.environ, "DO_NOT_TRACK": "1"},
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
    return log.read_text(errors="replace")


def marker_fields(text: str, build_id: str) -> dict:
    lines = {line.strip() for line in MARKER.findall(text)}
    if not lines:
        raise SystemExit(f"{build_id}: no [ACQUISITION-DOWN] line -- the cell did not reach grading")
    if len(lines) > 1:
        raise SystemExit(f"{build_id}: {len(lines)} DIFFERENT marker lines, refusing to guess:\n" +
                         "\n".join(sorted(lines)))
    fields = {}
    for token in lines.pop().split():
        if "=" in token:
            key, value = token.split("=", 1)
            fields[key] = value
    return fields


def surefire_roots(text: str, unparseable: list):
    """Every <testsuite> the log carries, each rendering reconstructed and parsed on its own.

    A build-log line belongs to the prefixed rendering only if it is BOTH timestamped and tagged
    [IDE OUT]; a timestamped line without that tag is TeamCity's own chrome (container events, step
    banners) and belongs to neither rendering -- left in, it splices non-XML into the middle of one.
    """
    for keep_prefixed in (True, False):
        lines = []
        for line in text.splitlines():
            if TS_LINE.match(line):
                if not keep_prefixed or not TS_PREFIX.match(line):
                    continue
                lines.append(TS_PREFIX.sub("", line))
            elif not keep_prefixed:
                lines.append(IDE_ONLY.sub("", line))
        blob = "\n".join(lines)
        at = blob.find(SUITE_OPEN)
        while at >= 0:
            end = blob.find(SUITE_CLOSE, at)
            if end < 0:
                break
            try:
                yield ET.fromstring(blob[at:end + len(SUITE_CLOSE)])
            except ET.ParseError as error:
                # Counted and reported, never swallowed: the two renderings interleave, so one can
                # arrive spliced. That is a fact about the log, not licence to skip the cross-check --
                # a cell with no rendering left standing is raised as a problem below.
                unparseable.append(f"{keep_prefixed and 'prefixed' or 'raw'} rendering: {error}")
            at = blob.find(SUITE_OPEN, end)


def axes_from_surefire(text: str, build_id: str) -> dict:
    """PASS/FAIL per retained axis, agreed across every rendering the log carries.

    Each rendering is checked against its own <testsuite> header counts as well, so a rendering that
    lost testcases to truncation cannot quietly agree by having fewer of them.
    """
    verdicts = None
    unparseable = []
    for root in surefire_roots(text, unparseable):
        seen = {tc.get("name"): ("FAIL" if tc.find("failure") is not None or tc.find("error") is not None
                                 else "PASS")
                for tc in root.findall("testcase") if tc.get("name") in RETAINED_AXES}
        if not seen:
            continue
        counts = {key: int(root.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")}
        if counts["tests"] == len(seen):
            header_passed = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
            if header_passed != sum(v == "PASS" for v in seen.values()):
                raise SystemExit(f"{build_id}: testsuite header implies {header_passed} passed, its own "
                                 f"testcases say {sum(v == 'PASS' for v in seen.values())}")
        if verdicts is not None and verdicts != seen:
            raise SystemExit(f"{build_id}: the log's surefire renderings disagree:\n"
                             f"  {sorted(verdicts.items())}\n  {sorted(seen.items())}")
        verdicts = seen
    if verdicts is None and unparseable:
        raise SystemExit(f"{build_id}: no readable surefire rendering -- "
                         + "; ".join(unparseable))
    return verdicts or {}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ids", default="/tmp/wave_ids.txt")
    parser.add_argument("--judged", default="/tmp/acq-judge7/out/actionable-curve.csv")
    parser.add_argument("--out", required=True)
    parser.add_argument("--axes-out", default=None, help="optional per-axis table")
    parser.add_argument(
        "--crashed", default="",
        help="comma-separated build ids whose harness died in transport before the cell could report. "
             "Such a cell contributes NO row: its outcome was never measured, and scoring it 0 would "
             "invent a worst-case reading for exactly the cells whose repair prompts grew longest. "
             "Each id must prove itself -- E2BIG in the log and no marker line -- so an inconvenient "
             "cell cannot be dropped by asserting it crashed.")
    args = parser.parse_args()

    u_note = {}
    with open(args.judged) as handle:
        for row in csv.DictReader(handle):
            if row.get("case") == CASE:
                u_note[(row["trajectory_id"], int(row["checkpoint"]))] = float(row["u_actionable"])
    REFUSED = {("none-b40-l2000-r1", 5), ("none-b40-l2000-r2", 5), ("none-b40-l2000-r2", 10)}
    if len(u_note) != 18 - len(REFUSED):
        raise SystemExit(f"expected {18 - len(REFUSED)} judged {CASE} notes, found {len(u_note)}")
    if u_note.keys() & REFUSED:
        raise SystemExit(f"the judge was expected to refuse {sorted(u_note.keys() & REFUSED)} but graded them")

    crashed = {token.strip() for token in args.crashed.split(",") if token.strip()}
    crashed_seen = set()
    cells, axis_rows, problems = [], [], []
    for line in open(args.ids):
        cell, rep, build_id = line.split()
        trajectory, checkpoint = cell.split("@")
        checkpoint = int(checkpoint)
        replicate = int(rep.lstrip("r"))
        text = fetch(build_id)
        if build_id in crashed:
            if MARKER.search(text):
                raise SystemExit(f"{build_id}: listed as crashed, but the log HAS a marker line: it reported")
            if E2BIG not in text:
                raise SystemExit(f"{build_id}: listed as crashed, but the log carries no {E2BIG!r}. "
                                 "A cell is only droppable with the transport failure in evidence.")
            crashed_seen.add(build_id)
            print(f"  {cell:<28} r{replicate} {build_id}  CRASHED IN TRANSPORT -- no row", flush=True)
            continue
        fields = marker_fields(text, build_id)

        # The mapping from build to note is the one thing that would silently corrupt every number,
        # so it is checked against the harness's own echo rather than trusted from the ledger.
        expected = {"case": CASE, "trajectory": trajectory,
                    "checkpoint": str(checkpoint), "replicate": str(replicate)}
        for key, want in expected.items():
            got = fields.get(key)
            if got != want:
                raise SystemExit(f"{build_id}: log says {key}={got!r}, ledger says {want!r}")

        raw_passed, raw_total = fields["oraclePassed"].split("/")
        total = int(raw_total)
        compiled = int(fields["compiled"])
        if total != len(RETAINED_AXES):
            raise SystemExit(f"{build_id}: graded {total} axes, oracle-v2 has {len(RETAINED_AXES)}")
        # A tree that does not build never reaches the oracle, and the harness says so rather than
        # printing a 0 it did not measure. That is a real outcome (nothing discharged), not a hole --
        # but only when the harness also says the build failed.
        if raw_passed == "unmeasured":
            if compiled:
                raise SystemExit(f"{build_id}: says compiled=1 yet the oracle was never measured")
            passed = None
        else:
            passed = int(raw_passed)

        axes = axes_from_surefire(text, build_id)
        if axes:
            if len(axes) != len(RETAINED_AXES):
                problems.append(f"{build_id}: surefire carries {len(axes)}/{len(RETAINED_AXES)} axes")
            elif passed is not None and sum(v == "PASS" for v in axes.values()) != passed:
                problems.append(f"{build_id}: marker says {passed}, surefire says "
                                f"{sum(v == 'PASS' for v in axes.values())}")
            axis_rows.append({"build": build_id, "trajectory": trajectory, "checkpoint": checkpoint,
                              "replicate": replicate, **axes})
        elif compiled:
            problems.append(f"{build_id}: compiled but no surefire block found")

        # Round 6's convention, kept so the two rounds' tables mean the same thing: a tree that did
        # not build scores 0 obligations and leaves `passed` empty rather than claiming a graded 0.
        cells.append({
            "run": build_id,
            "trajectory": trajectory,
            "arm": trajectory.split("-")[0],
            "checkpoint": checkpoint,
            "replicate": replicate,
            # Blank, never 0: this is the one place a refused note could silently become the
            # worst note in the ranking.
            "u_note": u_note.get((trajectory, checkpoint), ""),
            "u_obs": "",
            "passed": passed if compiled else "",
            "total": total,
            "endpoint": passed if compiled else 0,
            "compiled": compiled,
            "repairRounds": fields.get("repairRounds", ""),
            "agentTestsDiscarded": fields.get("agentTestsDiscarded", ""),
            "agentNonSourceFiles": fields.get("agentNonSourceFiles", ""),
            "calls": fields.get("toolCalls", ""),
            "denied": fields.get("denied", ""),
            "tokens": fields.get("outputTokens", ""),
            "secs": fields.get("agentSeconds", ""),
            "usd": fields.get("usd", ""),
        })
        print(f"  {cell:<28} r{replicate} {build_id}  "
              f"{'compiled' if compiled else 'NO BUILD'}  {passed}/{total}", flush=True)

    if crashed - crashed_seen:
        raise SystemExit(f"--crashed names {sorted(crashed - crashed_seen)}, which is not in the ledger")
    if len(cells) + len(crashed) != 36:
        raise SystemExit(f"expected 36 cells, collected {len(cells)} plus {len(crashed)} crashed")
    if crashed:
        print(f"\n{len(crashed)} cell(s) lost in transport, outcome never measured: {sorted(crashed)}")

    header = list(cells[0])
    with open(args.out, "w") as handle:
        writer = csv.DictWriter(handle, fieldnames=header)
        writer.writeheader()
        writer.writerows(cells)
    print(f"\nwrote {args.out}")

    if args.axes_out and axis_rows:
        with open(args.axes_out, "w") as handle:
            writer = csv.DictWriter(
                handle, fieldnames=["build", "trajectory", "checkpoint", "replicate"] + RETAINED_AXES)
            writer.writeheader()
            writer.writerows(axis_rows)
        print(f"wrote {args.axes_out}")

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
