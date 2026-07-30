/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.ProcessedArgument
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.jonnyzzz.mcpSteroid.mcp.CliExtraOption
import com.jonnyzzz.mcpSteroid.mcp.CliOptionType
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaParamSpec
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Everything one parsed `devrig <tool>` invocation yields, split by destination. The three parts are
 * deliberately separate: only [arguments] is the tool call, and neither of the other two may ever appear
 * in it.
 */
data class SchemaCliValues(
    /** The tool-call `arguments` object: schema parameters only, in declaration order, absent ones omitted. */
    val arguments: JsonObject,
    /**
     * Parameter name → the path (or `-` for standard input) given to that parameter's declared
     * [com.jonnyzzz.mcpSteroid.mcp.CliFileSource] flag. Opening the path is deliberately left undone:
     * reading a file, and reading stdin, is runtime work that a parse phase must not do. The runtime
     * substitutes the content into the named parameter before the tool call.
     */
    val fileSources: Map<String, String>,
    /**
     * Tool-scoped [CliExtraOption] values by flag — options the CLI acts on itself (e.g. `--wait`
     * polling after `open_project` returns) and never sends to the tool.
     */
    val extraOptions: Map<String, Boolean>,
)

/**
 * Binds one tool's declarative CLI metadata onto a Clikt command, and reads the parsed result back as
 * typed JSON. **Clikt is confined to the command line.** It must not reach the tool metadata
 * (`mcp-core`, `mcp-steroid-server`), the presentation/envelope/dispatch layer, or any per-tool
 * contract — those describe what a tool *is*, and a parser is an implementation detail that has to stay
 * replaceable. Inside `npx-kt`, Clikt therefore appears only where the command line is parsed: this file
 * for the schema-driven engine, and the hand-written command tree in `Cli.kt` that has always been
 * devrig's router.
 *
 * Every parameter is declared once, in the tool's own [com.jonnyzzz.mcpSteroid.mcp.ToolSchema], and
 * translated here: Clikt converts each raw token exactly once — into a `String`, `Int`, `Double`,
 * `Boolean`, or list thereof — and that already-typed value is serialized straight into the tool-call
 * JSON. There is no `Map<String, String?>` staging, no `toString()` round trip and no
 * `toInt()`/`toDouble()`/`toBoolean()`/`split(delimiter)` re-parse anywhere: a typed→string→typed detour
 * would parse the same value twice and lose Clikt's own error wording. `McpTool.call()` stays the
 * authoritative tool-side parser.
 *
 * Absence is preserved end to end, and it is a third state distinct from `false`. An optional boolean is
 * bound with `nullableFlag()` — never `flag()`, which reports absence as `false` — plus a negative
 * spelling ([negativeCliFlag]), so all three states are reachable: the flag given is `true`, the negative
 * flag is `false`, and neither contributes no key at all. That is what lets a default owned by the tool
 * (e.g. `open_project`'s `trust_project = true`) survive an invocation that mentions neither, while still
 * letting a caller ask for `false` on purpose.
 *
 * Construct it while the command is being constructed (from `init`), then call [parsed] after
 * `parse(...)` — from `run()`, which is still the parse lifecycle phase, never the dispatch phase.
 */
