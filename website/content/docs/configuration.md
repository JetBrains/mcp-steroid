---
title: "Configuration"
description: "Registry keys and settings for MCP Steroid"
weight: 2
---

MCP Steroid can be configured via IntelliJ's Registry (`Help > Find Action > Registry`) or via JVM system properties (`-D` flags). All settings use the `mcp.steroid.*` prefix.

## Server Settings

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.server.port` | `6315` | MCP server port. Use `0` for auto-assign. The actual URL is written to `.idea/mcp-steroid.md`. |
| `mcp.steroid.server.host` | `127.0.0.1` | MCP server bind address. Use `0.0.0.0` for Docker/remote access. |

## Review & Execution

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.review.mode` | `ALWAYS` | Code review mode: `ALWAYS` (default), `TRUSTED`, or `NEVER`. |
| `mcp.steroid.review.timeout` | `600` | Review timeout in seconds. |
| `mcp.steroid.execution.timeout` | `600` | Script execution timeout in seconds. |

## Storage

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.storage.path` | (empty) | Custom path for MCP execution storage. Empty uses `.idea/mcp-steroid/`. |
| `mcp.steroid.idea.description.enabled` | `true` | Generate `.idea/mcp-steroid.md` description file in projects. |

## Demo Mode

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.demo.enabled` | `false` | Enable Demo Mode overlay during MCP execution. |
| `mcp.steroid.demo.minDisplayTime` | `3000` | Minimum display time in milliseconds. |
| `mcp.steroid.demo.maxLines` | `15` | Maximum log lines to show in overlay. |
| `mcp.steroid.demo.opacity` | `85` | Background opacity (0-100). |
| `mcp.steroid.demo.focusFrame` | `true` | Bring project frame to front when showing overlay. |

## Updates

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.updates.enabled` | `true` | Enable automatic update checks. |

---

> **Note:** When the plugin starts, it writes the server URL to `.idea/mcp-steroid.md` in each open project. The first line contains the URL (for example, `http://127.0.0.1:6315/mcp`). This file is your MCP client's connection target.

> **Tip:** You can also set these as JVM system properties by using `-Dmcp.steroid.server.port=8080` in your IDE's VM options. System properties take precedence over Registry values.
