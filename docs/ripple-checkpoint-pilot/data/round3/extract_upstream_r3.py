#!/usr/bin/env python3
"""Per-step UPSTREAM work of ONE capture arm of ONE round-3 case, from that build's own artifacts.

This is round 2's `data/capture2/extract_capture_trajectory.py` generalized from one case to many. The
artifact layout, the log formats and the arithmetic are UNCHANGED — the round-3 recorder is the same
`RippleCheckpointRecorder` — so everything that still applies is reused verbatim and only the things
that were hardcoded to `dpaia__feature__service-125` are lifted into parameters:

  * the gold patch now comes from the dpaia dataset JSON whose PATH IS AN ARGUMENT. Round 2 vendored the
    13 gold file names into `gold_layers.py`; six more hand-copied lists would be six more places for a
    silent drift against the dataset, and a hardcoded download inside an analysis script would make the
    numbers depend on what the network served that afternoon. The operator fetches the dataset once (the
    URL is in `rcw_layers.DATASET_URL`) and passes the file.
  * the layer taxonomy is `rcw_layers`, frozen before any round-3 build was queued. This script never
    reimplements `coverage()`, `milestones()` or `select_checkpoints()`; it only feeds them.
  * one CSV holds every case and arm, so the twelve round-3 captures accumulate into one dataset instead
    of twelve files an aggregator would have to be taught to find.

Why the columns are what they are, where round 2 made a decision worth preserving:

  * `cumulativeOutputTokens` is the model's OWN output, deduplicated by assistant-message id. The CLI
    writes a message more than once (48 lines for 27 messages in the round-2 mcp capture) and every copy
    repeats the same final `usage`; summed naively they overcount, summed once per id they reproduce the
    run's reported total EXACTLY (45 702 / 45 702 mcp, 41 528 / 41 528 shell). That exactness is the only
    reason this number may be used as an upstream denominator at all. It is deliberately NOT the
    end-of-run context size, which is dominated by the cached prompt prefix.
  * `wallClockSec` and `timestampMs` come from the transcript, not from the build log: the build log's
    own timestamps include container start-up, Maven import and the verifier.
  * `cumulativeToolCalls` IS the step number. The hook fires once per tool call and counts them in order,
    so step k is the k-th tool call; the column exists so a reader never has to know that.
  * `stateId` is the SHA-1 of the state's whole-tree patch, and `sameStateAsPrev` is derived from it.
    Two steps hold the same tree exactly when their diffs against the pristine tree are byte-identical,
    so this is an exact stand-in for the shadow repository's tree ids and it is a digest of measured
    bytes rather than an empty column standing in for one. `select_checkpoints_r3.py` needs it to find
    `last distinct`, which is `C5`.
  * anything the artifacts do not contain stays EMPTY, never zero. A capture whose transcript is missing
    has no upstream token denominator, and that is a measurement gap to report, not to impute.

Usage:
    extract_upstream_r3.py --case petclinic-36 --arm pc36-mcp --build 1038000001 \
        --artifact /tmp/r3/pc36-mcp.zip --dataset /tmp/java-spring-ee-dataset.json \
        --out upstream-r3.csv

Re-running for the same (case, arm) REPLACES that pair's rows instead of duplicating them, so a capture
that has to be re-fetched or re-extracted cannot silently double-count itself. The file is rewritten
sorted by (case, arm, step) so the result does not depend on the order the twelve captures were
processed in.
"""
import argparse
import csv
import hashlib
import json
import os
import re
import sys
import zipfile
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcw_layers import (  # noqa: E402
    PILOT_CASE,
    ROUND3_CASES,
    coverage,
    gold_layers,
    milestones,
    transition_step,
)

