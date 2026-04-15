#!/bin/bash
# dpaia-arena-runner.sh — Automated DPAIA Arena experiment orchestrator.
#
# Runs every scenario in PRIMARY_COMPARISON_CASES (claude+mcp) sequentially,
# up to MAX_RUNS attempts each. After each run, spawns a Claude Code analysis
# sub-agent. On failure, also spawns an improvement sub-agent that may patch
# ArenaTestRunner.buildPrompt() and commit the fix before the next attempt.
#
# Usage:
#   bash docs/dpaia-arena-runner.sh [START_INDEX]
#
# START_INDEX: 0-based scenario index to start from (default 0).
#              Useful for resuming an interrupted run.
#
# Environment:
#   RUN_AGENT   — path to run-agent.sh (default: ~/Work/jonnyzzz-ai-coder/run-agent.sh)
#   MAX_RUNS    — maximum attempts per scenario (default: 3)
#   SKIP_IMPROVE  — set to 1 to skip improvement sub-agent on failure
#   SKIP_ANALYZE  — set to 1 to skip analysis sub-agent after each run (saves ~10 min/scenario)
#   DRY_RUN       — set to 1 to print commands without executing gradle/sub-agents
#
# Output:
#   docs/dpaia-arena-results.md   — running results table (appended per run)
#   docs/dpaia-arena-MESSAGE-BUS.md — append-only trace log
#   test-experiments/build/test-logs/test/dpaia-arena-run-*.json — per-run JSON

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RUN_AGENT="${RUN_AGENT:-$HOME/Work/jonnyzzz-ai-coder/run-agent.sh}"
MAX_RUNS="${MAX_RUNS:-3}"
SKIP_IMPROVE="${SKIP_IMPROVE:-0}"
SKIP_ANALYZE="${SKIP_ANALYZE:-0}"
DRY_RUN="${DRY_RUN:-0}"
START_INDEX="${1:-0}"

RESULTS_FILE="$SCRIPT_DIR/dpaia-arena-results.md"
MESSAGE_BUS="$SCRIPT_DIR/dpaia-arena-MESSAGE-BUS.md"
ANALYZE_PROMPT="$SCRIPT_DIR/dpaia-arena-analyze-prompt.md"
IMPROVE_PROMPT="$SCRIPT_DIR/dpaia-arena-improve-prompt.md"
TEST_OUTPUT_DIR="$PROJECT_ROOT/test-experiments/build/test-logs/test"

GRADLEW="$PROJECT_ROOT/gradlew"
GRADLE_TASK=":test-experiments:test"
PACKAGE="com.jonnyzzz.mcpSteroid.integration.arena"

# ── Scenario list ─────────────────────────────────────────────────────────────
# Format: "instanceId|TestClassName"
SCENARIOS=(
  "dpaia__empty__maven__springboot3-3|DpaiaSpringBoot33Test"
  "dpaia__feature__service-125|DpaiaFeatureService125Test"
  "dpaia__empty__maven__springboot3-1|DpaiaSpringBoot31Test"
  "dpaia__feature__service-25|DpaiaFeatureService25Test"
  "dpaia__spring__petclinic__rest-14|DpaiaPetclinicRest14Test"
  "dpaia__spring__petclinic-36|DpaiaPetclinic36Test"
  "dpaia__jhipster__sample__app-3|DpaiaJhipsterArenaTest"
  "dpaia__train__ticket-1|DpaiaTrainTicket1Test"
  "dpaia__train__ticket-31|DpaiaTrainTicket31Test"
  "dpaia__spring__boot__microshop-18|DpaiaMicroshop18Test"
  "dpaia__spring__boot__microshop-2|DpaiaMicroshop2Test"
  "dpaia__spring__petclinic-27|DpaiaPetclinic27Test"
  "dpaia__spring__petclinic__rest-3|DpaiaPetclinicRest3Test"
  "dpaia__piggymetrics-6|DpaiaPiggymetrics6Test"
  "dpaia__spring__petclinic__microservices-5|DpaiaPetclinicMicroservices5Test"
  "dpaia__spring__petclinic__rest-37|DpaiaPetclinicRest37Test"
  "dpaia__spring__petclinic-71|DpaiaPetclinic71Test"
)

