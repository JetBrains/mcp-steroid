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

