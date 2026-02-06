---
title: "Need Your Experiments and Support"
description: "Share reproducible scenarios to help improve MCP Steroid on real repositories"
weight: 4
aliases:
  - /need-your-experiments-and-support/
---

## Why community scenarios matter

Internal demos are not enough. MCP Steroid needs diverse external scenarios across repositories, languages, and team workflows.

If a repository is workable in an IntelliJ-based IDE, it is in scope.

## What to submit

Please provide:

1. Repository pointer (public repo, temporary private access, or archive)
2. Commit SHA
3. Agent client/model/version
4. Baseline setup (without MCP Steroid)
5. Treatment setup (with MCP Steroid)
6. Task prompt
7. Review prompt
8. Acceptance criteria
9. Environment constraints (OS, IDE build, branch policy, time limits)
10. Data-handling constraints

Submit via [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues).

## How we process submissions

1. Open the repository in IntelliJ-based IDE
2. Connect MCP Steroid to a supported agent
3. Run baseline and treatment with orchestrated sub-agents
4. Capture run artifacts and compare completion, interventions, regressions, token cost, and time
5. Convert stable findings into docs/prompts/benchmark tasks

Methodology details: [Learning Methodology](/docs/learning-methodology/).

## What you get back

- run artifact package
- baseline vs treatment pass/fail report
- clear limitation list (if any)
- follow-up action list for your workflow

## How you can support

- Developers: submit scenarios and issue reports with minimal repro
- Engineering leaders: request pilot-style evaluations
- Sponsors and investors: support sustained iteration and benchmark growth

## Submission template

```text
Repository: <link or attachment>
Commit SHA: <exact revision>
Agent client/model/version: <exact>
Baseline setup (without MCP Steroid): <exact toolchain and prompt flow>
Treatment setup (with MCP Steroid): <exact toolchain and prompt flow>
Task prompt: <exact prompt>
Review prompt: <exact prompt>
Acceptance criteria: <binary checks + required artifacts>
Constraints: <time, tooling, branch, policy, OS, IDE build>
Data handling: <public/anonymized/private + restrictions>
Number of reruns per condition: <count, minimum 3 for benchmark inclusion>
Expected output: <artifact or acceptance criteria>
Observed metrics (recommended for benchmark-ready submissions):
  Baseline resolution rate: <% or count>
  Baseline token cost: <approximate tokens>
  Baseline wall-clock time: <minutes>
  Treatment resolution rate: <% or count>
  Treatment token cost: <approximate tokens>
  Treatment wall-clock time: <minutes>
  Manual interventions (baseline vs treatment): <count>
  New regressions introduced: <count or N/A>
```
