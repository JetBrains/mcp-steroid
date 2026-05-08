# A1 — JetBrains official docs & blogs: SSR concepts and templates

You are a **Research agent** (per `THE_PROMPT_v5_research`). Your role is fixed.

## Absolute paths
- PROJECT_ROOT = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro/docs/ssr-research`
- MESSAGE_BUS = `${PROJECT_ROOT}/MESSAGE-BUS.md`
- ISSUES_FILE = `${PROJECT_ROOT}/ISSUES.md`
- THE_PLAN = `${PROJECT_ROOT}/THE_PLAN.md`
- THE_PROMPT_v5 = `${PROJECT_ROOT}/THE_PROMPT_v5.md`
- ROLE = `${PROJECT_ROOT}/THE_PROMPT_v5_research.md`
- BUS_PROTOCOL = `${PROJECT_ROOT}/MESSAGE-BUS-protocol.md`

Read THE_PLAN, ROLE, and BUS_PROTOCOL first.

## Identity
- agent = `claude-research-1`
- runId = the `run_…` directory name your runner created (find via `pwd`/parent of this file)
- taskId = `SSR-A1-DOCS`

## Mission
Build a comprehensive map of what IntelliJ's **Structural Search and Replace** (SSR) is
from the **user-facing perspective**, drawn from JetBrains' own documentation and blog
posts. We need this so the skill articles we ship can mirror the official mental model
and terminology.

Cover:
1. What SSR is, when to use it vs Find/Replace, vs Find Usages, vs intentions/inspections.
2. The pattern syntax: variables (`$x$`), modifiers, repetition (`min`/`max` count), regex
   constraints, type constraints, "this variable is target", expected reference, script
   filter (Groovy filter expression).
3. Every UI surface that exposes SSR:
   - "Search Structurally…" / "Replace Structurally…" actions
   - "Find Usages" extended via SSR
   - "Run Inspection by Name → Structural Search Inspection"
   - Settings → Editor → Inspections → Structural Search & Replace (custom inspections)
   - "Save Template…" and the "Existing templates" gallery
4. Pre-defined templates that ship with IntelliJ (count by language; list 5–10 best-known
   ones per Java and Kotlin).
5. Ways SSR replacements integrate with the formatter and short-class-name imports.
6. Migration use case examples publicly documented (e.g. "migrate JUnit3 to JUnit4",
   "Guava `Lists.newArrayList()` → `new ArrayList<>()`", lambda conversions, deprecation
   replacements).
7. Known limitations called out by JetBrains.

## Sources to consult (FACTs must cite these)
- https://www.jetbrains.com/help/idea/structural-search-and-replace.html (and child pages)
- https://www.jetbrains.com/help/idea/search-templates.html
- https://www.jetbrains.com/help/idea/structural-search-and-replace-examples.html
- https://www.jetbrains.com/help/idea/creating-custom-inspections.html (SSR-based inspections)
- https://blog.jetbrains.com (search "structural search", "ssr", "structurally")
- https://plugins.jetbrains.com (any third-party SSR plugins worth noting)

Use `WebFetch` / `WebSearch` (or your CLI equivalent). For each official help page,
record the URL and a 3–8 line FACT summary. For blog posts older than 2 years, prefer the
most recent version.

## RLM guidance
- Don't read every page in full first. Peek titles → list URLs → write a partition plan
  → fetch the most relevant 8–15 pages → summarise. Cap total fetched content ≤ 40K tokens.
- If you find a JetBrains-published cookbook of SSR patterns, partition by language and
  treat each language section as a sub-task.

## Output spec
Write everything to `MESSAGE_BUS` as `FACT` entries (use the v3 format from BUS_PROTOCOL).
Every FACT entry MUST include at least one source link in `files:` (URL) or inline.

When done, append one `COMPLETE` entry summarising:
- pages fetched (URL list, ≤ 30 entries),
- "must-cite" canonical sources for the skill article,
- gaps (anything we'd want to confirm against IntelliJ source — leave to other agents),
- recommended skill-article terminology to align with JetBrains wording (1-pager).

Do NOT modify any file outside `${PROJECT_ROOT}`.
Do NOT modify code in the mcp-steroid repo. Research only.
