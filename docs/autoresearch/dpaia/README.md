# DPAIA autoresearch — applyPatch optimization

Optimize the `applyPatch { hunk(...) }` DSL against real DPAIA arena
scenarios, using quantified metrics extracted from each agent run.

## Metrics system

`metrics.py` reads a DPAIA run directory (the folder containing the agent's
`agent-<name>-<N>-raw.ndjson` file) and emits structured metrics on four axes
the user asked for:

| axis | metric(s) |
|---|---|
| **tokens cost** | input / output / cache_read / cache_creation / total (sum of assistant.usage across the NDJSON) |
| **complexity** | steroid_execute_code call count + Kotlin-line stats (total, avg, p50, max) |
| **ease of use** | fetch_resource call count (indicates searching for help), native `Edit` call count (indicates bypassing the IDE), exec_code retry count (heuristic: same opening 80 chars), native `Bash` call count |
| **errors** | count of `is_error=true` tool_result blocks, plus whether `applyPatch`/`hunk(` appeared in any script (DSL adoption signal) |
| **DSL surface area (negative)** | `dsl_methods_added_vs_baseline` — count of suspend methods / member vals on `McpScriptContext` beyond the primitive baseline. Adding a new DSL method (e.g. `applyPatch`) is a **cost**: agents learn more surface, we carry more tests, the prompt footprint grows. Iteration must NOT add methods unless prompt-only fixes have been exhausted. Run `python3 metrics.py --dsl-methods` at iteration-close to report current count. |

Run:

```bash
# single run, pretty JSON
python3 docs/autoresearch/dpaia/metrics.py <run_dir>

# many runs, CSV for summary.md
python3 docs/autoresearch/dpaia/metrics.py --csv run-*
```

Output schema is stable so iter-to-iter deltas can be diffed mechanically.

## iter-00 baseline (88 Claude-MCP runs, historical data)

CSV at `iter-00/baseline.csv`. Aggregate across all 88 runs:

| metric | value |
|---|---|
| tokens_total (avg / run) | ~4.2 M |
| tokens_output (avg / run) | 1 381 |
| steroid_execute_code calls (avg) | 2.1 |
| exec_code Kotlin lines (avg / call) | 14.7 |
| native `Edit` calls (avg) | 8.7 |
| fetch_resource calls (avg) | **0.0** |
| errors (avg) | 1.1 |
| applyPatch-called (count, total) | **0 / 88** |

The two red flags this baseline surfaces:

1. **4:1 bias toward native `Edit`** — agents reach for the built-in tool
   that bypasses the IDE in most edit situations (8.7 vs 2.1 per run).
2. **Zero skill-guide reads** — `fetch_resource_calls = 0` across every
   single run. Whatever is in `mcp-steroid://skill/*` is invisible to
   agents in practice.

The `applyPatch` DSL addresses both directly: it's auto-available on the
script context (no fetch needed) and it gives one `steroid_execute_code`
call the same ergonomics an agent would otherwise split across N `Edit`s.

## Loop plan — 10 iterations

Each iteration:

1. Pick one DPAIA scenario (start with cheaper ones — petclinic-rest-37,
   microshop-2).
2. Run both agents with `--with mcp` flavour:
   - `./gradlew :test-experiments:test --tests '<DpaiaXxx>.claude with mcp' --rerun-tasks`
   - `./gradlew :test-experiments:test --tests '<DpaiaXxx>.codex with mcp' --rerun-tasks`
3. Extract metrics from the fresh run dirs with `metrics.py --csv …`.
4. Diff vs iter-(N-1):
   - Did `applyPatch_called` / `hunks_estimate` rise?
   - Did `native_edit_calls` fall?
   - Did `exec_code_lines_avg` converge to the DSL's ~5-line floor?
   - Did `errors` / `retries` fall?
5. Read the `agent-*-decoded.txt` for the top friction moment (highest-
   token or longest-script turn). Identify what made the agent stumble
   — pattern not discovered, example too terse, etc.
6. Apply ONE narrow prompt change (recipe or tool-description).
7. Commit as `autoresearch(dpaia) iter-NN: <short signal>`.

Each iteration targets one DPAIA scenario and one prompt change. Budget
~30–60 min per iteration (Docker IDE container + agent run + analysis).

## Non-goals

- Not running all 10 iterations in one session — each is a separate
  commit with its own evidence.
- Not optimizing for metrics that aren't bottlenecks — apply-patch is
  the focal point; generic token efficiency is a secondary metric.

## Files

```
iter-00/
  baseline.csv           # metrics.py --csv output for 88 historical runs
iter-NN/
  claude.json            # per-agent metrics (one per DPAIA scenario)
  codex.json
  friction.md            # observations + planned iter-(N+1) change
```
