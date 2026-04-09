# mcp-features Module

## Overview

Declares the **skeleton** of all MCP tool, resource, and prompt features as interfaces and method declarations. Provides a context-based registration pattern where consumers (like `ij-plugin`) supply concrete implementations.

This module answers the question: "What tools/resources/prompts does the MCP server offer?" without answering "How are they implemented?"

Depends on `mcp-core` for protocol types and registry APIs.

## Scope

### What this module defines

1. **Tool declarations** — one interface method per tool, with typed parameters and return types
2. **Resource declarations** — declared resource URIs and their content providers
3. **Prompt declarations** — declared prompts and their renderers
4. **Context interface** — `McpFeatureContext` that consumers implement to provide handlers
5. **Registration wiring** — function that takes a context and registers everything with `McpServerCore`

### What this module does NOT include

- **Concrete implementations** of any tool, resource, or prompt handler
- **IntelliJ Platform APIs** — all IDE interaction happens in `ij-plugin`'s implementations
- **Transport logic** — that's `mcp-http` and `mcp-stdio`

### Package

`com.jonnyzzz.mcpSteroid.features`

## Public API

### Tool Declarations

Each tool currently in `ij-plugin` gets a corresponding method declaration in the context interface. The method signature captures the tool's typed inputs and outputs:

```kotlin
/**
 * Context interface that consumers implement to provide tool handlers.
 * Each method corresponds to one MCP tool.
 *
 * Implementations are free to use IDE-specific APIs, file I/O, 
 * external services, etc. — this interface only declares the contract.
 */
interface McpFeatureContext {
    // --- Tool handlers ---

    /** List open projects with their paths and metadata */
    suspend fun listProjects(context: ToolCallContext): ToolCallResult

    /** List open IDE windows */
    suspend fun listWindows(context: ToolCallContext): ToolCallResult

    /** Execute code snippet in the IDE's scripting environment */
    suspend fun executeCode(context: ToolCallContext): ToolCallResult

    /** Discover available IDE actions matching a query */
    suspend fun discoverActions(context: ToolCallContext): ToolCallResult

    /** Take a screenshot of the IDE window */
    suspend fun takeScreenshot(context: ToolCallContext): ToolCallResult

    /** Send input events to the IDE (keyboard/mouse) */
    suspend fun sendInput(context: ToolCallContext): ToolCallResult

    /** Open a project by path */
    suspend fun openProject(context: ToolCallContext): ToolCallResult

    /** Submit structured feedback about a tool execution */
    suspend fun executeFeedback(context: ToolCallContext): ToolCallResult

    // --- Optional resource/prompt providers ---

    /** Register additional resources beyond the built-in tool set */
    fun registerResources(registry: McpResourceRegistry) {}

    /** Register additional prompts beyond the built-in tool set */
    fun registerPrompts(registry: McpPromptRegistry) {}
}
```

### Tool Metadata

Each tool's name, description, and JSON schema are declared as constants:

```kotlin
object McpToolDefinitions {
    val LIST_PROJECTS = ToolDef(
        name = "_list_projects",
        description = "List open projects...",
        inputSchema = buildJsonObject { /* ... */ }
    )
    val LIST_WINDOWS = ToolDef(
        name = "_list_windows",
        description = "List open IDE windows...",
        inputSchema = buildJsonObject { /* ... */ }
    )
    val EXECUTE_CODE = ToolDef(...)
    val ACTION_DISCOVERY = ToolDef(...)
    val VISION_SCREENSHOT = ToolDef(...)
    val VISION_INPUT = ToolDef(...)
    val OPEN_PROJECT = ToolDef(...)
    val EXECUTE_FEEDBACK = ToolDef(...)

    /** All tool definitions in registration order */
    val ALL: List<ToolDef> = listOf(...)
}

data class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)
```

### Registration Function

```kotlin
/**
 * Register all features from the given context with the MCP server.
 * This is the single entry point that wires up all tools, resources, and prompts.
 */
fun registerFeatures(server: McpServerCore, context: McpFeatureContext) {
    // Register each tool using its metadata + context handler
    server.toolRegistry.registerTool(
        name = McpToolDefinitions.LIST_PROJECTS.name,
        description = McpToolDefinitions.LIST_PROJECTS.description,
        inputSchema = McpToolDefinitions.LIST_PROJECTS.inputSchema,
        handler = { context.listProjects(it) }
    )
    // ... same for all tools

    // Let context register additional resources and prompts
    context.registerResources(server.resourceRegistry)
    context.registerPrompts(server.promptRegistry)
}
```

## Integration with ij-plugin

`ij-plugin` provides the concrete implementation:

```kotlin
// In ij-plugin
class IdeFeatureContext(private val project: Project) : McpFeatureContext {
    override suspend fun listProjects(context: ToolCallContext): ToolCallResult {
        // Uses ProjectManager, IDE-specific APIs
        // ... existing ListProjectsToolHandler logic ...
    }

    override suspend fun executeCode(context: ToolCallContext): ToolCallResult {
        // Uses ScriptEngine, IDE-specific APIs
        // ... existing ExecuteCodeToolHandler logic ...
    }

    // ... etc for all tools
}

// In SteroidsMcpServer setup:
val context = IdeFeatureContext(project)
registerFeatures(server, context)
```

### Migration from McpRegistrar

The current `McpRegistrar` extension point pattern (where each tool handler is a separate class registered in `plugin.xml`) can coexist during migration:

1. **Phase 1:** `mcp-features` declares the context interface; `ij-plugin` implements it alongside existing `McpRegistrar` handlers
2. **Phase 2:** Existing tool handler classes delegate to the context implementation
3. **Phase 3:** `McpRegistrar` handlers are removed; `registerFeatures()` is the sole registration path

This allows non-breaking migration.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    api(project(":mcp-core"))
    
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
```

**No IntelliJ Platform dependencies. No transport dependencies.**

## Testing

Tests verify the registration wiring with a mock context:

1. **Registration completeness** — `registerFeatures()` registers all declared tools with `McpServerCore`
2. **Tool metadata accuracy** — each tool's name, description, and schema match expectations
3. **Context delegation** — calling a registered tool handler invokes the correct context method
4. **Default resource/prompt registration** — default no-op implementations don't fail

## Constraints

- Tool method signatures use `ToolCallContext` (from `mcp-core`) — not IDE-specific types
- Parameter parsing from `ToolCallContext.params` is the responsibility of the implementation, not this module
- New tools added in the future require adding a method to `McpFeatureContext` (breaking change by design — ensures all implementations stay in sync)
- The context interface is intentionally non-default for tool methods — implementations must be explicit
