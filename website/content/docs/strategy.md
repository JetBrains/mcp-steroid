---
title: "Strategy"
description: "How MCP Steroid is building IDE-native infrastructure for AI coding agents"
weight: 30
group: "Vision"
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

1. **Phase 1 -- IDE Plugin:** IntelliJ plugin distribution (current)
2. **Phase 2 -- Fine-tune:** evals, benchmarks, prompt optimization
3. **Phase 3 -- Scale:** headless mode, packaging, SaaS, B2B distribution

### Phase 1: IDE Plugin (current)

Today MCP Steroid runs as a plugin inside JetBrains IDEs. A developer connects their AI Agent 
(Claude Code, Codex, Gemini, or any MCP client) to a running IDE instance -- IntelliJ IDEA, PyCharm, Android Studio, Rider, and others -- where their project is already open.

### Phase 2: Fine-tune -- evals, benchmarks, learn, and iterate

**Result: 20-54% speedup on benchmarks** when AI agents use MCP Steroid with full IDE access vs. file-only workflows.

DPAIA benchmark results across diverse Spring Boot tasks:

| Task | With MCP | Without MCP | Delta |
|------|----------|-------------|-------|
| Rename ROLE\_ADMIN across JHipster app (9 files) | 202s | 440s | **-54%** |
| JWT auth from scratch (5+ new files) | 288s | 396s | **-27%** |
| Parent-child JPA & Flyway (10 files) | 382s | 523s | **-27%** |
| Multi-layer JPA+service+controller (15 files) | 788s | 1002s | **-21%** |
| Simple URL prefix replace (7 files) | 188s | 181s | +4% |
| Extend OrderRepository JPQL (4 files) | 727s | 633s | +15% |

Tasks requiring semantic understanding -- refactorings across many files, multi-layer code generation -- show the largest gains. Simple text replacements perform similarly with or without IDE access.

We are collecting scenarios and execution logs from real MCP Steroid sessions (share your `.idea/mcp-steroid` folder with us).

The collected data is analyzed to identify sharp edges in the current implementation and to improve prompts, skills,
and documentation. AI Agents help us craft the better product for AI Agents. This is an iterative process; we have
completed roughly seven optimization rounds so far, primarily on the MCP Steroid project itself.

This validation loop is described in [Learning Methodology](/docs/learning-methodology/).

### Phase 3: Scale -- headless runtime, SaaS, B2B

The long-term target is a self-contained runtime, available both as SaaS and as an end-user product, that serves as the headless IDE for AI agents.

## Easy experimentation

MCP Steroid provides an easy way to experiment with new tasks, prompts, and skills locally. Create a new skill, ask your agent to use `steroid_execute_code`, and give it an example code snippet using IntelliJ API to solve your goal:

```kotlin
// Example: find all TODO comments in the project
val todoItems = readAction {
    val searchHelper = PsiSearchHelper.getInstance(project)
    val result = mutableListOf<String>()
    searchHelper.processCommentsContainingIdentifier("TODO") { comment ->
        result.add("${comment.containingFile.virtualFile.path}: ${comment.text.trim()}")
        true
    }
    result
}
todoItems.forEach { println(it) }
```

The [Debugging IDE with MCP Steroid](/docs/how-to-debug-ide/) guide was written entirely by AI agents using this approach -- a real skill created through experimentation with full IDE access.

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
