# DPAIA Arena: Update Test Code for Auto-Comparison Metrics

You are an **implementation agent**. Your goal: modify the arena test infrastructure to automatically write per-run comparison data (including token usage and cost) after each scenario completes, so future iterations don't need manual data collection.

## Context

Currently, arena test runs produce:
- A JSON result file (`dpaia-arena-run-<id>-claude-mcp.json`) with basic metrics
- A raw NDJSON log (`agent-claude-code-1-raw.ndjson`) with per-message token usage
- A decoded text log (`agent-claude-code-1-decoded.txt`) with human-readable tool calls

**Problem**: The JSON result file is missing key metrics needed for comparison:
- `exec_code_calls` (added recently but missing in older runs)
- Token usage (input, output, cache_create, cache_read)
- Estimated cost
- Number of turns
- Per-tool-type call counts (steroid_execute_code, Bash, Read, Write, Glob, Grep)

These must be extracted manually from NDJSON/decoded logs, making iteration slow.

## Data Sources

- **Test runner**: `/Users/jonnyzzz/Work/mcp-steroid/test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaTestRunner.kt`
- **Scenario base test**: Search for `DpaiaScenarioBaseTest` or `ArenaScenarioBaseTest` in test-experiments
- **Agent session infrastructure**: `/Users/jonnyzzz/Work/mcp-steroid/test-integration/src/main/kotlin/com/jonnyzzz/mcpSteroid/integration/infra/`
- **Claude output filter**: Search for `ClaudeOutputFilter` — parses NDJSON events
- **Result JSON writer**: Search for where `dpaia-arena-run-*.json` is written

### Token Usage in NDJSON

Each `assistant` message in the raw NDJSON has a `usage` object:
```json
{
  "type": "assistant",
  "message": {
    "usage": {
      "input_tokens": 48,
      "cache_creation_input_tokens": 12470,
      "cache_read_input_tokens": 10957,
      "output_tokens": 392,
      "service_tier": "standard"
    }
  }
}
```

The final event is `type=result` with:
```json
{
  "type": "result",
  "duration_ms": 145064,
  "duration_api_ms": 128366,
  "num_turns": 17
}
```

### Cost Calculation (Sonnet 4.6)

```
cost = (input_tokens * 3 + output_tokens * 15 + cache_creation * 3.75 + cache_read * 0.30) / 1_000_000
```

## Instructions

### Step 1: Find the JSON result writer

Search for where `dpaia-arena-run-*.json` is written. This is likely in the test base class or ArenaTestRunner. Use MCP Steroid (`steroid_execute_code` on project "mcp-steroid") with `FilenameIndex` to find the file.

### Step 2: Add NDJSON token parsing

After the agent run completes and the raw NDJSON log exists, parse it to extract:
- Total `input_tokens` (sum across all assistant messages)
- Total `output_tokens` (sum across all assistant messages)
- Total `cache_creation_input_tokens`
- Total `cache_read_input_tokens`
- `num_turns` from the final `result` event
- `duration_api_ms` from the final `result` event

### Step 3: Add tool call counting from decoded log

Parse the decoded log to count:
- `exec_code_calls`: lines matching `steroid_execute_code (`
- `bash_calls`: lines matching `>> Bash (`
- `read_calls`: lines matching `>> Read (`
- `write_calls`: lines matching `>> Write (` or `>> Edit (`
- `glob_calls`: lines matching `>> Glob (`
- `grep_calls`: lines matching `>> Grep (`

### Step 4: Extend the JSON result schema

Add these fields to the result JSON:
```json
{
  "...existing fields...",
  "exec_code_calls": 3,
  "bash_calls": 10,
  "read_calls": 16,
  "write_calls": 1,
  "glob_calls": 4,
  "grep_calls": 2,
  "num_turns": 17,
  "total_input_tokens": 48,
  "total_output_tokens": 392,
  "total_cache_creation_tokens": 106367,
  "total_cache_read_tokens": 1067297,
  "duration_api_ms": 128366,
  "estimated_cost_usd": 0.7251
}
```

### Step 5: Write a comparison CSV

After writing the JSON, also append a line to a CSV file at `test-experiments/build/test-logs/test/arena-comparison.csv`:

```csv
timestamp,instance_id,pass_label,agent_claimed_fix,duration_s,exec_code_calls,bash_calls,read_calls,write_calls,num_turns,total_input_tokens,total_output_tokens,total_cache_tokens,estimated_cost_usd,tests_pass,tests_run
```

The `pass_label` should be a system property or env var (`-Darena.pass.label=pass1`) so the runner script can tag each pass.

### Step 6: Verify

Run one arena test to verify the new fields are written:
```bash
./gradlew :test-experiments:test --tests '*DpaiaSpringBoot33Test.claude with mcp' --rerun-tasks
```

Check the JSON output has the new fields.

## Constraints

- Use MCP Steroid (`steroid_execute_code` on project "mcp-steroid") to navigate the codebase
- Do NOT change test behavior — only add metric collection AFTER the test completes
- Do NOT break existing JSON consumers — only ADD new fields
- Use kotlinx.serialization or simple JSON building (follow existing patterns)
- Commit changes with a descriptive message
- Append to `{{MESSAGE_BUS}}`:
```
METRICS: extended result JSON with token_usage, tool_counts, estimated_cost
METRICS: added arena-comparison.csv auto-writer
```
