---
title: "Code Review"
description: "How MCP Steroid lets you review, approve, and reject AI-generated code before it runs"
weight: 4
---

> **Available from 0.89.0.** Earlier versions did not include the always-on server or per-project review settings in Settings.

## Overview

When an AI agent calls `steroid_execute_code`, MCP Steroid opens the code in your editor **before running it** so you can review what the agent is about to execute. A yellow notification banner appears at the top of the editor with three action buttons.

You are always in control. No code runs without your approval.

## The Review Notification

When code is submitted for review, IntelliJ opens a `review.kts` file in the editor and shows a warning banner:

![Review notification banner showing "Review - Edit code to add comments, then Approve or Reject" with action buttons](/graphics/code-review-notification.png)

The banner reads:

> **Review** — Edit code to add comments, then Approve or Reject

You can edit the file before approving — useful for adding comments or modifying what the agent sent.

### Action Buttons

| Button | What it does |
|--------|-------------|
| **Always Approve** | Approves this execution **and** enables auto-approval for all future executions in this project (saves to project settings) |
| **Approve** | Approves and runs this single execution |
| **Reject (send edits to LLM)** | Cancels execution and returns the (optionally edited) file back to the agent as feedback |

## Controlling Review in Settings

You can configure per-project review behaviour in **Settings → Tools → MCP Steroid**:

![MCP Steroid settings panel showing Code Review section with "Automatically approve all MCP Steroid executions for this project" checkbox](/graphics/code-review-settings.png)

The **Code Review** section contains a single checkbox:

> **Automatically approve all MCP Steroid executions for this project**

When checked, all executions are approved without showing the review notification. This is equivalent to clicking **Always Approve** in the banner.

This setting is stored per-project in `.idea/mcp-steroid.xml` and is not shared across projects.

## Global Override via Registry

You can also disable the review prompt globally for all projects using the IntelliJ Registry:

1. Go to **Help → Find Action → Registry…**
2. Search for `mcp.steroid.review.mode`
3. Set the value to `NEVER`

When `NEVER` is active, all executions are auto-approved regardless of the per-project checkbox. The Settings panel will show a note indicating the registry override is active.

The default value is `ALWAYS`, which respects the per-project setting.

| Registry Value | Behaviour |
|---------------|-----------|
| `ALWAYS` (default) | Review prompt shown unless per-project auto-approve is enabled |
| `NEVER` | All executions auto-approved; per-project setting ignored |

## Review Timeout

If you don't respond to a review prompt within the timeout window, the execution is automatically rejected. The default timeout is 600 seconds (10 minutes) and can be changed via:

- Registry key: `mcp.steroid.review.timeout`
- JVM property: `-Dmcp.steroid.review.timeout=300`

## Related

- [Configuration](/docs/configuration/) — full list of registry keys including review settings
- [MCP Connection Settings](/docs/settings-connection-info/) — how the Settings panel works
