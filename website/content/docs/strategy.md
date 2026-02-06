---
title: "Strategy"
description: "How MCP Steroid is building IDE-native infrastructure for AI coding agents"
weight: 5
aliases:
  - /strategy/
---

## Give AI the whole IDE, not just the files

MCP Steroid makes AI Agents code with the IDE, not just with files. File-only workflows break on many real tasks because agents cannot run inspections,
execute refactorings, launch debugger flows, or use live IDE context. The bigger your repository is, the more research is needed to deal with it effectively, and the more tokens an agent would spend researching.

MCP Steroid closes that gap by exposing all IDE APIs, visual state, and runtime environment from JetBrains IDEs to AI Agents.

## Strategic thesis

MCP Steroid is an agent-first product. In the near-term, it's distribution is through existing IntelliJ users, while the long-term product
direction is infrastructure (headless software, SaaS) that lets AI Agents execute reliable engineering workflows with our headless IDE-native context.

On tasks that depend on IDE state, agents with MCP Steroid should complete more tasks with fewer interventions, lower token usage, and faster delivery than the same agent without MCP Steroid.

## Three-phase product arc

1. Phase 1. PoC: IntelliJ plugin distribution (currently running)
2. Phase 2. Fine-tune: evals, benchmarks, fine-tuning
3. Phase 3. Scaling: headless mode, packaging, SaaS, B2B distribution channel

### Phase 1: Proof of Concept. IntelliJ plugin (current)

Today MCP Steroid runs as a plugin in IntelliJ-based IDEs. A developer connects their AI Agent (Claude Code, Codex, Gemini, and others) to the running IntelliJ-based IDE (IntelliJ IDEA, PyCharm, Android Studio, Rider, ...)
where their project is already open.

### Phase 2: Fine-tune: evals, benchmark, learn, and iterate

We are collecting more and more scenarios and the execution logs from existing MCP Steroid runs (just share your `.idea/mcp-steroid` folder with us),

The collected data is processed with various AI Agents analyze sharp edges in the current implementation and to improve prompts, sills, and other
texts in the MCP Steroid plugin. This is iterative process and we did ~7 iterations already, mainly on the MCP Steroid project itself.

This validation loop is described in [Learning Methodology](/docs/learning-methodology/).

### Phase 3: Scaling: headless background runtime, SaaS, B2B

The long-term target is a self-contained runtime that is available both as SaaS and as end-user product that 
helps AI Agents code more effectively, by becoming the headless IDE for AI Agents. 


## How you can help

- Developers: submit reproducible scenarios in [Need Your Experiments and Support](/docs/need-your-experiments-and-support/), engage in the community
- Engineering leaders: request pilot evaluations on your repositories, we are eager to lear with you
- Sponsors and investors: support benchmark expansion and productization

## Creator

MCP Steroid is created by [Eugene Petrenko](https://linkedin.com/in/jonnyzzz), with 21 years of JetBrains ecosystem experience.

## Contact

- [LinkedIn](https://linkedin.com/in/jonnyzzz)
- [GitHub](https://github.com/jonnyzzz/mcp-steroid)
- [GitHub Sponsors](https://github.com/sponsors/jonnyzzz)
