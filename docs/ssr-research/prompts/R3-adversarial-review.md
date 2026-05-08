# R3 — Adversarial review: find what's wrong, missing, or unsafe

You are a **Review agent**. Your role is fixed.

## Absolute paths
- PROJECT_ROOT = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro/docs/ssr-research`
- MESSAGE_BUS  = `${PROJECT_ROOT}/MESSAGE-BUS.md`
- SYNTHESIS    = `${PROJECT_ROOT}/SYNTHESIS.md`
- IJ_ROOT      = `/Users/jonnyzzz/Work/intellij`
- MCP_STEROID  = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro`

Read SYNTHESIS.md and MCP_STEROID's `CLAUDE.md` (the project's banned-patterns list).

## Identity
- agent = `codex-review-3`
- runId = your `run_…` directory name
- taskId = `SSR-R3-ADVERSARIAL`

## Mission
Take an **adversarial** stance: assume SYNTHESIS.md is mostly right, then try to break
it. Specifically:

1. **Find gaps** — what's missing that a competent skill author would need? Examples:
   - SSR injection-language behaviour (the `searchInjectedCode` flag): is the synthesis
     correct that this is on by default? What does it actually mean for nested languages
     (`@Language` PSI in Java/Kotlin string literals)?
   - Performance characteristics — what scope size is "too big" for `findMatches` before
     the agent should chunk by module?
   - The synthesis says `Replacer.replace(info)` is the script-safe singular path, but
     does NOT explain how to hold the `MatchResult` references across the gap between
     read-only search (which holds `SmartPsiElementPointer`s) and write-action replace.
     Are pointers still valid? Cite source.
   - PSI invalidation: if a replacement deletes/replaces a `PsiElement`, are the
     remaining `MatchResult` instances in the same batch still valid?
2. **Find contradictions** — between SYNTHESIS sections, between SYNTHESIS and the
   bus, or between synthesis and current `~/Work/intellij` source. Examples:
   - §2 says the dialog uses `$x$` and the API uses `'_x`, but A1's Examples-page
     transcript shows `LOG.debug($params$)` next to `'_a` `'_st*` in the same
     "Logging not in conditional" example. Is that a real two-form pattern in one
     template, or a typo in the help page?
   - §6 says K1 and K2 are byte-for-byte equivalent at the user surface; A4 cites the
     `KotlinStructuralReplaceHandler.postProcess` divergence. Is that "user surface"
     or "implementation"? Could a slow K2 invokeShortening cause user-visible flicker
     during replacement?
3. **Find unsafe recipes** — anywhere the synthesis recommends a code pattern, ask:
   what happens if the input is malformed / pattern compilation fails / scope is empty
   / write action is rejected? Are the error paths in the canonical recipe sufficient,
   or will an agent script silently swallow `MalformedPatternException` and report
   "0 matches"?
4. **Find skill-article risks** — the proposed structure in §10 is 5–7 articles. Is
   that the right shape? Could a single LLM agent loading just `overview.md` be
   misled into a wrong recipe because the threading rules are in a separate article it
   never fetched? What's the right cross-link strategy?
5. **Find banned-patterns drift** — `MCP_STEROID/CLAUDE.md` lists banned patterns
   (`runCatching{}.onFailure{}`, raw `\n` appends, etc.). Does the proposed skill content
   itself comply? E.g., does the canonical recipe in A6 use any banned form?

## RLM guidance
- For each finding, propose a concrete fix (1–2 sentences).
- Cap your output at ~10 findings; quality over quantity.
- Severity-rate each finding: `blocker` (skill ships wrong) / `major` (significant
  inaccuracy) / `minor` (improves UX) / `nit` (typo / wording).

## Output spec
- One `REVIEW` entry on MESSAGE-BUS using the v3 template:
  ```
  Scope: SYNTHESIS.md sections X, Y, Z
  Severity: blocker|major|minor|nit
  Findings: <bulleted list with source refs>
  Tests: <if any new tests would help>
  Recommendation: <action items>
  ```
- For each `blocker` finding, also write a separate `ERROR` entry citing the exact
  evidence (path:lines).
- One `COMPLETE` entry with overall verdict (ship-as-is / minor edits / major rework /
  blocker).

Do NOT modify SYNTHESIS.md or IntelliJ source. Append-only on MESSAGE-BUS.md.
