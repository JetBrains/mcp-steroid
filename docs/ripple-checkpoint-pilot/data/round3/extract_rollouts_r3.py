#!/usr/bin/env python3
"""One row per round-3 probe rollout, recovered from the raw TeamCity logs of the probe builds.

Round 2's `data/extract_rollouts.py` generalized to many cases. The log formats are UNCHANGED — the
`[CHECKPOINT-PROBE]` verdict line and the `[ARENA]` run summary are printed by the same Kotlin — so the
regexes are round 2's, with exactly one deliberate widening and one addition:

  * `arm=(?P<arm>\\w+)` becomes `arm=(?P<arm>\\S+)`. `\\w` does not match a hyphen, and every round-3 arm
    token has one (`pc71-mcp`, `sb31-none`, …), so the round-2 pattern would have silently found no
    verdict line in any round-3 log and reported 300 builds as unparseable. `\\S+` is what
    `RCW-GENERALIZATION.md` names as the verdict regex, and it still matches `mcp2` / `none2`.
  * `editFraction` keeps round 2's SIGNED pattern. A state taken before the first write legitimately has
    a negative fraction (mcp2 step 13 is −0.091), and round 2's unsigned pattern dropped all five of
    those verdicts as "no probe line at all". That fix is preserved, not re-derived.

The CASE is not in the log. It is derived from the arm token through `RippleCheckpointCases.kt`, which is
PARSED here rather than copied: the Kotlin registry is what the probe build itself resolves the case
with (`rippleCheckpointCaseOfArm`), and a second hand-maintained table in Python is a table that will
eventually disagree with it. A token the registry does not know is an error listing the known ones — a
default would file a rollout under the wrong case and quietly contaminate a cross-case roll-up.

Censoring, exactly as `RCW-GENERALIZATION.md` → "Censoring — fixed in advance" fixes it:

  * a run with no terminal `result` event emits no usage — `[ARENA] Tokens: MISSING` — so `outputTokens`
    and `usd` are written EMPTY and `censored=1`. They are never written as zero and never imputed here.
    Imputation is a decision of the analysis (`analyze_rcw_r3.py` does it at the worst case, on purpose,
    as a robustness check), not of the extraction: a dataset that has already guessed cannot be re-read
    under a different assumption.
  * `LOST` — the patch did not apply, the verifier produced no grade, or the API transport aborted — is
    written with `lost=1` and is excluded from every statistic downstream. It is never published as a
    zero, because the cell's readiness is UNKNOWN rather than nil.
  * a run killed at the case's budget is NOT lost. `RippleCheckpointProbeTest` publishes it as `Y=0` and
    says so in the log: it is the task failing, and it counts towards `V`. `exitReason` records
    `budget-exhausted` so the two failure kinds stay distinguishable, while `Y` keeps both.
  * `toolCalls` and `editActions` come from the transcript-decoded `[ARENA]` counters, which exist even
    for a killed CLI. That is the whole reason they carry the corroboration role.

Usage:
    for id in $(cat probe-ids.txt); do jb tc builds log "$id" -o /tmp/r3/logs/$id.log; done
    extract_rollouts_r3.py --logs /tmp/r3/logs --out rollouts-r3.csv

`--plan checkpoint-plan-r3.csv` is optional and only affects the `checkpointId` column: with it, the
milestone id (`C1`..`C5`) of the state is resolved by joining on (case, arm, step), which is the id the
pre-registration's tables are written in; without it the column falls back to `C<index>` built from the
verdict's own ordinal, and a warning says so.
"""
import argparse
import csv
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
# docs/ripple-checkpoint-pilot/data/round3 -> repository root
REPO_ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
REGISTRY_KT = os.path.join(
    REPO_ROOT, "test-experiments", "src", "test", "kotlin", "com", "jonnyzzz", "mcpSteroid",
    "integration", "arena", "RippleCheckpointCases.kt",
)

STRIP = re.compile(r"^.*?\[:test-experiments:test\]\s?")

VERDICT = re.compile(
    r"\[CHECKPOINT-PROBE\] arm=(?P<arm>\S+) checkpoint=(?P<cp>\d+) step=(?P<step>\d+) "
    r"(?:editFraction=(?P<ef>-?[\d.]+) )?position=(?P<pos>[\d.]+) replicate=(?P<rep>\d+) "
    r"(?P<verdict>Y=[01]|LOST(?: reason=[\w-]+)?)"
    r"(?: usd=(?P<usd>[\d.]+))?(?: agentSeconds=(?P<sec>\d+))?(?: tokens=(?P<tok>\d+))?"
)
# The cell line is printed BEFORE the agent runs. It is the only evidence a build reached its state at
# all, so a log with a cell line and no verdict is a rollout that died mid-run — reported, not dropped.
CELL = re.compile(
    r"\[CHECKPOINT-PROBE\] cell arm=(?P<arm>\S+) checkpoint=(?P<cp>\d+) step=(?P<step>\d+) "
    r"(?:editFraction=(?P<ef>-?[\d.]+) )?position=(?P<pos>[\d.]+) replicate=(?P<rep>\d+) "
    r"patch=(?P<patch>\d+) chars"
)

