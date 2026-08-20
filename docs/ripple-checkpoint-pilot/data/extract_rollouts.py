#!/usr/bin/env python3
"""Recover a per-rollout dataset from the raw TeamCity logs of the ripple checkpoint pilot.

Usage:
    for id in $(cat ids.txt); do jb tc builds log "$id" -o "$LOG_DIR/$id.log"; done
    RIPPLE_LOG_DIR=$LOG_DIR RIPPLE_OUT=rollouts-raw.json python3 extract_rollouts.py

The build ids are in RUN-IDS.md; `jb tc builds list --branch worktree-semantic-ripple-pilot -n 200`
lists them all. Output feeds analyze_residual_difficulty.py.
"""
import json
import os
import re
import sys

LOGS = os.environ.get("RIPPLE_LOG_DIR", "/tmp/rd/logs")
STRIP = re.compile(r"^.*?\[:test-experiments:test\]\s?")

# Two grid generations print the same line: the first one had no `editFraction` and no cost fields.
VERDICT = re.compile(
    r"\[CHECKPOINT-PROBE\] arm=(?P<arm>\w+) checkpoint=(?P<cp>\d+) step=(?P<step>\d+) "
    r"(?:editFraction=(?P<ef>[\d.]+) )?position=(?P<pos>[\d.]+) replicate=(?P<rep>\d+) "
    r"(?P<verdict>Y=[01]|LOST(?: reason=[\w-]+)?)"
    r"(?: usd=(?P<usd>[\d.]+))?(?: agentSeconds=(?P<sec>\d+))?(?: tokens=(?P<tok>\d+))?"
)
CELL = re.compile(
    r"\[CHECKPOINT-PROBE\] cell arm=(?P<arm>\w+) checkpoint=(?P<cp>\d+) step=(?P<step>\d+) "
    r"(?:editFraction=(?P<ef>[\d.]+) )?position=(?P<pos>[\d.]+) replicate=(?P<rep>\d+) "
    r"patch=(?P<patch>\d+) chars"
)

SCALARS = [
    ("claimedFix", r"\[ARENA\]   Claimed fix:\s+(\w+)"),
    ("usedMcp", r"\[ARENA\]   Used MCP:\s+(\w+)"),
    ("exitCode", r"\[ARENA\]   Exit code:\s+(-?\d+)"),
    ("agentSecondsArena", r"\[ARENA\]   Agent time:\s+(\d+)s"),
    ("prewarmSeconds", r"\[ARENA\]   Prewarm time:\s+(\d+)s"),
    ("cacheCreationTokens", r"\[ARENA\]   Cache create:\s+(\d+)"),
    ("cacheReadTokens", r"\[ARENA\]   Cache read:\s+(\d+)"),
    ("costUsdArena", r"\[ARENA\]   Cost:\s+\$([\d.]+)"),
    ("numTurns", r"\[ARENA\]   Turns:\s+(\d+)"),
    ("apiSeconds", r"\[ARENA\]   API duration:\s+(\d+)s"),
    ("execCodeCalls", r"\[ARENA\]   exec_code:\s+(\d+)"),
]
TOKENS_IO = re.compile(r"\[ARENA\]   Tokens in/out:\s+(\d+)/(\d+)")
RWX = re.compile(r"\[ARENA\]   Read/Edit/Write: (\d+)/(\d+)/(\d+)")
GGB = re.compile(r"\[ARENA\]   Glob/Grep/Bash: (\d+)/(\d+)/(\d+)")
AGENT_TESTS = re.compile(r"\[ARENA\]   Tests:\s+(\d+) run, (\d+) fail, BUILD (\w+)")
FTP = re.compile(r"\[ARENA\]   Verified FTP:\s+(\d+)/(\d+)")
OBJ = re.compile(r"\[ARENA\]   Objective:\s+(\w+) \(regressions: (\d+)")
BASELINE = re.compile(r"\[ARENA\]   Baseline suite: (\d+) passing, (\d+) already failing")
TOKENS_MISSING = "[ARENA]   Tokens:         MISSING"
BUDGET = "[ARENA]   Agent budget:   EXHAUSTED"
TRANSPORT = re.compile(r"\[ARENA\]   API transport:  ABORTED — (.*)")
TAMPER = re.compile(r"\[ARENA-VERIFY\] test-patch files edited by the agent: (.*)")
VERIFY_HEAD = re.compile(r"\[ARENA-VERIFY\] \S*mvnw exit=(-?\d+);")
PER_CLASS = re.compile(
    r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: [^-]*-- in ([\w.$]+)"
)
SUMMARY = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$")
POST_SUITE = re.compile(r"\[ARENA-VERIFY\] post-agent full suite[^\n]*")
MODEL = re.compile(r"\[CHECKPOINT-PROBE\] resolved agent model: (\S+)")


def first(pat, text, cast=str):
    m = re.search(pat, text)
    return cast(m.group(1)) if m else None


