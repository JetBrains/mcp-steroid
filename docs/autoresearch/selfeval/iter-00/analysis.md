# iter-00 analysis → iter-01 plan

## What iter-00 surfaced

Claude's section-1 classification:
- **(a)** atomic cross-file refactoring, type hierarchy, structural overview,
  external-dep navigation, code intelligence, IDE operations — strong wins.
- **(b)** small single-file edits (1–5 lines), single-file private rename,
  insert-at-anchor method creation — Edit/Grep beat `steroid_execute_code`.
- **(c)** non-code files, free-text search, shell, file-pattern matching — built-in only.

Section-4 token analysis pins the category-(b) cost precisely: for a 1-3 line
edit, the agent expects ~500 bytes of Kotlin script boilerplate against ~100
bytes for `Edit`. ~4× payload for zero semantic gain.

## Top gap

The quick-reference table in `skill/coding-with-intellij.md` already steers
agents off `steroid_execute_code` for file **creation** (Write tool) and for
**reads / greps / directory ops** — but it says nothing about small **edits**
or about **single-file private renames**. The category-(b) finding is therefore
predictable: the agent doesn't know the threshold rule, so when it enumerates
MCP Steroid's offering for small edits it projects the heavy PSI script path.

## iter-01 edit (one narrow change)

Add two rows to the existing "Operations that do NOT need steroid_execute_code"
table in `prompts/src/main/prompts/skill/coding-with-intellij.md`:

1. **Small in-place edit (1–3 lines) → Edit tool** — cite the ~4× payload
   difference the iter-00 agent observed; name the exception (needs type-aware
   reasoning like a cross-file rename).
2. **Single-file private rename → Grep + Edit(replace_all=true)** — pin the
   threshold: use PSI rename only when the symbol has cross-file references.

Each row includes a concrete crossover condition so the agent has a rule, not
a slogan.

## What we expect iter-01 to change in the next report

- Section 1 (b) list shrinks — small-edit + private-rename should no longer
  appear as "applies but no improvement", because the prompt now explicitly
  routes them to built-ins (so the agent reports them under category (c)
  "outside scope" or drops them entirely).
- Section 4 token analysis should converge on the same crossover numbers but
  in a shorter paragraph (less speculation, more rule-based).
- Section 9 practical usage rule should echo the two new thresholds.

No change to the EVAL_REPORT framing, section count, or the verbatim Serena
prompt body.
