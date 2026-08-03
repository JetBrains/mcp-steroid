/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.UsageError
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one canonical list of tools devrig projects onto the command line: the SAME specs the `devrig mcp`
 * stdio server advertises, from the shared `devrigToolSpecs()` factory. Reading it here — rather than
 * asking a registry what it happens to hold — is what makes "adding a tool adds its subcommand" true with
 * no second list to keep in step.
 *
 * Metadata only: [CliToolSpec.cli] and [CliToolSpec.schema] never resolve a handler, so the specs are
 * built over a tool source that has none.
 */
fun devrigCliTools(): List<CliToolSpec> = MetadataOnlyMcpSteroidTools().devrigToolSpecs()

/**
 * A tool source that exists purely so the specs can be constructed and their metadata read. A handler is
 * resolved only from [McpTool.call], which reading metadata never reaches, so a resolution here signals
 * that parsing has strayed into the runtime — fail loudly instead of quietly opening a bridge connection
 * while parsing a command line.
 */
private class MetadataOnlyMcpSteroidTools : McpSteroidTools() {
    override fun <T> handler(type: Class<T>): T =
        error("handler ${type.name} must not be resolved while parsing the command line")
}

/**
 * One generated [SchemaToolCliCommand] per CLI-visible spec in [tools], in factory order — a
 * [com.jonnyzzz.mcpSteroid.mcp.CliCommandSpec.hidden] spec contributes nothing at all. This is the whole
 * registration rule: there is no per-tool command class, no `when (toolName)`, and no command-name list.
 */
fun schemaToolCliCommands(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand?,
    tools: List<CliToolSpec> = devrigCliTools(),
): List<SchemaToolCliCommand> =
    tools.filterNot { it.cli.hidden }.map { SchemaToolCliCommand(it, selected, parent) }

/**
 * One `devrig <tool>` subcommand, generated from a metadata-only [CliToolSpec]. It PARSES ONLY: Clikt owns
 * tokenizing and routing, [SchemaCliBinding] turns the declaration into typed Clikt parameters, and [run]
 * ends by selecting an inert [DevrigCommand.RunTool]. No handler, service or backend is touched while
 * parsing — the spec bound here is handler-free by construction (see [devrigCliTools]) — and the runtime
 * resolves the live spec later, by [DevrigCommand.RunTool.toolName].
 *
 * Every rule about the parameters themselves lives in the binding: typing, numeric bounds, enum choices,
 * requiredness, the `--flag`/`--no-flag` pair of an optional boolean, and the two rules a
 * [com.jonnyzzz.mcpSteroid.mcp.CliFileSource] implies (not both spellings, and one of them when the tool
 * requires the value). None of them is restated here, and none of them is per-tool.
 */
class SchemaToolCliCommand(
    private val spec: CliToolSpec,
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand?,
) : DevrigToolCliktCommand(
    name = spec.cli.name,
    selected = selected,
    parent = parent,
    // The tool's own one-line synopsis heads `devrig <tool> --help` and labels the command in the root
    // command's subcommand list — the same declared text the generated global section renders.
    help = spec.cli.synopsis,
    // Only a tool whose result can carry an image gets `--out`; the rest refuse it as an unknown option.
    acceptsOut = spec.cli.producesImage,
) {
    private val binding = SchemaCliBinding.bind(this, spec)

    init {
        // The binding rejects collisions among a tool's OWN declarations; this covers the other half — a
        // parameter claiming a devrig framework flag (`--json`, `--debug`, `--out`), which Clikt would
        // resolve to one option and let the other silently shadow.
        val names = registeredOptions().flatMap { it.names + it.secondaryNames }
        val duplicates = names.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "${spec.cli.name}: CLI name(s) $duplicates are claimed both by a parameter and by devrig itself"
        }
    }

    /**
     * This tool's own wording for "you left [paramName] out", or null when the parameter declares none (and
     * Clikt's default wording stands). [paramName] is what a failure reports — `MissingOption.paramName`,
     * `MissingArgument.paramName`, or the name a [MissingCliValue] carries — so one lookup serves every
     * source of a missing-value failure.
     */
    fun missingHintFor(paramName: String): String? = binding.paramFor(paramName)?.cliMissingHint

    /** The `--no-<flag>` spelling of [paramName] when it names an optional boolean, else null; feeds A12's wording. */
    fun negativeFlagFor(paramName: String): String? = binding.paramFor(paramName)?.negativeCliFlag

    override fun run() {
        val options = options()
        val values = binding.parsed()
        rejectFlagLikeValues(values)
        select(
            DevrigCommand.RunTool(
                toolName = spec.name,
                commandName = spec.cli.name,
                arguments = values.arguments,
                fileSources = values.fileSources,
                extraOptions = values.extraOptions,
                out = outPath(),
                debug = options.debug,
                json = options.json,
            )
        )
    }

    /**
     * Rejects a parsed value that is actually one of this command's own flags, given SPACE-SEPARATED. With
     * `--task_id --help` Clikt reads `--help` as `--task_id`'s value and the tool would run with
     * `task_id = "--help"`; with `--code-file --help` it would try to open a file named `--help`. A flag
     * appearing where a value belongs is far more likely a flag the caller meant to pass on its own than a
     * literal value, so it fails as a usage error before any tool call — the flag they wanted never fired,
     * and running the tool with it as data would hide the mistake.
     *
     * The `=` form is the opposite and is NOT rejected: `--code=--help` and `--reason=--json` bind the token
     * to the flag explicitly — an odd value, but an unambiguous one the caller clearly typed on purpose.
     * The parsed values alone cannot tell the two apart (both yield `code = "--help"`), so the raw argv
     * decides: a flag given `=`-joined never appears as its own token, while the forgotten-value form leaves
     * it standing alone. Only a value that also stands alone in [rawArgs] is refused.
     */
    private fun rejectFlagLikeValues(values: SchemaCliValues) {
        val knownFlags = registeredOptions().flatMap { it.names + it.secondaryNames }.toSet()
        val standaloneTokens = rawArgs().toSet()
        fun reject(paramName: String, token: String): Nothing = throw UsageError(
            "'$token' is a devrig flag, not a value; it was read as the value of '$paramName'. " +
                "A flag cannot be another option's value — pass a real value for '$paramName', " +
                "give '$token' on its own, or bind it explicitly as '$paramName=$token' if you truly meant it."
        )
        fun scan(paramName: String, element: JsonElement) {
            val token = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
            if (token in knownFlags && token in standaloneTokens) reject(paramName, token)
        }
        for ((name, element) in values.arguments) {
            when (element) {
                is JsonArray -> element.forEach { scan(name, it) }
                else -> scan(name, element)
            }
        }
        for ((name, path) in values.fileSources) {
            if (path in knownFlags && path in standaloneTokens) reject(name, path)
        }
    }
}
