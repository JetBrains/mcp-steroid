# Marketing committee brief — MCP Steroid landing, iteration 2

You are sitting on a marketing committee. The MCP Steroid landing page just went through repositioning iteration 1 (audit memo in your run history if relevant). The user is reviewing the live page locally and asks the committee for **specific copy + structural fixes** in five areas. Be concrete: file paths, line numbers, exact strings.

Read the current state first:

- `[/Users/jonnyzzz/Work/mcp-steroid/website/layouts/index.html]` — the entire homepage
- `[/Users/jonnyzzz/Work/mcp-steroid/website/hugo.toml]` — site title / description
- `[/Users/jonnyzzz/Work/mcp-steroid/website/content/docs/strategy.md]` — strategy page (already updated)
- `[/Users/jonnyzzz/Work/mcp-steroid/release/prompts/0.95.0-social-posts.md]` — voice reference, fresh
- `[/Users/jonnyzzz/Work/mcp-steroid/docs/PHILOSOPHY.md]` — tone target

Five issues to fix. For each, list specific concrete proposals — exact wording where possible.

## Issue 1 — Primary CTA is too long and the row overflows

The hero currently has **five visible buttons** in two rows:

Row 1 (cta-ladder): `Install the plugin and give it a try` (primary) · `Submit scenarios` · `Join the PoC`
Row 2 (cta-secondary): `Watch Demos` · `GitHub`

On the rendered page the long primary label crowds row 1; the row visually breaks against the next-row buttons and the whole CTA block looks crowded. Five buttons is too many. Propose:

a. A **shorter primary CTA label** — 2–3 words, max two visual lines. Drop "Install the plugin and give it a try" verbatim, but **keep the meaning** (an install action that scans as primary). At least three candidates ranked by your preference.
b. A **consolidation plan**: how many buttons land in the hero, in what hierarchy. Should `Watch Demos` / `GitHub` move out of the hero entirely (e.g. into a top-nav row or a small icon strip)? Or should the three-CTA ladder collapse to two (install + one secondary)?
c. Mobile + desktop fit: how does your proposal wrap on narrow viewports?

## Issue 2 — Two distinct buyer flows are not visible

The site today reads as one undifferentiated flow. The user wants the homepage to address **two audiences explicitly**:

- **Bottom-up**: individual developers, hobbyists, agent-curious engineers — "cool plugin to run with my Claude / Codex / Gemini, locally, today." CTA = Install.
- **Top-down**: companies, engineering leaders, AI-platform teams — "we run agents at scale and want fewer rework cycles across our engineering org." CTA = PoC engagement.

Propose:

a. **Where each flow appears** on the homepage (above-fold, mid-page, footer? a split-row section under the hero? a horizontal "for developers / for teams" pair of cards?).
b. **Exact copy** — one-paragraph value prop for each audience, including the verb each one wants to hear (Install vs. Talk to us / Book a PoC / Pilot).
c. **CTA mapping** — which of the existing CTA buttons (or new ones) anchors each flow.

Do not duplicate the bullet list inside the existing "What Your Agent Can Do" section — keep that as a generic capabilities recap.

## Issue 3 — "Why MCP Steroid" needs to lead with "any AI Agent" and align with iteration 1

The two cards under "Why MCP Steroid" today read:

- **Fewer wasted iterations** — "AI Agents finish semantic tasks in fewer attempts when they can use real refactorings, inspections, and the debugger instead of guessing through file edits. Measured on selected DPAIA projects."
- **Closed verify loop** — "Your AI Agent runs inspections, the build, and the tests through the same IDE that humans use to verify their own work — so the Agent catches its mistakes before you have to."

The user wants this section to **first** state plainly that MCP Steroid works with *any* MCP-capable AI Agent (Claude, Codex, Gemini, Cursor, OpenCode, any MCP client — not just one vendor's), then revisit the two cards so they fit the iteration-1 positioning (reliability / less rework, not raw speed). Propose:

a. A **one-line agnosticism statement** to sit immediately under the "Why MCP Steroid" H2, before the two cards.
b. Updated card titles + bodies (3 cards if you think a third belongs — but only if you can justify the count).
c. Where the existing agent-badge strip (`Claude` `GPT` `Gemini` `Codex CLI` `Cursor` `OpenCode` `Any MCP Client`) currently in the "What Your Agent Can Do" section should live now — same place, or moved up under "Why MCP Steroid" to reinforce the agnosticism point?

## Issue 4 — Sponsorship / supporters section

The user is looking for **support of the project, its development, and the related research** — specifically: **tokens, hardware, tools**. They want this written into the site but **not catchy / not hard-selling**. Understated. Propose:

a. **Where** on the homepage this lives (likely a paragraph inside the existing "Proof-of-Concept & Support" section, or a new small section below it).
b. **Exact copy** — one short paragraph (50–80 words) covering "we accept support in the form of compute tokens, hardware, and tooling, in addition to GitHub Sponsors." No exclamation marks, no "Help us change the world" framing, no emojis. Reads like a footnote, not a campaign.

## Issue 5 — Casing leak audit

Quick sweep: are there any places on the homepage or strategy page still using lowercase "AI agent(s)" / "agent" that should now read "AI Agent(s)"? List any leftovers by file:line.

## Output format

A single memo, **under 800 words**, structured as five numbered sections matching the five issues. Lead each section with your top recommendation in one sentence; supporting detail below. Final line: a one-sentence overall verdict (ship as-is / ship with these specific edits / rework one of the five issues further).

## What NOT to do

- Do not rewrite the whole homepage. Surgical edits only.
- Do not propose adding any new heavy assets (testimonial widgets, social proof carousels, animated hero videos).
- Do not propose emoji, marketing fluff, exclamation marks, or stock verbs ("Unlock", "Supercharge", "Revolutionize").
- Do not invent claims that aren't in the release notes or the existing site copy.
- Do not duplicate iteration-1 advice. The H1, tagline, benchmark reframe, strategy page lead are already in place — you can flag if any is still wrong, but assume the iter-1 decisions stand unless you have a new specific reason.