class SchemaCliBinding private constructor(
    private val params: List<BoundParam>,
    private val extras: List<BoundExtra>,
    private val specsByCliName: Map<String, InputSchemaParamSpec>,
) {
    /**
     * The parameter that declared [paramName], or null when nothing here declares it (a devrig framework
     * flag such as `--json`, for instance).
     *
     * [paramName] is what Clikt reports on a failure — `MissingOption.paramName` /
     * `MissingArgument.paramName` — and what the [UsageError]s raised by [parsed] carry: the declared
     * `cliFlag` for an option, the parameter name for a positional (see [cliParamName]). A
     * [com.jonnyzzz.mcpSteroid.mcp.CliFileSource] flag and an optional boolean's negative spelling both
     * map back to the parameter that declares them. This is the hook a command needs to substitute
     * [InputSchemaParamSpec.cliMissingHint] for Clikt's default wording; the substitution itself is a
     * command-level concern and does not live here.
     */
    fun paramFor(paramName: String): InputSchemaParamSpec? = specsByCliName[paramName]

    /**
     * Reads the parsed values into the three destinations of [SchemaCliValues]. Touches no filesystem and
     * no standard input — a file source contributes its *path*, never its content.
     *
     * Throws [UsageError] for the three requirements Clikt itself cannot express, each derived from the
     * shape of the declaration and none needing a per-tool rule. Two follow from a
     * [com.jonnyzzz.mcpSteroid.mcp.CliFileSource] being a second source for one value: both forms given at
     * once, and neither form given for a parameter the tool requires — the latter is what makes "one of the
     * two" mandatory, because a required parameter offering a file source is `cliOptional` by construction
     * (`ToolSchema.register` enforces that pairing) and Clikt therefore never demands the direct flag. The
     * third is a required boolean, whose switch pair cannot carry Clikt's `.required()`; demanding one of
     * its two spellings here is what keeps [InputSchemaParamSpec.cliRequired] — the file's one requiredness
     * rule — true for booleans as well, and keeps the failure on the parse-time path where `paramName` →
     * [paramFor] → [InputSchemaParamSpec.cliMissingHint] still reaches the user.
     */
    fun parsed(): SchemaCliValues {
        val arguments = LinkedHashMap<String, JsonElement>()
        val fileSources = LinkedHashMap<String, String>()
        for (bound in params) {
            val spec = bound.spec
            val value = bound.value()
            val path = bound.filePath()
            val fileSource = spec.cliFileSource
            if (fileSource != null) {
                // Keyed by cliParamName, the same name paramFor() resolves, so a command can still reach
                // this parameter's cliMissingHint — including for a positional, whose name is not a flag.
                if (value != null && path != null) throw UsageError(
                    "give either ${spec.cliParamName} or ${fileSource.flag} for '${spec.name}', not both",
                    paramName = spec.cliParamName,
                )
                if (value == null && path == null && spec.required) throw UsageError(
                    "'${spec.name}' is required: pass ${spec.cliParamName} or ${fileSource.flag}",
                    paramName = spec.cliParamName,
                )
            }
            val negativeFlag = spec.negativeCliFlag
            if (negativeFlag != null && spec.cliRequired && value == null) throw UsageError(
                "'${spec.name}' is required: pass ${spec.cliParamName} for true or $negativeFlag for false",
                paramName = spec.cliParamName,
            )
            if (value != null) arguments[spec.name] = value
            if (path != null) fileSources[spec.name] = path
        }
        return SchemaCliValues(
            arguments = JsonObject(arguments),
            fileSources = fileSources,
            extraOptions = extras.associate { it.option.flag to it.value() },
        )
    }

    companion object {
        /** Binds [spec]'s CLI projection — its schema parameters and its tool-scoped extra options. */
        fun bind(command: CliktCommand, spec: CliToolSpec): SchemaCliBinding =
            bind(command, spec.schema.asCliParams(), spec.cli.extraOptions)

        /**
         * Binds every CLI-visible parameter in [params], in declaration order, plus [extraOptions], onto
         * [command]. A [InputSchemaParamSpec.cliHidden] parameter is skipped: it is part of the MCP schema
         * only and must reach neither the command line nor the tool-call arguments.
         */
        fun bind(
            command: CliktCommand,
            params: List<InputSchemaParamSpec>,
            extraOptions: List<CliExtraOption> = emptyList(),
        ): SchemaCliBinding {
            val visible = params.filterNot { it.cliHidden }
            return SchemaCliBinding(
                params = visible.map { bindParam(command, it) },
                extras = extraOptions.map { bindExtra(command, it) },
                specsByCliName = cliNames(visible, extraOptions),
            )
        }
    }
}

/** One bound parameter: its declaration, its own typed value, and the path from its file source. */
private class BoundParam(
    val spec: InputSchemaParamSpec,
    /** The typed value of the parameter's own flag or positional; null when it was not supplied. */
    val value: () -> JsonElement?,
    /** The path supplied to the declared file-source flag; null when undeclared or not supplied. */
    val filePath: () -> String?,
)

