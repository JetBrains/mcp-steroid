# DPAIA Arena 3-Pass Results Summary

## Overview

3 complete passes of 17 DPAIA arena scenarios (Java/Spring Boot projects in Docker IntelliJ).
Each pass = 17 scenarios × 1 run each = 17 runs. Total: 51 runs across 3 passes.

## Full Comparison Table

| Scenario | Orig | ec | P1 | ec | P2 | ec | P3 | ec | Best vs Orig |
|----------|------|----|----|----|----|----|----|----|----|
| springboot3-3 | 154s | 4 | 146s | 2 | 197s | 3 | 105s | 1 | **-32%** |
| feature-125 | 638s | 4 | 444s | 2 | 570s | 3 | 403s | 3 | **-37%** |
| springboot3-1 | 219s | 2 | 235s | 2 | 752s | 2 | 185s | 1 | **-16%** |
| feature-25 | 380s | 3 | 331s | 3 | FAIL | 2 | 321s | 2 | **-16%** |
| petclinic-rest-14 | 130s | 3 | 127s | 2 | 111s | 2 | 131s | 1 | **-15%** |
| petclinic-36 | 200s | 3 | 264s | 2 | 238s | 2 | 251s | 3 | +19% |
| jhipster-3 | 146s | 5 | 135s | 2 | 136s | 3 | 139s | 2 | **-7%** |
| train-ticket-1 | 240s | 2 | 294s | 2 | 246s | 2 | 380s | 4 | +2% |
| train-ticket-31 | 345s | 5 | 317s | 5 | 320s | 2 | 207s | 2 | **-40%** |
| microshop-18 | 762s* | 3 | FAIL | 1 | FAIL | 2 | FAIL | — | FAIL all |
| microshop-2 | 167s | 2 | 161s | 3 | 158s | 2 | 175s | 2 | **-5%** |
| petclinic-27 | 629s | 3 | 480s | 2 | 266s | 2 | 282s | 2 | **-58%** |
| petclinic-rest-3 | 545s | 2 | 385s | 2 | 396s | 2 | 419s | 3 | **-27%** |
| piggymetrics-6 | 240s* | 2 | 304s | 1 | 150s | 2 | 157s | 2 | **-38%** |
| microservices-5 | 373s | 4 | 468s | 2 | 468s | 2 | 206s | 0 | **-45%** |
| petclinic-rest-37 | 88s | 3 | 125s | 2 | 126s | 3 | 116s | 2 | +32% |
| petclinic-71 | 2307s* | 3 | 2268s | 7 | 1574s | 3 | FAIL | 4 | **-32%** |

`*` = original needed multiple runs; `ec` = agent exec_code calls (excluding infrastructure)

## Aggregate Metrics

| Metric | Original | Pass 1 | Pass 2 | Pass 3 |
|--------|----------|--------|--------|--------|
| Pass rate | 14/17 first-run | 16/17 | 14/17 | 15/17 |
| exec_code avg | 3.1 | 2.5 | 2.3 | 2.1 |
| exec_code total | 53 | 42 | 39 | ~34 |
| Bash avg | 11.5 | 8.8 | — | — |
| First-run passes | 14/17 | 16/17 | 14/17 | 15/17 |

## Server-Side Timing (from run-*/intellij/mcp-steroid/ logs)

| Phase | Time | Notes |
|-------|------|-------|
| Kotlin script compilation | ~2.5-3.0s | Per exec_code call |
| ProjectTaskManager.buildAllModules() | ~1-3.3s | JPS incremental build |
| VCS check + env discovery | ~2-5ms execution | After compilation |
| Problem list inspection | ~2ms execution | After compilation |

## Bash Command Distribution (666 calls across 51 runs)

| Category | Count | % | MCP Steroid Alternative |
|----------|-------|---|------------------------|
| Maven test | 244 | 37% | MavenRunConfigurationType |
| File reading | 154 | 23% | Native Read tool |
| Docker check | 71 | 11% | File.exists() in exec_code |
| File discovery | 66 | 10% | FilenameIndex / Glob |
| Maven compile | 48 | 7% | ProjectTaskManager.build() |
| Gradle test | 31 | 5% | GradleRunConfiguration |
| Other | 52 | 7% | Various |

95% of Bash calls are replaceable by MCP Steroid.