# ── Helpers ───────────────────────────────────────────────────────────────────

log() { echo "[RUNNER $(date -u '+%H:%M:%S')] $*"; }

bus() {
  local msg="$*"
  echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') $msg" >> "$MESSAGE_BUS"
}

init_results_file() {
  if [ ! -f "$RESULTS_FILE" ]; then
    cat > "$RESULTS_FILE" <<'EOF'
# DPAIA Arena Results

| Scenario | Run | Fix? | Exit | Duration | exec_code | Summary |
|----------|-----|------|------|----------|-----------|---------|
EOF
    bus "INIT: created $RESULTS_FILE"
  fi
}

append_result() {
  local scenario="$1" run="$2" fix="$3" exit_code="$4" duration_s="$5" exec_code_calls="$6" summary="$7"
  echo "| ${scenario##dpaia__} | $run | $fix | $exit_code | ${duration_s}s | $exec_code_calls | $summary |" >> "$RESULTS_FILE"
}

# Return the most-recently-created run dir for a given scenario+mode
find_latest_run_dir() {
  local instance_id="$1" mode="$2"
  # Dirs created by DpaiaScenarioBaseTest are named: run-<timestamp>-<instanceId>-<mode>
  # (no "arena-" prefix, no "-claude-" segment — just instanceId + mode label)
  ls -dt "$TEST_OUTPUT_DIR"/run-*-${instance_id}-${mode} 2>/dev/null | head -1 || true
}

# Parse a field from the JSON summary file
json_field() {
  local file="$1" field="$2"
  python3 -c "import json,sys; d=json.load(open('$file')); print(d.get('$field',''))" 2>/dev/null || echo ""
}

# Count steroid_execute_code calls in a decoded agent log.
# Decoded logs use the MCP-qualified format ">> mcp__mcp-steroid__steroid_execute_code (reason)"
# Match "steroid_execute_code (" to count actual tool invocations only (not ToolSearch lookups).
count_exec_code_calls() {
  local decoded_log="$1"
  # grep -c exits with code 1 when there are 0 matches (but still prints "0").
  # Using "|| true" avoids the double-0 that "|| echo 0" would produce.
  grep -c "steroid_execute_code (" "$decoded_log" 2>/dev/null || true
}

# Run a Claude sub-agent for analysis or improvement
run_sub_agent() {
  local role="$1"          # "analysis" or "improvement"
  local prompt_file="$2"   # absolute path to filled-in prompt
  local cwd="$3"           # working directory for the agent

  if [ "$DRY_RUN" = "1" ]; then
    log "DRY_RUN: would run $role sub-agent with prompt $prompt_file"
    return 0
  fi

  if [ ! -f "$RUN_AGENT" ]; then
    log "WARNING: run-agent.sh not found at $RUN_AGENT — skipping $role sub-agent"
    bus "WARN: run-agent.sh missing, skipped $role for $(basename "$prompt_file")"
    return 0
  fi

  log "Spawning $role sub-agent ..."
  export MESSAGE_BUS
  bash "$RUN_AGENT" claude "$cwd" "$prompt_file" || {
    log "WARNING: $role sub-agent exited non-zero — continuing"
    bus "WARN: $role sub-agent non-zero exit"
  }
}

# Build the analysis prompt for a specific run and write it to a temp file
build_analysis_prompt() {
  local instance_id="$1"
  local run_dir="$2"
  local result_json="$3"
  local out_file="$4"

  local decoded_log
  decoded_log=$(ls "$run_dir"/agent-claude-code-*-decoded.txt 2>/dev/null | head -1 || echo "")

  sed \
    -e "s|{{INSTANCE_ID}}|$instance_id|g" \
    -e "s|{{PROJECT_ROOT}}|$PROJECT_ROOT|g" \
    -e "s|{{RUN_DIR}}|$run_dir|g" \
    -e "s|{{DECODED_LOG}}|$decoded_log|g" \
    -e "s|{{RESULT_JSON}}|$result_json|g" \
    -e "s|{{MESSAGE_BUS}}|$MESSAGE_BUS|g" \
    "$ANALYZE_PROMPT" > "$out_file"
}

