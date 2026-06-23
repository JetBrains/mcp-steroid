/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class OpenProjectToolSpecSchemaTest {
    @Test
    fun `inputSchema default omits backend_name`() {
        val spec = OpenProjectToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_open_project")
        assertRequiredExactly(schema, "project_path", "task_id", "reason")
        assertStringProperty(schema, "project_path")
        assertStringProperty(schema, "task_id")
        assertStringProperty(schema, "reason")
        assertBooleanProperty(schema, "trust_project")
        assertPropertyAbsent(schema, "backend_name")
    }

    @Test
    fun `inputSchema with backend name exposes optional backend_name`() {
        val spec = OpenProjectToolSpec(includeBackendName = true) { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_open_project")
        // backend_name is optional: omit when there is exactly one candidate; the handler picks it
        // automatically. The required set does NOT include backend_name.
        assertRequiredExactly(schema, "project_path", "task_id", "reason")
        assertStringProperty(schema, "backend_name")
    }
}
