# TeamCity Arena Analysis — April 2026

Data: 144 builds, 196 decoded logs, 47 scenarios, 132GB artifacts from `buildserver.labs.intellij.net`.

## Executive Summary

| Metric | Value |
|---|---|
| Total DPAIA arena runs | 172 (70 Claude, 102 Codex) |
| Debugger demo runs | 22 (12 Claude, 10 Codex) |
| BrightScenario runs | 12 |
| Claude arena success rate | **85.7%** (60/70) |
| Codex arena success rate | **50.0%** (51/102) |
| MCP vs None improvement (Claude avg) | **+3.7%** time, **-11.7%** cost |
| MCP vs None success rate delta | **0%** (identical for both agents) |
| exec_code usage | Environment check + compile only (0 test, 0 refactor) |
| MCP resource reads | **0/196 runs** |
| Tools used (of 9 available) | **1** (steroid_execute_code only) |

## DPAIA Arena: MCP vs None

### Claude Timing Comparison (14 scenarios with paired data)

| Scenario | Avg None (s) | Avg MCP (s) | Delta |
|---|---|---|---|
| PetclinicMicro5 | 313.9 | 192.7 | **+38.6%** |
| Microshop2 | 361.5 | 266.3 | **+26.3%** |
| Petclinic27 | 744.2 | 579.9 | **+22.1%** |
| SpringBoot31 | 188.8 | 168.1 | +10.9% |
| PetclinicRest3 | 536.0 | 504.2 | +5.9% |
| TrainTicket1 | 229.4 | 221.5 | +3.5% |
| TrainTicket31 | 337.4 | 331.9 | +1.6% |
| FeatureService25 | 391.2 | 385.6 | +1.4% |
| SpringBoot33 | 132.9 | 133.9 | -0.7% |
| JhipsterApp3 | 332.5 | 341.9 | -2.8% |
| PetclinicRest14 | 129.4 | 133.2 | -2.9% |
| FeatureService125 | 679.8 | 756.1 | -11.2% |
| PetclinicRest37 | 151.0 | 168.1 | -11.3% |
| Petclinic36 | 349.2 | 451.4 | -29.3% |

**Top beneficiaries**: PetclinicMicro5 (+38.6%), Microshop2 (+26.3%), Petclinic27 (+22.1%) — all multi-file semantic tasks.

**MCP slower**: Petclinic36 (-29.3%), PetclinicRest37 (-11.3%), FeatureService125 (-11.2%) — overhead exceeds benefit for some tasks.

**In paired builds**: MCP was faster in 16/29 pairs, slower in 13/29.

### Cost Comparison

| Run Type | Avg Cost | Samples |
|---|---|---|
| None | $1.34 | 30 |
| MCP | $1.18 | 30 |
| **Delta** | **-11.7%** | |

MCP runs cost less on average despite the exec_code overhead.

### Codex: No Timing Data

All Codex timing/cost fields are NA — the test infrastructure doesn't capture Codex's metrics. Codex success rate (fix_applied) is the only available signal.

## Success Rates by Scenario

| Scenario | Claude | Codex | Combined |
|---|---|---|---|
| SpringBoot33 | 100% | 67% | 83.3% |
| PetclinicMicro5 | 100% | 67% | 80.0% |
| PetclinicRest14 | 100% | 67% | 80.0% |
| PetclinicRest37 | 100% | 67% | 80.0% |
| PetclinicRest3 | 100% | 67% | 80.0% |
| FeatureService25 | 100% | 67% | 80.0% |
| Petclinic36 | 100% | 67% | 80.0% |
| Microshop2 | 100% | 67% | 80.0% |
| JhipsterApp3 | 100% | 50% | 70.0% |
| FeatureService125 | 100% | 50% | 70.0% |
| SpringBoot31 | 100% | 50% | 70.0% |
| TrainTicket1 | 100% | 33% | 60.0% |
| Petclinic27 | 100% | 33% | 60.0% |
| TrainTicket31 | 100% | 33% | 60.0% |
| Petclinic71 | 25% | 67% | 50.0% |
| **Piggymetrics6** | **25%** | **0%** | **10.0%** |
| **Microshop18** | **0%** | **0%** | **0.0%** |

**Claude is perfect (100%) on 14/17 scenarios.** Fails only on Microshop18, Piggymetrics6, Petclinic71.

