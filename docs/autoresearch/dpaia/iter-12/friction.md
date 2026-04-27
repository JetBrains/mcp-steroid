# iter-12 — Microshop-2 (4-service forEach target) — same timeout pattern

Same scenario the forEach idiom was designed for: 4 `*Application.java`
files getting identical `@ComponentScan("shop")` annotation, plus
matching validation logic across 4 service impls.

## Result

| metric                | iter-03/04 (old prompt) | iter-12 (iter-09 prompt) |
|-----------------------|--------------------------|---------------------------|
| applyPatch called     | no                       | **yes** (8 hunks attempted) |
| native_edit_calls     | 8                        | 8                         |
| mcp_steroid_calls     | 2                        | 2                         |
| mcp_share             | 0.04-0.05                | 0.045                     |
| errors                | 1                        | 1 (timeout)               |
| Fix success           | yes                      | yes                       |

Claude's applyPatch reason: `"Apply all patches: @ComponentScan to 4
Application classes + validation to 4 service impls"` — **exactly** the
forEach-idiom target shape.

## The applyPatch ran to ~37s kotlinc compile, script started, then client timeout

IDE log `productid-validation` timeline:
- 07:10:21 Starting execution (Apply all patches)
- 07:10:58 ScriptExecutor.Starting (after 37 s of kotlinc compile)
- **NO completed entry** — execution still running when Claude client
  disconnected at the 60s mark and fell back to 8 × `Edit`.

So server-side: applyPatch was mid-execution. The file mutations may or
may not have landed before Claude's disconnect; the subsequent 8 × Edit
on the same files succeeded, so either applyPatch didn't finish OR
Edit's `old_string` matched independently of applyPatch's outcome.

## Consistent pattern across iter-09 / iter-10 / iter-12

| iter     | scenario       | compile time | script outcome       | Claude action       |
|----------|---------------|--------------|----------------------|---------------------|
| iter-09  | PetRest3       | 32 s (25 hunks) | ran to ~60s, timeout | 1 × Edit fallback   |
| iter-10  | FS125          | 32 s (5 hunks)  | script exec began → timeout | 7 × Edit fallback   |
| iter-12  | Microshop-2    | 37 s (8 hunks)  | script exec began → timeout | 8 × Edit fallback   |

Key insight: **kotlinc compile time of a 30-110 line applyPatch script
is ~30-40s regardless of hunk count** (dominated by kotlinc parse/type-
check overhead, not by string-literal payload size). That leaves 20-30 s
for dialog killer + script execution before Claude's 60s fires.

## Why it's hard to beat with a prompt-only fix

Every applyPatch call on Claude gets about **3 seconds** of headroom
after kotlinc + dialog kill. Script body itself is <1 s for 26 hunks
(local stress-test: 60 ms). So the entire user-visible cost is kotlinc,
and the prompt can't shrink kotlinc.

Prompt-side workarounds ("split into smaller calls", "keep script
<30 lines") don't help — every applyPatch call eats the same compile
overhead. Two 4-hunk calls cost 2 × 37s = 74s, worse than one 8-hunk
call.

## Real fix requires infrastructure

The three candidate infrastructure fixes, ranked by scope:

**(A) Send MCP `notifications/progress` during kotlinc compile** from
`CodeEvalManager.kt`. MCP protocol standard; Claude's client *may*
reset its per-tool timer per progress event. Cost: ~20 lines in
CodeEvalManager + McpProgressReporter plumbing. Risk: Claude might not
honor progress-based timer reset (empirical check needed).

**(B) Pre-warm kotlinc compilation for the `applyPatch { … }` shape.**
Ship a pre-compiled receiver DSL, with `hunk(...)` specs serialised as
data. Kotlinc compile avoided entirely. Cost: new scripting path in
ScriptExecutor. Crosses into "new engine code" territory.

**(C) Dedicated `steroid_apply_patch` MCP tool at the server layer.**
No kotlinc involvement at all. Cost: ~100 lines of server code; adds
MCP-tool surface (not McpScriptContext surface, so negative metric
unaffected).

## iter-13 plan

Land (A): MCP progress notifications from CodeEvalManager during
compile. Lowest risk, reuses existing progress plumbing. If Claude does
reset on progress, applyPatch completes. If not, fallback to (C) in
iter-14.

## DSL surface area

`dsl_methods_added_vs_baseline = 1`. Unchanged.
