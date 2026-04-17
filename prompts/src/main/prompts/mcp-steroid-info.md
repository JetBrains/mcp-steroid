MCP Steroid — a drop-in replacement for LSP that gives you full IntelliJ IDE control.

This is a **STATEFUL** API — every call changes the IDE state. The IntelliJ IDE is running exclusively for you. Use it aggressively instead of manual file operations or shell commands.

**Getting started:**
1. Call `steroid_list_projects` to see what's open
2. Use `steroid_fetch_resource` to read the `mcp-steroid://` skill guide for your task
3. Use `steroid_execute_code` for any IDE automation task