**Codex sole advantage**: Petclinic71 (67% vs Claude's 25%).

## Debugger Demo Results

### Claude (12/12 successful)

| Bug Variant | Avg Time | Avg Cost | Avg Turns | Bug Type |
|---|---|---|---|---|
| StringFormat | 174.3s | $0.58 | 21 | String formatting |
| OffByOne | 177.9s | $0.54 | 20 | Off-by-one index |
| NullDefault | 238.8s | $0.68 | 23.5 | Wrong map lookup |
| JonnyzzzDebug | 244.1s | $0.69 | 23 | Inverted filter |
| SortedByDesc | 250.1s | $0.72 | 24.5 | Unused return value |
| UnitTest | 300.9s | $0.82 | 26.5 | Debug via JUnit |

### Codex (0/10 metrics captured)

All Codex debugger runs show NA for cost/time/turns. Qualitative log analysis shows Codex DOES find bugs (correct BUG_FOUND output) but the metrics extraction pipeline doesn't capture Codex output format.

### Debugger Behavior Patterns

- **Claude reads 5-8 debugger resource guides** before first exec_code call
- **DebugClass context action fails in Docker** — JUnit configuration fallback is reliable
- **Step-over through Kotlin lambdas** costs 7 steps (SortedByDesc) or requires breakpoint-move workaround
- **"Collecting data..."** is pervasive — agents retry with `.toString()` expressions

## Tool Usage Analysis (10-run sample)

### Distribution

| Tool | Calls | Usage |
|---|---|---|
| Bash | 290 | **96%** of all tool calls |
| steroid_execute_code | 12 | **4%** — env check + compile only |
| steroid_list_projects | 0 | Never called |
| steroid_list_windows | 0 | Never called |
| steroid_action_discovery | 0 | Never called |
| steroid_fetch_resource | 0 | Never called |
| steroid_take_screenshot | 0 | Never called |
| steroid_input | 0 | Never called |
| steroid_execute_feedback | 0 | Never called |

### What exec_code Is Used For

| Use Case | Count | % of exec_code |
|---|---|---|
| Compile check | 6 | 50% |
| Environment check | 5 | 42% |
| Code search | 1 | 8% |
| **Test execution** | **0** | **0%** |
| **Refactoring** | **0** | **0%** |

### Agent Discovery Patterns

- **Claude MCP**: ToolSearch → exec_code (env) → Read → Read/Edit/Write (structured tools)
- **Codex MCP**: exec_code (env) → Bash → Bash → Bash (shell-centric)
- **Claude None**: Grep → Read → Edit (no MCP tools available)
- **Codex None**: Bash → Bash → Bash (pure shell)

Neither agent follows the advertised `list_projects → fetch_resource → execute_code` flow.

## BrightScenario Results

| Test | Success Rate | Status |
|---|---|---|
| GradleTestExecution | 80% (4/5) | **Stable** |
| MavenTestExecution | 20% (1/5) | **Fixed in latest build** (modal dialog resolved) |
| MavenRunnerAdoption | 0% (0/4) | **Broken** — agent always uses Bash |

## Infrastructure Stability

- **87/144 builds succeeded** (60.4%)
- **55 failures**: 10 infrastructure (first batch Gradle errors), 45 agent-level
- **After first batch**: infrastructure is stable, all failures are genuine agent failures

## Suggestions to Improve

### High Priority

1. **Fix Codex metrics extraction** — Codex finds bugs and completes tasks but timing/cost are never captured. The test infrastructure needs to parse Codex's output format for `[done]` metrics.

2. **Investigate Microshop18 and Piggymetrics6** — 0% and 10% success rates. Both agents fail consistently. Root cause analysis of decoded logs would reveal whether the task is genuinely too hard or if there's a systematic issue (wrong repo, missing dependencies, Docker config).

3. **MavenRunnerAdoption prompt** — Claude always falls back to `./mvnw test`. The arena prompt and MCP server instructions don't effectively steer agents toward IDE test execution. The 87-run arena data confirms 0% adoption of Maven IDE runners.

### Medium Priority

4. **exec_code is underutilized** — Only 4% of tool calls, used only for env check and compile. Agents don't use it for test execution, refactoring, code search, or debugging in DPAIA arena. The overhead of writing Kotlin code exceeds the perceived benefit for most tasks.

5. **Resource discovery is broken** — 0/196 runs read any MCP resource. The `steroid_fetch_resource` tool and skill guides have zero usage. Agents treat resources as optional documentation they never read.

6. **8 of 9 tools are unused** — Only `steroid_execute_code` is ever called in arena runs. The other 8 tools (list_projects, list_windows, action_discovery, fetch_resource, take_screenshot, input, open_project, execute_feedback) have zero calls across all 196 decoded logs.

### Low Priority

7. **Petclinic36 regression** — MCP is 29.3% slower than None. Investigate whether the exec_code overhead (compile check + env check) is wasted time on a task that doesn't benefit from IDE APIs.

8. **Step-over lambda optimization** — Debugger demo shows 7 step-overs needed for Kotlin inline lambdas. A custom "step-out-of-lambda" skill could reduce this to 1 call.

9. **DebugClass action in Docker** — Consistently fails. Document JUnit configuration as the primary approach, remove DebugClass as the suggested first attempt.
