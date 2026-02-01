---
title: "MCP Steroid Preview Release v0.86.0"
description: "Early access preview of MCP Steroid plugin"
_build:
  list: never
  render: always
---

<div class="success">
<p><strong>Welcome to the MCP Steroid Preview!</strong> Congratulations on getting early access to the plugin. We're excited to have you as part of our preview program!</p>
</div>

## Preview Release

This is an **early preview version** of MCP Steroid. Thank you for helping us test and improve the plugin!

<div class="warning">
<p><strong>Important:</strong> This preview build will expire in <strong>10 days</strong>. After expiration, you'll need to obtain a new build to continue using the plugin.</p>
</div>

Not, this plugin is only tested to run on macOS. 

## Reporting issues

Use "Collect Logs and Diagnostics Data" action in IntelliJ
to collect information about the problem.
Review the log file for specific problem.

## Slack Community

[DM Eugene](https://linkedin.com/in/jonnyzzz) to get invite to the Slack community
[MCP-Steroid Community](https://mcp-steroidcommunity.slack.com) 

That is the best place to discuss the plugin, ideas, features.

## Download

Download the latest plugin build from GitHub Releases:

- **[The Release on GitHub](https://github.com/jonnyzzz/mcp-steroid/releases/tag/0.86.0-SNAPSHOT-2026-02-01-20-29-4ad98c4)**
- [v0.86.0-SNAPSHOT (2026-02-01).zip](https://github.com/jonnyzzz/mcp-steroid/releases/tag/0.86.0-SNAPSHOT-2026-02-01-20-29-4ad98c4) - Current preview plugin build

### Installation

1. Download the `.zip` file from the GitHub release above
2. In IntelliJ IDEA, go to **Settings** → **Plugins** → ⚙️ → **Install Plugin from Disk...**
3. Select the downloaded ZIP file (e.g., `mcp-steroid-0.86.0-SNAPSHOT-2026-02-01-20-29-4ad98c4.zip`)
4. Restart the IDE

## Help Us Improve

We're actively learning what content and features work best for the plugin. Your feedback is invaluable!

<div class="note">
<p><strong>Please share your usage data:</strong> After using the plugin for a while, please send us your <code>.idea/mcp-steroid</code> folder. This helps us understand real-world usage patterns and improve the plugin.</p>
</div>

## Connecting to Cloud Agents

See [Getting Started](/docs/getting-started/).

### How to Submit Your Data

You can submit your `.idea/mcp-steroid` folder in any of these ways:

1. **Email**: Send a ZIP of the folder to us
2. **GitHub**: Create a gist or attach it to an issue
3. **Direct message**: Share via your preferred communication channel

The folder contains execution logs and metadata that help us understand how the plugin is being used in practice.

## Supported AI Agents

| Agent | Connection Method |
|-------|-------------------|
| **Claude Code CLI** | `claude mcp add --transport http mcp-steroid <URL>` |
| **Codex CLI** | TOML config at `~/.codex/config.toml` |
| **Gemini CLI** | `gemini mcp add intellij-steroid <URL> --transport http` |
| **Cursor** | JSON config in `mcpServers` section |
| **Any MCP Client** | HTTP transport at server URL |

Prompt your AI Agent to use IntelliJ, explicitly. Ask what does it see in your IntelliJ to start with.

## Notable Features in v0.86.0

This is the **first public release** of MCP Steroid, bringing full IntelliJ Platform automation 
to AI agents through MCP protocol.

## Feedback

If you encounter any issues or have suggestions, please let us know! Your feedback directly shapes the development of MCP Steroid.

---

*Thank you for being an early adopter!*