def parse(path):
    with open(path, errors="replace") as fh:
        raw = fh.read()
    lines = [STRIP.sub("", l) for l in raw.splitlines()]
    text = "\n".join(lines)
    rec = {"buildId": os.path.basename(path)[:-4]}

    c = CELL.search(text)
    m = VERDICT.search(text)
    if not m and not c:
        return None
    g = (m or c).groupdict()
    rec.update(
        arm=g["arm"],
        checkpoint=int(g["cp"]),
        step=int(g["step"]),
        editFraction=float(g["ef"]) if g.get("ef") else None,
        position=float(g["pos"]),
        replicate=int(g["rep"]),
        grid="fraction" if g.get("ef") else "position",
        verdict=m.group("verdict") if m else None,
        usdVerdict=float(m.group("usd")) if m and m.group("usd") else None,
        agentSecondsVerdict=int(m.group("sec")) if m and m.group("sec") else None,
        endContextTokens=int(m.group("tok")) if m and m.group("tok") else None,
    )
    v = rec["verdict"]
    rec["success"] = 1 if v == "Y=1" else (0 if v == "Y=0" else None)
    rec["lostReason"] = (
        (v.split("reason=")[1] if "reason=" in v else "no-grade") if v and v.startswith("LOST")
        else ("no-verdict-line" if v is None else None)
    )

    rec["patchChars"] = int(c.group("patch")) if c else None
    rec["agentModel"] = first(MODEL, text)

    for name, pat in SCALARS:
        v = first(pat, text)
        if v is None:
            rec[name] = None
        elif name in ("claimedFix", "usedMcp"):
            rec[name] = v == "true"
        elif name == "costUsdArena":
            rec[name] = float(v)
        else:
            rec[name] = int(v)

    t = TOKENS_IO.search(text)
    rec["inputTokens"] = int(t.group(1)) if t else None
    rec["outputTokens"] = int(t.group(2)) if t else None
    rec["usageEventMissing"] = TOKENS_MISSING in text
    rec["budgetExhausted"] = BUDGET in text
    tr = TRANSPORT.search(text)
    rec["apiTransportError"] = tr.group(1).strip() if tr else None

    r = RWX.search(text)
    rec["readCalls"], rec["editCalls"], rec["writeCalls"] = (
        (int(r.group(1)), int(r.group(2)), int(r.group(3))) if r else (None, None, None)
    )
    gg = GGB.search(text)
    rec["globCalls"], rec["grepCalls"], rec["bashCalls"] = (
        (int(gg.group(1)), int(gg.group(2)), int(gg.group(3))) if gg else (None, None, None)
    )
    at = AGENT_TESTS.search(text)
    rec["agentTestsRun"] = int(at.group(1)) if at else None
    rec["agentTestsFail"] = int(at.group(2)) if at else None
    rec["agentBuildSuccess"] = (at.group(3) == "SUCCESS") if at else None

    f = FTP.search(text)
    rec["ftpClassesPassed"] = int(f.group(1)) if f else None
    rec["ftpClassesTotal"] = int(f.group(2)) if f else None
    o = OBJ.search(text)
    rec["verifierObjective"] = (o.group(1) == "true") if o else None
    rec["regressions"] = int(o.group(2)) if o else None
    b = BASELINE.search(text)
    rec["baselinePassing"] = int(b.group(1)) if b else None
    rec["baselineFailing"] = int(b.group(2)) if b else None

    tam = TAMPER.search(text)
    rec["testPatchFilesEdited"] = tam.group(1).strip() if tam else None
    rec["ftpTampered"] = bool(tam and "FAIL_TO_PASS ORACLE" in tam.group(1))

    # FAIL_TO_PASS surefire tail: the region printed right after the verifier's own maven line.
    vh = None
    for i, l in enumerate(lines):
        if VERIFY_HEAD.search(l):
            vh = i
            break
    rec["verifyMavenExit"] = int(VERIFY_HEAD.search(lines[vh]).group(1)) if vh is not None else None
    per, total_run, total_fail = {}, None, None
    if vh is not None:
        end = min(vh + 260, len(lines))
        for l in lines[vh:end]:
            pc = PER_CLASS.search(l)
            if pc:
                per[pc.group(5)] = dict(
                    run=int(pc.group(1)), fail=int(pc.group(2)), err=int(pc.group(3)), skip=int(pc.group(4))
                )
                continue
            sm = SUMMARY.search(l.rstrip())
            if sm and "-- in" not in l:
                total_run, total_fail = int(sm.group(1)), int(sm.group(2)) + int(sm.group(3))
    rec["ftpPerClass"] = per
    rec["ftpTestsRun"] = total_run
    rec["ftpTestsFailed"] = total_fail
    ps = POST_SUITE.search(text)
    rec["postSuiteLine"] = ps.group(0) if ps else None
    return rec


def main():
    out = []
    for name in sorted(os.listdir(LOGS)):
        if not name.endswith(".log"):
            continue
        rec = parse(os.path.join(LOGS, name))
        if rec is None:
            print(f"no probe line at all: {name}", file=sys.stderr)
            continue
        out.append(rec)
    with open(os.environ.get("RIPPLE_OUT", "/tmp/rd/rollouts.json"), "w") as fh:
        json.dump(out, fh, indent=1)
    print(f"{len(out)} rollouts")


if __name__ == "__main__":
    main()
