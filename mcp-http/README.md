# mcp-http Module

## Overview

HTTP transport layer for MCP servers using Ktor. Handles HTTP-based JSON-RPC communication (POST/GET/DELETE), CORS, session header management, and server lifecycle.

Depends on `mcp-core` for protocol types and `McpServerCore`.

## Scope

### Extracted from `ij-plugin`

| Current file | Target in mcp-http | Changes needed |
|---|---|---|
| `mcp/McpHttpTransport.kt` | `com.jonnyzzz.mcpSteroid.transport.http.McpHttpTransport` | Replace IntelliJ Logger with SLF4J; move to new package |

### What stays in `ij-plugin`

- `SteroidsMcpServer.kt` — IntelliJ `@Service` that creates the Ktor app, discovers port, and wires extension points. It will call into `mcp-http` to install MCP routes.
- All Ktor app-level configuration (plugins, SSE, additional routes like `/skill.md`, `/.well-known/mcp.json`)

### Package

`com.jonnyzzz.mcpSteroid.transport.http`

## Public API

### McpHttpTransport

```kotlin
object McpHttpTransport {
    const val SESSION_HEADER = "Mcp-Session-Id"
    const val SESSION_NOTICE_HEADER = "Mcp-Session-Notice"
    const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"

    /**
     * Install MCP HTTP routes at the given path on a Ktor Route.
     *
     * Routes:
     *   OPTIONS {path} — CORS preflight
     *   POST    {path} — JSON-RPC request/notification handling
     *   GET     {path} — Server info / health check
     *   DELETE  {path} — Session termination
     */
    fun Route.installMcp(path: String, server: McpServerCore)
}
```

### Session Management (via headers)

- Creates new session if `Mcp-Session-Id` header is missing or unknown
- Returns `Mcp-Session-Id` header on new session creation
- Returns `Mcp-Session-Notice` header when stored session ID is stale
- Session lookup delegates to `McpServerCore.sessionManager`

### CORS

- Responds to `OPTIONS` with permissive CORS headers
- Adds CORS headers to all responses (`Access-Control-Allow-Origin: *`, etc.)

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    api(project(":mcp-core"))

    implementation("io.ktor:ktor-server-core:3.1.0")
    // Note: does NOT depend on a specific Ktor engine (CIO, Netty, etc.)
    // The engine choice remains with the consumer (ij-plugin, test harness)

    implementation("org.slf4j:slf4j-api:2.0.+")

    testImplementation("io.ktor:ktor-server-test-host:3.1.0")
    testImplementation("io.ktor:ktor-server-cio:3.1.0")
}
```

**No IntelliJ Platform dependencies.**

## Integration with ij-plugin

After extraction, `SteroidsMcpServer.kt` in `ij-plugin` changes from:

```kotlin
// Before (direct access)
import com.jonnyzzz.mcpSteroid.mcp.McpHttpTransport
McpHttpTransport.run { installMcp("/mcp", server) }
```

to:

```kotlin
// After (module dependency)
import com.jonnyzzz.mcpSteroid.transport.http.McpHttpTransport
McpHttpTransport.run { installMcp("/mcp", server) }
```

The only change is the import path. All behavior remains identical.

## Testing

### Protocol-level integration tests (new)

Tests should verify MCP protocol compliance over HTTP using `ktor-server-test-host` or an embedded CIO server:

1. **Initialize handshake** — POST `initialize` → verify `InitializeResult` with capabilities, protocol version header
2. **Tool lifecycle** — register tools → `tools/list` → `tools/call` → verify results
3. **Resource lifecycle** — register resources → `resources/list` → `resources/read`
4. **Prompt lifecycle** — register prompts → `prompts/list` → `prompts/get`
5. **Session management** — verify session creation, reuse, stale notification, deletion via DELETE
6. **Error handling** — invalid JSON, unknown method, invalid params → proper JSON-RPC error codes
7. **CORS** — OPTIONS preflight → verify headers
8. **Batch requests** — send JSON array → verify batch response

Use the MCP specification (2025-11-25) as the reference for expected behavior. Consult the official MCP spec via Perplexity MCP for exact protocol requirements.

## Constraints

- Does **not** include Ktor engine dependency — consumers choose their engine
- Does **not** include SSE streaming (future addition if needed)
- Does **not** handle server lifecycle (port binding, startup/shutdown) — that stays with the consumer
- The HTTP transport is stateless beyond session header management
