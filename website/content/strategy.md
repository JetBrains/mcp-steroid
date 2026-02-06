---
title: "Strategy"
description: "Strategic direction, validation model, and funding path for MCP Steroid"
---

## Strategy in one sentence

MCP Steroid is building the IDE-native reliability layer for AI coding agents, so they can execute real engineering workflows in JetBrains IDEs instead of file-only approximations.

Scope focus: IntelliJ-native workflows where outcomes depend on IDE state (inspections, run configurations, debugger, and UI context).

## Creator and leadership

MCP Steroid is created and strategically led by Eugene Petrenko (jonnyzzz).

This is intentionally founder-led:

- direct product accountability
- fast iteration cycles
- roadmap decisions based on reproducible technical evidence

## Why this exists

Agents are improving quickly, but many workflows still break on non-trivial repositories because they cannot reliably use:

- inspections and refactorings
- run configurations and test flows
- debugger/runtime state
- live IDE/UI context

MCP Steroid addresses this gap by exposing IntelliJ capabilities through MCP in a traceable, automation-friendly way.

## Efficiency thesis

We expect higher efficiency than file-only workflows when tasks require IDE state. We validate this by comparing scenario outcomes against file-only baselines.

Core metrics:

- completion rate against explicit acceptance criteria
- manual interventions per run
- post-run defects/regressions

## Product strategy and sequencing

Today MCP Steroid runs as an IntelliJ plugin. This is a deliberate validation wedge, not the final architecture.

Phase goals:

1. Reliability in high-frequency engineering tasks
2. Auditability: traceable runs, explicit pass/fail criteria, reproducible artifacts
3. Continuous adaptation to model and agent drift through external experiments
4. Productization path toward a self-contained background runtime for team-scale usage

## How we validate claims

Each major claim is tested using a scenario package:

- repository pointer + commit SHA
- task prompt
- review prompt
- acceptance criteria
- run artifacts from `runs/run_*/`

A scenario passes only when criteria are met and a separate validation pass confirms the result.

## What success looks like

- reproducible scenario playbooks that hold across multiple repositories
- fewer manual recoveries during agent execution
- improved execution quality through IDE inspections, refactorings, and test workflows
- external scenario intake that continuously improves the roadmap

## Why community participation matters

This strategy depends on external scenario diversity. Internal demos are not enough.

Please contribute a reproducible package:

- Repository pointer (public repo, temporary private access, or archive)
- Commit SHA and environment constraints
- Task prompt (what you want the agent to do)
- Review prompt (how success should be judged)
- Acceptance criteria

Details are documented in:

- [Learning Methodology](https://github.com/jonnyzzz/mcp-steroid/blob/main/docs/LEARNING-METHODOLOGY.md)
- [Need Your Experiments and Support](https://github.com/jonnyzzz/mcp-steroid/blob/main/docs/NEED-YOUR-EXPERIMENTS-AND-SUPPORT.md)

## Investor framing

- Problem: file-level agent workflows fail on many real engineering tasks.
- Solution: IDE-native execution via MCP for reliable agent behavior.
- Wedge: IntelliJ-first teams already using frontier agents.
- Moat: deep IDE integration plus a reproducible scenario corpus that compounds over time.

## Funding and investment

MCP Steroid is seeking funding to accelerate:

- core reliability engineering
- broader scenario coverage across repositories and team contexts
- faster conversion of findings into docs, prompts, and regression checks

## Call to action

- Developers: submit one reproducible scenario package (repository pointer + task prompt + review prompt).
- Engineering leaders: request a pilot on your repository workflow.
- Angels and VCs: contact Eugene for roadmap and funding discussion.

Contact:

- LinkedIn: [linkedin.com/in/jonnyzzz](https://linkedin.com/in/jonnyzzz)
- GitHub Sponsors: [github.com/sponsors/jonnyzzz](https://github.com/sponsors/jonnyzzz)
