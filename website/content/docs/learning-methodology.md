---
title: "Learning Methodology"
description: "How MCP Steroid improves agent reliability with reproducible experiments"
weight: 3
aliases:
  - /learning-methodology/
---

## Why this exists

MCP Steroid targets fast-moving AI agents on fast-moving IDE platforms. Static documentation is not enough. We need a repeatable loop that measures what works, what fails, and what should be improved.

The primary user is the AI agent. This methodology is product research applied to agents.

## The operating loop

1. Pick a concrete scenario with explicit acceptance criteria.
2. Run baseline and treatment using the same repository SHA, model/version, prompt, and time budget.
3. Compare outcomes across completion, interventions, token cost, speed, and regressions.
4. Keep only reproducible improvements.
5. Update prompts, tool descriptions, and docs.
6. Repeat.

## What we measure

- task completion rate
- manual intervention count
- token cost
- time to first accepted result
- defect/regression rate

For benchmark-quality claims we require reruns: at least 3 for internal claims, 5 for publication confidence.

## Data and feedback flow

Every execution stores a `reason` field and logs run artifacts. Agents can submit explicit feedback with the dedicated feedback tool. We analyze this data to identify pain points and prioritize fixes.

The orchestration baseline is the run-agent methodology ([run-agent.jonnyzzz.com](https://run-agent.jonnyzzz.com)) implemented in this repository with `run-agent.sh` and `THE_PROMPT_v5.md`.

## External scenario intake

External scenarios are core input to this loop:

1. Submission arrives through [Need Your Experiments and Support](/docs/need-your-experiments-and-support/)
2. Baseline and treatment runs are executed
3. Stable scenarios are promoted into benchmark tasks
4. Findings are published and used for product iteration

## Quality gates

Each iteration requires:

- reproducible task definition
- independent validation pass
- controlled baseline/treatment comparison
- run artifacts and explicit pass/fail decision
- no new warnings/errors in relevant checks

A failing result is useful if it reveals a real constraint. Hidden failures are not acceptable.
