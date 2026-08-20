#!/usr/bin/env python3
"""Per-step UPSTREAM work of one capture, from its own artifacts.

Round 1 could publish a readiness curve but not an honest x-axis: `editFraction` alone flatters whichever
arm takes fewer, larger steps, and the streamed build log cannot supply the fair denominator — over the
round-1 mcp capture its `assistant` events sum to 354 output tokens (642 counting the duplicates the
build log carries) against the 59 164 the terminal `result` event reports. Round 2 therefore records the
CLI's own session transcript, whose per-message `usage` is complete, plus one hook record per tool call.

This script reads a capture's run-directory artifact (the `<runName>.zip` a capture build publishes, or
an already-unpacked copy) and writes one row per source step:

    step, tool, patchChars, files, fileCov, layerCov, layers, dLayerCov,
    cumOutputTokens, cumOutputCharsProxy, cumToolCalls, cumSeconds, editFraction

Anything the artifacts do not contain stays EMPTY. Capture 1 has no hook records and no transcript, so
its `tool` and `cumOutputTokens` columns are empty by construction — that is the measurement gap round 2
exists to close, and it is not filled by guesswork.

Usage:
    extract_capture_trajectory.py --capture 2 --arm mcp2 --build 1037066974 \
        --artifact /tmp/r2/mcp2.zip --out upstream-r2.csv [--append]
"""
import argparse
import csv
import json
import os
import re
import sys
import zipfile
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gold_layers import coverage, milestones, transition_step  # noqa: E402

STEP_PATCH = re.compile(r"^checkpoints/step-(\d+)\.patch$")
STEP_HOOK = re.compile(r"^checkpoints/step-(\d+)\.hook\.json$")
TRANSCRIPT = re.compile(r"^checkpoints/transcript-\d+\.jsonl$")
TRANSCRIPT_PATH_FIELD = re.compile(r'"transcript_path"\s*:\s*"([^"]*)"')
TOOL_NAME_FIELD = re.compile(r'"tool_name"\s*:\s*"([^"]*)"')

FIELDS = [
    "capture", "arm", "build", "step", "tool", "patchChars", "files", "fileCov", "layerCov", "layers",
    "dLayerCov", "cumOutputTokens", "cumOutputCharsProxy", "cumToolCalls", "cumSeconds", "editFraction",
    "isM0", "isT", "isMfull", "isMapi",
]


class Artifact:
    """A capture's run directory, whether it is still a zip or already unpacked."""

    def __init__(self, path):
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
    except Exception:
        m = TOOL_NAME_FIELD.search(record)
        return m.group(1) if m else None


def transcript_steps(text):
    """Ordered tool calls of one session, each with the work spent BY the model up to and including it.

    The hook fires after every tool call and counts them in order, so the k-th tool call of the
    transcript is step k. Usage is attributed per assistant MESSAGE: a message that issues two tool calls
    has its output counted once, at the first of them, because the model produced those tokens once.

    **Deduplication by message id is what makes the denominator exact.** The CLI writes an assistant
    message more than once — 48 lines for 27 messages in the round-2 mcp capture — and each copy repeats
    the same final `usage`. Summed naively they overcount; summed once per id they reproduce the run's
    reported output tokens EXACTLY (45 702 / 45 702 mcp, 41 528 / 41 528 shell), which is the check that
    licenses using this number as upstream work at all.
    """
    out, cum_tokens, cum_chars, start = [], 0, 0, None
    seen, tools_seen = set(), set()
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            entry = json.loads(line)
        except Exception:
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
        # Tool calls are deduplicated by their OWN id, not by the message's. A repeated copy of a
        # message carries the same `usage` — hence the dedup above — but the copies are not identical:
        # the tool_use blocks of the mcp capture appear only on the later copy, so skipping a whole
        # repeated message would have lost 18 of its 26 tool calls and left every state after step 8
        # without an upstream denominator.
        for block in blocks:
            if not isinstance(block, dict) or block.get("type") != "tool_use":
                continue
            if block.get("id") in tools_seen:
                continue
            tools_seen.add(block.get("id"))
            out.append(dict(
                tool=block.get("name"),
                cumOutputTokens=cum_tokens,
                cumOutputCharsProxy=cum_chars,
                seconds=elapsed(start, stamp),
            ))
    return out