# The recorder publishes these under `checkpoints/` inside the run directory. The optional prefix lets
# the operator point `--artifact` either at the run directory (or its zip) or straight at the unpacked
# `checkpoints/` folder — both happen while fetching twelve builds by hand, and neither should need a
# different command.
STEP_PATCH = re.compile(r"^(?:.*/)?step-(\d+)\.patch$")
STEP_HOOK = re.compile(r"^(?:.*/)?step-(\d+)\.hook\.json$")
TRANSCRIPT = re.compile(r"^(?:.*/)?transcript-\d+\.jsonl$")
# Hook records are truncated at 20 000 bytes by `snapshot.sh`, so a regex has to back the JSON parse up.
TOOL_NAME_FIELD = re.compile(r'"tool_name"\s*:\s*"([^"]*)"')

FIELDS = [
    "case", "arm", "buildId", "step", "tool", "timestampMs", "wallClockSec",
    "cumulativeOutputTokens", "cumulativeOutputCharsProxy", "cumulativeToolCalls",
    "patchChars", "changedFiles", "layerCov", "fileCov", "layers", "dLayerCov",
    "editFraction", "stateId", "sameStateAsPrev", "isM0", "isMmid", "isMlast", "isT",
]


class Artifact:
    """A capture's run directory, whether it is still a zip or already unpacked.

    Taken from round 2 unchanged. A capture artifact is 20 MB of IDE logs around 25 patches, and
    unpacking twelve of them to read one file each is the kind of manual step that gets skipped.
    """

    def __init__(self, path):
        if not os.path.exists(path):
            raise SystemExit(f"--artifact {path} does not exist")
        self.zip = zipfile.ZipFile(path) if zipfile.is_zipfile(path) else None
        self.root = None if self.zip else path
        self.names = (
            self.zip.namelist() if self.zip
            else [
                os.path.relpath(os.path.join(d, f), path).replace(os.sep, "/")
                for d, _, files in os.walk(path) for f in files
            ]
        )

    def read(self, name):
        if self.zip:
            return self.zip.read(name).decode("utf-8", "replace")
        with open(os.path.join(self.root, name), errors="replace") as fh:
            return fh.read()


def hook_tool(record):
    """The tool a hook record names. Records are truncated, so a regex backs the JSON parse up."""
    try:
        return json.loads(record).get("tool_name")
    except ValueError as e:
        # Expected for every record over 20 000 bytes; announced once per step rather than swallowed,
        # because a record that fails to parse for any OTHER reason must not look like the normal case.
        print(f"  hook record truncated ({e}); tool name recovered by regex", file=sys.stderr)
        m = TOOL_NAME_FIELD.search(record)
        return m.group(1) if m else None


def transcript_steps(text):
    """Ordered tool calls of one session, each with the work spent BY the model up to and including it.

    Round 2's function, unchanged, because the transcript format is unchanged. Usage is attributed per
    assistant MESSAGE: a message that issues two tool calls has its output counted once, at the first of
    them, because the model produced those tokens once.

    **Deduplication by message id is what makes the denominator exact** — see the module docstring. Tool
    calls are deduplicated by their OWN id and not by the message's: a repeated copy of a message carries
    the same `usage`, but the copies are not identical, and the mcp capture's `tool_use` blocks appear
    only on the later copy. Skipping a whole repeated message would have lost 18 of its 26 tool calls.
    """
    out, cum_tokens, cum_chars, start = [], 0, 0, None
    seen, tools_seen = set(), set()
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            entry = json.loads(line)
        except ValueError as e:
            print(f"  transcript line skipped, not JSON: {e}", file=sys.stderr)
            continue
        stamp = entry.get("timestamp")
        if stamp and start is None:
            start = stamp
        message = entry.get("message") or {}
        if entry.get("type") != "assistant" or not isinstance(message, dict):
            continue
        identifier = message.get("id")
        blocks = message.get("content") or []
        if not isinstance(blocks, list):
            continue
        if identifier not in seen:
            seen.add(identifier)
            cum_tokens += (message.get("usage") or {}).get("output_tokens", 0)
            for block in blocks:
                if isinstance(block, dict) and block.get("type") in ("text", "thinking"):
                    cum_chars += len(block.get("text") or block.get("thinking") or "")
        for block in blocks:
            if not isinstance(block, dict) or block.get("type") != "tool_use":
                continue
            if block.get("id") in tools_seen:
                continue
            tools_seen.add(block.get("id"))
            out.append(dict(
                tool=block.get("name"),
                cumulativeOutputTokens=cum_tokens,
                cumulativeOutputCharsProxy=cum_chars,
                timestampMs=epoch_ms(stamp),
                wallClockSec=elapsed(start, stamp),
            ))
    return out


