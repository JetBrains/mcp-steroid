/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.koltinc.CodeWrapperForCompilation
import com.jonnyzzz.mcpSteroid.koltinc.LineMapping

inline val codeButcher: CodeButcher get() = service()

private val mcpScriptContextFqn = McpScriptContext::class.java.name
private val mcpScriptBuilderFqn = McpScriptBuilder::class.java.name
private val mcpScriptBuilderAddBlock = McpScriptBuilder::addBlock.name

@Service(Service.Level.APP)
class CodeButcher {
    data class ScriptCoordinates(
        val classFqn: String,
        val methodName: String = CodeWrapperForCompilation.DEFAULT_METHOD_NAME,
        val code: String,
        val lineMapping: LineMapping = LineMapping.IDENTITY,
    )

    /** Wrap user code with imports and execute binding. */
    fun wrapToKotlinClass(scriptClassName: String, code: String): ScriptCoordinates {
        val result = CodeWrapperForCompilation.wrap(
            className = scriptClassName,
            code = code,
            scriptContextFqn = mcpScriptContextFqn,
            scriptBuilderFqn = mcpScriptBuilderFqn,
            addBlockName = mcpScriptBuilderAddBlock,
        )
        return ScriptCoordinates(classFqn = result.classFqn, methodName = result.methodName, code = result.code, lineMapping = result.lineMapping)
    }
}
