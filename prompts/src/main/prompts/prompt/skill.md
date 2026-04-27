IntelliJ API Power User Guide

RECOMMENDED: Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to IntelliJ Platform APIs.


# MCP Steroid - IDE API Access for AI Agents

Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to the IntelliJ Platform API.

## Important Notes for AI Agents

**Learning Curve**: Writing working code for IntelliJ APIs may require several attempts. This is normal! The API is vast and powerful. Keep trying - each attempt teaches you more about the available APIs. Use `printException()` to see stack traces when errors occur.

**Drop-in replacement for LSP**: This MCP server replaces LSP (Language Server Protocol) tools with IntelliJ's native APIs — same operations, deeper understanding:
- PSI (Program Structure Interface) instead of LSP document symbols — full semantic analysis
- IntelliJ inspections, refactorings, intentions instead of LSP code actions
- Full project model with module dependencies instead of workspace folders
- Platform-specific indices for O(1) code search instead of filesystem scans

## Quickstart Flow

```
1. steroid_list_projects → get list of open projects
2. Pick a project_name from the list
3. steroid_execute_code → run Kotlin code with that project
4. steroid_execute_feedback → report success/failure for tracking
```

**Example session:**
```
→ steroid_list_projects
← {"ide":{"name":"IntelliJ IDEA","version":"2025.3.2","build":"IU-253.30387.160"},"projects":[{"name":"my-app","path":"/path/to/my-app"}]}

→ steroid_execute_code(project_name="my-app", code="println(project.name)", ...)
← "my-app"

→ steroid_execute_feedback(project_name="my-app", task_id="...", execution_id="...", success_rating=1.0, explanation="Got project name")
```

## When to Use This Skill

**ALWAYS prefer IntelliJ APIs over file-based operations:**

| Instead of...                   | Use IntelliJ API               |
|---------------------------------|--------------------------------|
| Reading files with `cat`/`read` | VFS and PSI APIs               |
| Searching with `grep`/`find`    | Find Usages, Structural Search |
| Manual text replacement         | Automated refactorings         |
| Guessing code structure         | Query project model directly   |

The IDE has indexed everything. It knows the code better than any file search.

## Available Tools

### `steroid_list_projects`
List all open projects. Returns IDE metadata and project names for use with `steroid_execute_code`.

### `steroid_list_windows`
List open IDE windows and their associated projects. Some windows may not be tied to a project and a project can have multiple windows.
Use this in multi-window setups to pick the correct `project_name` and `window_id` for screenshot/input tools.

### `steroid_action_discovery`
Discover available editor actions, quick-fixes, and gutter actions for a file and caret context.

**Parameters:**
- `project_name` (required): Target project name
- `file_path` (required): Absolute or project-relative path to the file
- `caret_offset` (optional): Caret offset within the file (default: 0)
- `action_groups` (optional): Action group IDs to expand (default: editor popup + gutter)
- `max_actions_per_group` (optional): Cap actions returned per group (default: 200)

### `steroid_take_screenshot`
Capture a screenshot of the IDE frame and return image content.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters:**
- `project_name` (required): Target project name
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the screenshot is needed
- `window_id` (optional): Window id from `steroid_list_windows` to target a specific window

**Artifacts (saved under the execution folder):**
- `screenshot.png`
- `screenshot-tree.md`
- `screenshot-meta.json`

Use the returned `execution_id` as `screenshot_execution_id` for `steroid_input`. The response includes `window_id` (also stored in `screenshot-meta.json`).

### `steroid_input`
Send input events (keyboard + mouse) using a sequence string.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters:**
- `project_name` (required): Target project name
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the input is needed
- `screenshot_execution_id` (required): Execution ID from `steroid_take_screenshot` or `takeIdeScreenshot()`
- `sequence` (required): Comma-separated or newline-separated input sequence (commas inside values are allowed unless they look like `, <step>:`; commas are optional when using newlines)

**Sequence examples:**
- `stick:ALT, delay:400, press:F4, type:hurra`
- `click:CTRL+Left@120,200`
- `click:Right@screen:400,300`

**Notes:**
- Comma separators are detected by `, <step>:` patterns, so avoid typing `, delay:` etc in text.
- Trailing commas before a newline are ignored.
- Use `#` for comments until the end of the line.
- Targets default to screenshot coordinates; use `screen:` for absolute screen pixels.
- Input focuses the screenshot window before dispatching events.

### `steroid_execute_code`
**Execute code with IntelliJ's brain, not just text files.**

