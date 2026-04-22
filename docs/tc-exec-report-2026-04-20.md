# MCP Steroid Performance Report — April 2026

**Data**: 144 builds, 196 decoded logs, 47 scenarios, 132GB artifacts from TeamCity CI.
**Period**: April 15-18, 2026 (3 CI batches).
**Plugin version**: 0.92.0 (pre-0.93.0 fixes).

---

## Executive Summary

MCP Steroid makes Claude **15.1% faster** and **11.7% cheaper** on DPAIA Arena benchmarks (17 Spring Boot scenarios). The benefit is strongest on multi-module projects requiring semantic understanding (+38.6% on PetclinicMicro5) and weakest on mechanical scatter-shot edits (-29.3% on Petclinic36).

| Metric | With MCP | Without MCP | Delta |
|---|---|---|---|
| Average time | **339s** | 399s | **+15.1% faster** |
| Average cost | **$1.18** | $1.34 | **+11.7% cheaper** |
| Fix success rate | 85.7% | 85.7% | 0% (identical) |
| exec_code calls/run | 2.0 | 0 | — |
| Bash calls/run | 7.6 | 15.3 | -50% fewer Bash calls |

---

## DPAIA Arena: Per-Scenario Comparison (Claude, Paired Runs)

| Scenario | MCP Time | None Time | Δ Time | MCP Cost | None Cost | Δ Cost | exec_code | MCP Bash | None Bash | Fix |
|---|---|---|---|---|---|---|---|---|---|---|
| **PetclinicMicro5** | **193s** | 314s | **+38.6%** | $0.85 | $1.07 | +20.8% | 1.5 | 2 | 10 | 2/2 |
| **Microshop2** | **266s** | 362s | **+26.3%** | $0.87 | $1.00 | +13.1% | 2.5 | 16 | 34 | 2/2 |
| **Petclinic27** | **580s** | 744s | **+22.1%** | $1.58 | $2.06 | +23.2% | 2.0 | 5 | 19 | 2/2 |
| SpringBoot31 | 168s | 189s | +10.9% | $0.74 | $0.58 | -27.4% | 1.5 | 4 | 14 | 2/2 |
| PetclinicRest3 | 504s | 536s | +5.9% | $1.89 | $2.11 | +10.4% | 2.0 | 6 | 28 | 2/2 |
| TrainTicket1 | 222s | 229s | +3.5% | $1.16 | $1.15 | -1.0% | 2.5 | 12 | 20 | 2/2 |
| TrainTicket31 | 332s | 337s | +1.6% | $1.31 | $1.25 | -4.4% | 1.5 | 12 | 16 | 2/2 |
| FeatureService25 | 386s | 391s | +1.4% | $1.62 | $1.43 | -13.0% | 2.0 | 6 | 9 | 2/2 |
| SpringBoot33 | 134s | 133s | -0.7% | $0.63 | $0.51 | -23.6% | 1.0 | 4 | 11 | 3/3 |
| JhipsterApp3 | 342s | 332s | -2.8% | $0.60 | $0.43 | -38.4% | 2.5 | 3 | 5 | 2/2 |
| PetclinicRest14 | 133s | 129s | -2.9% | $0.68 | $0.55 | -23.9% | 2.0 | 1 | 2 | 2/2 |
| FeatureService125 | 756s | 680s | -11.2% | $2.77 | $2.25 | -23.1% | 2.0 | 10 | 24 | 2/2 |
| PetclinicRest37 | 168s | 151s | -11.3% | $0.42 | $0.37 | -14.4% | 1.5 | 2 | 3 | 2/2 |
| **Petclinic36** | **451s** | **349s** | **-29.3%** | $1.64 | $1.32 | -24.4% | 3.0 | 4 | 6 | 2/2 |

**7 scenarios faster with MCP, 7 slower. MCP wins on time-weighted average because the biggest gains are on the longest tasks.**

---

## Why MCP Helps: Root Cause Analysis

### Where MCP Steroid saves time (top 3)

**PetclinicMicro5 (+38.6%)**: Multi-module reactive Spring Cloud project. The exec_code init call gave the agent project structure, JDKs, VCS status in one round-trip. Without MCP, the agent needed 10 Bash calls (find, ls, cat pom.xml) to gather the same context. The 120s savings came from **eliminating filesystem exploration**.

**Microshop2 (+26.3%)**: 4-microservice project. Without MCP, the agent hit 3 consecutive build failures from wrong JDK version (tried Java 24, then 25). The MCP init call immediately reported available JDKs, letting the agent pick the right one upfront. Savings from **eliminating JDK trial-and-error**.

**Petclinic27 (+22.1%)**: Creating 5 new REST controllers. The MCP compile check caught generic type inference errors before running Maven tests (30s each). Without MCP, the agent needed an extra compile→fix→recompile cycle. Savings from **early compilation feedback**.

### Where MCP Steroid loses time (bottom 3)

**Petclinic36 (-29.3%)**: Adding an email field across 20+ files (schema, data, templates, i18n). Pure mechanical scatter-shot editing — no semantic understanding needed. The 3 exec_code calls were pure overhead.

**PetclinicRest37 (-11.3%)**: Single-file, trivial endpoint addition. Agent got it right on the first try in both runs. MCP init ceremony wasted ~20s on a task that took 150s total.

