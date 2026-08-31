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
import re
import sys
import urllib.request

ANTHROPIC_API_BASE = "https://api.anthropic.com"
# A gateway in front of the API (LiteLLM and friends) is configured through the same variable the
# Claude CLI reads, so a machine already set up for the CLI needs no separate setup here.
API_BASE = os.environ.get("ANTHROPIC_BASE_URL", ANTHROPIC_API_BASE).rstrip("/")
API_URL = f"{API_BASE}/v1/messages"
MODELS_URL = f"{API_BASE}/v1/models"


def auth_headers(api_key: str) -> dict:
    """Anthropic authenticates with `x-api-key`; a gateway usually wants a bearer token instead.

    Against the real endpoint only `x-api-key` goes out, so the direct path stays byte-identical.
    Against an `ANTHROPIC_BASE_URL` override both headers are sent and the gateway takes whichever
    it understands, which is cheaper than making the caller also declare the scheme their proxy speaks.
    """
    headers = {"x-api-key": api_key, "anthropic-version": "2023-06-01"}
    if API_BASE != ANTHROPIC_API_BASE:
        headers["Authorization"] = f"Bearer {api_key}"
    return headers


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
        headers=auth_headers(api_key),
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = json.load(response)
    # A gateway namespaces ids by vendor (`anthropic/claude-opus-5`); the direct API does not.
    opus = [model for model in payload.get("data", [])
            if model.get("id", "").rsplit("/", 1)[-1].startswith("claude-opus")]
    if not opus:
        raise SystemExit(f"the key reaches no opus model; it offers {[m.get('id') for m in payload.get('data', [])]}")
    # Ordering by a field the endpoint does not serve is worse than refusing: every candidate sorts
    # equal, the sort is stable, and the LAST list entry silently becomes "the newest model" -- which
    # is how a round gets judged by a year-old opus and never records that it happened.
    undated = [model.get("id") for model in opus if not model.get("created_at")]
    if undated:
        raise SystemExit(
            f"{len(undated)} of {len(opus)} opus models carry no `created_at`, so 'newest' is not "
            "decidable here and guessing would silently pick an arbitrary judge. Pass --model "
            f"explicitly. Available: {sorted(model.get('id') for model in opus)}"
        )
    newest = sorted(opus, key=lambda model: model["created_at"])[-1]["id"]
    print(f"resolved model: {newest}")
    return newest


def read_api_key() -> str:
    for name in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_EVAL_API_KEY"):
        value = os.environ.get(name)
        if value:
            return value.strip()
    path = pathlib.Path.home() / ".anthropic"
    if path.exists():
        return path.read_text().strip()
    raise SystemExit(
        "no API key: set ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, CLAUDE_EVAL_API_KEY "
        "or write ~/.anthropic"
    )


class EmptyAnswer(RuntimeError):
    """Raised when a call came back with no text at all, so the caller can retry with more room."""


class Refused(RuntimeError):
    """Raised when the API answered `stop_reason=refusal`, which is not a transient failure.

    Separated from EmptyAnswer because the two need opposite handling. An empty answer is a budget
    that ran out and is worth one retry with more room; a refusal is a decision about the content and
    a retry only spends money to be told the same thing. This was not hypothetical: the first wave of
    the replication round distilled 22 notes and then died on the 23rd because the JUDGE prompt for
    one note -- an OAuth grant that refuses the wrong kind of token -- came back refused, taking the
    whole run's judged output with it.
    """


def call_model(api_key: str, model: str, prompt: str, max_tokens: int) -> str:
    body = json.dumps({
        "model": model,
        "max_tokens": max_tokens,
        "messages": [{"role": "user", "content": prompt}],
    }).encode()
    request = urllib.request.Request(
        API_URL,
        data=body,
        headers={"content-type": "application/json", **auth_headers(api_key)},
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        payload = json.load(response)
    text = "".join(block.get("text", "") for block in payload.get("content", []))
    if not text.strip():
        # An empty answer is never "the model had nothing to say" — it is a budget that ran out
        # inside a reasoning block, or a refusal, and the caller a level up used to report it as
        # "the judge did not answer with JSON: ''", which points at the wrong thing entirely.
        blocks = [block.get("type") for block in payload.get("content", [])]
        detail = (
            f"{model} returned no text: stop_reason={payload.get('stop_reason')} "
            f"blocks={blocks} usage={payload.get('usage')}"
        )
        if payload.get("stop_reason") == "refusal":
            raise Refused(detail)
        raise EmptyAnswer(detail)
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
        "--seed",
        default=None,
        help="directory of ALREADY COMMITTED notes, laid out as <caseId>/<trajectoryId>-at<B>.md. They "
             "are copied into --out before anything is called, so a re-run judges the notes the "
             "solving cells actually received instead of buying different ones.",
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
    refusals = []

    # Seeding is what makes this step safely re-runnable. Distillation is not deterministic, so a
    # re-run without it produces DIFFERENT notes -- and if some cells already ran against the first
    # set, the published table would describe notes that no solver ever saw. It also means a wave
    # interrupted at note 23 costs three notes to finish, not twenty-three.
    if args.seed:
        seed_root = pathlib.Path(args.seed)
        if not seed_root.is_dir():
            raise SystemExit(f"--seed {seed_root} is not a directory")
        seeded = 0
        for committed in seed_root.rglob("*-at*.md"):
            match = re.fullmatch(r"(?P<trajectory>.+)-at(?P<checkpoint>\d+)\.md", committed.name)
            if not match:
                continue
            target_dir = out / committed.parent.name
            target_dir.mkdir(parents=True, exist_ok=True)
            target = target_dir / f"{match['trajectory']}.note-b{match['checkpoint']}.md"
            if not target.exists():
                target.write_text(committed.read_text())
                seeded += 1
        print(f"seeded {seeded} already-committed notes from {seed_root}")

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

        # A refusal is recorded as a hole, never as a zero and never as a crash. The note itself is
        # already distilled and paid for, and the downstream wave needs the NOTE; the judged score is
        # a secondary axis. Turning the hole into a 0 would silently push a real note to the bottom of
        # the U_note ranking, which is the one place the whole analysis would never notice it.
        try:
            verdict = parse_judgement(
                call_model_persistently(api_key, model, judge_prompt(note, facts), max_tokens=4_000),
                facts,
            )
        except Refused as refusal:
            print(f"    JUDGE REFUSED, recorded as missing: {refusal}", file=sys.stderr)
            refusals.append({"case": case_id, "trajectory_id": trajectory.name, "checkpoint": checkpoint})
            continue
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

    if refusals:
        print(f"\n{len(refusals)} note(s) went unjudged because the model refused the judge prompt: "
              f"{refusals}. Their notes are on disk and usable; their U_note is missing, not zero.",
              file=sys.stderr)
    if not rows:
        raise SystemExit("every judge call was refused; nothing to write")

    # The header is the UNION of the rows' keys, not the first row's. Two cases have different fact
    # ids -- one has an H2, the other does not -- and taking the header from row zero killed a wave
    # with `KeyError: 'H2'` AFTER every paid call had already been made. A missing fact is written as
    # an empty cell, which reads as "this case has no such fact" and never as a zero score.
    csv_path = out / "actionable-curve.csv"
    header = []
    for row in rows:
        for key in row:
            if key not in header:
                header.append(key)
    with csv_path.open("w") as handle:
        handle.write(",".join(header) + "\n")
        for row in rows:
            handle.write(",".join(str(row.get(key, "")) for key in header) + "\n")
    print(f"wrote {csv_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
