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
MODELS_URL = "https://api.anthropic.com/v1/models"


def resolve_model(api_key: str) -> str:
    """The newest model of the strong family the key can actually reach.

    Asked rather than hardcoded, and the reason is a real failure mode rather than tidiness: the
    distiller is not the agent, so it goes through the plain Messages API, whose model ids are dated
    (`claude-opus-4-5-20251101`) while the CLI takes aliases (`claude-opus-5`). A wrong id fails the
    whole wave with a 404 after the prompts are built, and a stale-but-valid one silently distils
    every note with a weaker model than the round it belongs to.
    """
    request = urllib.request.Request(
        MODELS_URL + "?limit=100",
        headers={"x-api-key": api_key, "anthropic-version": "2023-06-01"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = json.load(response)
    opus = [model for model in payload.get("data", []) if model.get("id", "").startswith("claude-opus")]
    if not opus:
        raise SystemExit(f"the key reaches no opus model; it offers {[m.get('id') for m in payload.get('data', [])]}")
    newest = sorted(opus, key=lambda model: model.get("created_at", ""))[-1]["id"]
    print(f"resolved model: {newest}")
    return newest


def read_api_key() -> str:
    for name in ("ANTHROPIC_API_KEY", "CLAUDE_EVAL_API_KEY"):
        value = os.environ.get(name)
        if value:
            return value.strip()
    path = pathlib.Path.home() / ".anthropic"
    if path.exists():
        return path.read_text().strip()
    raise SystemExit("no API key: set ANTHROPIC_API_KEY, CLAUDE_EVAL_API_KEY or write ~/.anthropic")


class EmptyAnswer(RuntimeError):
    """Raised when a call came back with no text at all, so the caller can retry with more room."""


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
    text = "".join(block.get("text", "") for block in payload.get("content", []))
    if not text.strip():
        # An empty answer is never "the model had nothing to say" — it is a budget that ran out
        # inside a reasoning block, or a refusal, and the caller a level up used to report it as
        # "the judge did not answer with JSON: ''", which points at the wrong thing entirely.
        blocks = [block.get("type") for block in payload.get("content", [])]
        raise EmptyAnswer(
            f"{model} returned no text: stop_reason={payload.get('stop_reason')} "
            f"blocks={blocks} usage={payload.get('usage')}"
        )
    return text


def call_model_persistently(api_key: str, model: str, prompt: str, max_tokens: int) -> str:
    """One retry with four times the room, then give up loudly.

    The failure this exists for is specific and was hit on the first paid wave: with a modern model
    the whole budget can be spent before a single visible character is emitted, and a fifteen-question
    verdict is exactly the shape that provokes it. Retrying blindly forever would be worse than
    failing — an infinite spend — so it is one retry, and the second failure carries the API's own
    stop reason into the build log.
    """
    try:
        return call_model(api_key, model, prompt, max_tokens)
    except EmptyAnswer as first:
        print(f"    empty answer ({first}); retrying with {max_tokens * 4} tokens")
        return call_model(api_key, model, prompt, max_tokens * 4)


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
    parser.add_argument(
        "--artifacts",
        required=True,
        help="directory of <caseId>/<trajectoryId>/ folders, as the recompute step writes them",
    )
    parser.add_argument("--out", default=None, help="where to write notes and the judged CSV")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--checkpoints",
        default="5,10,20",
        help="which checkpoints to distil, comma separated. The downstream round buys 5/10/20; "
             "40 is where every trajectory has already plateaued and its note answers nothing new.",
    )
    parser.add_argument(
        "--cases",
        default=None,
        help="comma-separated case ids to distil. Omitted, every case under --artifacts is distilled, "
             "which re-buys notes that are already committed for the earlier rounds.",
    )
    parser.add_argument(
        "--model",
        default=None,
        help="override the model. Omitted, the newest opus the key reaches is used for both steps.",
    )
    args = parser.parse_args()
    wanted = {int(value) for value in args.checkpoints.split(",") if value.strip()}
    wanted_cases = {value.strip() for value in args.cases.split(",") if value.strip()} if args.cases else None

    root = pathlib.Path(args.artifacts)
    # Searched recursively, and the case level is the reason. Trajectory ids repeat across cases --
    # every case has an `mcp-b40-l2000-r1` -- so a note file named after the trajectory alone would
    # have one case silently overwrite another's, and the judged row would carry the wrong checklist.
    trajectories = sorted(path.parent for path in root.rglob("checklist.json"))
    if not trajectories:
        raise SystemExit(f"no <caseId>/<trajectoryId>/checklist.json under {root}")
    flat = [path for path in trajectories if path.parent == root]
    if flat:
        raise SystemExit(
            f"{[p.name for p in flat]} sit directly under {root}, i.e. the old single-case layout. "
            f"Re-run the recompute step, which writes <caseId>/<trajectoryId>/"
        )
    if wanted_cases is not None:
        present = {path.parent.name for path in trajectories}
        unknown = wanted_cases - present
        if unknown:
            raise SystemExit(f"--cases names {sorted(unknown)}, which is not under {root} ({sorted(present)})")
        trajectories = [path for path in trajectories if path.parent.name in wanted_cases]

    prompts = [(trajectory, prompt)
               for trajectory in trajectories
               for prompt in sorted(trajectory.glob("distill-b*.txt"))
               if int(prompt.stem.split("-b")[1]) in wanted]
    total_chars = sum(prompt.stat().st_size for _, prompt in prompts)
    print(f"{len(trajectories)} trajectories, {len(prompts)} checkpoints, "
          f"{total_chars / 1e6:.2f} M characters of prompt "
          f"(~{total_chars / 4 / 1e3:.0f}k input tokens)")
    if args.dry_run:
        return 0

    api_key = read_api_key()
    model = args.model or resolve_model(api_key)
    out = pathlib.Path(args.out or root)
    out.mkdir(parents=True, exist_ok=True)
    rows = []

    for trajectory, prompt_path in prompts:
        checklist = json.loads((trajectory / "checklist.json").read_text())
        facts = checklist["facts"]
        case_id = checklist["caseId"]
        checkpoint = int(prompt_path.stem.split("-b")[1])
        note_dir = out / case_id
        note_dir.mkdir(parents=True, exist_ok=True)
        note_path = note_dir / f"{trajectory.name}.note-b{checkpoint}.md"
        if note_path.exists():
            note = note_path.read_text()
            print(f"  {trajectory.name} b={checkpoint}: note already distilled")
        else:
            note = call_model_persistently(
                api_key, model, prompt_path.read_text(), max_tokens=4_000
            ).strip()
            note_path.write_text(note)
            print(f"  {case_id}/{trajectory.name} b={checkpoint}: distilled {len(note)} characters")

        verdict = parse_judgement(
            call_model_persistently(api_key, model, judge_prompt(note, facts), max_tokens=4_000),
            facts,
        )
        score = sum(verdict.values()) / len(facts)
        rows.append({
            "case": case_id,
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
