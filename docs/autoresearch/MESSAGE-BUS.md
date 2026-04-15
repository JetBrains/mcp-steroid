# Autoresearch Message Bus

Append-only log for the autoresearch optimization loop.

## 2026-04-15 Research Pass — Latest 7 Scenarios

### Data Summary

| Scenario | Bash | exec_code | Total Tools | Resource Reads | Duration | Cost |
|----------|------|-----------|-------------|----------------|----------|------|
| ticket-31 | 0 | 1 | 21 | ToolSearch only | 293.6s | — |
| ticket-1 | 21 | 2 | 31 | ToolSearch only | 294.7s | — |
| jhipster-3 | 3 | 2 | 11 | ToolSearch only | 135.9s | — |
| petclinic-36 | 4 | 2 | 28 | ToolSearch only | 264.6s | — |
| rest-14 | 3 | 2 | 41 | ToolSearch only | 126.0s | — |
| feature-25 | 10 | 3 | ~49 | NONE | 330.5s | $1.32 |
| feature-125 | 15 | 2 | ~54 | NONE | 443.3s | $1.84 |

**exec_code ratio**: 2-3 / 11-54 = **5-18%** (target: higher)

### Bottleneck Findings

RESEARCH: bottleneck=no-resource-reading file=prompts/src/main/prompts/skill/execute-code-tool-description.md savings=30-60s
RESEARCH: hypothesis=Add mandatory "read mcp-steroid://skill/execute-code-maven before first Maven Bash command" directive to tool description

RESEARCH: bottleneck=maven-test-via-bash file=prompts/src/main/prompts/skill/execute-code-tool-description.md savings=10-20s-per-test-run
RESEARCH: hypothesis=Inline a minimal copy-paste MavenRunConfigurationType test pattern directly in tool description (not behind link)

RESEARCH: bottleneck=maven-compile-via-bash file=prompts/src/main/prompts/skill/execute-code-tool-description.md savings=15-25s-per-compile
RESEARCH: hypothesis=Strengthen "after editing files, always use ProjectTaskManager.build() not ./mvnw compile" with inline pattern

RESEARCH: bottleneck=jdk-trial-and-error file=prompts/src/main/prompts/skill/execute-code-maven.md savings=10-20s
RESEARCH: hypothesis=Move JDK selection algorithm summary into first exec_code output or tool description (agents never read maven resource)

RESEARCH: bottleneck=docker-probe-retries file=prompts/src/main/prompts/skill/execute-code-tool-description.md savings=15-30s
RESEARCH: hypothesis=Add "Docker unavailable on first check = skip integration tests, no retries" to tool description

### Implementation Pass 1

IMPLEMENT: applied hypothesis=mandatory-maven-resource-read file=prompts/src/main/prompts/skill/execute-code-tool-description.md change=Added MANDATORY gate directing agents to read mcp-steroid://skill/execute-code-maven before any Bash Maven/Gradle command. Placed after Run Maven tests bullet in Common Operations. Contract test passes. Commit 86fed7b8.

## 2026-04-15 Research Pass 2 — Latest 17 Scenarios (post-Implementation Pass 1)

### Data Summary

| Scenario | dur(s) | exec_code | bash | total | ratio | fix |
|----------|--------|-----------|------|-------|-------|-----|
| rest-37 | 125 | 2 | 2 | 12 | 17% | yes |
| rest-14 | 127 | 2 | 3 | 13 | 15% | yes |
| jhipster-3 | 136 | 2 | 3 | 11 | 18% | yes |
| microshop-2 | 161 | 3 | 10 | 27 | 11% | yes |
| springboot3-3 | 198 | 3 | 4 | 18 | 17% | yes |
| springboot3-1 | 235 | 2 | 4 | 13 | 15% | yes |
| petclinic-36 | 265 | 2 | 4 | 28 | 7% | yes |
| ticket-1 | 295 | 2 | 21 | 31 | 6% | yes |
| piggymetrics-6 | 305 | 1 | 17 | 33 | 3% | yes |
| ticket-31 | 318 | 5 | 15 | 31 | 16% | yes |
| feature-25 | 332 | 3 | 10 | 30 | 10% | yes |
| rest-3 | 385 | 2 | 3 | 23 | 9% | yes |
| feature-125 | 444 | 2 | 15 | 43 | 5% | yes |
| microservices-5 | 468 | 2 | 5 | 24 | 8% | yes |
| petclinic-27 | 481 | 2 | 6 | 25 | 8% | yes |
| microshop-18 | 900 | 1 | 0 | 58 | 2% | NO |
| petclinic-71 | 2269 | 7 | 20 | 113 | 6% | yes |

**exec_code ratio**: avg 10.2% (target: higher)
**MCP resource reads**: ZERO in all 17 runs — MANDATORY gate from Implementation Pass 1 had NO effect
**IDE Maven runner usage**: ZERO — all ~60 Maven invocations used Bash `./mvnw`
**IDE compile checks**: Only petclinic-71 (7 exec_code calls included some builds)

### Root Cause Analysis

**Why the MANDATORY gate failed**: The gate in execute-code-tool-description.md says "Read mcp-steroid://skill/coding-with-intellij-spring" but agents never invoke `ListMcpResourcesTool` or `ReadMcpResourceTool`. They call `ToolSearch (2)` which loads tool schemas, not resources. The gate is text in a tool description — agents see it but treat it as informational since they don't know HOW to read MCP resources programmatically.

