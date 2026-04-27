# iter-06 — scenario swap confirms the pattern is agent-wide

Run: `run-20260422-234402-dpaia__feature__service-125-mcp` (Claude with MCP on
DpaiaFeatureService125 — NAVIGATE_MODIFY + MCP HIGH curation).

|                     | Microshop2 iter-05 | FeatureService125 iter-06 |
|---------------------|--------------------|---------------------------|
| mcp_share           | 0.026              | **0.027** |
| exec_code_calls     | 1                  | 2 |
| Edit                | 8                  | **7** |
| Bash                | 7                  | 23 |
| Read                | 17                 | 33 |
| applyPatch_called   | false              | false |
| total tokens        | 1.8 M              | **8.0 M** |
| total calls         | 38                 | 73 |
| errors              | 0                  | 1 |

## Key finding — pattern is NOT scenario-specific

Two completely different DPAIA cases (Spring Boot Microshop with 4 symmetric
microservices vs FeatureService125 with a release-management endpoint add)
both show:
- `mcp_share` parked at 0.025–0.028
- 7–8 native `Edit` calls in the edit phase
- Zero applyPatch invocations
- Zero `fetch_resource` calls (agent never reads skill guides)
- Claude happily makes 23 Bash calls (mostly `find`, `./gradlew test`)

Prompt iterations (02 anti-bias, 04 decision-tree-top, 05 STOP-signal,
06 forEach-idiom + scenario swap) collectively moved discovery behaviour
(-18 Bash for Microshop2; FeatureService125 still has lots because it's
a bigger project). They did NOT move edit behaviour.

## What this means

Claude's MCP tool selection for edits is driven by its trained prior, not
by our tool-description text. The iter-02/04/05/06 tool-description grew
~60 lines trying to reframe "prefer applyPatch"; Claude still picked `Edit`.

Information framing has an upper bound. iter-07+ needs a different lever.

## Options for iter-07 (NEGATIVE metric still in force)

Negative metric: `dsl_methods_added_vs_baseline` must not grow. That
covers `McpScriptContext.kt` additions. The following options do NOT
add to that count:

1. **`steroid_apply_patch` as a dedicated MCP tool.** Surfaces at
   Claude's tool-selection layer next to `Edit`. Agent sees it in the
   tool list; doesn't have to remember a recipe. But it's still a NEW
   tool — it adds surface at the MCP layer even if it doesn't grow the
   DSL surface. Needs user sign-off if negative metric is interpreted
   strictly.

2. **Hide native Edit from Claude's tool menu.** MCP doesn't control
   the client's native tools. Not actionable from our side.

3. **Measure Codex instead of Claude.** Codex has no structured Edit
   tool — edits go through shell `command_execution`. Running the same
   scenario under Codex-with-mcp might produce very different
   mcp_share, since Codex has no comfortable "chain of Edits"
   default. This is MEASUREMENT, not optimization, but it tests
   whether the prior is agent-specific.

4. **Accept the ceiling and optimize for adjacencies** — focus iter-07+
   on the ~30% of calls we CAN move: Read, Glob, Grep batching. The
   `native_edit_calls` metric stays stuck at 7–8 per scenario, but
   overall total calls drops as agents batch the discovery phase into
   fewer `exec_code` calls.

iter-07 plan: **measure Codex on the same FeatureService125 scenario** to
characterize whether this prior is Claude-specific. That's one DPAIA run
and gives us a comparison data point before making a bigger bet.

## DSL surface area

dsl_methods_added_vs_baseline = 1 (applyPatch). Unchanged.
