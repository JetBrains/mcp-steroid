---
title: "Strategy"
description: "How MCP Steroid is building IDE-native infrastructure for AI coding agents"
weight: 2
aliases:
  - /strategy/
---

## Give AI the whole IDE, not just the files

MCP Steroid makes AI Agents code with IDEs, not just with files. File-only workflows break on real tasks because agents cannot 
run inspections, execute refactorings, launch debugger flows, or use live IDE context and actions. The larger the repository, 
the more research an agent must do -- and the more tokens it burns -- without IDE-level access.

MCP Steroid closes that gap by exposing IDE APIs, visual state, and runtime environment from JetBrains IDEs to AI Agents.

## Strategic thesis

MCP Steroid is an agent-first product. In the near term, its distribution is through existing IntelliJ-based IDE users.
The long-term product direction is infrastructure -- headless software and SaaS -- that lets AI Agents execute reliable 
engineering workflows with IDE-native context.

On tasks that depend on IDE capabilities, agents with MCP Steroid should complete more work with
fewer interventions, lower token usage, and faster delivery than the same agents without it.

## Three-phase product arc

1. **Phase 1 -- PoC:** IntelliJ plugin distribution (current)
2. **Phase 2 -- Fine-tune:** evals, benchmarks, prompt optimization
3. **Phase 3 -- Scale:** headless mode, packaging, SaaS, B2B distribution

### Phase 1: Proof of concept -- IntelliJ plugin (current)

Today MCP Steroid runs as a plugin inside JetBrains IDEs. A developer connects their AI Agent 
(Claude Code, Codex, Gemini, or any MCP client) to a running IDE instance -- IntelliJ IDEA, PyCharm, Android Studio, Rider, and others -- where their project is already open.

### Phase 2: Fine-tune -- evals, benchmarks, learn, and iterate

We are collecting scenarios and execution logs from real MCP Steroid sessions (share your `.idea/mcp-steroid` folder with us).

The collected data is analyzed to identify sharp edges in the current implementation and to improve prompts, skills, 
and documentation. AI Agents help us craft the better product for AI Agents. This is an iterative process; we have 
completed roughly seven optimization rounds so far, primarily on the MCP Steroid project itself.

This validation loop is described in [Learning Methodology](/docs/learning-methodology/).

### Phase 3: Scale -- headless runtime, SaaS, B2B

The long-term target is a self-contained runtime, available both as SaaS and as an end-user product, that serves as the headless IDE for AI agents.

## How you can help

- **Developers:** submit reproducible scenarios via [Need Your Experiments and Support](/docs/need-your-experiments-and-support/) and engage in the community
- **Engineering leaders:** request pilot evaluations on your repositories -- we are eager to learn alongside you
- **Sponsors and investors:** support benchmark expansion and productization

## Creator

MCP Steroid is built by [Eugene Petrenko](https://linkedin.com/in/jonnyzzz), with 21 years of JetBrains ecosystem experience.

## Contact

- [LinkedIn](https://linkedin.com/in/jonnyzzz)
- [GitHub](https://github.com/jonnyzzz/mcp-steroid)
- [GitHub Sponsors](https://github.com/sponsors/jonnyzzz)