Give your AI agent a senior developer's toolkit: semantic code understanding, automated refactorings, and IDE intelligence that LSP can't provide.

**Parameters:** `project_name`, `code` (Kotlin suspend function body), `task_id`, `reason`, `timeout` (optional)

**Returns:** Execution output with `execution_id` for feedback

**Complete guide:** `mcp-steroid://skill/coding-with-intellij` (API reference, patterns, examples, best practices)

### `steroid_execute_feedback`
Rate execution results. Use after `steroid_execute_code`.

### `steroid_open_project`
Open a project in the IDE. This tool initiates the project opening process and returns quickly.

**IMPORTANT**: This tool does NOT wait for the project to fully open. The project opening process may show dialogs (such as "Trust Project", project type selection, etc.) that require interaction. Use `steroid_take_screenshot` and `steroid_input` tools to interact with any dialogs that appear.

**Parameters:**
- `project_path` (required): Absolute path to the project directory to open
- `task_id` (required): Task identifier for logging
- `reason` (required): Why you are opening the project
- `trust_project` (optional): If true, trust the project path before opening (skips trust dialog). Default: true

**Workflow:**
1. Call `steroid_open_project` with the project path
2. If `trust_project=true`, the project will be trusted automatically (no trust dialog)
3. Call `steroid_take_screenshot` to see the current IDE state
4. If dialogs are shown, use `steroid_input` to interact with them
5. Call `steroid_list_projects` to verify the project is open

## MCP Resources (Use Them)

This server exposes built-in resources through the MCP resource APIs. These are the fastest way to load full examples and guides without guessing or copy/pasting from the web.

**How to access resources:**
1. Call `steroid_fetch_resource` with a `mcp-steroid://` URI to load the content.
2. Or use `list_mcp_resources` to browse all available resources.

**Key resources provided by this server:**
- `mcp-steroid://prompt/skill` - This guide as a resource.
- `mcp-steroid://skill/coding-with-intellij` - Comprehensive guide for writing IntelliJ API code (execution model, patterns, examples).
- `mcp-steroid://prompt/debugger-skill` - Debugger-focused skill guide (breakpoints, sessions, threads).
- `mcp-steroid://lsp/overview` - Overview of LSP-like examples and how to use them.
- `mcp-steroid://lsp/<id>` - Runnable Kotlin scripts (e.g., `go-to-definition`, `find-references`, `rename`, `code-action`, `signature-help`).
- `mcp-steroid://ide/overview` - Overview of IDE power operation examples (refactorings, inspections, generation).
- `mcp-steroid://ide/<id>` - Runnable Kotlin scripts (e.g., `extract-method`, `introduce-variable`, `change-signature`, `safe-delete`, `optimize-imports`, `pull-up-members`, `push-down-members`, `extract-interface`, `move-class`, `generate-constructor`, `call-hierarchy`, `project-dependencies`, `inspection-summary`, `project-search`, `run-configuration`).
- `mcp-steroid://debugger/overview` - Overview of debugger examples (breakpoints, sessions, threads).
- `mcp-steroid://debugger/<id>` - Runnable Kotlin scripts (e.g., `set-line-breakpoint`, `debug-run-configuration`, `debug-session-control`, `debug-list-threads`, `debug-thread-dump`).
- `mcp-steroid://open-project/overview` - Guide for opening projects via MCP.
- `mcp-steroid://open-project/<id>` - Project opening examples (e.g., `open-trusted`, `open-with-dialogs`, `open-via-code`).

These resources are designed to be plugged directly into `steroid_execute_code` after you configure file paths/positions.

## Critical Rules

These are the essential rules you must follow. For detailed examples and patterns, read `mcp-steroid://skill/coding-with-intellij`.

### 1. Script Body is a SUSPEND Function
```kotlin
// This is a coroutine - use suspend APIs!
// waitForSmartMode() is called automatically before your script starts.
delay(1000)         // coroutine delay - works directly
```
**NEVER use `runBlocking`** - it causes deadlocks.

**NEVER re-probe `waitForSmartMode()` before every operation.** The automatic wait before script
start is only a point-in-time check; IntelliJ may enter dumb mode again before the next statement.
For index-dependent PSI queries, wrap the whole query in `smartReadAction { }`. After project
open/import/sync/configuration, first await `Observation.awaitConfiguration(project)`, then use
`smartReadAction { }`.

### 2. Imports Are Optional

Default imports are provided automatically. Add imports only when you need APIs outside the defaults.
Imports must be at the top of the script, never after code.

### 3. Read/Write Actions for PSI/VFS

