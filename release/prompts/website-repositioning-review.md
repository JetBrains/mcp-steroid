# Brief — MCP Steroid website repositioning review

You are auditing the **current** MCP Steroid website against a new strategic positioning and proposing **minimum-viable concrete changes**. The goal is a minimalistic, powerful update — not a rewrite.

## The strategic shift

**Today's positioning (what's wrong):** the homepage leads with "Give AI the whole IDE, not just the files" and "20–54% faster on benchmarks." It frames MCP Steroid as an *IntelliJ plugin that gives agents IDE access*, with headless/SaaS as a future phase. This puts the implementation (IntelliJ plugin) in the hero, and stakes the value on a raw speedup number that lives inside a wider AI-productivity-numbers war (see `[~/Work/jonnyzzz-brain/projects/ai-metrics/chapters/00-executive-summary.md]` — METR found a measured **−19% slowdown** while developers *felt* +20% faster; DORA 2024 reported −1.5% throughput per 25%-unit of AI adoption; GitClear measured 4× code-clone growth and doubled churn since Copilot shipped; Veracode measured 2.74× more vulnerabilities in AI-generated code).

**Target positioning:** MCP Steroid is **the way to make your AI Agents understand and manipulate code more effectively with fewer quality errors.** The IntelliJ plugin is implementation, not identity. The value is *reliability* and *less rework on the human side*, not raw speed. The headline should sell the outcome AI Agent users care about — fewer hallucinated edits, closed verify loop, less time you spend cleaning up after the Agent — and let "JetBrains IDE plugin" live one click deeper.

**Three CTAs** (exact wording, must appear on the homepage as the conversion ladder):
1. **Install the plugin and give it a try** (download)
2. **Submit scenarios** (where your AI Agent struggles in your codebase)
3. **Join the PoC** (where we fine-tune MCP Steroid to the needs of your AI Agent)

**Casing rule:** "AI Agent" / "AI Agents" — both words capitalized, always. Current site uses "AI agent" / "AI agents" lowercased; that has to change throughout. Be exact.

## Source materials to read

Current website (the surfaces that need to change or stay):

- `[/Users/jonnyzzz/Work/mcp-steroid/website/layouts/index.html]` — homepage layout (hero, "Why MCP Steroid", benchmarks, "What Your Agent Can Do", "Create Powerful IntelliJ-Driven Skills", PoC)
- `[/Users/jonnyzzz/Work/mcp-steroid/website/content/_index.md]` — homepage frontmatter (minimal)
- `[/Users/jonnyzzz/Work/mcp-steroid/website/content/docs/_index.md]` — docs landing
- `[/Users/jonnyzzz/Work/mcp-steroid/website/content/docs/strategy.md]` — current strategic thesis (phase 1/2/3 arc)
- `[/Users/jonnyzzz/Work/mcp-steroid/website/content/docs/how-it-works.md]` — implementation detail page
- `[/Users/jonnyzzz/Work/mcp-steroid/website/hugo.toml]` — site-wide params + whatsnew
- `[/Users/jonnyzzz/Work/mcp-steroid/website/content/docs/getting-started.md]` — install path
- `[/Users/jonnyzzz/Work/mcp-steroid/website/content/docs/need-your-experiments-and-support.md]` — scenario-submission CTA target

Input research:

- `[~/Work/jonnyzzz-brain/projects/ai-metrics/report.md]` — top-level executive report
- `[~/Work/jonnyzzz-brain/projects/ai-metrics/chapters/00-executive-summary.md]` — perception gap, trust-and-verify, hallucination/fabricated-package rate, GitClear/Veracode/METR/DORA findings
- `[~/Work/jonnyzzz-brain/projects/ai-metrics/chapters/02-post-ai-metrics.md]` — what the AI-era measurement layer actually tracks
- `[~/Work/jonnyzzz-brain/projects/ai-metrics/chapters/05-pre-vs-post-ai-deltas.md]` — what got harder, the methodological crisis

Project anchor for tone:

- `[/Users/jonnyzzz/Work/mcp-steroid/docs/PHILOSOPHY.md]` — the three agent-first design tenets (the writing style here is the target tone)
- `[/Users/jonnyzzz/Work/mcp-steroid/release/notes/0.95.0.md]` — fresh release artifact for current voice

## What to produce

A single memo, **under 700 words**, with exactly these four sections. No preamble.

### 1. Audit (≤200 words)

Where on the current homepage / strategy page does the positioning fail the target most painfully? Cite specific lines / files. Pick the 3–5 worst offenders. Be specific about *which words* leak the "IDE plugin is the product" framing.

### 2. Concrete change list (≤300 words)

For each file you'd change, name the file and write the **exact textual change** you propose. Format:

```
file: <path>
where: <line numbers or section name>
from: "<current text>"
to:   "<proposed text>"
why:  <one-sentence rationale>
```

Constraints:
- **Minimum-viable.** Do not rewrite whole pages. Surgical word-level / sentence-level edits when possible.
- **Three CTAs as a unit.** Whatever shape they take on the homepage (buttons, a 3-step ladder, a section), they must appear together as the conversion path. No separate "Download" hero CTA that lives away from the other two.
- **"AI Agent" casing.** When proposing rewrites, apply the casing rule. Do not list every casing fix individually — `replace_all` is fine — but explicitly flag the rule and the scope of files affected.
- **Headline must change.** "Give AI the whole IDE, not just the files" is the lead and the lead has to deliver the new positioning. Propose one final headline (no list of three; pick).
- **Keep DPAIA benchmarks as evidence, not as the lead.** Reframe what they prove — fewer wasted iterations, not raw speedup.

### 3. What NOT to change (≤100 words)

What on the current site is already on-message and should be left alone? Be specific. Helps prevent over-editing.

### 4. Risks / counter-positions (≤100 words)

What could go wrong with this shift? E.g., audience overlap with file-edit-only agentic-coding tooling, claims that overlap with competitors, or messaging that overpromises on "fewer errors" without data behind it. Be terse. One paragraph max.

## What NOT to do

- Do not write the whole new website. Just the surgical change list.
- Do not invent claims that aren't in the release notes or in the ai-metrics report.
- Do not propose adding new pages unless the user-specified CTAs require it (e.g., a `/poc/` landing page is acceptable if the existing surface doesn't host the PoC CTA cleanly).
- Do not mention specific Claude / Codex / Gemini model versions or capabilities — they date quickly.
- Do not propose "fewer quality errors than competitor X" — we don't have controlled head-to-head data.
- Do not propose emoji, marketing fluff, exclamation marks, or stock CTA verbs ("Unlock", "Supercharge", "Revolutionize").
