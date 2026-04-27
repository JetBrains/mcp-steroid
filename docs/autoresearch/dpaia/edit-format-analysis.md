# How Claude / Codex transmit edits — and where tokens hide

Question from user: *"review how the model is sending the changes, is that really the whole content or a diff format? What are the options to lower the number of tokens, now claude and codex implement the native edit command?"*

Evidence from iter-03/04 DPAIA run dirs (both on `DpaiaMicroshop2Test.claude with mcp`, 8 Edits each) + Codex historical run `run-20260414-154627-dpaia-arena`.

## Claude `Edit` tool — NOT whole file, already a diff

The `Edit` tool_use payload has four keys only:

```
file_path          ~133 chars  (absolute path)
old_string         ~284 chars  (literal text to replace)
new_string         ~415 chars  (replacement)
replace_all        bool
```

It's literally an old/new string pair. No whole file content. No line numbers. Already as compact as a textual-anchor diff.

Per-call payload sizes from the iter-04 Microshop-2 run (8 Edits):

| target                               | old | new | total |
|--------------------------------------|-----|-----|-------|
| ProductServiceImpl.java              | 344 | 521 | 865   |
| ProductCompositeServiceImpl.java     | 540 | 717 | 1257  |
| RecommendationServiceImpl.java       | 444 | 622 | 1066  |
| ReviewServiceImpl.java               | 373 | 551 | 924   |
| ProductServiceApplication.java       | 143 | 227 | 370   |
| ProductCompositeServiceApplication.java | 143 | 227 | 370 |
| RecommendationServiceApplication.java | 143 | 227 | 370  |
| ReviewServiceApplication.java        | 143 | 227 | 370   |
| **sum**                              |     |     | **5 592** |

Key observation: the four `Application.java` edits are **byte-identical** — same
`old_string = "@SpringBootApplication\npublic class …ServiceApplication …"` add,
same `new_string` with `@ComponentScan("shop")` added. The `Edit` format ships
each copy in full = 4 × 370 = 1 480 chars of duplicated content.

## Codex edit strategy