# Build the improvement prompt for a specific failed run
build_improve_prompt() {
  local instance_id="$1"
  local run_dir="$2"
  local analysis_out="$3"
  local out_file="$4"

  sed \
    -e "s|{{INSTANCE_ID}}|$instance_id|g" \
    -e "s|{{PROJECT_ROOT}}|$PROJECT_ROOT|g" \
    -e "s|{{RUN_DIR}}|$run_dir|g" \
    -e "s|{{ANALYSIS_OUTPUT}}|$analysis_out|g" \
    -e "s|{{MESSAGE_BUS}}|$MESSAGE_BUS|g" \
    "$IMPROVE_PROMPT" > "$out_file"
}

# ── Main loop ─────────────────────────────────────────────────────────────────

if [ ! -f "$ANALYZE_PROMPT" ]; then
  log "ERROR: $ANALYZE_PROMPT not found. Run from project root."
  exit 1
fi
if [ ! -f "$IMPROVE_PROMPT" ]; then
  log "ERROR: $IMPROVE_PROMPT not found. Run from project root."
  exit 1
fi

init_results_file
bus "START: dpaia-arena-runner.sh START_INDEX=$START_INDEX MAX_RUNS=$MAX_RUNS"

TOTAL=${#SCENARIOS[@]}
PASSED=0
FAILED=0

for (( idx=START_INDEX; idx<TOTAL; idx++ )); do
  entry="${SCENARIOS[$idx]}"
  INSTANCE_ID="${entry%%|*}"
  TEST_CLASS="${entry##*|}"
  RESULT_JSON="$TEST_OUTPUT_DIR/dpaia-arena-run-${INSTANCE_ID}-claude-mcp.json"

  log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  log "Scenario $((idx+1))/$TOTAL: $INSTANCE_ID"
  log "  Test class: $TEST_CLASS"
  log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  bus "SCENARIO[$((idx+1))/$TOTAL]: $INSTANCE_ID start"

  SCENARIO_PASSED=0

  for (( run=1; run<=MAX_RUNS; run++ )); do
    log "Run $run/$MAX_RUNS for $INSTANCE_ID ..."
    bus "RUN[$run]: $INSTANCE_ID claude+mcp"

    GRADLE_FILTER="$PACKAGE.${TEST_CLASS}.claude with mcp"
    GRADLE_LOG="/tmp/dpaia-arena-${INSTANCE_ID}-run${run}.log"

    # ── Execute Gradle test ──────────────────────────────────────────────────
    RUN_START=$(date +%s)
    if [ "$DRY_RUN" = "1" ]; then
      log "DRY_RUN: $GRADLEW $GRADLE_TASK --tests '$GRADLE_FILTER'"
      echo '{"agent_claimed_fix":false,"exit_code":-1,"agent_duration_ms":0,"agent_summary":"dry-run","used_mcp_steroid":false}' > "$RESULT_JSON"
    else
      (
        cd "$PROJECT_ROOT"
        "$GRADLEW" "$GRADLE_TASK" \
          --tests "$GRADLE_FILTER" \
          --rerun-tasks \
          2>&1
      ) | tee "$GRADLE_LOG" || true  # don't abort on test failure
    fi
    RUN_END=$(date +%s)
    DURATION_S=$(( RUN_END - RUN_START ))

    # ── Parse result ─────────────────────────────────────────────────────────
    CLAIMED_FIX="false"
    EXIT_CODE="?"
    AGENT_DURATION_S="?"
    AGENT_SUMMARY="(no summary)"
    EXEC_CODE_CALLS="0"

    if [ -f "$RESULT_JSON" ]; then
      CLAIMED_FIX=$(json_field "$RESULT_JSON" "agent_claimed_fix")
      EXIT_CODE=$(json_field "$RESULT_JSON" "exit_code")
      AGENT_DURATION_MS=$(json_field "$RESULT_JSON" "agent_duration_ms")
      AGENT_DURATION_S=$(( ${AGENT_DURATION_MS:-0} / 1000 ))
      AGENT_SUMMARY=$(json_field "$RESULT_JSON" "agent_summary")
    fi

    RUN_DIR=$(find_latest_run_dir "$INSTANCE_ID" "mcp")
    if [ -n "$RUN_DIR" ]; then
      DECODED_LOG=$(ls "$RUN_DIR"/agent-claude-code-*-decoded.txt 2>/dev/null | head -1 || echo "")
      if [ -n "$DECODED_LOG" ] && [ -f "$DECODED_LOG" ]; then
        EXEC_CODE_CALLS=$(count_exec_code_calls "$DECODED_LOG")
      fi
    fi

    log "  Result: fix=$CLAIMED_FIX exit=$EXIT_CODE agent=${AGENT_DURATION_S}s exec_code=$EXEC_CODE_CALLS"
    append_result "$INSTANCE_ID" "$run" "$CLAIMED_FIX" "$EXIT_CODE" "$AGENT_DURATION_S" "$EXEC_CODE_CALLS" "$AGENT_SUMMARY"
    bus "RESULT[$run]: $INSTANCE_ID fix=$CLAIMED_FIX exit=$EXIT_CODE duration=${AGENT_DURATION_S}s exec_code=$EXEC_CODE_CALLS"

    # ── Analysis sub-agent ───────────────────────────────────────────────────
    if [ -n "$RUN_DIR" ] && [ "${SKIP_ANALYZE:-0}" != "1" ]; then
      ANALYSIS_PROMPT_TMP="$RUN_DIR/analysis-prompt.md"
      ANALYSIS_OUTPUT="$RUN_DIR/analysis.md"
      build_analysis_prompt "$INSTANCE_ID" "$RUN_DIR" "$RESULT_JSON" "$ANALYSIS_PROMPT_TMP"
      run_sub_agent "analysis" "$ANALYSIS_PROMPT_TMP" "$PROJECT_ROOT"
      bus "ANALYSIS[$run]: done run_dir=$RUN_DIR"
    fi

    # ── Success check ────────────────────────────────────────────────────────
    if [ "$CLAIMED_FIX" = "True" ]; then
      log "  ✓ PASSED on run $run"
      bus "PASS: $INSTANCE_ID on run $run"
      SCENARIO_PASSED=1
      PASSED=$(( PASSED + 1 ))
      break
    fi

    # ── Improvement sub-agent (only between failed runs) ─────────────────────
    if [ "$run" -lt "$MAX_RUNS" ] && [ "$SKIP_IMPROVE" != "1" ] && [ -n "$RUN_DIR" ]; then
      log "  Run $run failed — spawning improvement sub-agent ..."
      IMPROVE_PROMPT_TMP="$RUN_DIR/improve-prompt.md"
      build_improve_prompt "$INSTANCE_ID" "$RUN_DIR" "$ANALYSIS_OUTPUT" "$IMPROVE_PROMPT_TMP"
      run_sub_agent "improvement" "$IMPROVE_PROMPT_TMP" "$PROJECT_ROOT"
      bus "IMPROVE[$run]: $INSTANCE_ID done"

      # Pull any committed improvements before the next run
      if [ "$DRY_RUN" != "1" ]; then
        (cd "$PROJECT_ROOT" && git pull --ff-only origin main 2>/dev/null || true)
      fi
    fi
  done

  if [ "$SCENARIO_PASSED" = "0" ]; then
    log "  ✗ FAILED after $MAX_RUNS runs"
    bus "FAIL: $INSTANCE_ID failed all $MAX_RUNS runs"
    FAILED=$(( FAILED + 1 ))
  fi

done

log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log "DONE: $PASSED/$TOTAL passed, $FAILED failed"
log "Results: $RESULTS_FILE"
log "Message bus: $MESSAGE_BUS"
bus "DONE: $PASSED passed $FAILED failed out of $TOTAL"