/** One bound tool-scoped option the CLI acts on itself; never part of the tool call. */
private class BoundExtra(val option: CliExtraOption, val value: () -> Boolean)

/**
 * Every CLI name this command will carry, mapped back to its declaring parameter, failing fast on any
 * collision: Clikt keeps one option per name, so two declarations claiming one name would let one
 * silently shadow the other. All four sources of a name take part — a parameter's own flag or positional
 * name, an optional boolean's negative spelling, a file-source flag, and an [CliExtraOption] flag — and
 * they are checked against each other, not only within their own kind. Extra options are not in the
 * returned map: an extra option is not a parameter, so nothing can map back to a spec for it.
 */
private fun cliNames(
    params: List<InputSchemaParamSpec>,
    extraOptions: List<CliExtraOption>,
): Map<String, InputSchemaParamSpec> {
    val declaredBy = LinkedHashMap<String, String>()
    val specs = LinkedHashMap<String, InputSchemaParamSpec>()
    fun claim(name: String, declaration: String, spec: InputSchemaParamSpec?) {
        val previous = declaredBy.put(name, declaration)
        require(previous == null) { "CLI name '$name' is declared twice: by $previous and by $declaration" }
        if (spec != null) specs[name] = spec
    }
    for (spec in params) {
        claim(spec.cliParamName, "parameter '${spec.name}'", spec)
        spec.negativeCliFlag?.let { claim(it, "the negative flag of '${spec.name}'", spec) }
        spec.cliFileSource?.let { claim(it.flag, "the file source of '${spec.name}'", spec) }
    }
    for (extra in extraOptions) claim(extra.flag, "extra option '${extra.flag}'", null)
    return specs
}

private fun bindParam(command: CliktCommand, spec: InputSchemaParamSpec): BoundParam {
    // The filter in bind() is the rule; this pins it, so a refactor that drops the filter fails loudly
    // instead of exposing an MCP-only parameter on the command line.
    require(!spec.cliHidden) { "cliHidden parameter '${spec.name}' must never be bound to the CLI" }
    // The parameter's own form is registered first so generated help lists it before the alternative
    // (`--code` above `--code-file`); Clikt renders options in registration order.
    val value = if (spec.cliPositional) bindPositional(command, spec) else bindOption(command, spec)
    val fileOption = spec.cliFileSource?.let { source ->
        command.option(source.flag, help = source.synopsis, metavar = "PATH").also { command.registerOption(it) }
    }
    return BoundParam(spec, value) { fileOption?.value }
}

private fun bindExtra(command: CliktCommand, option: CliExtraOption): BoundExtra =
    when (option.type) {
        // A switch has no third state to preserve: it is not a tool parameter, so no tool-side default can
        // be overwritten by reporting an absent switch as false.
        CliOptionType.BOOLEAN -> {
            val bound = command.option(option.flag, help = option.synopsis).flag()
            command.registerOption(bound)
            BoundExtra(option) { bound.value }
        }
    }

private fun bindOption(command: CliktCommand, spec: InputSchemaParamSpec): () -> JsonElement? {
    val required = spec.cliRequired
    // A non-positional boolean is exactly the parameter that carries a negative spelling.
    val negativeFlag = spec.negativeCliFlag
    if (negativeFlag != null) {
        // `nullableFlag` decides presence by "the name used is not a secondary name", so WITHOUT a
        // secondary name the option has arity 0..0 and `false` is unreachable: `--flag` yields true,
        // omitting it yields null, and `--flag=false` fails as IncorrectOptionValueCount. The negative
        // spelling is what makes the third state expressible — without it a parameter like
        // `trust_project` (tool default true) could never be turned off from the CLI at all.
        // Requiredness is NOT expressible here — Clikt's `.required()` does not apply to a flag pair — so
        // a required boolean is demanded by `parsed()` instead. Because the pair exists, an absent value is
        // now distinguishable from a deliberate `false` (either spelling means "supplied explicitly"), so
        // the CLI can demand one of them rather than leaving it to a backend error.
        val bound = command.option(spec.cliFlag, help = spec.cliSynopsis).nullableFlag(negativeFlag)
        command.registerOption(bound)
        return { bound.value?.let { JsonPrimitive(it) } }
    }
    val typed = command.option(spec.cliFlag, help = spec.cliSynopsis).typedJson(spec)
    if (spec.type == "array") {
        // Repeated occurrences (`--flag=a --flag=b`); never a delimiter protocol.
        val bound = typed.multiple(required = required)
        command.registerOption(bound)
        return { bound.value.toJsonArrayOrNull() }
    }
    if (required) {
        val bound = typed.required()
        command.registerOption(bound)
        return { bound.value }
    }
    command.registerOption(typed)
    return { typed.value }
}