def _parse_stamp(stamp):
    """One ISO-8601 transcript timestamp, or None. Never raises — a clock field is not a measurement."""
    if not stamp:
        return None
    try:
        return datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError as e:
        print(f"  unparseable transcript timestamp {stamp!r}: {e}", file=sys.stderr)
        return None


def epoch_ms(stamp):
    parsed = _parse_stamp(stamp)
    return None if parsed is None else int(parsed.timestamp() * 1000)


def elapsed(start, stamp):
    a, b = _parse_stamp(start), _parse_stamp(stamp)
    return None if a is None or b is None else int((b - a).total_seconds())


def instance_id_of(case):
    """The dataset id of a `resourceDir`, from the frozen round-3 case table.

    An unknown directory is an error listing the known ones, never a guess: computing coverage against
    the wrong case's gold patch would publish a full curve that measures nothing.
    """
    known = dict(PILOT_CASE, **ROUND3_CASES)
    if case not in known:
        raise SystemExit(
            f"--case {case} is not a round-3 case; rcw_layers knows {sorted(known)}"
        )
    return known[case]


def rows_for(artifact, case, arm, build, dataset_path):
    """One row per recorded step of this capture arm."""
    if not os.path.exists(dataset_path):
        raise SystemExit(
            f"--dataset {dataset_path} does not exist. Fetch it once from rcw_layers.DATASET_URL; "
            f"this script never downloads, so a published number cannot depend on today's network."
        )
    with open(dataset_path) as fh:
        by_id = {c["instance_id"]: c for c in json.load(fh)}
    instance_id = instance_id_of(case)
    if instance_id not in by_id:
        raise SystemExit(f"{instance_id} is not in {dataset_path}")
    case_layers, gold_files = gold_layers(by_id[instance_id])

    patches = {int(m.group(1)): m.group(0) for m in map(STEP_PATCH.match, artifact.names) if m}
    hooks = {int(m.group(1)): m.group(0) for m in map(STEP_HOOK.match, artifact.names) if m}
    transcripts = sorted(n for n in artifact.names if TRANSCRIPT.match(n))
    steps = sorted(patches)
    if not steps:
        raise SystemExit(
            f"{arm}: the artifact holds no step-<n>.patch at all, so no trajectory can be read from it"
        )

    decoded = transcript_steps(artifact.read(transcripts[0])) if transcripts else []
    if not decoded:
        print(f"{arm}: no transcript in the artifact — upstream tokens stay EMPTY for this capture",
              file=sys.stderr)

    text = {step: artifact.read(patches[step]) for step in steps}
    first_write, previous, rows = None, 0.0, []
    for index, step in enumerate(steps):
        cov = coverage(text[step], case_layers, gold_files)
        chars = len(text[step])
        if first_write is None and chars > 0:
            first_write = step
        upstream = decoded[step - 1] if 0 < step <= len(decoded) else {}
        rows.append(dict(
            case=case, arm=arm, buildId=build, step=step,
            tool=hook_tool(artifact.read(hooks[step])) if step in hooks else upstream.get("tool"),
            timestampMs=upstream.get("timestampMs"),
            wallClockSec=upstream.get("wallClockSec"),
            cumulativeOutputTokens=upstream.get("cumulativeOutputTokens"),
            cumulativeOutputCharsProxy=upstream.get("cumulativeOutputCharsProxy"),
            cumulativeToolCalls=step,
            patchChars=chars,
            changedFiles=len(cov["files"]),
            layerCov=round(cov["layerCov"], 4),
            fileCov=round(cov["fileCov"], 4),
            layers="|".join(cov["layers"]),
            dLayerCov=round(cov["layerCov"] - previous, 4),
            stateId="sha1:" + hashlib.sha1(text[step].encode()).hexdigest(),
            sameStateAsPrev=int(index > 0 and text[step] == text[steps[index - 1]]),
        ))
        previous = cov["layerCov"]

    if first_write is None:
        raise SystemExit(
            f"{arm}: no step of this capture wrote anything, so it has no edit phase and fails Gate 1"
        )

    n = max(steps)
    for row in rows:
        row["editFraction"] = (
            None if n == first_write
            else round((row["step"] - first_write) / (n - first_write), 4)
        )

    pairs = [(r["step"], r["layerCov"]) for r in rows]
    t, delta = transition_step(pairs)
    marks = milestones(pairs, first_write)
    for row in rows:
        row["isM0"] = int(row["step"] == marks["M0"])
        row["isMmid"] = int(row["step"] == marks["Mmid"])
        row["isMlast"] = int(row["step"] == marks["Mlast"])
        row["isT"] = int(row["step"] == t)

    distinct = len({r["stateId"] for r in rows})
    print(
        f"{case}/{arm}: n={n} firstWrite={first_write} T={t} (dLayerCov {delta:+.3f}) "
        f"milestones={marks} L_case={case_layers} distinctStates={distinct} "
        f"hookRecords={len(hooks)}/{len(steps)} transcript={'yes' if decoded else 'no'}"
    )
    return rows