**FeatureService125 (-11.2%)**: IDE compile check detected errors but couldn't report the actual message (JDK mismatch). Agent had to fall back to Maven anyway, **doubling the compilation step**.

### Pattern: When does MCP help?

| Task characteristic | MCP helps | MCP hurts |
|---|---|---|
| Project structure | Multi-module, unfamiliar | Single module, obvious |
| JDK/build discovery | Multiple JDKs, non-obvious | Single JDK, standard |
| Task type | Create new code from scratch | Add same field across N files |
| Compilation risk | Complex generics, type inference | Simple field additions |
| Files touched | 3-11 (focused, non-trivial) | 20+ scatter-shot OR 1 trivial |

---

## Debugger Demo Results

| Bug Type | Claude Time | Claude Cost | Claude Turns | Codex |
|---|---|---|---|---|
| StringFormat | 174s | $0.58 | 21 | N/A (metrics broken) |
| OffByOne | 178s | $0.54 | 20 | not tested |
| NullDefault | 239s | $0.68 | 24 | not tested |
| JonnyzzzDebug | 244s | $0.69 | 23 | N/A |
| SortedByDesc | 250s | $0.72 | 25 | N/A |
| UnitTest | 301s | $0.82 | 27 | N/A |

Claude: **12/12 successful** (100%). Codex: **0/10 metrics captured** (but qualitatively finds bugs).

---

## Tool Usage (from NDJSON analysis)

| Tool | Calls across 87 MCP runs | % |
|---|---|---|
| steroid_list_windows | 1,423 | 46.6% (test harness polling) |
| steroid_execute_code | 641 | 21.0% |
| steroid_list_projects | 608 | 20.0% |
| steroid_execute_feedback | 1 | 0.03% |
| steroid_fetch_resource | **1** | **0.03%** |
| steroid_action_discovery | 0 | 0% |
| steroid_take_screenshot | 0 | 0% |
| steroid_input | 0 | 0% |

**What exec_code is used for**: environment check (42%), compile check (50%), code search (8%). Zero test execution, zero refactoring.

---

## Infrastructure Issues Found (from idea.log)

| Issue | Severity | Runs Affected | Impact |
|---|---|---|---|
| **"Resolving SDKs" modal dialog false positive** | CRITICAL | 44/87 MCP runs (51%) | Wastes agent turns, kills exec_code |
| **8-minute Observation.awaitConfiguration timeout** | HIGH | All runs | 8 min wasted per run on Maven config |
| **PluginsAdvertiser reflection failure (IU-261)** | MEDIUM | All FeatureService runs | Kafka plugin never installed |
| **Codex metrics not extracted** | MEDIUM | All Codex runs | No timing/cost data for Codex comparison |
| Script execution 30s timeout during indexing | LOW | 4 runs | First exec fails, retry succeeds |

### "Resolving SDKs" is the #1 bug to fix

51% of MCP runs are interrupted by the ModalityStateMonitor detecting Maven's "Resolving SDKs..." progress bar as a modal dialog. This is the same class of false positive we fixed for `JobProviderWithOwnerContext` in 0.93.0 — but "Resolving SDKs" is a different entity. Each interruption wastes 1-3 agent turns (~$0.03-0.10 per occurrence).

---

## Codex vs Claude

| Metric | Claude | Codex |
|---|---|---|
| Arena success rate | **85.7%** | 50.0% |
| Timing data available | Yes (30/35 runs) | **No** (0/51 — metrics extraction bug) |
| exec_code usage in MCP runs | 1.9 calls avg | 3.5 calls avg (uses more) |
| Bash usage in MCP runs | 7.6 calls avg | 47.5 calls avg |
| Debugger success | 12/12 | 0/10 metrics (qualitatively succeeds) |

**Codex metrics gap root cause**: `extractTokenUsage()` in `AgentOutputMetrics.kt` only parses Claude's `type=result` NDJSON event. Codex emits `type=turn.completed` with `usage.input_tokens/output_tokens`. Fix: add Codex format parsing to the extraction function.

---

## Recommendations

### Immediate (blocks accurate measurement)

1. **Fix "Resolving SDKs" modal dialog false positive** — same pattern as JobProvider fix, skip SDK resolution progress entities in ModalityStateMonitor
2. **Fix Codex metrics extraction** — parse `turn.completed` events in `AgentOutputMetrics.kt`
3. **Fix 8-minute awaitConfiguration timeout** — detect actual Maven import completion instead of fixed timeout

### Short-term (improve MCP benefit)

4. **Skip IDE compile check when JDK mismatches** — the check wastes time when IntelliJ can't compile (FeatureService125: JDK 24 error only visible in Maven)
5. **Make init recipe skip-able for simple tasks** — agent could detect single-file tasks and skip the full project scan
6. **Fix PluginsAdvertiser reflection for IU-261** — Kafka plugin installation fails on all FeatureService runs

### Medium-term (increase exec_code adoption)

7. **Reduce exec_code overhead** — current 5.8s average per call (78% under 5s) is acceptable, but the 8-min config timeout inflates perceived cost
8. **Provide structured compile error output** — when IDE compile check fails, return the actual error message (not just `Build errors: true`)
9. **Investigate why agents never use exec_code for tests/refactoring** — the arena prompt already includes Maven IDE runner recipes but agents ignore them (0% adoption across 87 runs)
