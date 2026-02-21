---
title: "How it works"
description: "How MCP Steroid exposes IntelliJ as an MCP server that AI agents can call over HTTP"
weight: 25
group: "Vision"
---

## The pattern

[Model Context Protocol](https://modelcontextprotocol.io/) (MCP) is an open standard that lets AI agents call external tools at runtime.
An **MCP server** is a program that exposes tools — actions the agent can invoke, with structured inputs and outputs.
The agent discovers what tools exist, decides which to call, calls them, and acts on the results.

This pattern is used across the industry today:

- **[Playwright MCP](https://github.com/microsoft/playwright-mcp)** — runs an HTTP server that gives agents control of a real browser: navigate to a URL, click a button, read the accessibility tree, take a screenshot
- **[Cloudflare MCP servers](https://developers.cloudflare.com/agents/model-context-protocol/mcp-servers-for-cloudflare/)** — run remote HTTP servers that give agents control of cloud infrastructure: deploy a Worker, query DNS analytics, inspect observability logs

MCP Steroid follows the same pattern — but for the **IDE**. It runs an HTTP server inside IntelliJ's JVM process and exposes tools that give agents full access to the IDE's understanding of your project.

---

## The server

When IntelliJ starts, MCP Steroid starts an HTTP server inside the running IDE process.

```
http://127.0.0.1:6315/mcp
```

The server runs on port `6315` by default and uses the **Streamable HTTP transport** — HTTP POST for agent requests, SSE for streaming responses. The URL is written to `.idea/mcp-steroid.md` in every open project so agents can discover it automatically.

Because the server runs **inside the IDE's JVM**, it has direct access to IntelliJ's runtime — the project model, the semantic index, the PSI tree, the test runner, the debugger, the VCS layer. There is no inter-process communication layer to cross.

---

## Connecting an agent

To connect Claude Code, add the server to its MCP config:

```bash
claude mcp add --transport http mcp-steroid http://127.0.0.1:6315/mcp
```

Or add it directly to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "mcp-steroid": {
      "type": "http",
      "url": "http://127.0.0.1:6315/mcp"
    }
  }
}
```

The agent calls `initialize` once to discover all available tools. From that point it can invoke any tool with a simple HTTP POST.

---

## The tools

MCP Steroid exposes nine tools. Most are supporting utilities — the core capability is `steroid_execute_code`.

| Tool | What it does |
|------|-------------|
| `steroid_execute_code` | Run Kotlin code inside the IDE's JVM |
| `steroid_take_screenshot` | Capture a screenshot of the IDE window with component metadata |
| `steroid_input` | Send keyboard and mouse events to the IDE |
| `steroid_action_discovery` | Discover available IDE actions and quick-fixes at a code location |
| `steroid_list_projects` | List open projects and IDE version info |
| `steroid_list_windows` | List open IDE windows and their positions |
| `steroid_open_project` | Open a project directory in the IDE |
| `steroid_execute_feedback` | Rate a previous execution (used for learning) |
| `steroid_server_metadata` | Get server version and capability info |

---

## steroid_execute_code — the key tool

The insight behind MCP Steroid is the same one Cloudflare applies to their
[workers-bindings MCP server](https://developers.cloudflare.com/agents/model-context-protocol/mcp-servers-for-cloudflare/):
instead of exposing thousands of individual APIs as separate tools,
expose **one tool that runs code**, and let the code do anything.

The agent submits a Kotlin function body. MCP Steroid compiles it with the Kotlin scripting engine and runs it inside IntelliJ's JVM — the same JVM that is running your IDE right now. The code has access to the full IntelliJ Platform API through a built-in context object:

```kotlin
// Find all files that reference a symbol
val refs = smartReadAction {
    val symbol = findProjectPsiFile("src/main/kotlin/Foo.kt")
        ?.let { ReferencesSearch.search(it).findAll() }
    symbol?.map { it.element.containingFile.virtualFile.path }
}
printJson(refs)
```

```kotlin
// Run inspections on a file and return findings
val findings = runInspectionsDirectly(findProjectPsiFile("src/Main.kt")!!)
printJson(findings.map { it.description })
```

```kotlin
// Check test results after running a suite
val results = readAction {
    SMTestProxy.ROOT.allTests
        .filter { it.isLeaf }
        .map { mapOf("name" to it.name, "passed" to it.isPassed) }
}
printJson(results)
```

Because the code runs inside the IDE's JVM, it is not limited to what MCP Steroid explicitly exposes — it can call any IntelliJ Platform API, navigate the semantic index, trigger refactorings, inspect the debugger state, or query the VCS layer. This is the difference between a **thin wrapper** (like Playwright, which exposes specific browser actions) and an **open runtime** where the agent can express any operation.

---

## Human oversight

Before any submitted code runs, MCP Steroid opens it in the editor and shows a review banner:

> **Review** — Edit code to add comments, then Approve or Reject

Three actions are available:

- **Always Approve** — run this code and auto-approve all future executions for this project
- **Approve** — run this code once
- **Reject (send edits to LLM)** — cancel execution and return any edits you made as feedback to the agent

The agent receives a structured rejection response including the original code, your edited version, and a unified diff — so it can correct course and try again.

Review can be disabled per-project (Settings → Tools → MCP Steroid) or globally via the `mcp.steroid.review.mode` registry key.

---

## End-to-end flow

```
Agent calls steroid_execute_code(project="my-app", code="...", reason="...")
    │
    ▼
MCP Steroid opens code in editor
    │
    ▼
You review and click Approve  ──────────────► Rejection returned to agent
    │
    ▼
Kotlin script engine compiles the code
(fast failure on syntax errors — no timeout wait)
    │
    ▼
Code runs inside the IDE's JVM with access to all IntelliJ APIs
    │
    ▼
Output collected (println / printJson / progress updates)
    │
    ▼
Result returned to agent over HTTP
```

The entire execution is synchronous from the agent's perspective — one HTTP request, one response. Long-running operations stream progress updates back through the SSE channel so the agent can report status without polling.

---

## What this unlocks

Because the agent is running code inside the IDE's runtime — not reading files from disk — it operates at the same level of semantic understanding that the IDE itself uses:

| Operation | Without MCP Steroid | With MCP Steroid |
|-----------|--------------------|-----------------------|
| Find all usages of a symbol | Text search (false positives) | Semantic index query (exact, type-aware) |
| Check for errors | Heuristic parsing | Run actual IntelliJ inspections |
| Rename a class | Regex replace | IDE refactoring (handles all references) |
| Understand a class hierarchy | Read individual files | Traverse the full type hierarchy |
| Run a test | Launch subprocess | Execute in IDE, get live results |
| Inspect visual state | Not possible | Screenshot with component tree |
