# MCP Protocol Specification Gap Analysis

> Archived on 2026-05-24 from the stale `intellij-mcp-steroids-2` worktree. File paths inside
> (`src/main/kotlin/com/jonnyzzz/intellij/mcp/...`) reflect the pre-refactor single-module layout;
> current paths live under `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/`. Cancellation work
> has since landed (see `TASKS.md` Cluster A, 2026-05-19); most other items remain open.

**Date**: 2026-01-29
**Current Implementation Version**: 2025-11-25
**Target Specification Version**: 2025-11-25

## Executive Summary

This document provides a detailed gap analysis between the current IntelliJ MCP Steroids implementation and the official MCP specification (2025-11-25). It identifies missing features, protocol compliance issues, and prioritizes work items following TDD principles.

---

## Current Implementation Status

### What IS Implemented ✅

| Feature | Status | Notes |
|---------|--------|-------|
| JSON-RPC 2.0 | ✅ Full | Batch requests supported |
| HTTP POST transport | ✅ Full | Client-to-server messages |
| HTTP DELETE | ✅ | Session termination |
| `initialize` / `initialized` | ✅ | Capability negotiation |
| `ping` | ✅ | Server health check |
| `tools/list` | ✅ | Tool discovery |
| `tools/call` | ✅ | Tool execution |
| `resources/list` | ✅ | Resource discovery |
| `resources/read` | ✅ | Resource content retrieval |
| `sampling/createMessage` | ✅ | Server-initiated LLM sampling |
| Session management | ✅ | `Mcp-Session-Id` header |
| CORS | ✅ | Full support |
| Progress notifications | ✅ | Throttled at 1/sec |

### What IS NOT Implemented ❌

#### Critical Protocol Compliance

| Issue | Priority | Spec Requirement |
|-------|----------|------------------|
| Protocol version outdated | P0 | Must be `2025-11-25` |
| `MCP-Protocol-Version` header | P0 | Required on all HTTP requests |
| HTTP GET for SSE | P0 | Required for server-to-client streaming |
| Origin header validation | P0 | Security requirement |

#### Server Capabilities

| Feature | Priority | Spec Section |
|---------|----------|--------------|
| `prompts` capability | P1 | Server Features / Prompts |
| `logging` capability | P1 | Server Utilities / Logging |
| `completions` capability | P2 | Server Utilities / Completion |
| `resources/subscribe` | P2 | Server Features / Resources |
| `resources/templates/list` | P2 | Server Features / Resources |

#### Client Features (Server Supports)

| Feature | Priority | Spec Section |
|---------|----------|--------------|
| Roots support | P2 | Client Features / Roots |
| Elicitation | P1 | Client Features / Elicitation |

#### New Features (2025-11-25)

| Feature | Priority | Spec Section |
|---------|----------|--------------|
| Tasks (async operations) | P2 | Base Protocol / Tasks |

#### Protocol Details

| Feature | Priority | Notes |
|---------|----------|-------|
| Pagination (cursor-based) | P1 | `tools/list`, `resources/list`, `prompts/list` |
| Tool `title` field | P3 | Display name |
| Tool `icons` field | P3 | Visual representation |
| Tool `outputSchema` | P2 | Structured output validation |
| Tool annotations | P2 | `execution.taskSupport` |
| Resource `title` field | P3 | Display name |
| Resource `icons` field | P3 | Visual representation |
| Resource `annotations` | P2 | `audience`, `priority`, `lastModified` |
| `notifications/cancelled` | P1 | Request cancellation |

---

## Detailed Gap Analysis

### 1. Protocol Version & Headers

**Current State**:
- `protocolVersion: "2025-11-25"` in McpServerCore.kt
- No `MCP-Protocol-Version` header validation

**Required Changes**:
1. Update `PROTOCOL_VERSION` constant to `"2025-11-25"`
2. Add `MCP-Protocol-Version` header to all HTTP responses
3. Validate incoming `MCP-Protocol-Version` header on requests (after init)
4. Return `400 Bad Request` for invalid/unsupported protocol versions

### 2. Streamable HTTP Transport (SSE)

**Current State**:
- HTTP GET returns JSON health check, not SSE stream
- No support for server-initiated messages via SSE

**Required Changes**:
1. Implement HTTP GET to return `text/event-stream` (SSE)
2. Support `Accept: text/event-stream` header detection
3. Implement SSE event format with `id` fields
4. Support `Last-Event-ID` header for stream resumption
5. Implement connection keep-alive and proper SSE termination

### 3. Origin Header Validation (Security)

**Current State**:
- No Origin header validation

**Required Changes**:
1. Validate `Origin` header on all incoming connections
2. Return `403 Forbidden` for invalid origins
3. Allow localhost origins by default
4. Make allowed origins configurable

