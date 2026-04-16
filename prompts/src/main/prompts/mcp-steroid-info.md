Use this MCP to access IntelliJ-based IDE and manipulate the code like a professional developer.

**Before starting work, read the skill guide that matches your task** using `ReadMcpResourceTool`:

| Task | Read this first | URI for ReadMcpResourceTool |
|------|----------------|---------------------------|
| Build / compile | Test & Build patterns | `mcp-steroid://test/overview` |
| Run tests | Test Runner Skill | `mcp-steroid://prompt/test-skill` |
| Debug | Debugger Skill | `mcp-steroid://prompt/debugger-skill` |
| Refactor code | IDE Operations | `mcp-steroid://ide/overview` |
| Navigate code / find usages | Coding Guide | `mcp-steroid://skill/coding-with-intellij` |
| Any IDE automation | Power User Guide | `mcp-steroid://prompt/skill` |

Call `ListMcpResourcesTool(server="intellij-steroid")` to browse all 84 available resources with copy-paste code recipes.

**Available tools:**
- `steroid_execute_code` — run Kotlin code in the IDE (builds, tests, refactoring, inspections, navigation)
- `steroid_list_projects` — list open projects (call this first)
- `steroid_list_windows` — check window state, indexing, dialogs
- `steroid_open_project` — open a project directory
- `steroid_action_discovery` — discover quick-fixes and actions at a location
- `steroid_take_screenshot` / `steroid_input` — visual inspection and interaction
- `steroid_execute_feedback` — rate results to improve suggestions

**Getting started:**
1. Call `steroid_list_projects` to see what's open
2. Read the skill guide for your task (table above)
3. Use `steroid_execute_code` with patterns from the guide
