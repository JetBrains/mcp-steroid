# DPAIA Arena: Build Comparison Table

You are a **data collection agent**. Your role: collect metrics from all DPAIA arena test runs and produce a structured comparison table.

## Data Sources

### JSON Result Files
`/Users/jonnyzzz/Work/mcp-steroid/test-experiments/build/test-logs/test/dpaia-arena-run-dpaia__*-claude-mcp.json`

Each file contains: `instance_id`, `agent_duration_ms`, `agent_claimed_fix`, `exit_code`, `exec_code_calls` (may be missing in older runs), `read_calls`, `write_calls`, `bash_calls`, `tests_run`, `tests_pass`, `tests_fail`, `build_success`, `agent_summary`, `timestamp`.

### Run Directories
`/Users/jonnyzzz/Work/mcp-steroid/test-experiments/build/test-logs/test/run-*-mcp/`

Each contains:
- `agent-claude-code-1-decoded.txt` — human-readable tool call trace
- `agent-claude-code-1-raw.ndjson` — raw NDJSON events
- `analysis.md` — analysis report (pass 1 only)

### Run Directory Naming
Format: `run-YYYYMMDD-HHMMSS-dpaia__<scenario>-mcp`
- **Original baseline runs**: `run-20260414-*` and `run-20260415-0[0-5]*` (before 05:14 UTC)
- **Pass 1 runs**: `run-20260415-07*` and later (after 05:14 UTC, when the 3-pass script started)
- **Pass 2/3 runs**: later timestamps (may not exist yet)

### Decoded Log Metrics
For runs where `exec_code_calls` is missing from JSON, count from the decoded log:
```
grep -c "steroid_execute_code (" <decoded_log>
```

## Instructions

1. **List all run directories** sorted by timestamp. Group them into:
   - Original baseline (before 3-pass script)
   - Pass 1, Pass 2, Pass 3

2. **For each scenario in each pass**, extract:
   - `instance_id` (scenario name)
   - `agent_duration_ms` (converted to seconds)
   - `agent_claimed_fix` (true/false)
   - `exec_code_calls` (from JSON or decoded log count)
   - `bash_calls`
   - `read_calls`
   - `write_calls`
   - `tests_pass` / `tests_run`
   - Key Bash commands used (extract from decoded log: `grep ">> Bash" <log> | head -20`)

3. **Build the comparison table** in this format and write to `docs/dpaia-arena-comparison-table.md`:

```markdown
# DPAIA Arena — Comparison Table

Generated: <timestamp>

## Per-Scenario Comparison

| Scenario | Orig dur | P1 dur | P2 dur | P3 dur | Orig ec | P1 ec | P2 ec | P3 ec | Orig bash | P1 bash | P2 bash | P3 bash |
|----------|---------|--------|--------|--------|---------|-------|-------|-------|-----------|---------|---------|---------|
| springboot3-3 | 154s | 146s | ? | ? | 4 | 2 | ? | ? | 6 | 5 | ? | ? |
...

## Aggregate Metrics

| Metric | Original | Pass 1 | Pass 2 | Pass 3 |
|--------|----------|--------|--------|--------|
| Pass rate | 17/17 | ?/17 | ?/17 | ?/17 |
| Mean duration | 445s | ? | ? | ? |
| Total exec_code | ~55 | ? | ? | ? |
| Total Bash | ~186 | ? | ? | ? |
| Bash:exec_code ratio | ~3.4x | ? | ? | ? |
```

4. **Fill in `?` with actual data** from the runs. Use `-` for runs that don't exist yet.

5. **Compute delta columns** showing percentage improvement from original to each pass.

6. **Append summary to message bus** at `{{MESSAGE_BUS}}`:
```
COMPARISON: pass1_complete=<N>/17 pass2_complete=<N>/17 pass3_complete=<N>/17
COMPARISON: pass1_mean_dur=<Xs> pass1_mean_bash=<N>
```

## Scenario List (17 scenarios)

```
dpaia__empty__maven__springboot3-3
dpaia__feature__service-125
dpaia__empty__maven__springboot3-1
dpaia__feature__service-25
dpaia__spring__petclinic__rest-14
dpaia__spring__petclinic-36
dpaia__jhipster__sample__app-3
dpaia__train__ticket-1
dpaia__train__ticket-31
dpaia__spring__boot__microshop-18
dpaia__spring__boot__microshop-2
dpaia__spring__petclinic-27
dpaia__spring__petclinic__rest-3
dpaia__piggymetrics-6
dpaia__spring__petclinic__microservices-5
dpaia__spring__petclinic__rest-37
dpaia__spring__petclinic-71
```

## Constraints

- Read-only: do NOT modify test code or run configurations
- Write output ONLY to `docs/dpaia-arena-comparison-table.md` and `{{MESSAGE_BUS}}`
- If a pass hasn't started yet, mark cells as `-`
- Use the JSON `timestamp` field to determine which pass a run belongs to