### 4. Prompts Capability

**Current State**:
- Protocol types exist but not wired up
- `prompts` capability not advertised

**Required Changes**:
1. Create `PromptsCapability` in server capabilities
2. Implement `prompts/list` handler with pagination
3. Implement `prompts/get` handler
4. Create `McpPromptRegistry` similar to tool/resource registries
5. Add `notifications/prompts/list_changed` support

### 5. Logging Capability

**Current State**:
- Internal logging only, not exposed via MCP

**Required Changes**:
1. Advertise `logging` capability
2. Implement `logging/setLevel` handler
3. Implement `notifications/message` notifications
4. Create `McpLoggingService` to bridge IntelliJ logging

### 6. Completions Capability

**Current State**:
- Not implemented

**Required Changes**:
1. Advertise `completions` capability
2. Implement `completion/complete` handler
3. Support `ref/prompt` and `ref/resource` reference types
4. Integrate with IntelliJ completion providers

### 7. Resource Subscriptions

**Current State**:
- `subscribe: false` in capabilities

**Required Changes**:
1. Set `subscribe: true` in resources capability
2. Implement `resources/subscribe` handler
3. Implement `resources/unsubscribe` handler
4. Implement `notifications/resources/updated` notifications
5. Track subscriptions per session

### 8. Resource Templates

**Current State**:
- Not implemented

**Required Changes**:
1. Implement `resources/templates/list` handler
2. Create `McpResourceTemplateRegistry`
3. Support URI template expansion

### 9. Pagination

**Current State**:
- No pagination for list operations

**Required Changes**:
1. Add `cursor` parameter support to `tools/list`
2. Add `cursor` parameter support to `resources/list`
3. Add `cursor` parameter support to `prompts/list`
4. Return `nextCursor` in responses when more items available
5. Implement cursor generation and validation

### 10. Cancellation Support

**Current State**:
- Not implemented

**Required Changes**:
1. Handle `notifications/cancelled` notification
2. Track active requests by ID
3. Implement request cancellation logic
4. Clean up resources on cancellation

### 11. Elicitation (New in 2025-11-25)

**Current State**:
- Not implemented

**Required Changes**:
1. Implement `elicitation/create` request (server-to-client)
2. Support form mode with JSON Schema validation
3. Support URL mode for sensitive operations
4. Implement `notifications/elicitation/complete`

### 12. Tasks (New in 2025-11-25)

**Current State**:
- Not implemented

**Required Changes**:
1. Add `tasks` capability
2. Implement `tasks/get` handler
3. Implement `tasks/list` handler
4. Implement `tasks/result` handler
5. Implement `tasks/cancel` handler
6. Implement `notifications/tasks/status`
7. Support task-augmented tool calls

### 13. Tool Enhancements

**Current State**:
- Basic tool definition

**Required Changes**:
1. Add `title` field to tool definitions
2. Add `icons` field to tool definitions
3. Add `outputSchema` field for structured outputs
4. Add `annotations` field for tool behavior hints
5. Add `execution.taskSupport` for task-augmented requests

### 14. Resource Enhancements

**Current State**:
- Basic resource definition

**Required Changes**:
1. Add `title` field to resource definitions
2. Add `icons` field to resource definitions
3. Add `annotations` field (audience, priority, lastModified)

---

## Implementation Plan

### Phase 1: Critical Protocol Compliance (P0)

1. **Update protocol version** (1 commit)
2. **Add MCP-Protocol-Version header** (1 commit)
3. **Origin header validation** (1 commit)
4. **SSE transport basics** (2-3 commits)

### Phase 2: Core Server Features (P1)

1. **Pagination support** (2 commits)
2. **Cancellation support** (1 commit)
3. **Prompts capability** (3 commits)
4. **Logging capability** (2 commits)
5. **Elicitation support** (2 commits)

### Phase 3: Enhanced Features (P2)

1. **Completions capability** (2 commits)
2. **Resource subscriptions** (2 commits)
3. **Resource templates** (1 commit)
4. **Tool outputSchema** (1 commit)
5. **Tasks support** (3-4 commits)
6. **Roots support** (1 commit)

### Phase 4: Polish (P3)

1. **Icons support** (1 commit)
2. **Title fields** (1 commit)
3. **Resource annotations** (1 commit)

---

## Test Coverage Requirements

Each feature must have:
1. **Unit test** for the handler/capability
2. **Integration test** via `McpServerIntegrationTest`
3. **Protocol compliance test** checking JSON-RPC format

### Existing Test Files to Update

