MCP Steroid — a drop-in replacement for LSP that gives you full IntelliJ IDE control.

This is a **STATEFUL** API — every call changes the IDE state. The IntelliJ IDE is running exclusively for you. Use it aggressively instead of manual file operations or shell commands.

**File edits: always through MCP Steroid, even when Edit looks cheaper on tokens.** The native `Edit` tool writes to disk bypassing IntelliJ. VFS + PSI + search indices go stale, and the next semantic operation (find-references, rename, hierarchy search, inspections) returns inconsistent results until something forces a refresh. The 5-line `VfsUtil.saveText` recipe in `steroid_execute_code`'s tool description reads+writes in one call with auto-refresh; its 1.5–2.5× token overhead is cheaper than the debugging turns you spend when PSI disagrees with disk. This applies to every edit size, including 1–3 line changes.

**Getting started:**
1. Call `steroid_list_projects` to see what's open
2. Use `steroid_fetch_resource` to read the `mcp-steroid://` skill guide for your task
3. Use `steroid_execute_code` for any IDE automation task (including every file edit)