**Why agents use Bash for all Maven**: Three reinforcing causes:
1. The **arena prompt** (ArenaTestRunner.kt:165) says "Reserve `./mvnw test` (Bash) only for running the full test suite to confirm final pass/fail" — agents read this as permission/instruction to use Bash
2. The arena prompt (line 166) bans ProcessBuilder inside steroid — agents conflate "no ProcessBuilder" with "no Maven in steroid", not understanding that `MavenRunConfigurationType` is NOT ProcessBuilder
3. The MavenRunConfigurationType pattern requires ~30 lines of boilerplate (SMTRunnerEventsListener + 14 override stubs) which agents would need to copy from a resource they never read

**Bash-heaviest scenarios**:
- train-ticket-1: 21 Bash (multi-module Maven, `install -pl ts-common`, repeated test retries)
- petclinic-71: 20 Bash (spring-javaformat:apply ×5, test-compile ×4, test ×8)
- piggymetrics-6: 17 Bash (multi-module, Docker/Testcontainers probe loops)

### Bottleneck Findings (ranked by aggregate impact)

RESEARCH: bottleneck=maven-test-always-bash file=prompts/src/main/prompts/skill/execute-code-tool-description.md savings=31s-per-test-run×3-avg=93s
RESEARCH: hypothesis=Inline the FULL MavenRunConfigurationType+SMTRunnerEventsListener pattern (all 30 lines) directly in execute-code-tool-description.md. Agents never follow links to resources. Also add explicit note: "MavenRunConfigurationType is NOT ProcessBuilder — it is the IDE's internal Maven runner."

RESEARCH: bottleneck=compile-via-bash file=prompts/src/main/prompts/skill/execute-code-tool-description.md savings=20-55s-per-compile
RESEARCH: hypothesis=Inline the full ProjectTaskManager.buildAllModules() pattern (with result checking) in execute-code-tool-description.md. Current one-liner on line 24 lacks the await/result-check code that agents need to copy-paste.

RESEARCH: bottleneck=arena-prompt-conflict file=prompts/src/main/prompts/skill/execute-code-tool-description.md savings=indirect-enables-hypotheses-1-2
RESEARCH: hypothesis=Add stronger language: "BANNED: ./mvnw test via Bash for ANY purpose. Use MavenRunConfigurationType exclusively. The pattern below replaces ALL Bash Maven calls including full test suite runs. If MavenRunConfigurationType times out after 5 min, ONLY THEN fall back to Bash."

RESEARCH: bottleneck=spring-javaformat-via-bash file=prompts/src/main/prompts/skill/coding-with-intellij-spring.md savings=20-40s-for-petclinic-scenarios
RESEARCH: hypothesis=Add note in coding-with-intellij-spring.md: "For spring-javaformat:apply, use MavenRunConfigurationType with goals=listOf('spring-javaformat:apply'). Same pattern as test execution. Run ONCE before test, not interleaved."

RESEARCH: bottleneck=multi-module-dependency-bash file=prompts/src/main/prompts/skill/execute-code-maven.md savings=60-120s-for-multi-module-scenarios
RESEARCH: hypothesis=Add pattern for multi-module Maven: "For multi-module projects, run `install -pl <module> -DskipTests` via MavenRunner (IDE pattern, not Bash). Use MavenRunnerSettings.mavenProperties for -pl flag." Also add: "If install fails, try install -N first (parent POM only), then install -pl <module>."

### Ranked Hypotheses (by estimated impact)

1. **Inline Maven test runner pattern** — estimated 93s avg savings across all scenarios. File: execute-code-tool-description.md. HIGH confidence.
2. **Inline compile check pattern** — estimated 20-55s per compile. File: execute-code-tool-description.md. MEDIUM confidence.
3. **Strengthen Bash Maven ban** — enables #1 and #2 by overriding arena prompt. File: execute-code-tool-description.md. HIGH confidence (but may need arena prompt fix too).
4. **spring-javaformat via IDE** — saves 20-40s but only for petclinic. File: coding-with-intellij-spring.md. LOW confidence (niche).
5. **Multi-module Maven via IDE** — saves 60-120s for train-ticket/piggymetrics. File: execute-code-maven.md. MEDIUM confidence.

### Critical Insight

The single biggest lever is NOT the skill resources — it's the **arena prompt in ArenaTestRunner.kt** which explicitly tells agents to use Bash for Maven. Skill resource changes compete against this user-message-level instruction. For maximum impact, BOTH the arena prompt AND the tool description need to align on "use IDE Maven runner, not Bash." However, the arena prompt is outside the allowed modification scope (prompts/src/main/prompts/).

### Implementation Pass 2

IMPLEMENT: applied hypothesis=inline-maven-test-pattern file=prompts/src/main/prompts/skill/execute-code-tool-description.md change=Inlined full 30-line MavenRunConfigurationType+SMTRunnerEventsListener kotlin[IU] code block directly in tool description. Agents see copy-paste pattern on every exec_code call. Commit d24d909e (applied by prior session).

IMPLEMENT: applied hypothesis=inline-compile-check-pattern file=prompts/src/main/prompts/skill/execute-code-tool-description.md change=Inlined 5-line ProjectTaskManager.buildAllModules() kotlin code block with result checking. Agents see copy-paste compile check on every exec_code call. Contract test passes. Commit 22d7cf04.