> **THREADING RULE — NEVER SKIP**: Any PSI access **MUST** be inside `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately.

**Built-in helpers (no imports needed):**
```kotlin
// Reading PSI/VFS/indices
val data = readAction { project.name }

// Modifying PSI/VFS/documents
writeAction { /* modifications here */ }

// Runs index-dependent PSI work under IntelliJ's smart-mode read constraint
val smart = smartReadAction { /* PSI operations */ }
```

For detailed threading patterns, see `mcp-steroid://skill/coding-with-intellij-threading`.

### 4. Context API

Built-in helpers available in every script (no imports needed):

| Category | APIs |
|----------|------|
| **Properties** | `project`, `disposable`, `isDisposed` |
| **Output** | `println()`, `printJson()`, `progress()`, `printException()` |
| **Read/Write** | `readAction { }`, `writeAction { }`, `smartReadAction { }` |
| **Scopes** | `projectScope()`, `allScope()` |
| **File access** | `findFile()`, `findPsiFile()`, `findProjectFile()`, `findProjectPsiFile()` |
| **Analysis** | `runInspectionsDirectly()` |

Full API reference: `mcp-steroid://skill/coding-with-intellij-context-api`

### 5. Running Tests

**Always prefer the IntelliJ IDE runner over `./mvnw test` or `./gradlew test`.**
The IDE runner returns a simple exit code (0 = all passed), shows structured results, and reuses the running JVM.

See `mcp-steroid://skill/coding-with-intellij` → **"Run Tests via IntelliJ IDE Runner"** for the complete pattern.

Only fall back to CLI test commands when the IDE runner cannot be used. Even then, **never print the full output** — always `take(30) + takeLast(30)` to avoid MCP token limit errors.

## Error Handling

Use `printException` for errors - it includes the stack trace in the output:

```kotlin
try {
    // risky operation
} catch (e: Exception) {
    printException("Operation failed", e)
}
```

## Troubleshooting

### Check if Server is Running
The MCP server runs inside IntelliJ. To verify:
1. Open IntelliJ IDEA with the MCP Steroid plugin installed
2. Open any project
3. Check `.idea/mcp-steroid.md` in the project folder for the server URL
4. The server port is configurable via `mcp.steroid.server.port`; read `.idea/mcp-steroid.md` for the active URL

### Endpoints
- `/` - Returns this SKILL.md content
- `/skill.md` - Same as above
- `/mcp` - MCP protocol endpoint for tool calls
- `/.well-known/mcp.json` - MCP server discovery

### MCP Resources (Preferred)
Use MCP `resources/list` and `resources/read` instead of HTTP fetching when possible.

### Common Issues
- **"Project not found"** - Run `steroid_list_projects` first to get exact project names
- **No output from execute** - Make sure to call `println()` or `printJson()` to see results
- **Timeout** - Increase `timeout` parameter (default 60 seconds)
- **Script errors** - Check Kotlin syntax; imports are optional

## Detailed Guides

For API examples, patterns, and in-depth coverage, read the dedicated articles:

| Topic | Resource |
|-------|----------|
| **Full guide (start here)** | `mcp-steroid://skill/coding-with-intellij` |
| **Execution model & script structure** | `mcp-steroid://skill/coding-with-intellij-intro` |
| **PSI operations & code analysis** | `mcp-steroid://skill/coding-with-intellij-psi` |
| **Document, editor & VFS operations** | `mcp-steroid://skill/coding-with-intellij-vfs` |
| **Threading & read/write actions** | `mcp-steroid://skill/coding-with-intellij-threading` |
| **Common patterns & project info** | `mcp-steroid://skill/coding-with-intellij-patterns` |
| **Refactoring, completion & services** | `mcp-steroid://skill/coding-with-intellij-refactoring` |
| **McpScriptContext API reference** | `mcp-steroid://skill/coding-with-intellij-context-api` |
| **Java & Spring Boot patterns** | `mcp-steroid://skill/coding-with-intellij-spring` |

### Other Resources
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Debug workflows and stateful execution
- [Test Runner Guide](mcp-steroid://prompt/test-skill) - Test execution patterns
- [LSP Examples](mcp-steroid://lsp/overview) - LSP-like operations (navigation, code intelligence, refactoring)
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations (refactorings, inspections, generation)
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugger workflows and API usage
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [VCS Examples](mcp-steroid://vcs/overview) - Version control operations (git blame, history)
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows

---

**This is like LSP, but more powerful.** IntelliJ APIs offer deeper code understanding and more features than standard LSP. Don't settle for file-level operations when you have IDE-level access.
