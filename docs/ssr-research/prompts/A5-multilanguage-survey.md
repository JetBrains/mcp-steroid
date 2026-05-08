# A5 — Multi-language SSR profile survey

You are a **Research agent** (per `THE_PROMPT_v5_research`). Your role is fixed.

## Absolute paths
- PROJECT_ROOT = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro/docs/ssr-research`
- MESSAGE_BUS = `${PROJECT_ROOT}/MESSAGE-BUS.md`
- ROLE = `${PROJECT_ROOT}/THE_PROMPT_v5_research.md`
- IJ_ROOT = `/Users/jonnyzzz/Work/intellij`

## Identity
- agent = `codex-research-5`
- runId = your `run_…` directory name
- taskId = `SSR-A5-MULTILANG`

## Mission
Survey **every other language SSR profile** that ships in the IntelliJ codebase and
state, per language, whether SSR is supported, where the profile lives, and what's
distinctive about the patterns/replacement rules. We need a coverage matrix for the
top-level skill article.

Languages to confirm or refute (in priority order):
1. Groovy
2. JavaScript / TypeScript / JSX / TSX
3. Python
4. Go
5. PHP
6. SQL (DataGrip / DBE)
7. C# / .NET (Rider — note: ReSharper has its own SSR engine, document the difference)
8. Ruby (likely **no** SSR profile — confirm)
9. Rust (likely **no** SSR profile — confirm)
10. HTML
11. XML
12. CSS
13. JSP
14. Flex/ActionScript
15. YAML / JSON / Markdown / properties (likely **no** — confirm)
16. Scala (third-party? confirm)

For each: produce a single FACT with a fixed schema (see Output spec).

## Sources
- `find ${IJ_ROOT} -maxdepth 7 -type d -iname "*structural*search*"` is the master index.
- For each candidate language, locate the **profile class** with
  `rg "extends StructuralSearchProfile" ${IJ_ROOT} -l` and cross-reference the FQN.
- ReSharper / Rider .NET SSR: see `${IJ_ROOT}/dotnet/Psi.Features/src/Features/StructuralSearch/`
  — note this uses ReSharper engine, NOT the IntelliJ platform `StructuralSearchProfile`
  extension. Document this nuance prominently.
- Test data under each language's `testData/structuralsearch` is gold for realistic
  patterns; pick 1–2 real patterns per language to quote.

## RLM guidance
- Don't read profile sources end-to-end. For each language, cap at:
  - profile class FQN + path:lines
  - 3 lines of distinctive override
  - 1–2 example patterns from testData (with file path:lines)
- Use parallel `rg` calls grouped per language directory.
- If a language doesn't have a profile, write `state: NOT_SUPPORTED, evidence: <neg.>`.

## Output spec — one FACT per language using this schema

```
language: <name>
state: SUPPORTED | NOT_SUPPORTED | THIRD_PARTY_ENGINE | UNKNOWN
profile_class: <FQN> or n/a
module_path: <abs path under IJ_ROOT> or n/a
distinctive: <1–3 lines on what makes patterns/replacements special for this language>
example_pattern: <quoted, ≤6 lines>
example_pattern_source: <abs path:lines>
gaps: <1 line; e.g. "no replace support", "no script filter">
```

Plus:
- One FACT containing a **coverage matrix table** of all languages above, columns:
  language | state | replace? | script_filter? | predefined_templates? | gotchas.
- COMPLETE entry summarising which languages we should write skill sub-articles for and
  which we should mention only in the overview.

Do NOT modify code. Research only.
