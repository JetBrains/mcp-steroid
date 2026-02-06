---
title: "Learning Methodology"
description: "How MCP Steroid improves agent reliability with reproducible experiments"
weight: 4
aliases:
  - /learning-methodology/
---

## Why this exists

MCP Steroid targets fast-moving AI agents on fast-moving IDE platforms. Static documentation alone is not enough. We need a repeatable loop that measures what works, what fails, and what should change next.

We treat the MCP server itself as a product that learns. Every prompt, skill description, and tool schema is refined through data-driven iteration -- a user manual designed specifically for AI agents, our primary audience.

## The operating loop

Every agent call is recorded in the `.idea/mcp-steroid` folder. Each invocation includes the caller's stated reason for the call, and agents periodically send feedback with a text message and a score.

This telemetry drives continuous improvement. We analyze call patterns, failure modes, and feedback signals to refine prompts, tool schemas, and skill descriptions. The result is an MCP server that handles increasingly sophisticated tasks across the full surface of IntelliJ-platform IDEs -- including third-party plugins and extensions.

We are looking for your support:
- Share your `.idea/mcp-steroid` logs from real plugin usage
- Submit complete project and task scenarios for benchmarking

Submissions are accepted through [Need Your Experiments and Support](/docs/need-your-experiments-and-support/).
