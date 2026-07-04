/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

/**
 * Wraps Kotlin code into a compilable class with imports and execution binding.
 *
 * This is the shared implementation used by both:
 * - `CodeButcher` in ij-plugin (for runtime script execution)
 * - `KtBlockCompilationTestBase` in prompts (for compilation-only testing)
 *
 * The caller supplies the FQNs for McpScriptContext and McpScriptBuilder since
 * kotlin-cli doesn't depend on ij-plugin and can't resolve them via reflection.
 */
object CodeWrapperForCompilation {
    val defaultImports = listOf(
        "import com.intellij.openapi.project.*",
        "import com.intellij.openapi.application.*",
        "import com.intellij.openapi.application.readAction",
        "import com.intellij.openapi.application.writeAction",
        "import com.intellij.openapi.vfs.*",
        "import com.intellij.openapi.editor.*",
        "import com.intellij.openapi.fileEditor.*",
        "import com.intellij.openapi.command.*",
        "import com.intellij.psi.*",
        "import com.intellij.psi.search.*",
        "import com.intellij.psi.search.searches.*",
        "import com.intellij.psi.util.*",
        "import kotlinx.coroutines.*",
        "import kotlin.time.Duration.Companion.seconds",
        "import kotlin.time.Duration.Companion.minutes",
        // Expose ApplyPatchBuilder / ApplyPatchResult / ApplyPatchException so that
        // fenced-block examples in `ide/apply-patch.md` and user scripts can use
        // the DSL classes by short name — e.g. `catch (e: ApplyPatchException)`
        // without a fully-qualified name.
        "import com.jonnyzzz.mcpSteroid.execution.ApplyPatchBuilder",
        "import com.jonnyzzz.mcpSteroid.execution.ApplyPatchException",
        "import com.jonnyzzz.mcpSteroid.execution.ApplyPatchResult",
    )

    const val DEFAULT_SCRIPT_CONTEXT_FQN = "com.jonnyzzz.mcpSteroid.execution.McpScriptContext"
    const val DEFAULT_SCRIPT_BUILDER_FQN = "com.jonnyzzz.mcpSteroid.execution.McpScriptBuilder"
    const val DEFAULT_ADD_BLOCK_NAME = "addBlock"
    const val DEFAULT_METHOD_NAME = "jonnyzzz_execute_all_script_content_77"

    data class WrapResult(
        val classFqn: String,
        val methodName: String,
        val code: String,
        val lineMapping: LineMapping,
    )

    /**
     * Result of extracting import lines from user code, with original line number tracking.
     */
    data class ExtractedCode(
        val importLines: List<String>,
        val otherLines: List<String>,
        /** For each otherLines[i], its 1-based line number in the original user code */
        val otherLineNumbers: List<Int>,
        /** For each importLines[i], its 1-based line number in the original user code */
        val importLineNumbers: List<Int>,
    )

    /**
     * Extracts import lines from code while respecting triple-quoted strings,
     * and returns (importLines, otherLines).
     */
    fun extractImports(code: String): Pair<List<String>, List<String>> {
        val result = extractImportsWithLineNumbers(code)
        return result.importLines to result.otherLines
    }

    /**
     * Extracts import lines from code while respecting triple-quoted strings,
     * tracking original line numbers for each extracted line.
     */
    fun extractImportsWithLineNumbers(code: String): ExtractedCode {
        val importLines = mutableListOf<String>()
        val otherLines = mutableListOf<String>()
        val importLineNumbers = mutableListOf<Int>()
        val otherLineNumbers = mutableListOf<Int>()
        var tripleQuoteCount = 0
        var lineNumber = 0
        for (line in code.lineSequence()) {
            lineNumber++
            val inTripleQuotedString = tripleQuoteCount % 2 != 0
            var idx = 0
            while (idx <= line.length - 3) {
                if (line[idx] == '"' && line[idx + 1] == '"' && line[idx + 2] == '"') {
                    tripleQuoteCount++
                    idx += 3
                } else {
                    idx++
                }
            }
            if (!inTripleQuotedString && line.trim().trimStart(';').trim().startsWith("import ")) {
                importLines.add(line)
                importLineNumbers.add(lineNumber)
            } else {
                otherLines.add(line)
                otherLineNumbers.add(lineNumber)
            }
        }
        return ExtractedCode(
            importLines = importLines,
            otherLines = otherLines,
            otherLineNumbers = otherLineNumbers,
            importLineNumbers = importLineNumbers,
        )
    }

