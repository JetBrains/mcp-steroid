# Autoresearch Program: MCP Steroid Prompt Optimization

## Objective

Maximize MCP Steroid tool usage (exec_code calls) and minimize agent token spend
by iteratively improving the MCP Steroid plugin's skill resources (markdown prompt files).

## Metric

**Primary**: `exec_code_calls / total_tool_calls` ratio (higher = better MCP Steroid leverage)
**Secondary**: `estimated_cost_usd` (lower = better token efficiency)
**Constraint**: `agent_claimed_fix == true` (must still pass — no regression)

## Single Point of Modification

The agent only modifies files under:
```
prompts/src/main/prompts/skill/*.md
prompts/src/main/prompts/prompt/*.md
```

These are the MCP Steroid resource files served to agents via `mcp-steroid://` URIs.
They control how agents discover and use IntelliJ APIs.

## Evaluation Budget

Each experiment = one DPAIA arena scenario run (~2-5 minutes).
Use `dpaia__spring__petclinic__rest-37` as the fast benchmark (88s baseline, simplest scenario).
Use `dpaia__feature__service-125` as the complex benchmark (444s, multi-file, Docker-dependent).

## Iteration Cycle

1. **Analyze**: Read decoded logs from last run → identify where agent used Bash instead of exec_code
2. **Hypothesize**: Which resource change would shift that Bash call to exec_code?
3. **Modify**: Edit one skill resource file (minimal change)
4. **Evaluate**: Run the benchmark scenario
5. **Compare**: Extract metrics from result JSON + NDJSON
6. **Retain/Discard**: If metrics improved, commit. If not, revert.

## Current Bottlenecks (from pass 1 analysis)

1. **Test execution via Bash**: 100% of test runs use `./mvnw test` via Bash. None use `MavenRunConfigurationType.runConfiguration()`. Savings: ~25-60s per scenario.
2. **JDK selection**: Agents try wrong JDK versions (3 Bash calls wasted in feature-25). Already partially addressed.
3. **Docker probing**: Agents probe Docker after HTTP 400 (3+ Bash calls wasted). Already partially addressed.
4. **Resource discovery**: Agents never read `mcp-steroid://skill/*` resources proactively. The tool description mentions them but doesn't push hard enough.

## Data Location

- Run directories: `test-experiments/build/test-logs/test/run-*-mcp/`
- Result JSON: `test-experiments/build/test-logs/test/dpaia-arena-run-*.json`
- Skill resources: `prompts/src/main/prompts/skill/`
- Arena prompt: `test-experiments/src/test/kotlin/.../arena/ArenaTestRunner.kt`

## Verification

After modifying a skill resource:
```bash
./gradlew :prompts:test --tests '*MarkdownArticleContractTest*'
```
Must pass before running arena evaluation.