TOKENS_IO = re.compile(r"\[ARENA\]   Tokens in/out:\s+(\d+)/(\d+)")
RWX = re.compile(r"\[ARENA\]   Read/Edit/Write: (\d+)/(\d+)/(\d+)")
GGB = re.compile(r"\[ARENA\]   Glob/Grep/Bash: (\d+)/(\d+)/(\d+)")
EXEC_CODE = re.compile(r"\[ARENA\]   exec_code:\s+(\d+)")
COST = re.compile(r"\[ARENA\]   Cost:\s+\$([\d.]+)")
AGENT_SECONDS = re.compile(r"\[ARENA\]   Agent time:\s+(\d+)s")
NUM_TURNS = re.compile(r"\[ARENA\]   Turns:\s+(\d+)")
TAMPER = re.compile(r"\[ARENA-VERIFY\] test-patch files edited by the agent: (.*)")
TRANSPORT = re.compile(r"\[ARENA\]   API transport:  ABORTED — (.*)")
MODEL = re.compile(r"\[CHECKPOINT-PROBE\] resolved agent model: (\S+)")
TOKENS_MISSING = "[ARENA]   Tokens:         MISSING"
BUDGET = "[ARENA]   Agent budget:   EXHAUSTED"

FIELDS = [
    "case", "arm", "checkpointId", "checkpointIndex", "step", "replicate", "buildId", "Y",
    "exitReason", "outputTokens", "inputTokens", "toolCalls", "editActions", "usd", "agentSeconds",
    "numTurns", "censored", "lost", "lostReason", "budgetExhausted", "tampered", "agentModel",
    "apiTransportError", "editFraction", "position", "patchChars",
]

# ── the Kotlin case registry, parsed rather than copied ──────────────────────────────────────────

SPEC = re.compile(r"RippleCheckpointCaseSpec\((?P<body>.*?)\n\s*\),?\n", re.S)
FIELD = re.compile(r"(?P<name>resourceDir|instanceId)\s*=\s*(?P<value>[^,\n]+)")
ARMS = re.compile(r"arms\s*=\s*listOf\((?P<items>[^)]*)\)", re.S)
ARM_DIRS = re.compile(r"armDirs\s*=\s*mapOf\((?P<items>[^)]*)\)", re.S)
STRING = re.compile(r'"([^"]*)"')
CONST = re.compile(r'const val (?P<name>\w+)\s*:\s*String\s*=\s*"(?P<value>[^"]*)"')
RIPPLE_CASE_ID = re.compile(
    r'val (?P<name>\w+)\s*:\s*RippleCase\s*=\s*RippleCase\(\s*instanceId\s*=\s*"(?P<value>[^"]*)"'
)


class CaseSpec:
    """One registered case: the directory its states live in and the arm tokens that address them."""

    def __init__(self, resource_dir, instance_id, arms, arm_dirs):
        self.resourceDir = resource_dir
        self.instanceId = instance_id
        self.arms = arms
        self.armDirs = arm_dirs

    def arm_dir(self, arm):
        """The committed DIRECTORY of an arm token — they differ only for the discarded keycloak case."""
        if arm not in self.arms:
            raise SystemExit(f"the case '{self.resourceDir}' has no arm '{arm}' — its arms are {self.arms}")
        return self.armDirs.get(arm, arm)


