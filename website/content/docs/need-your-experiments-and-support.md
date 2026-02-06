---
title: "Need Your Experiments and Support"
description: "Share reproducible scenarios to help improve MCP Steroid on real repositories"
weight: 99
aliases:
  - /need-your-experiments-and-support/
---

## Why community scenarios matter

MCP Steroid improves by learning from real-world usage. The more diverse the repositories, languages, and workflows we test against, the more reliable the product becomes for every user.

If your repository works in an IntelliJ-based IDE, it is in scope.

## What to submit

Please provide:

1. **Repository pointer** -- public repo, temporary private access, or archive
2. **Commit SHA** -- exact revision to reproduce from
3. **Agent client/model/version** -- the exact agent setup used
4. **Baseline setup** -- toolchain and prompt flow without MCP Steroid
5. **Treatment setup** -- toolchain and prompt flow with MCP Steroid
6. **Task prompt** -- the exact prompt given to the agent
7. **Review prompt** -- how the output was evaluated
8. **Acceptance criteria** -- binary checks and required artifacts
9. **Environment constraints** -- OS, IDE build, branch policy, time limits
10. **Data-handling constraints** -- public, anonymized, or private with restrictions

Submit via [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues).

## How we process submissions

1. Open the repository in an IntelliJ-based IDE
2. Connect MCP Steroid to a supported agent
3. Run baseline (without MCP Steroid) and treatment (with MCP Steroid) using orchestrated sub-agents
4. Capture run artifacts and compare completion rate, manual interventions, regressions, token cost, and wall-clock time
5. Convert stable findings into documentation, prompts, and benchmark tasks

Methodology details: [Learning Methodology](/docs/learning-methodology/).

## What you get back

- Run artifact package with full logs
- Baseline vs. treatment pass/fail report
- Clear list of limitations encountered (if any)
- Follow-up action items tailored to your workflow

## How you can support

- **Developers**: submit scenarios and issue reports with minimal reproductions
- **Engineering leaders**: request pilot evaluations on your repositories
- **Sponsors and investors**: support sustained iteration and benchmark growth via [GitHub Sponsors](https://github.com/sponsors/jonnyzzz)

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

## Usage log sharing

Beyond full scenario submissions, you can help by sharing your tool call logs.
The `.idea/mcp-steroid` folder in your project contains a log of every tool call
your AI agent sent to the plugin. This data helps us fine-tune prompts, skills,
and documentation to make agents more effective.

For details on how we process this data, see [Learning Methodology](/docs/learning-methodology/).