Codex emits `command_execution` items only — it does not have a structured
`Edit` tool. Edits happen through the shell, typically via `apply_patch` heredoc
(OpenAI's CLI wraps a unified-diff applier), `sed -i`, or direct `cat > file
<<EOF`. The payload cost is whatever shell body Codex writes into the command.
Unified diff with minimal context is very compact when you have the right
anchor — but the shell invocation itself adds overhead.

## applyPatch DSL as-shipped — current token cost

```kotlin
applyPatch {
    hunk("/abs/path/A.java",  "oldA", "newA")
    hunk("/abs/path/B.java",  "oldB", "newB")
    …
}
```

Payload = the Kotlin script source sent through `steroid_execute_code.code`.
Per hunk:

  `hunk("` + path + `", "` + old + `", "` + new + `")\n`  ≈ ~25 chars wrapper + fields

For the Microshop-2 8-hunk scenario: ~5 600 chars of script + ~120 chars of
wrapping = ~5 720 chars. **Essentially identical to the 8 × Edit cost.** The
savings are elsewhere: 1 tool-call round-trip vs 8, atomic undo, in-IDE PSI.

## Where the duplication sits — the real optimization target

Four of the eight hunks were the EXACT SAME edit to different files:

  `@SpringBootApplication` → `@SpringBootApplication\n@ComponentScan("shop")`

In the `Edit` chain the agent shipped that text 4 times. In `applyPatch` the
agent also shipped it 4 times (once per `hunk(...)` call). Net duplication
this scenario: 4 × 370 = **1 480 chars of copies** (~26% of the total edit
payload for this run).

## Token-reduction options (for iter-06+, NEGATIVE-metric compliant)

The negative metric rule forbids adding new DSL methods. That means the
options below are sorted from "prompt-only, no API change" to "heavier":

### (A) Prompt-only: teach the "shared pattern" Kotlin idiom — no API change

The `applyPatch { }` lambda body is plain Kotlin. Agents can already loop:

```kotlin
val oldPat = "@SpringBootApplication\npublic class"
val newPat = "@SpringBootApplication\n@ComponentScan(\"shop\")\npublic class"
applyPatch {
    listOf(
        "/abs/A.java",
        "/abs/B.java",
        "/abs/C.java",
        "/abs/D.java",
    ).forEach { hunk(it, oldPat, newPat) }
}
```

Token cost: `old` + `new` ship ONCE + 4 × path ≈ 144+228 + 4×130 = **~892 chars**
for the 4-file Application.java group, versus 4 × 370 = 1 480 chars today.
**~40% cut** for the shared-pattern subset.

This is a **prompt change**, not an API change. Zero DSL surface growth. The
change lives in `ide/apply-patch.md` and `skill/execute-code-tool-description.md`:
add one example that demonstrates the `listOf(...).forEach { hunk(it, old, new) }`
idiom so agents mimic the shape.

### (B) Prompt-only: minimize `old_string` — one more idiom

Shortest unique anchor instead of a multi-line block. Current DSL hunks often
carry `old_string` of 300+ chars for safety; in most cases ~30-60 chars of
unique signature text is enough. Prompt should teach: **shortest unique
anchor wins.**

Risk: non-unique anchors fail pre-flight. Already caught by `ApplyPatchException`.

Token saving: 50–70% of `old_string` size when agents apply the idiom.

### (C) Prompt-only: "if `new_string` only adds, prefer insertion anchor"

For pure additions (`old` is a subset of `new`), the `old_string` is redundant:
the agent could use `new_string = old_string + ADDITION`. Ship only the
`ADDITION` after a known anchor. Not easily done inside the current DSL
without a new method, so this falls under the NO-CHANGE-ALLOWED rule —
deferred.

### (D) API-shape change — forbidden by negative metric

`applyPatch { hunk(paths = listOf(…), old, new) }` — cleanest for shared
patterns, but adds a `hunk(List<String>, String, String)` overload — new
surface area. Don't ship.

### (E) Claude-side: use `MultiEdit` when available

If Claude's toolset exposes `MultiEdit` (batched Edits in one tool call), the
per-call overhead per Edit drops. Our MCP server doesn't control Claude's
native toolset; we can only prompt the agent about what it already has.

## Recommendation for iter-06

Land option (A) + (B) as a single prompt change:

1. `prompts/src/main/prompts/ide/apply-patch.md` — add a "Share the same edit
   across N files" section with the `listOf(paths).forEach { hunk(p, old, new) }`
   idiom and an explicit token-saved comparison.
2. `prompts/src/main/prompts/skill/execute-code-tool-description.md` — extend
   the STOP-before-2nd-Edit section with "when the same old→new applies
   to N files, ship it once in the loop form."
3. Same iter-06 section documents "shortest unique anchor" guidance.

Expected iter-06 effect on Microshop-2 scenario: if Claude adopts the idiom,
8 separate hunks → 2 hunk-groups (4 Applications with shared pattern, 4
ServiceImpls with similar), net ~40% token cut on the edit phase.

Still gated on agents reaching for `applyPatch` in the first place — the
iter-05 `🛑 STOP before 2nd Edit` nudge tests that. If iter-05 succeeds in
converting the 8 Edits → 1 applyPatch call, iter-06 optimizes the contents
of that call.

## Takeaway

Claude's `Edit` tool already sends a compact diff (just old/new pair). There
is no "whole file" fat to trim. The real token waste is **duplicated edits
across N files** — four Application.java edits shipped the same bytes four
times. Both the `Edit` chain AND the naive `applyPatch { hunk; hunk; hunk; … }`
pattern suffer from this; the Kotlin `forEach` idiom fixes it inside the
existing DSL surface.
