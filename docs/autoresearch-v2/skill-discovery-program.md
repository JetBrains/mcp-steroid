# Autoresearch: Skill Discovery — Make Agents Treat MCP Resources as Skills

## Problem

Agents see MCP Steroid as 2 tools (list_projects + execute_code). They never discover
the 84 resources that contain ready-to-use recipes for builds, tests, refactoring, debugging.
Instead they reinvent everything via Bash.

## Objective

Make agents:
1. Call `ListMcpResourcesTool` during planning to discover available skills
2. Call `ReadMcpResourceTool` to learn specific recipes BEFORE attempting tasks
3. Prefer the recipe pattern (exec_code) over Bash for builds, tests, file discovery
4. Treat `mcp-steroid://` resources the same way they treat documentation — read first, act second

## Metrics

| Metric | Current | Target |
|--------|---------|--------|
| Runs that call ListMcpResourcesTool | 0/68 (0%) | >50% |
| Runs that call ReadMcpResourceTool | 0/68 (0%) | >30% |
| Bash build/test calls | 8.8/run avg | <4/run |
| exec_code for test execution | 0/68 (0%) | >20% |

## Lever: MCP Server Instructions (mcp-steroid-info.md)

This is the ONLY text agents see in the system prompt before planning.
Currently 18 lines. It mentions resources as links but doesn't create
urgency to read them.

The tool description (9563 chars) is in the tool schema — agents see it
as metadata, not as instructions. It has MANDATORY warnings that agents ignore.

## Hypothesis Queue

1. **Restructure mcp-steroid-info.md as a skill manifest** — list the top-5 skills
   with one-line descriptions and explicit "Read with ReadMcpResourceTool(uri=...)"
   for each. Make the first instruction "Before starting, read the skill that matches
   your task."

2. **Add task-to-skill mapping** — "Building? Read mcp-steroid://test/overview.
   Debugging? Read mcp-steroid://prompt/debugger-skill. Refactoring? Read
   mcp-steroid://ide/overview." Agents need the connection between their task
   and the resource URI.

3. **Shorten the tool description dramatically** — 9563 chars is too much metadata.
   Move ALL patterns to resources. The tool description should be <500 chars:
   "Execute Kotlin in IntelliJ. Read mcp-steroid://skill/coding-with-intellij first."

4. **Add "skill loaded" confirmation to exec_code output** — when an agent calls
   exec_code, the output header could say "Available skills not yet read: test-skill,
   debugger-skill, maven-patterns. Read them with ReadMcpResourceTool for better results."

5. **Make resource reading part of the first exec_code recipe** — the arena prompt's
   mandatory first call could include reading a resource in the same script.

## Iteration Cycle

1. Modify `mcp-steroid-info.md` (MCP server instructions in system prompt)
2. Verify: `./gradlew :prompts:test --tests '*MarkdownArticleContractTest*'`
3. Deploy: `./gradlew deployPlugin`
4. Run fast arena test: petclinic-rest-37
5. Check decoded log for ListMcpResourcesTool / ReadMcpResourceTool calls
6. Retain if resource reads > 0, discard if still 0

## Files to Modify

- `prompts/src/main/prompts/mcp-steroid-info.md` — MCP server instructions (system prompt)
- `prompts/src/main/prompts/skill/execute-code-tool-description.md` — tool description (schema)
- `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ExecuteCodeToolHandler.kt` — tool output
