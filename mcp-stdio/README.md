# mcp-stdio Module

## Overview

Stdio transport layer for MCP servers. Reads JSON-RPC messages from stdin and writes responses to stdout, delegating dispatch to `McpServerCore` from `mcp-core`.

Adapted from the framing and I/O logic in `npx-kt`, but designed as a reusable transport — not a proxy. `npx-kt` can later be adapted to use this module instead of its own `StdioServer`.

## Scope

### Adapted from `npx-kt`

| npx-kt source | Reused concept | Notes |
|---|---|---|
| `StdioServer.kt` | Stdin/stdout framing, NDJSON detection | Core I/O loop adapted, proxy logic removed |
| `Framing.kt` | Frame buffer for length-prefixed messages | Utility extracted as-is |

### What this module does NOT include

- **Proxy/aggregation logic** — that stays in `npx-kt` (`ServerRegistry`, `Protocol.kt`)
- **Server discovery** — not a concern of the transport layer
- **Any IntelliJ dependencies**

### Package

`com.jonnyzzz.mcpSteroid.transport.stdio`

## Public API

### McpStdioTransport

```kotlin
class McpStdioTransport(
    private val server: McpServerCore,
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
) {
    /**
     * Run the stdio MCP server. Blocks until EOF on input.
     * Reads JSON-RPC messages, dispatches via McpServerCore,
     * and writes responses + notifications to output.
     *
     * Handles both:
     *   - NDJSON (newline-delimited JSON) — one JSON object per line
     *   - Length-prefixed framing — Content-Length header followed by body
     *
     * Auto-detects mode from the first message.
     */
    suspend fun run()

    /**
     * Process a single JSON-RPC message string.
     * Returns the response string, or null for notifications.
     */
    suspend fun handleMessage(message: String, session: McpSession): String?
}
```

### Session Management

Unlike HTTP (which uses headers for session identity), stdio uses a **single implicit session** for the lifetime of the transport:

```kotlin
// Internal — created on first message
private val session: McpSession = server.sessionManager.createSession()
```

Notifications from the server are written to stdout interleaved with responses.

### Framing Support

```kotlin
/**
 * Reads frames from an InputStream.
 * Supports both NDJSON and Content-Length framing.
 */
class FrameReader(input: InputStream) {
    suspend fun readFrame(): String?  // null = EOF
}

/**
 * Writes frames to an OutputStream.
 */
class FrameWriter(output: OutputStream) {
    fun writeFrame(message: String)
}
```

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    api(project(":mcp-core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.slf4j:slf4j-api:2.0.+")

    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
```

**No IntelliJ Platform dependencies. No Ktor dependencies.**

## Integration with npx-kt

After extraction, `npx-kt` has two migration paths:

1. **Immediate:** `npx-kt` continues with its own `StdioServer.kt` and `Protocol.kt` (no changes needed — experimental module)
2. **Future:** `npx-kt` depends on `mcp-stdio` and uses `McpStdioTransport` for the server half, while keeping its proxy/aggregation logic in `ServerRegistry`

The ticket's success criteria only require that `npx-kt` *can be adapted* — not that it is immediately migrated.

## Testing

### Protocol-level integration tests (new)

Tests use piped `InputStream`/`OutputStream` to simulate stdio:

1. **Initialize handshake** — write `initialize` request → read `InitializeResult` response
2. **Tool lifecycle** — register tools → `tools/list` → `tools/call` → verify results
3. **Resource lifecycle** — register resources → `resources/list` → `resources/read`
4. **Prompt lifecycle** — register prompts → `prompts/list` → `prompts/get`
5. **NDJSON mode** — send newline-delimited JSON → verify line-delimited responses
6. **Length-prefixed mode** — send framed messages → verify framed responses
7. **Notification delivery** — trigger server notification → verify it appears on stdout
8. **Error handling** — malformed JSON, unknown method → proper JSON-RPC errors
9. **Batch requests** — send JSON array → verify batch response
10. **EOF handling** — close input → verify graceful shutdown

Use the MCP specification (2025-11-25) as the reference. Consult the official spec via Perplexity MCP for exact stdio transport requirements.

## Constraints

- Single session per transport instance (stdio is point-to-point)
- No concurrent request handling guarantee — messages are processed sequentially from stdin
- Output is synchronized (no interleaved partial writes)
- The transport owns the session lifecycle (creates on start, closes on EOF)
