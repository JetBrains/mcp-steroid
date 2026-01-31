---
title: "Getting Started"
description: "Install and set up MCP Steroid with your AI agent"
weight: 1
---

## What is MCP Steroid?

MCP Steroid gives AI agents **visual understanding and full control** of your IntelliJ-based IDE through the Model Context Protocol. Unlike traditional code assistants that only see text, MCP Steroid lets agents SEE your IDE: dialogs, toolbars, debugger state, test results, and visual elements.

**[Read the full introduction →](https://jonnyzzz.com/blog/2026/01/04/mcp-steroids-intellij/)**

## Installation

MCP Steroid is distributed as an IntelliJ plugin. Currently in early access - [message Eugene Petrenko on LinkedIn](https://linkedin.com/in/jonnyzzz) to get access.

### Requirements

- IntelliJ IDEA 2025.3+ (or any IntelliJ-based IDE: Rider, Android Studio, GoLand, WebStorm, PyCharm, etc.)
- An MCP-compatible AI agent (Claude Code, Codex, Gemini, or any MCP client)

### Install the Plugin

1. Download the plugin ZIP file (provided via LinkedIn)
2. In IntelliJ, go to **Settings > Plugins > Gear icon > Install Plugin from Disk**
3. Select the downloaded ZIP file
4. Restart IntelliJ

## Connecting Your AI Agent

When the plugin starts, it automatically creates a description file at `.idea/mcp-steroid.md` in each open project. This file contains the MCP server URL.

### Finding the Server URL

The MCP server URL is written to `.idea/mcp-steroid.md`. The first line contains the URL:

```
http://127.0.0.1:6315/mcp
```

### Claude Code CLI

The official Anthropic CLI with full MCP support:

```bash
claude mcp add --transport http mcp-steroid http://127.0.0.1:6315/mcp
```

Verify the connection:
```bash
claude -p "List all open projects using steroid_list_projects"
```

### OpenAI Codex CLI

Codex CLI uses a TOML configuration file for HTTP-based MCP servers. Create or edit `~/.codex/config.toml`:

```bash
MCP_URL=$(cat .idea/mcp-steroid.md)
mkdir -p ~/.codex && cat > ~/.codex/config.toml << EOF
[features]
rmcp_client = true

[mcp_servers.intellij-steroid]
url = "$MCP_URL"
EOF
```

Verify the connection:
```bash
codex exec "List all open projects using steroid_list_projects"
```

> **Note:** `codex mcp add` only supports stdio servers; HTTP servers require TOML configuration.

### Google Gemini CLI

The official Google CLI with MCP support:

```bash
MCP_URL=$(cat .idea/mcp-steroid.md)
gemini mcp add intellij-steroid "$MCP_URL" --transport http --scope user

# Verify connection
gemini mcp list
# Should show: ✓ intellij-steroid: <url> (http) - Connected
```

Test the connection:
```bash
gemini "List all open projects using steroid_list_projects"
```

### Cursor (Alternative)

Cursor has basic MCP support. Add to your MCP configuration:

```json
{
  "mcpServers": {
    "mcp-steroid": {
      "url": "http://127.0.0.1:6315/mcp"
    }
  }
}
```

> **Note:** For the best MCP Steroid experience, we recommend using Claude Code CLI, Codex CLI, or Gemini CLI, which have more mature MCP implementations.

### Other MCP Clients

Any MCP-compatible client can connect using the HTTP/SSE transport at the server URL.

## Agent Skills Setup (Optional)

MCP Steroid implements the [Agent Skills](https://agentskills.io) protocol, making capabilities discoverable to AI agents.

### Quick Setup

Download the skill documentation from the running server:

```bash
MCP_URL=$(cat .idea/mcp-steroid.md)
MCP_BASE_URL=${MCP_URL%/mcp}
curl -s "$MCP_BASE_URL/skill.md" > SKILL.md
```

This creates a `SKILL.md` file in your project that AI agents can read to understand available IntelliJ API capabilities.

**Skill Endpoints**:
- `$MCP_BASE_URL/` - Returns SKILL.md content
- `$MCP_BASE_URL/skill.md` - Returns SKILL.md content

For more setup options (symlinks, scripts), see the [README documentation](https://github.com/jonnyzzz/mcp-steroid#agent-skills-support).

## Available Tools

Once connected, your AI agent has access to these MCP tools:

- **steroid_execute_code** - Execute Kotlin code with full IntelliJ API access
- **steroid_list_projects** - List all open projects
- **steroid_list_windows** - List IDE windows and their properties
- **steroid_take_screenshot** - Capture IDE screenshots
- **steroid_input** - Send keyboard/mouse input to the IDE
- **steroid_open_project** - Open a project programmatically
- **steroid_action_discovery** - Discover available editor actions
- **steroid_capabilities** - List IDE capabilities and installed plugins
- **steroid_execute_feedback** - Provide feedback on execution results

For complete API documentation, see the [SKILL.md](https://github.com/jonnyzzz/mcp-steroid/blob/main/SKILL.md) or [README](https://github.com/jonnyzzz/mcp-steroid).

## Human Review Mode

By default, MCP Steroid requires human approval for all code execution (**ALWAYS** mode). When your AI agent sends code:

1. A notification appears in IntelliJ
2. You can review, edit, or reject the code
3. Only approved code gets executed

This ensures you maintain full control over what runs in your IDE.

## Use Cases & Examples

MCP Steroid enables powerful agentic workflows:

- **Multi-Agent Orchestration**: Use a primary agent to coordinate multiple AI agents working on different aspects of your codebase. [Read about orchestrating AI fleets →](https://jonnyzzz.com/blog/2026/01/30/orchestrating-ai-fleets/)

- **Natural Language Development**: Instruct agents in plain English while they handle the technical implementation. [Learn about coding in English with AI →](https://jonnyzzz.com/blog/2026/01/27/coding-in-english-with-ai/)

- **Documentation Refactoring**: See how 16 agents improved documentation quality by 15% and reduced time-to-value by 5x. [Read the case study →](https://jonnyzzz.com/blog/2026/01/24/16-ai-agents-documentation-refactor/)

## Troubleshooting

### Connection Issues

**MCP Server Not Starting**
- Check if IntelliJ is running with a project open
- Verify `.idea/mcp-steroid.md` exists in your project
- Check registry key: `Help > Find Action > Registry...` → `mcp.steroid.server.port`

**Port Conflicts**

If port 6315 is in use, change it:
1. Go to `Help > Find Action > Registry...`
2. Search for `mcp.steroid.server.port`
3. Set a different port (e.g., 6316)
4. Restart IntelliJ
5. Update your MCP client configuration with the new URL from `.idea/mcp-steroid.md`

**Session Recovery After IDE Restart**

When IntelliJ restarts, the MCP server creates a new session. MCP clients will receive a `Mcp-Session-Notice` header and should update their stored session ID automatically.

### Quick Verification

After connecting your AI agent, you should see a list of currently open IntelliJ projects when testing with the `steroid_list_projects` tool. If the connection works, your agent can now access all MCP Steroid capabilities.

For more troubleshooting help, see [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues).

## Next Steps

- [Configuration Options](/docs/configuration/) - Customize server settings, timeouts, and review mode
- [SKILL.md](https://github.com/jonnyzzz/mcp-steroid/blob/main/SKILL.md) - Complete API reference for all MCP tools
- [README](https://github.com/jonnyzzz/mcp-steroid) - Full documentation and advanced setup
- [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues) - Report bugs or request features