def _resolve(expression, kotlin_dir):
    """A Kotlin initializer expression as a string value, or an error naming what could not be read.

    Three shapes occur in the registry and all three are resolved from the sources themselves:
    a quoted literal, `Object.CONST` (a `const val … : String`), and `RippleCases.<case>.instanceId`.
    Anything else fails loudly — a registry that grew a fourth shape must be READ, not guessed at, or
    the token→case mapping this whole file exists to avoid duplicating would be silently wrong.
    """
    expression = expression.strip().rstrip(",")
    literal = STRING.fullmatch(expression)
    if literal:
        return literal.group(1)

    sources = {}
    for name in sorted(os.listdir(kotlin_dir)):
        if name.endswith(".kt"):
            with open(os.path.join(kotlin_dir, name), errors="replace") as fh:
                sources[name] = fh.read()

    const = re.fullmatch(r"\w+\.(?P<name>[A-Z_]+)", expression)
    if const:
        for text in sources.values():
            for match in CONST.finditer(text):
                if match.group("name") == const.group("name"):
                    return match.group("value")

    ripple = re.fullmatch(r"RippleCases\.(?P<name>\w+)\.instanceId", expression)
    if ripple:
        for text in sources.values():
            for match in RIPPLE_CASE_ID.finditer(text):
                if match.group("name") == ripple.group("name"):
                    return match.group("value")

    raise SystemExit(
        f"cannot resolve the Kotlin expression {expression!r} from {kotlin_dir}. The case registry has "
        f"grown a shape this parser does not read; teach it that shape rather than hardcoding the value."
    )


def load_registry(path=REGISTRY_KT):
    """Every `(resourceDir, instanceId, arms)` the harness registers, read out of the Kotlin source."""
    if not os.path.exists(path):
        raise SystemExit(
            f"the case registry {path} is missing, so no arm token can be resolved to a case. "
            f"Pass --registry with the path to RippleCheckpointCases.kt."
        )
    with open(path, errors="replace") as fh:
        text = fh.read()
    kotlin_dir = os.path.dirname(os.path.abspath(path))

    specs = []
    for block in SPEC.finditer(text):
        body = block.group("body")
        fields = {m.group("name"): m.group("value") for m in FIELD.finditer(body)}
        arms_match = ARMS.search(body)
        if "resourceDir" not in fields or "instanceId" not in fields or not arms_match:
            raise SystemExit(f"a RippleCheckpointCaseSpec in {path} has no resourceDir/instanceId/arms")
        arms = STRING.findall(arms_match.group("items"))
        dirs_match = ARM_DIRS.search(body)
        pairs = STRING.findall(dirs_match.group("items")) if dirs_match else []
        specs.append(CaseSpec(
            resource_dir=_resolve(fields["resourceDir"], kotlin_dir),
            instance_id=_resolve(fields["instanceId"], kotlin_dir),
            arms=arms,
            arm_dirs=dict(zip(pairs[0::2], pairs[1::2])),
        ))
    if not specs:
        raise SystemExit(f"{path} parsed to zero cases — the registry format changed")

    seen = {}
    for spec in specs:
        for arm in spec.arms:
            if arm in seen:
                raise SystemExit(
                    f"the arm token '{arm}' is registered twice ({seen[arm]} and {spec.resourceDir}); "
                    f"a probe cell would be ambiguous"
                )
            seen[arm] = spec.resourceDir
    return specs


def case_of_arm(specs, arm):
    """The case an arm token belongs to — the inversion the whole token scheme exists for."""
    for spec in specs:
        if arm in spec.arms:
            return spec
    known = [a for spec in specs for a in spec.arms]
    raise SystemExit(f"no checkpoint case is registered for the arm '{arm}' — the known arms are {known}")


# ── the logs ─────────────────────────────────────────────────────────────────────────────────────

def first(pattern, text, cast=str):
    m = pattern.search(text)
    return cast(m.group(1)) if m else None


