---
title: "MCP Steroid Release v0.87.0"
description: "New skills system, stability improvements, and Docker-based integration tests"
_build:
  list: never
  render: always
---

<div class="success">
<p><strong>Welcome to MCP Steroid v0.87.0!</strong> This release brings a major rework of the skills and resources system, stability improvements, and comprehensive integration test infrastructure.</p>
</div>

**Release Date:** February 8, 2026

Note: this plugin is currently tested only on macOS.

## Reporting issues

Use "Collect Logs and Diagnostics Data" action in IntelliJ
to collect information about the problem.
Review the log file for specific problem.

## Slack Community

Message Eugene on LinkedIn for a Slack invite: [linkedin.com/in/jonnyzzz](https://linkedin.com/in/jonnyzzz)
Workspace: [mcp-steroid.slack.com](https://mcp-steroid.slack.com)

Direct invite link: [join.slack.com/t/mcp-steroid/shared_invite/zt-3p3oq91kx-BXJng8GSXveqncFVYWUcpQ](https://join.slack.com/t/mcp-steroid/shared_invite/zt-3p3oq91kx-BXJng8GSXveqncFVYWUcpQ)

That is the best place to discuss the plugin, ideas, features.

## Download

Download the latest plugin build from GitHub Releases:

- **[The Release on GitHub](https://github.com/jonnyzzz/mcp-steroid/releases/tag/0.87.0)**

**[End User License Agreement (EULA)](/LICENSE)** - By downloading and using this plugin, you agree to the terms of the EULA.

### Installation

1. Download the `.zip` file from the GitHub release above
2. In IntelliJ IDEA, go to **Settings** → **Plugins** → ⚙️ → **Install Plugin from Disk...**
3. Select the downloaded ZIP file
4. Restart the IDE


## Connecting to Cloud Agents

See [Getting Started](/docs/getting-started/).

## Supported AI Agents

Claude Code, Codex, Gemini, Cursor, and any other coding agent that supports the latest MCP via HTTP Streaming.

## What's New in v0.87.0

### Skills & Resources System Overhaul

The entire skills and resources system has been rebuilt from the ground up:

- **Build-time code generation** — Prompt files are compiled to Kotlin classes with KotlinPoet, providing type-safe access to all skill content
- **Rich article model** — Each resource now has a header, payload, description, and cross-references (see-also links) generated automatically
- **Table of contents** — Auto-generated TOC per resource folder, making it easy for agents to discover related content
- **Skill catalog & prompt registry** — Skills are properly registered as MCP prompts and resources, so agents can discover them via standard MCP protocol

### Plugin No Longer Expires

The time-bomb mechanism from the preview release has been removed. The plugin no longer has a build expiration date.

### Stability & Bug Fixes

- **Fix for paths with spaces** — Kotlinc argfile escaping now correctly handles project paths containing spaces
- **MCP session stability** — Server restart now properly resets the Ktor server, fixing stale session issues
- **MCP readiness check** — Improved readiness detection and compiler stderr handling
- **Reduced storage noise** — `list_projects` and `list_windows` calls are no longer logged to the `.idea/mcp-steroid` folder

### Improved Tool Output

- `steroid_list_projects` and `steroid_list_windows` now include IDE product info and PID in their responses
- Tool preference integration for AI agents — agents can express which tools they prefer

### Docker-Based Integration Test Infrastructure

A comprehensive integration test framework for running AI agents inside Docker containers with a full IntelliJ IDE:

- **IdeContainerSession** — Manages the full lifecycle of an IntelliJ instance inside Docker
- **Live video streaming** — Watch agent sessions in real-time via an HTML dashboard
- **Multi-agent support** — Test with Claude Code, Codex, and Gemini CLIs in isolated containers
- **IntelliJ Community Edition** — Integration tests now run against Community Edition for broader compatibility

### Website & Documentation

- Published Strategy, Learning Methodology, and Experiments pages
- Improved docs layout with weight-based page ordering
- Added PostHog analytics for understanding website usage
- Video carousel on homepage

## Feedback

We're actively learning what content and features work best for the plugin. Your feedback is invaluable!

If you encounter any issues or have suggestions, please let us know in [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues)! Your feedback directly shapes the development of MCP Steroid.


## Support the Project

This project needs funding to continue development, testing, and support.

[![GitHub Sponsors](https://img.shields.io/badge/GitHub-Sponsors-EA4AAA?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/jonnyzzz)

- Sponsor MCP Steroid: [github.com/sponsors/jonnyzzz](https://github.com/sponsors/jonnyzzz)
- Contact Eugene on LinkedIn: [linkedin.com/in/jonnyzzz](https://linkedin.com/in/jonnyzzz)

---

*Thank you for using MCP Steroid!*