    /**
     * Wraps user code into a compilable Kotlin class.
     *
     * @param className base name for the generated class (sanitized internally)
     * @param code the user code to wrap
     * @param scriptContextFqn FQN of the McpScriptContext class
     * @param scriptBuilderFqn FQN of the McpScriptBuilder class
     * @param addBlockName name of the addBlock method on the builder
     * @param methodName name of the generated entry-point method
     */
    fun wrap(
        className: String,
        code: String,
        scriptContextFqn: String = DEFAULT_SCRIPT_CONTEXT_FQN,
        scriptBuilderFqn: String = DEFAULT_SCRIPT_BUILDER_FQN,
        addBlockName: String = DEFAULT_ADD_BLOCK_NAME,
        methodName: String = DEFAULT_METHOD_NAME,
    ): WrapResult {
        val clazzName = className.replace("[^a-z0-9_]+".toRegex(RegexOption.IGNORE_CASE), "_")
        val extracted = extractImportsWithLineNumbers(code)
        val importLines = extracted.importLines
        val otherLines = extracted.otherLines

        // Build the wrapped code AND its line mapping in lock-step: every line goes through
        // [WrappedLineEmitter.emit], which appends it and advances the wrapped-line counter
        // together. This is drift-proof — earlier the offsets were hardcoded (12 default
        // imports → user code at line 23+N), so growing [defaultImports] silently shifted every
        // user line and broke remapping (compiler errors then pointed at the generated line,
        // not the submitted one). Because the counter can only move inside emit(), the mapping
        // can never drift from the generated layout. Never reintroduce magic offsets here.
        val mapping = mutableMapOf<Int, Int>()
        val wrappedCode = buildString {
            val out = WrappedLineEmitter(this)
            defaultImports.forEach { out.emit(it) }
            out.emit()
            out.emit("//imports from the submitted code")
            importLines.forEachIndexed { i, line ->
                out.emit(line)
                mapping[out.line] = extracted.importLineNumbers[i]
            }
            out.emit()
            out.emit("class $clazzName {")
            out.emit("  inline fun $scriptContextFqn.execute(ƒ: $scriptContextFqn.() -> Unit) = ƒ()")
            out.emit("  fun $methodName(builder : $scriptBuilderFqn) { ")
            out.emit("    builder.$addBlockName { ${methodName}_code() }")
            out.emit("  }")
            out.emit("  suspend fun $scriptContextFqn.${methodName}_code() {")
            out.emit("    //the rest of submitted code")
            otherLines.forEachIndexed { i, line ->
                out.emit("    $line")
                mapping[out.line] = extracted.otherLineNumbers[i]
            }
            out.emit("  }")
            out.emit("}")
            append("\n")
        }

        val lineMapping = LineMapping(mapping)

        return WrapResult(classFqn = clazzName, methodName = methodName, code = wrappedCode, lineMapping = lineMapping)
    }

    /**
     * Single choke point for emitting wrapped-code lines. Appending and advancing the
     * wrapped-line counter happen together in [emit], so the line mapping built in [wrap]
     * cannot drift from the generated layout (the bug this design replaced used hardcoded
     * offsets keyed off the default-import count).
     *
     * [emit] rejects any text containing a newline: `appendLine` would then write more than
     * one physical line while the counter advances by one, silently desyncing every later
     * mapping entry — exactly the failure mode we are guarding against.
     */
    private class WrappedLineEmitter(private val sb: StringBuilder) {
        /** 1-based number of the last emitted line. */
        var line: Int = 0
            private set

        fun emit(text: String = "") {
            require('\n' !in text) { "emit() writes exactly one line; embedded newline would desync the line counter: <$text>" }
            sb.appendLine(text)
            line++
        }
    }
}