private fun bindPositional(command: CliktCommand, spec: InputSchemaParamSpec): () -> JsonElement? {
    val typed = command.argument(spec.name, help = spec.cliSynopsis).typedJson(spec)
    if (spec.type == "array") {
        val bound = typed.multiple(required = spec.cliRequired)
        command.registerArgument(bound)
        return { bound.value.toJsonArrayOrNull() }
    }
    if (spec.cliRequired) {
        command.registerArgument(typed)
        return { typed.value }
    }
    val bound = typed.optional()
    command.registerArgument(bound)
    return { bound.value }
}

/**
 * The declared type as a single Clikt conversion producing a [JsonPrimitive], so the value is typed once
 * and needs no second pass when it is serialized. Numeric bounds become a Clikt `restrictTo`, making an
 * out-of-range value a parse-time usage error rather than a backend error, and an enum becomes a Clikt
 * `choice`. Clikt validates each of these exactly once — there is no suppression mechanism.
 *
 * Deliberately duplicated for options and positionals ([typedJson] below): the two Clikt receivers share
 * no supertype that carries `int()`/`choice()`/`convert()`, so the alternative would be one indirection
 * per conversion for the sake of a dozen lines.
 */
private fun RawOption.typedJson(spec: InputSchemaParamSpec): NullableOption<JsonPrimitive, JsonPrimitive> {
    val bounds = spec.cliBounds()
    return when (spec.cliValueType()) {
        // The metavar is passed explicitly because `convert` replaces the one the type conversion set.
        CliValueType.STRING -> spec.enumValues
            ?.let { values -> choice(*values.toTypedArray()).convert(values.joinToString("|")) { JsonPrimitive(it) } }
            ?: convert("TEXT") { JsonPrimitive(it) }

        CliValueType.INTEGER -> int().restrictTo(bounds.minInt, bounds.maxInt).convert("INT") { JsonPrimitive(it) }

        CliValueType.NUMBER -> double().restrictTo(bounds.min, bounds.max).convert("FLOAT") { JsonPrimitive(it) }

        CliValueType.BOOLEAN -> choice(*BOOLEAN_CHOICES).convert("true|false") { JsonPrimitive(it) }
    }
}

private fun ProcessedArgument<String, String>.typedJson(
    spec: InputSchemaParamSpec,
): ProcessedArgument<JsonPrimitive, JsonPrimitive> {
    val bounds = spec.cliBounds()
    return when (spec.cliValueType()) {
        CliValueType.STRING -> spec.enumValues
            ?.let { values -> choice(*values.toTypedArray()).convert { JsonPrimitive(it) } }
            ?: convert { JsonPrimitive(it) }

        CliValueType.INTEGER -> int().restrictTo(bounds.minInt, bounds.maxInt).convert { JsonPrimitive(it) }

        CliValueType.NUMBER -> double().restrictTo(bounds.min, bounds.max).convert { JsonPrimitive(it) }

        CliValueType.BOOLEAN -> choice(*BOOLEAN_CHOICES).convert { JsonPrimitive(it) }
    }
}

/** A single typed value the CLI can parse; for an `array` it is the type of one item. */
private enum class CliValueType { STRING, INTEGER, NUMBER, BOOLEAN }