def merge(out_path, case, arm, rows):
    """Write `rows` into `out_path`, replacing whatever (case, arm) held before.

    Idempotence is not a convenience here. Twelve captures land over several days, artifacts are
    re-downloaded when a fetch is interrupted, and an appended duplicate of one arm would silently
    double the weight of that trajectory in every cross-case statistic.
    """
    kept, before = [], 0
    if os.path.exists(out_path):
        with open(out_path, newline="") as fh:
            reader = csv.DictReader(fh)
            if reader.fieldnames != FIELDS:
                raise SystemExit(
                    f"{out_path} has columns {reader.fieldnames}, which are not this script's "
                    f"{FIELDS}. Refusing to mix two schemas in one dataset."
                )
            for row in reader:
                before += 1
                if (row["case"], row["arm"]) != (case, arm):
                    kept.append(row)
        if len(kept) != before:
            print(f"replacing {before - len(kept)} existing rows of {case}/{arm} in {out_path}",
                  file=sys.stderr)
    merged = kept + [{k: ("" if v is None else v) for k, v in r.items()} for r in rows]
    merged.sort(key=lambda r: (r["case"], r["arm"], int(r["step"])))
    with open(out_path, "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(merged)
    return len(merged)


def main():
    parser = argparse.ArgumentParser(
        description="Per-step upstream work of one round-3 capture arm, appended to upstream-r3.csv.",
        epilog="The dataset JSON is fetched once by the operator from rcw_layers.DATASET_URL.",
    )
    parser.add_argument("--case", required=True, help="resourceDir of the case, e.g. petclinic-36")
    parser.add_argument("--arm", required=True, help="arm token, e.g. pc36-mcp")
    parser.add_argument("--build", required=True, help="TeamCity build id of the capture")
    parser.add_argument("--artifact", required=True, help="the run directory, its zip, or checkpoints/")
    parser.add_argument("--dataset", required=True, help="path to java-spring-ee-dataset.json")
    parser.add_argument("--out", default="upstream-r3.csv")
    args = parser.parse_args()

    rows = rows_for(Artifact(args.artifact), args.case, args.arm, args.build, args.dataset)
    total = merge(args.out, args.case, args.arm, rows)
    print(f"{len(rows)} steps of {args.case}/{args.arm} -> {args.out} ({total} rows total)")


if __name__ == "__main__":
    main()
