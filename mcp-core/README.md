# mcp-core Module

## Overview

Transport-agnostic MCP (Model Context Protocol) server core. Contains the protocol data types, JSON-RPC dispatch, session management, and tool/resource/prompt registries — with **zero IntelliJ Platform dependencies**.

This module is the foundation that `mcp-http`, `mcp-stdio`, `mcp-features`, and `ij-plugin` all depend on.

## Scope

### Extracted from `ij-plugin`

All files currently in `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/mcp/`:

| Current file | Target in mcp-core | Changes needed |
|---|---|---|
| `McpProtocol.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpProtocol` | None — already portable |
| `McpJson.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpJson` | None — already portable |
| `McpServerCore.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpServerCore` | Replace `com.intellij.openapi.diagnostic.thisLogger` with SLF4J |
| `McpSession.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpSession` | Replace IntelliJ Logger with SLF4J |
| `McpToolRegistry.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpToolRegistry` | Replace IntelliJ Logger with SLF4J |
| `McpResourceRegistry.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpResourceRegistry` | Replace IntelliJ Logger with SLF4J |
| `McpPromptRegistry.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpPromptRegistry` | Replace IntelliJ Logger with SLF4J |
| `McpRootsService.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpRootsService` | Replace IntelliJ Logger with SLF4J |
| `McpBuilders.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpBuilders` | None — already portable |

Additionally extracted from `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/`:

| Current file | Target in mcp-core | Changes needed |
|---|---|---|
| `McpProgressReporter.kt` | `com.jonnyzzz.mcpSteroid.mcp.McpProgressReporter` | None — already a plain interface |

### Package

`com.jonnyzzz.mcpSteroid.mcp` (unchanged — enables gradual migration)

## Public API

### Protocol Types (`McpProtocol.kt`)

All `@Serializable` data classes for MCP 2025-11-25:

- **JSON-RPC:** `JsonRpcRequest`, `JsonRpcNotification`, `JsonRpcResponse`, `JsonRpcError`
- **Initialization:** `InitializeParams`, `ClientInfo`, `ClientCapabilities`, `InitializeResult`, `ServerInfo`, `ServerCapabilities`
- **Tools:** `Tool`, `ToolsListParams`, `ToolsListResult`, `ToolCallParams`, `ToolCallResult`
- **Content:** `ContentItem` (sealed: Text, Image, Resource), `EmbeddedResource`
- **Sampling:** `CreateMessageParams`, `SamplingMessage`, `SamplingContent`, `CreateMessageResult`, `ModelPreferences`, `ModelHint`
- **Resources:** `Resource`, `ResourcesListParams`, `ResourcesListResult`, `ResourceReadParams`, `ResourceReadResult`, `ResourceContent`
- **Prompts:** `Prompt`, `PromptArgument`, `Icon`, `PromptsListParams`, `PromptsListResult`, `PromptGetParams`, `PromptMessage`, `PromptContent`, `PromptGetResult`
- **Roots:** `Root`, `RootsListResult`
- **Progress:** `ProgressParams`
- **Constants:** `MCP_PROTOCOL_VERSION`, `JSONRPC_VERSION`, `McpMethods`, `JsonRpcErrorCodes`

### JSON Configuration (`McpJson.kt`)

```kotlin
val McpJson: Json  // ignoreUnknownKeys, encodeDefaults, classDiscriminator="type"
```

### Server Core (`McpServerCore.kt`)

```kotlin
class McpServerCore(
    val serverInfo: ServerInfo,
    capabilities: ServerCapabilities,
    instructions: String? = null,
) {
    val sessionManager: McpSessionManager
    val toolRegistry: McpToolRegistry
    val resourceRegistry: McpResourceRegistry
    val promptRegistry: McpPromptRegistry

    suspend fun handleMessage(message: String, session: McpSession): String?
    fun notifyToolsListChanged()
}
```

### Session Management (`McpSession.kt`)

```kotlin
class McpSession(val id: String = UUID.randomUUID().toString()) {
    fun markInitialized(info: ClientInfo, capabilities: ClientCapabilities)
    fun supportsSampling(): Boolean
    fun supportsRoots(): Boolean
    fun sendNotification(notification: JsonRpcNotification)
    fun notifications(): Flow<JsonRpcNotification>
    fun outgoingRequests(): Flow<JsonRpcRequest>
    suspend fun sendRequest(method: String, params: JsonElement?, timeout: Duration): JsonElement?
    fun handleResponse(id: String, result: JsonElement?): Boolean
    fun handleErrorResponse(id: String, error: JsonRpcError): Boolean
    fun close()
}

class McpSessionManager {
    fun createSession(): McpSession
    fun getSession(id: String): McpSession?
    fun removeSession(id: String)
    fun getAllSessions(): Collection<McpSession>
}
```

### Registries

```kotlin
class McpToolRegistry {
    fun registerTool(name: String, description: String?, inputSchema: JsonObject,
                     handler: suspend (ToolCallContext) -> ToolCallResult)
    fun listTools(): List<Tool>
    suspend fun callTool(params: ToolCallParams, session: McpSession): ToolCallResult
}

class McpResourceRegistry {
    fun registerResource(uri: String, name: String, description: String?,
                         mimeType: String, contentProvider: () -> String)
    fun registerResourceMultiContent(uri: String, name: String, description: String?,
                                      mimeType: String, contentsProvider: () -> List<ResourceContent>)
    fun listResources(): List<Resource>
    fun readResource(uri: String): ResourceReadResult?
}

class McpPromptRegistry {
    fun registerPrompt(prompt: Prompt, renderer: (PromptGetParams) -> PromptGetResult)
    fun listPrompts(): List<Prompt>
    fun getPrompt(params: PromptGetParams): PromptGetResult?
}
```

### Tool Call Context

```kotlin
data class ToolCallContext(
    val params: ToolCallParams,
    val session: McpSession,
    val mcpProgressReporter: McpProgressReporter,
) {
    fun supportsSampling(): Boolean
    suspend fun requestSampling(...): CreateMessageResult?
    suspend fun requestCompletion(prompt: String, ...): String?
}

interface McpProgressReporter {
    fun report(message: String)
}

object NoOpProgressReporter : McpProgressReporter
```

### Roots Service

```kotlin
class McpRootsService {
    suspend fun getRoots(session: McpSession, forceRefresh: Boolean = false): List<Root>?
    fun handleRootsListChanged(session: McpSession)
    fun clearCache(sessionId: String)
}
```

### Builders

```kotlin
fun ToolCallResult.Companion.builder(): ToolCallBuilder

class ToolCallBuilder {
    fun addTextContent(content: String): ToolCallBuilder
    fun addContent(content: ContentItem): ToolCallBuilder
    fun markAsError(): ToolCallBuilder
    fun build(): ToolCallResult
}
```

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.slf4j:slf4j-api:2.0.+")
}
```

**No IntelliJ Platform dependencies.**

## Logging Migration

All usages of `com.intellij.openapi.diagnostic.Logger` / `thisLogger()` are replaced with:

```kotlin
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(ClassName::class.java)
```

This is a mechanical replacement — the IntelliJ Logger API is a thin wrapper around SLF4J anyway.

## Constraints

- Package name stays `com.jonnyzzz.mcpSteroid.mcp` to minimize churn in dependent code
- No breaking API changes — all public signatures remain identical
- `ij-plugin` switches from source-level access to module dependency
- Unit tests for protocol serialization/deserialization should be added in this module
