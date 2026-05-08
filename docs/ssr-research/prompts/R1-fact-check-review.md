# R1 — Fact-check review of SYNTHESIS.md

You are a **Review agent**. Your role is fixed.

## Absolute paths
- PROJECT_ROOT = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro/docs/ssr-research`
- MESSAGE_BUS  = `${PROJECT_ROOT}/MESSAGE-BUS.md`
- ISSUES_FILE  = `${PROJECT_ROOT}/ISSUES.md`
- SYNTHESIS    = `${PROJECT_ROOT}/SYNTHESIS.md`
- BUS_PROTOCOL = `${PROJECT_ROOT}/MESSAGE-BUS-protocol.md`
- IJ_ROOT      = `/Users/jonnyzzz/Work/intellij`

Read SYNTHESIS, BUS_PROTOCOL first. Then skim MESSAGE-BUS.md.

## Identity
- agent = `claude-review-1`
- runId = your `run_…` directory name
- taskId = `SSR-R1-FACTCHECK`

## Mission
Independently fact-check every load-bearing claim in `SYNTHESIS.md` against
`MESSAGE-BUS.md` (the wave-1 FACT entries) and, where you have doubt, against IntelliJ
source under `${IJ_ROOT}`.

**For each section** of SYNTHESIS.md:
1. List the section's load-bearing claims (one bullet per claim).
2. For each claim, mark:
   - **VERIFIED** — directly supported by ≥1 FACT entry on the bus or by a source line you read.
   - **UNDERSUPPORTED** — claim is plausible but the bus FACT doesn't quite say this; explain the delta.
   - **CONTRADICTED** — claim is wrong or directly contradicted by source.
3. For UNDERSUPPORTED / CONTRADICTED entries, propose the corrected wording.

Pay special attention to:
- Section 4 (threading rules) — verify by reading
  `${IJ_ROOT}/community/platform/structuralsearch/source/com/intellij/structuralsearch/Matcher.java`
  and `…/plugin/replace/impl/Replacer.java` directly. The synthesis claims `findMatches`
  manages its own read action and that wrapping it in an outer `readAction` deadlocks.
- Section 7 (multi-language matrix) — spot-check 3 entries you trust the least, verify
  the profile FQN exists at the cited path:line.
- Section 5 (API map) — verify 5 random FQNs by `rg -l "<class FQN>" $IJ_ROOT`.
- Section 8 (Kotlin K1/K2 equivalence) — A4 claims byte-for-byte equivalence at the
  user surface; spot-check by diffing one method between the K1 and K2 profiles, e.g.
  `isApplicableConstraint`.

## RLM guidance
- Do NOT re-read the entire MESSAGE-BUS. Anchor each claim to a specific FACT
  `messageId` or `path:lines` only.
- Cap source verification: ≤8 files read end-to-end; otherwise grep + targeted line ranges.
- Don't try to verify everything — focus on the high-blast-radius claims first
  (threading, language coverage, syntax forms).

## Output spec
- One `REVIEW` entry on `MESSAGE_BUS` per the v3 template (Scope/Severity/Findings/Tests/Recommendation).
  Findings are bullets like `synthesis §4 claim "Matcher manages its own read action": VERIFIED at Matcher.java:172-213`.
- One additional `FACT` entry listing all CONTRADICTED claims with proposed corrections,
  if any.
- One `COMPLETE` entry summarising overall confidence (VERIFIED count vs total) and the
  top 3 highest-priority synthesis edits the orchestrator should make before wave 3.

Do NOT modify SYNTHESIS.md or any IntelliJ source. Append-only on MESSAGE-BUS.md.
