# iter-01 friction analysis — DpaiaPetclinicRest37.claude with mcp

Fresh run dir: `run-20260422-230121-dpaia__spring__petclinic__rest-37-mcp`.
iter-01's plugin snapshot pre-dated the "flip anti-MCP bias" prompt changes
(2d4af091), so this is a pure measurement of the applyPatch DSL being
available without pro-MCP prompting.

## Metrics

- **mcp_share: 0.091** (baseline 0.065 mean, 0.200 max) — tiny bump.
- exec_code_calls: **1** (one discovery call, 17 lines)
- native calls:
  - Read: 2  (code discovery)
  - Grep: 2  (code discovery)
  - Edit: 2  (actual feature implementation)
  - Bash: 3  (`./mvnw test` variants)
- applyPatch_called: **false** — the DSL was available in the tool description
  but the agent did not reach for it.
- fetch_resource_calls: 0
- tokens_total: ~694 k (dominated by cache_read 618 k)
- errors: 0

## Top friction moments (in order of call count)

1. **Maven test runs — 3× `./mvnw test`** via Bash.
   The old tool description said "Do NOT use Bash `./mvnw test`" but pointed
   at a separate resource for the IDE pattern; the agent had to fetch that
   guide to even know where to start, and `fetch_resource_calls = 0` so the
   guide stayed unread. `./mvnw test -Dtest=X -Dspotless.check.skip=true` is
   a single line the agent already knows how to write.

2. **Single-method add — 2× `Edit`** for the pagination endpoint.
   applyPatch DSL would have fit (single hunk), but for a 1-file 1-site edit
   the ergonomic delta vs `Edit(old, new)` is marginal.

3. **Read+Grep — 4 calls total** for code exploration.
   Inside one `steroid_execute_code` call the agent could read the test +
   controller + service with `findProjectFile(...).contentsToByteArray()`.
   But streaming the file contents back to the agent has limits, so this
   may stay with native tools.

## iter-02 prompt change (commit 2d4af091 + follow-up)

1. Flip `skill/coding-with-intellij.md` from "use native X" to "IDE-first":
   applied in 2d4af091.
2. Inline Maven-runner recipe in `skill/execute-code-tool-description.md`
   so agents see it on first tool call — applied just now.
3. "Default to `steroid_execute_code` for every code-touching step" nudge
   at the top of the tool description — applied just now.

## Expected iter-02 delta

- `Bash` count: 3 → 1 (IDE Maven runner handles at least 2 of the 3 test
  invocations).
- `exec_code_calls`: 1 → 3 (one for Maven-test run, plus the existing
  discovery pattern).
- `mcp_share`: 0.091 → ≥0.30 on the same scenario.
- Risk: tool description grew ~15 lines with the Maven recipe — token
  cost per exec_code call creeps up. Monitor cache_read_input_tokens.