def elapsed(start, stamp):
    if not start or not stamp:
        return None
    try:
        fmt = lambda s: datetime.fromisoformat(s.replace("Z", "+00:00"))
        return int((fmt(stamp) - fmt(start)).total_seconds())
    except Exception:
        return None


def rows_for(artifact, capture, arm, build):
    patches = {int(m.group(1)): m.group(0) for m in map(STEP_PATCH.match, artifact.names) if m}
    hooks = {int(m.group(1)): m.group(0) for m in map(STEP_HOOK.match, artifact.names) if m}
    transcripts = [n for n in artifact.names if TRANSCRIPT.match(n)]
    steps = sorted(patches)
    if not steps:
        raise SystemExit(f"{arm}: the artifact holds no step patch at all, so no trajectory can be read")

    decoded = transcript_steps(artifact.read(transcripts[0])) if transcripts else []
    if not decoded:
        print(f"{arm}: no transcript in the artifact — upstream tokens stay empty for this capture",
              file=sys.stderr)

    first_write, previous, rows = None, 0.0, []
    for step in steps:
        cov = coverage(artifact.read(patches[step]))
        chars = len(artifact.read(patches[step]))
        if first_write is None and chars > 0:
            first_write = step
        # Step k is the k-th tool call; the transcript is indexed from 0.
        upstream = decoded[step - 1] if 0 < step <= len(decoded) else {}
        rows.append(dict(
            capture=capture, arm=arm, build=build, step=step,
            tool=hook_tool(artifact.read(hooks[step])) if step in hooks else upstream.get("tool"),
            patchChars=chars,
            files=len(cov["files"]),
            fileCov=round(cov["fileCov"], 4),
            layerCov=round(cov["layerCov"], 4),
            layers="|".join(cov["layers"]),
            dLayerCov=round(cov["layerCov"] - previous, 4),
            cumOutputTokens=upstream.get("cumOutputTokens"),
            cumOutputCharsProxy=upstream.get("cumOutputCharsProxy"),
            cumToolCalls=step,
            cumSeconds=upstream.get("seconds"),
        ))
        previous = cov["layerCov"]

    n = max(steps)
    for row in rows:
        row["editFraction"] = (
            None if first_write is None or n == first_write
            else round((row["step"] - first_write) / (n - first_write), 4)
        )

    t, delta = transition_step([(r["step"], r["layerCov"]) for r in rows])
    marks = milestones([(r["step"], r["layerCov"], r["layers"].split("|")) for r in rows], first_write)
    for row in rows:
        row["isT"] = int(row["step"] == t)
        row["isM0"] = int(row["step"] == first_write)
        row["isMfull"] = int(row["step"] == marks["Mfull"])
        row["isMapi"] = int(row["step"] == marks["Mapi"])
    print(
        f"{arm}: n={n} firstWrite={first_write} T={t} (dLayerCov {delta:+.3f}) milestones={marks} "
        f"hookRecords={len(hooks)}/{n} transcript={'yes' if decoded else 'no'}"
    )
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture", required=True)
    parser.add_argument("--arm", required=True)
    parser.add_argument("--build", required=True)
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--out", default="upstream-r2.csv")
    parser.add_argument("--append", action="store_true")
    args = parser.parse_args()

    rows = rows_for(Artifact(args.artifact), args.capture, args.arm, args.build)
    exists = args.append and os.path.exists(args.out)
    with open(args.out, "a" if args.append else "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS, extrasaction="ignore")
        if not exists:
            writer.writeheader()
        writer.writerows(rows)
    print(f"{len(rows)} steps -> {args.out}")


if __name__ == "__main__":
    main()