- `McpServerIntegrationTest.kt` - Protocol compliance
- `CliClaudeIntegrationTest.kt` - Claude CLI compatibility
- `CliCodexIntegrationTest.kt` - Codex CLI compatibility
- `CliGeminiIntegrationTest.kt` - Gemini CLI compatibility

### New Test Files Needed

- `PromptsCapabilityTest.kt`
- `LoggingCapabilityTest.kt`
- `CompletionCapabilityTest.kt`
- `ResourceSubscriptionTest.kt`
- `SseTransportTest.kt`
- `TasksCapabilityTest.kt`
- `ElicitationTest.kt`
- `CancellationTest.kt`
- `PaginationTest.kt`

---

## Work Breakdown (Atomic Commits)

### Sprint 1: Protocol Compliance

| # | Task | Type | Files |
|---|------|------|-------|
| 1.1 | Add test: protocol version should be 2025-11-25 | Test | `McpServerIntegrationTest.kt` |
| 1.2 | Update PROTOCOL_VERSION constant | Fix | `McpProtocol.kt` |
| 1.3 | Add test: MCP-Protocol-Version header required | Test | `McpServerIntegrationTest.kt` |
| 1.4 | Implement MCP-Protocol-Version header validation | Fix | `McpHttpTransport.kt` |
| 1.5 | Add test: Origin header validation | Test | `McpHttpTransport.kt` |
| 1.6 | Implement Origin header validation | Fix | `McpHttpTransport.kt` |

### Sprint 2: SSE Transport

| # | Task | Type | Files |
|---|------|------|-------|
| 2.1 | Add test: HTTP GET returns SSE stream | Test | `SseTransportTest.kt` |
| 2.2 | Implement basic SSE response | Fix | `McpHttpTransport.kt` |
| 2.3 | Add test: SSE event IDs | Test | `SseTransportTest.kt` |
| 2.4 | Implement SSE event ID generation | Fix | `McpHttpTransport.kt` |
| 2.5 | Add test: Last-Event-ID resumption | Test | `SseTransportTest.kt` |
| 2.6 | Implement SSE stream resumption | Fix | `McpHttpTransport.kt` |

### Sprint 3: Pagination

| # | Task | Type | Files |
|---|------|------|-------|
| 3.1 | Add test: tools/list with cursor | Test | `McpServerIntegrationTest.kt` |
| 3.2 | Implement pagination in McpToolRegistry | Fix | `McpToolRegistry.kt` |
| 3.3 | Add test: resources/list with cursor | Test | `McpServerIntegrationTest.kt` |
| 3.4 | Implement pagination in McpResourceRegistry | Fix | `McpResourceRegistry.kt` |

### Sprint 4: Cancellation

| # | Task | Type | Files |
|---|------|------|-------|
| 4.1 | Add test: notifications/cancelled handling | Test | `CancellationTest.kt` |
| 4.2 | Implement cancellation notification handler | Fix | `McpServerCore.kt` |
| 4.3 | Add test: cancelled request cleanup | Test | `CancellationTest.kt` |
| 4.4 | Implement request tracking and cleanup | Fix | `McpServerCore.kt` |

### Sprint 5: Prompts Capability

| # | Task | Type | Files |
|---|------|------|-------|
| 5.1 | Add test: prompts capability advertised | Test | `PromptsCapabilityTest.kt` |
| 5.2 | Create McpPromptRegistry | Fix | `McpPromptRegistry.kt` |
| 5.3 | Add test: prompts/list handler | Test | `PromptsCapabilityTest.kt` |
| 5.4 | Implement prompts/list handler | Fix | `McpServerCore.kt` |
| 5.5 | Add test: prompts/get handler | Test | `PromptsCapabilityTest.kt` |
| 5.6 | Implement prompts/get handler | Fix | `McpServerCore.kt` |

### Sprint 6: Logging Capability

| # | Task | Type | Files |
|---|------|------|-------|
| 6.1 | Add test: logging capability advertised | Test | `LoggingCapabilityTest.kt` |
| 6.2 | Create McpLoggingService | Fix | `McpLoggingService.kt` |
| 6.3 | Add test: logging/setLevel handler | Test | `LoggingCapabilityTest.kt` |
| 6.4 | Implement logging/setLevel handler | Fix | `McpServerCore.kt` |
| 6.5 | Add test: notifications/message | Test | `LoggingCapabilityTest.kt` |
| 6.6 | Implement log message notifications | Fix | `McpLoggingService.kt` |

---

## Sources

- [MCP Specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)
- [MCP GitHub Repository](https://github.com/modelcontextprotocol/modelcontextprotocol)
- [TypeScript Schema](https://github.com/modelcontextprotocol/specification/blob/main/schema/2025-11-25/schema.ts)
