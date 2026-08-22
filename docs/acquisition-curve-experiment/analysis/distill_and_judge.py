#!/usr/bin/env python3
"""Turn recorded research trajectories into the actionable acquisition curve.

Two offline steps, no container and no repository:

  distil  one hand-off note per checkpoint, written by the same model with no tools from the
          prompt the Kotlin cell already assembled (`distill-b<K>.txt`). The slicing is NOT
          re-implemented here: a second definition of "after ten interactions" would drift from
          the one that scored `U_observed`, and nothing downstream would show it.

  judge   the fifteen pre-registered yes/no questions, asked against the NOTE ALONE. The note is
          prose about the repository — the distiller's brief forbids it to mention a tool, a
          command or a search — so the judge cannot tell which arm produced it. That is the whole
          blinding, and it is structural rather than promised.

Usage:
    python3 distill_and_judge.py --artifacts build/acquisition --out docs/.../data
    python3 distill_and_judge.py --artifacts build/acquisition --dry-run   # prints what it would spend

The API key is read exactly the way the test harness reads it: $ANTHROPIC_API_KEY, then
$CLAUDE_EVAL_API_KEY, then ~/.anthropic.
"""

import argparse
import json
import os
import pathlib
import sys
import urllib.request

API_URL = "https://api.anthropic.com/v1/messages"
DISTILL_MODEL = "claude-opus-4-6"
JUDGE_MODEL = "claude-opus-4-6"


def read_api_key() -> str:
    for name in ("ANTHROPIC_API_KEY", "CLAUDE_EVAL_API_KEY"):
        value = os.environ.get(name)
        if value:
            return value.strip()
    path = pathlib.Path.home() / ".anthropic"
    if path.exists():
        return path.read_text().strip()
    raise SystemExit("no API key: set ANTHROPIC_API_KEY, CLAUDE_EVAL_API_KEY or write ~/.anthropic")


def call_model(api_key: str, model: str, prompt: str, max_tokens: int) -> str:
    body = json.dumps({
        "model": model,
        "max_tokens": max_tokens,
        "messages": [{"role": "user", "content": prompt}],
    }).encode()
    request = urllib.request.Request(
        API_URL,
        data=body,
        headers={
            "content-type": "application/json",
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
        },
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        payload = json.load(response)
    return "".join(block.get("text", "") for block in payload.get("content", []))


JUDGE_PREAMBLE = """You are grading a hand-off note that one developer left another about a large Java
repository. You have never seen the repository and you must not use anything you happen to know about
it: grade ONLY what the note says.

Below the note you will find numbered questions. For each one answer strictly:

  1  the note states this, correctly and specifically enough to act on;
  0  the note does not state it, states it vaguely, hedges it away, or states it wrongly.

A note that names the right thing but tells the reader to do the wrong thing with it scores 0. A note
that says it does not know something scores 0 without penalty — that is what 0 means here.

Answer with one JSON object and nothing else: {"A1": 0, "A2": 1, ...}, one key per question id.
"""


def judge_prompt(note: str, facts: list) -> str:
    lines = [JUDGE_PREAMBLE, "", "## The note", "", note.strip(), "", "## Questions", ""]
    for fact in facts:
        lines.append(f"- **{fact['id']}**: {fact['judgeQuestion']}")
    return "\n".join(lines)


def parse_judgement(raw: str, facts: list) -> dict:
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end < 0:
        raise ValueError(f"the judge did not answer with JSON: {raw[:200]!r}")
    parsed = json.loads(raw[start:end + 1])
    missing = [fact["id"] for fact in facts if fact["id"] not in parsed]
    if missing:
        raise ValueError(f"the judge skipped {missing}")
    return {fact["id"]: 1 if int(parsed[fact["id"]]) else 0 for fact in facts}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", required=True, help="directory of <trajectoryId>/ folders")
    parser.add_argument("--out", default=None, help="where to write notes and the judged CSV")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    root = pathlib.Path(args.artifacts)
    trajectories = sorted(path for path in root.iterdir() if (path / "checklist.json").exists())
    if not trajectories:
        raise SystemExit(f"no trajectory artifacts under {root}")

    prompts = [(trajectory, prompt)
               for trajectory in trajectories
               for prompt in sorted(trajectory.glob("distill-b*.txt"))]
    total_chars = sum(prompt.stat().st_size for _, prompt in prompts)
    print(f"{len(trajectories)} trajectories, {len(prompts)} checkpoints, "
          f"{total_chars / 1e6:.2f} M characters of prompt "
          f"(~{total_chars / 4 / 1e3:.0f}k input tokens)")
    if args.dry_run:
        return 0

    api_key = read_api_key()
    out = pathlib.Path(args.out or root)
    out.mkdir(parents=True, exist_ok=True)
    rows = []

    for trajectory, prompt_path in prompts:
        facts = json.loads((trajectory / "checklist.json").read_text())["facts"]
        checkpoint = int(prompt_path.stem.split("-b")[1])
        note_path = out / f"{trajectory.name}.note-b{checkpoint}.md"
        if note_path.exists():
            note = note_path.read_text()
            print(f"  {trajectory.name} b={checkpoint}: note already distilled")
        else:
            note = call_model(api_key, DISTILL_MODEL, prompt_path.read_text(), max_tokens=2_000).strip()
            note_path.write_text(note)
            print(f"  {trajectory.name} b={checkpoint}: distilled {len(note)} characters")

        verdict = parse_judgement(
            call_model(api_key, JUDGE_MODEL, judge_prompt(note, facts), max_tokens=1_000),
            facts,
        )
        score = sum(verdict.values()) / len(facts)
        rows.append({
            "trajectory_id": trajectory.name,
            "checkpoint": checkpoint,
            "u_actionable": round(score, 4),
            "precedent": verdict.get("A1", 0),
            "note_chars": len(note),
            **verdict,
        })
        print(f"    U_actionable = {score:.2f}  A1 = {verdict.get('A1')}")

    csv_path = out / "actionable-curve.csv"
    header = list(rows[0].keys())
    with csv_path.open("w") as handle:
        handle.write(",".join(header) + "\n")
        for row in rows:
            handle.write(",".join(str(row[key]) for key in header) + "\n")
    print(f"wrote {csv_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