/**
 * The type of one parsed value: the declared type, or for an `array` the `items` type from the schema's
 * own `extra` (defaulting to a string array). Fails fast on anything a command line cannot express, such
 * as an array of objects — that is a declaration bug, caught while the command is built.
 */
private fun InputSchemaParamSpec.cliValueType(): CliValueType {
    val declared = if (type == "array") itemsType() else type
    return when (declared) {
        "string" -> CliValueType.STRING
        "integer" -> CliValueType.INTEGER
        "number" -> CliValueType.NUMBER
        "boolean" -> CliValueType.BOOLEAN
        else -> throw IllegalArgumentException(
            "parameter '$name' cannot be exposed on the CLI: unsupported type '$declared'; " +
                "use string, integer, number, boolean, or an array of those"
        )
    }
}

private fun InputSchemaParamSpec.itemsType(): String =
    schemaExtra()["items"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull ?: "string"

/** The parameter's `extra` JSON keys (`items`, `minimum`, `maximum`, `enum`) as an object. */
private fun InputSchemaParamSpec.schemaExtra(): JsonObject = buildJsonObject { extra(this) }

/**
 * The bounds the CLI enforces: the tightest of the CLI-only [InputSchemaParamSpec.cliMinimum] /
 * [InputSchemaParamSpec.cliMaximum] and the JSON-schema `minimum`/`maximum` the parameter already
 * carries. Both are declarations of the same intent from different surfaces, so both apply; one-sided
 * bounds are honoured independently.
 */
private class CliBounds(val min: Double?, val max: Double?) {
    /** An integer bound never widens its double form: a fractional 0.5 minimum still rejects 0. */
    val minInt: Int? get() = min?.let { ceil(it).toInt() }
    val maxInt: Int? get() = max?.let { floor(it).toInt() }
}

private fun InputSchemaParamSpec.cliBounds(): CliBounds {
    val extra = schemaExtra()
    return CliBounds(
        min = listOfNotNull(cliMinimum, extra["minimum"]?.jsonPrimitive?.doubleOrNull).maxOrNull(),
        max = listOfNotNull(cliMaximum, extra["maximum"]?.jsonPrimitive?.doubleOrNull).minOrNull(),
    )
}

/** The CLI demands a parameter only when the tool requires it and cannot supply it itself. */
private val InputSchemaParamSpec.cliRequired: Boolean get() = required && !cliOptional

/**
 * The name the CLI reports this parameter by — its flag, or its bare name when it is a positional (Clikt
 * reports a missing argument by the name it was declared with). Both `cliNames` and the [UsageError]s
 * [SchemaCliBinding.parsed] raises key on this, so one lookup resolves every failure.
 */
private val InputSchemaParamSpec.cliParamName: String get() = if (cliPositional) name else cliFlag

/**
 * The negative spelling of an optional boolean's flag — `--trust_project` gains `--no-trust_project` —
 * and null for every parameter that is not a boolean switch (a positional boolean takes an explicit
 * `true`/`false` value instead). Derived rather than declared: `--no-<flag>` is Clikt's own convention for
 * a flag's secondary name, so no metadata field has to exist for a single mechanical spelling. It is
 * built from [InputSchemaParamSpec.cliFlag] rather than [InputSchemaParamSpec.name] so an overridden flag
 * keeps its pair coherent; for the default flag (`--<name>`) the two are the same string.
 */
private val InputSchemaParamSpec.negativeCliFlag: String?
    get() = if (type == "boolean" && !cliPositional) "--no-" + cliFlag.removePrefix("--") else null

/** An empty repetition means the flag never appeared, which must contribute no key at all. */
private fun List<JsonPrimitive>.toJsonArrayOrNull(): JsonArray? = takeIf { it.isNotEmpty() }?.let { JsonArray(it) }

/** The only spelling of a boolean value the CLI accepts, for a boolean inside an array or a positional. */
private val BOOLEAN_CHOICES = arrayOf("true" to true, "false" to false)