def parse(path, specs, milestone_of):
    """One rollout, or None when the log holds no probe line at all."""
    with open(path, errors="replace") as fh:
        raw = fh.read()
    text = "\n".join(STRIP.sub("", line) for line in raw.splitlines())

    cell = CELL.search(text)
    verdict = VERDICT.search(text)
    if not verdict and not cell:
        return None
    g = (verdict or cell).groupdict()
    arm = g["arm"]
    spec = case_of_arm(specs, arm)
    step = int(g["step"])
    index = int(g["cp"])

    decision = verdict.group("verdict") if verdict else None
    lost = decision is None or decision.startswith("LOST")
    y = None if lost else int(decision[-1])
    lost_reason = None
    if decision is None:
        lost_reason = "no-verdict-line"
    elif decision.startswith("LOST"):
        lost_reason = decision.split("reason=")[1] if "reason=" in decision else "no-grade"

    tamper = TAMPER.search(text)
    tampered = bool(tamper and "FAIL_TO_PASS ORACLE" in tamper.group(1))
    budget = BUDGET in text
    tokens = TOKENS_IO.search(text)
    # No terminal `result` event means no usage event at all: the token counters and the cost are NA for
    # exactly the SLOWEST runs, which is why the missingness is informative and is flagged rather than
    # filled. `[ARENA] Tokens: MISSING` is the harness saying so in as many words.
    censored = tokens is None or TOKENS_MISSING in text

    rwx = RWX.search(text)
    ggb = GGB.search(text)
    exec_code = first(EXEC_CODE, text, int)
    parts = (
        [int(rwx.group(i)) for i in (1, 2, 3)] + [int(ggb.group(i)) for i in (1, 2, 3)] + [exec_code]
        if rwx and ggb and exec_code is not None else None
    )

    if lost:
        reason = "LOST"
    elif tampered:
        reason = "tampered"
    elif y == 1:
        reason = "solved"
    elif budget:
        reason = "budget-exhausted"
    else:
        reason = "not-solved"

    milestone = milestone_of(spec.resourceDir, arm, step)
    return dict(
        case=spec.resourceDir, arm=arm,
        checkpointId=milestone if milestone else "C%d" % index,
        checkpointIndex=index, step=step, replicate=int(g["rep"]),
        buildId=os.path.basename(path)[:-4],
        Y="" if y is None else y,
        exitReason=reason,
        outputTokens="" if censored or not tokens else int(tokens.group(2)),
        inputTokens="" if censored or not tokens else int(tokens.group(1)),
        toolCalls="" if parts is None else sum(parts),
        editActions="" if rwx is None else int(rwx.group(2)) + int(rwx.group(3)),
        usd="" if censored else (first(COST, text, float) or ""),
        agentSeconds=first(AGENT_SECONDS, text, int),
        numTurns=first(NUM_TURNS, text, int),
        censored=int(censored), lost=int(lost), lostReason=lost_reason or "",
        budgetExhausted=int(budget), tampered=int(tampered),
        agentModel=first(MODEL, text) or "",
        apiTransportError=(first(TRANSPORT, text) or "").strip(),
        editFraction=g.get("ef") or "",
        position=g.get("pos") or "",
        patchChars=int(cell.group("patch")) if cell else "",
    )


def load_plan(path):
    """`(case, arm, step) -> 'C1'` (or `'C1|C2'` for a collision) from the checkpoint plan, if there is one."""
    if not path or not os.path.exists(path):
        return {}
    ids = {}
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            key = (row["case"], row["arm"], int(row["step"]))
            ids.setdefault(key, []).append(row["checkpointId"])
    return {key: "|".join(sorted(set(v))) for key, v in ids.items()}


def main():
    parser = argparse.ArgumentParser(
        description="One row per round-3 probe rollout, from the downloaded probe build logs.",
        epilog="LOST rows are written with lost=1 and are excluded from every statistic downstream.",
    )
    parser.add_argument("--logs", required=True, help="directory of <buildId>.log files")
    parser.add_argument("--out", default="rollouts-r3.csv")
    parser.add_argument("--plan", default="checkpoint-plan-r3.csv",
                        help="checkpoint-plan-r3.csv, for the C1..C5 milestone ids")
    parser.add_argument("--registry", default=REGISTRY_KT, help="path to RippleCheckpointCases.kt")
    args = parser.parse_args()

    if not os.path.isdir(args.logs):
        raise SystemExit(f"--logs {args.logs} is not a directory")
    names = sorted(n for n in os.listdir(args.logs) if n.endswith(".log"))
    if not names:
        raise SystemExit(f"--logs {args.logs} holds no .log file; nothing to extract")

    specs = load_registry(args.registry)
    plan = load_plan(args.plan)
    if not plan:
        print(f"no checkpoint plan at {args.plan} — checkpointId falls back to C<index>, the verdict's "
              f"own ordinal, which is NOT the milestone id when a trajectory has a collision",
              file=sys.stderr)
    milestone_of = lambda case, arm, step: plan.get((case, arm, step))

    rows, empty = [], []
    for name in names:
        row = parse(os.path.join(args.logs, name), specs, milestone_of)
        if row is None:
            empty.append(name)
            continue
        rows.append(row)
    for name in empty:
        print(f"no probe line at all: {name}", file=sys.stderr)
    if not rows:
        raise SystemExit(
            f"none of the {len(names)} logs in {args.logs} holds a [CHECKPOINT-PROBE] line. "
            f"Refusing to write an empty dataset."
        )

    rows.sort(key=lambda r: (r["case"], r["arm"], r["step"], r["replicate"]))
    with open(args.out, "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)

    counts = {}
    for row in rows:
        counts[row["exitReason"]] = counts.get(row["exitReason"], 0) + 1
    print(f"{len(rows)} rollouts -> {args.out}  {counts}  "
          f"censored={sum(r['censored'] for r in rows)} lost={sum(r['lost'] for r in rows)} "
          f"unparsed={len(empty)}")


if __name__ == "__main__":
    main()
