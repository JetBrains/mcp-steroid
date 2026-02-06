---
title: "Strategy"
description: "How MCP Steroid is building IDE-native infrastructure for AI coding agents"
weight: 5
aliases:
  - /strategy/
---

## Give AI the whole IDE, not just the files

MCP Steroid makes AI agents code with the IDE, not just with files. File-only workflows break on many real tasks because agents cannot run inspections, execute refactorings, launch debugger flows, or use live IDE context.

MCP Steroid closes that gap by exposing IDE APIs, visual state, and runtime environment from JetBrains IDEs to AI agents. MCP is the current implementation detail.

## Strategic thesis

MCP Steroid is an agent-first product. Near-term distribution is through IntelliJ users, while the long-term product direction is infrastructure that lets agents execute reliable engineering workflows with IDE-native context.

On tasks that depend on IDE state, agents with MCP Steroid should complete more tasks with fewer interventions, lower token usage, and faster delivery than the same agent without MCP Steroid.

## Three-phase product arc

1. Phase 1: IntelliJ plugin (current)
2. Phase 2: benchmark, learn, and iterate
3. Phase 3: headless background runtime

### Phase 1: IntelliJ plugin (current)

Today MCP Steroid runs as a plugin in IntelliJ-based IDEs. A developer connects their agent (Claude Code, Codex, Gemini, and others), and the agent gets controlled access to IDE-aware workflows.

### Phase 2: benchmark, learn, and iterate

Every major claim is validated with baseline/treatment runs:

- Baseline: same task without MCP Steroid
- Treatment: same task with MCP Steroid
- Controlled variables: same repository SHA, model/version, prompt, and time budget

Core metrics:

- task completion rate
- manual interventions
- token cost
- time to first accepted result
- regressions or new defects

This validation loop is described in [Learning Methodology](/docs/learning-methodology/).

### Phase 3: headless background runtime

The long-term target is a self-contained runtime that can run locally or in hosted environments, managed by the AI agent, without requiring an open IDE window.

## How you can help

- Developers: submit reproducible scenarios in [Need Your Experiments and Support](/docs/need-your-experiments-and-support/)
- Engineering leaders: request pilot evaluations on your repositories
- Sponsors and investors: support benchmark expansion and productization

## Creator

MCP Steroid is created by [Eugene Petrenko](https://linkedin.com/in/jonnyzzz), with 21 years of JetBrains ecosystem experience.

## Contact

- [LinkedIn](https://linkedin.com/in/jonnyzzz)
- [GitHub](https://github.com/jonnyzzz/mcp-steroid)
- [GitHub Sponsors](https://github.com/sponsors/jonnyzzz)
