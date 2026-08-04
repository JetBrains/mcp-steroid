/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.IncorrectOptionValueCount
import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.MissingOption
import com.github.ajalt.clikt.core.MultiUsageError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.jonnyzzz.mcpSteroid.aiAgents.AgentCliNotLaunchableException
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

const val NO_BACKENDS_DETECTED_MESSAGE: String = "No backends detected."

sealed interface DevrigCommand {
    val debug: Boolean
    val json: Boolean

    data class MCP(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackend(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackendDownload(
        val id: String? = null,
        val version: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackendStart(
        val id: String? = null,
        val version: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackendStop(
        val id: String? = null,
        val version: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackendProvision(
        val id: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandProject(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandInstall(
        val agent: AiAgentCli,
        /** Read-only dry-run: report the registration diff + IDE reachability, change nothing. */
        val check: Boolean = false,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /**
     * Bare `devrig install` — no target given. Lists the valid install targets (with per-agent CLI
     * detection) and exits 0 instead of failing with a missing-argument usage error: the bootstrap
     * installers historically recommended the bare command, so it must guide, not error
     * (jonnyzzz/mcp-steroid#277). Informational like `help`; ignores --json.
     */
    data class DevrigCommandInstallOverview(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /**
     * `devrig install devrig` — register devrig's OWN `~/.mcp-steroid/bin` launcher + PATH (NOT an
     * agent) and print the next-steps info message. One behavior, same result on every call (issue
     * #398): registration always derives the install tree + JDK from the running binary. The
     * `--install-script` / `--jdk-home` flags the install scripts send (a forward contract, by design)
     * are accepted and ignored today.
     */
    data class DevrigCommandInstallDevrig(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /**
     * `devrig install config` — print the MANUAL MCP configuration recipe: the stdio `mcpServers` JSON
     * snippet pointing at the stable launcher, plus the per-agent `mcp add` command lines. For MCP
     * clients devrig cannot configure automatically (issue #398). Informational like `install` overview:
     * read-only, exits 0, ignores --json (the output already IS the JSON snippet plus its context).
     */
    data class DevrigCommandInstallConfig(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /**
     * `devrig install plugin` — install the MCP Steroid plugin into locally-running JetBrains IDEs via
     * each IDE's built-in REST endpoint (which shows the IDE's own native install dialog). [check] is a
     * read-only dry-run: report which IDEs would be asked, show no dialog. The EXPLICIT plugin-install
     * step — `devrig install devrig` only promotes this command, it never runs it (issue #398).
     */
    data class DevrigCommandInstallPlugin(
        val check: Boolean = false,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /**
     * A parsed but inert tool invocation, produced by the generated [SchemaToolCliCommand] for one
     * `steroid_*` tool. Clikt has routed, tokenized and typed everything, and nothing has executed:
     * parsing touches no handler, service or backend. The runtime resolves the live tool spec by
     * [toolName] and calls it; [commandName] is the CLI command the user invoked, echoed into the `--json`
     * envelope.
     *
     * Plain data only — no service, no `Presentation`, no handler-bound spec:
     *  - [arguments] is the tool call itself, already typed;
     *  - [fileSources] maps a parameter name to the path (or `-` for standard input) its declared
     *    [com.jonnyzzz.mcpSteroid.mcp.CliFileSource] flag was given; reading it is runtime work, so the
     *    parse phase deliberately carries the path only;
     *  - [extraOptions] holds the tool-scoped options the CLI acts on itself, by
     *    [com.jonnyzzz.mcpSteroid.mcp.CliExtraOption.name], and never reaches the tool;
     *  - [out] is the framework `--out` path, applied to the image in the RESULT (see [renderWithOut]),
     *    never a tool argument.
     */
    data class RunTool(
        val toolName: String,
        val commandName: String,
        val arguments: JsonObject = JsonObject(emptyMap()),
        val fileSources: Map<String, String> = emptyMap(),
        val extraOptions: Map<String, Boolean> = emptyMap(),
        val out: Path? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /**
     * Help was asked for. Which text answers is a deliberate split, and [generatedHelp] is where it is
     * recorded: a generated `devrig <tool>` command ([SchemaToolCliCommand]) carries the help Clikt
     * produced for that command — its grammar is generated from the tool's declaration, so only a
     * generated text can stay in step with it — while every lifecycle verb leaves this null and gets
     * devrig's curated banner, which is the document that explains those verbs.
     */
    data class DevrigCommandHelp(
        override val debug: Boolean = false,
        override val json: Boolean = false,
        val generatedHelp: String? = null,
    ) : DevrigCommand

    data class DevrigCommandVersion(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /**
     * A usage failure Clikt raised while parsing. [text] is the whole rendered report — the message plus
     * the usage banner — and it is all this carries: [runCli] prints it to stderr and answers 64, and
     * `--json` deliberately emits no parse-error envelope (the failure precedes any command's options,
     * `--json` among them, so there is no reliable way to know the caller wanted one). A `commandName`
     * field lived here to populate that envelope, was read by nothing but its own test, and was recovered
     * by scanning the raw tokens under an invariant — "every flag that may precede a subcommand is
     * boolean" — that `--out` falsified. Reinstate it only together with the envelope that needs it.
     */
    data class DevrigCommandParseError(
        val text: String,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand
}

fun parseDevrigCommand(rawArgs: Array<String>): DevrigCommand {
    val selected = SelectedDevrigCommand()
    selected.rawArgs = rawArgs.toList()
    val root = DevrigRootCommand(selected)
    return try {
        root.parse(rawArgs)
        selected.command ?: DevrigCommand.DevrigCommandHelp()
    } catch (e: PrintHelpMessage) {
        // Help was asked for, by the eager `-h`/`--help` every command registers — except when
        // `error = true`, which is Clikt reporting a usage failure BY printing help, and stays a usage
        // failure here.
        if (e.error) parseError(root, e)
        else DevrigCommand.DevrigCommandHelp(
            debug = rawArgs.debugRequested(),
            json = rawArgs.jsonRequested(),
            generatedHelp = e.generatedToolHelp(),
        )
    } catch (e: CliktError) {
        parseError(root, e)
    }
}

/**
 * Clikt's own help for the command that asked, when that command is a generated `devrig <tool>` one, and
 * null for every other command — the whole of the split [DevrigCommand.DevrigCommandHelp.generatedHelp]
 * records. The generated commands are the only ones whose grammar is derived from metadata, so they are
 * the only ones no hand-written banner can describe; the lifecycle verbs are the reverse, and taking
 * Clikt's rendering for them would throw devrig's curated banner away. [PrintHelpMessage] carries the
 * context of the command the eager `--help` fired on, which is exactly the command to ask.
 */
private fun PrintHelpMessage.generatedToolHelp(): String? =
    (context?.command as? SchemaToolCliCommand)?.getFormattedHelp()

private fun parseError(
    root: DevrigRootCommand,
    e: CliktError,
): DevrigCommand.DevrigCommandParseError {
    val reported = (e as? UsageError)?.withCuratedMissingHints() ?: e
    return DevrigCommand.DevrigCommandParseError(
        text = root.getFormattedHelp(reported) ?: reported.message ?: "Invalid arguments",
    )
}

/**
 * Substitutes a parameter's own [com.jonnyzzz.mcpSteroid.mcp.InputSchemaParamSpec.cliMissingHint] — a
 * runnable example, or where to obtain the value — for the default "missing option" wording, and leaves
 * every other failure untouched: a rejected VALUE (`--modal=bogus`) must keep the message that explains
 * what was wrong with it.
 *
 * One lookup covers both sources of a missing value, because both key on the same name: Clikt's own
 * [MissingOption] / [MissingArgument] (which expose nothing but `paramName`) and the [MissingCliValue] the
 * schema binding raises for a parameter whose value may arrive in more than one spelling. A
 * [MultiUsageError] is rebuilt from its curated parts so a first run that omits several parameters gets a
 * hint for each.
 */
private fun UsageError.withCuratedMissingHints(): UsageError {
    val command = context?.command as? SchemaToolCliCommand ?: return this
    return withCuratedMissingHints(command)
}

private fun UsageError.withCuratedMissingHints(command: SchemaToolCliCommand): UsageError {
    if (this is MultiUsageError) {
        return MultiUsageError(errors.map { it.withCuratedMissingHints(command) }).also { it.context = context }
    }
    // `--trust_project=false` on an optional boolean is Clikt's IncorrectOptionValueCount ("takes no value"),
    // which never mentions the one spelling that DOES set it false. Point the caller at the negative flag.
    if (this is IncorrectOptionValueCount) {
        val name = paramName ?: return this
        val negative = command.negativeFlagFor(name) ?: return this
        // Resolve BOTH spellings from the spec instead of echoing the typed name: `name` may itself be
        // the negative spelling (`--no-trust_project=true`), which must not be offered as the way to set
        // the value true.
        val positive = command.positiveFlagFor(name) ?: return this
        return UsageError(
            "$name is a switch and takes no value; use $negative to set it false, or $positive to set it true",
            paramName = name,
        ).also { it.context = context }
    }
    if (this !is MissingOption && this !is MissingArgument && this !is MissingCliValue) return this
    val name = paramName ?: return this
    val hint = command.missingHintFor(name) ?: return this
    return UsageError(hint, paramName = name).also { it.context = context }
}

/**
 * `--debug` / `--json` read straight off the raw tokens. Needed only where no Clikt option delegate can be
 * read: an eager `--help` fires before the other options are finalized, and a parse failure can abort
 * before they are seen at all. Exact token matches are enough because both are boolean flags accepted at
 * every command level, so neither can appear as another option's value.
 */
private fun Array<String>.debugRequested(): Boolean = devrigDebugEnvEnabled() || any { it == "--debug" }

private fun Array<String>.jsonRequested(): Boolean = any { it == "--json" }

/**
 * DEVRIG_DEBUG — the env var that also makes the launcher attach a JDWP agent — additionally turns on full
 * debug mode for every command, identical to passing `--debug`, so the verbose DEBUG logs that explain a
 * debugging session are emitted without also having to pass the flag.
 */
private fun devrigDebugEnvEnabled(): Boolean = !System.getenv("DEVRIG_DEBUG").isNullOrBlank()

class SelectedDevrigCommand {
    var command: DevrigCommand? = null

    /**
     * The raw argv this parse was handed, verbatim. A command needs it to tell a value supplied as
     * `--code=--help` (the token joined to its flag — an explicit, if odd, value) from `--code --help`
     * (the flag and a separate token — the ambiguous forgotten-value case), a distinction the parsed
     * values alone have lost. Set once by [parseDevrigCommand]; read via [DevrigCliktCommand.rawArgs].
     */
    var rawArgs: List<String> = emptyList()
}

data class GenericOptions(
    val debug: Boolean,
    val json: Boolean,
)

abstract class DevrigCliktCommand(
    name: String,
    private val selected: SelectedDevrigCommand,
    private val parent: DevrigCliktCommand?,
    invokeWithoutSubcommand: Boolean = false,
    hidden: Boolean = false,
    help: String = "",
) : CliktCommand(
    name = name,
    help = help,
    invokeWithoutSubcommand = invokeWithoutSubcommand,
    hidden = hidden,
) {
    private val debugFlag by option("--debug", help = DEVRIG_DEBUG_FLAG_HELP).flag()
    private val jsonFlag by option("--json", help = DEVRIG_JSON_FLAG_HELP).flag()

    init {
        context { helpOptionNames = emptySet() }
        // Help is EAGER, which is what makes it win over a required parameter: Clikt finalizes eager
        // options before it validates that every required option and argument was supplied, so
        // `devrig execute_code --help` and `devrig install --help` print help instead of "missing …".
        // It must NOT win over an error already found in the tokens themselves (`devrig --bogus --help`):
        // Clikt collects such an error and keeps parsing to gather more, so returning normally here lets it
        // surface as the usage failure it is, rather than answering a typo with a help banner and exit 0.
        // Registered here rather than left to Clikt's own default help option, which cannot make that
        // distinction.
        eagerOption("--help", "-h", help = "print help and exit") {
            if (!context.errorEncountered) throw PrintHelpMessage(context)
        }
    }

    protected fun options(): GenericOptions {
        val parentOptions = parent?.options()
        return GenericOptions(
            debug = debugFlag || parentOptions?.debug == true || devrigDebugEnvEnabled(),
            json = jsonFlag || parentOptions?.json == true,
        )
    }

    protected fun select(command: DevrigCommand) {
        selected.command = command
    }

    /** The raw argv this parse was handed; see [SelectedDevrigCommand.rawArgs]. */
    protected fun rawArgs(): List<String> = selected.rawArgs
}

/**
 * The base of the GENERATED tool commands: a [DevrigCliktCommand] that accepts `--out` when — and only
 * when — [acceptsOut] says this tool's result can carry an image.
 *
 * `--out` sits here and not on [DevrigCliktCommand] because it redirects the image a tool RESULT carries
 * (the behavior is [renderWithOut]), and no lifecycle verb — `project`, `backend`, `install`, `help`,
 * `version` — ever produces a result at all. But not every tool command produces an image either: only
 * `take_screenshot` (always) and `execute_code` (a script's `logImage` or a dialog-failure screenshot) do,
 * which is what [com.jonnyzzz.mcpSteroid.mcp.CliCommandSpec.producesImage] records. So the option is
 * registered per command, gated on that flag, rather than declared once for everything: declared for all it
 * parsed everywhere and was read in one place, so `devrig project --out=/tmp/x.png` (and `devrig
 * list_projects --out=x`) exited having written nothing. Accepting a flag and ignoring it is the outcome
 * `requireNoUnhandledExtraOption` argues against for `--wait`, and the same answer applies: no flag may be
 * accepted and silently dropped. The lever differs only because the ownership does — `--wait` is declared by
 * a tool's own metadata that devrig cannot unilaterally withhold, so it can only be refused at runtime,
 * whereas devrig owns this declaration and can simply not make it. Scoping it wins where it is available: on
 * a command that cannot honour `--out` the refusal is Clikt's own unknown-option error at parse time, and
 * that command's `--help` stops listing it.
 *
 * The cost is that `--out` must now follow its command (`devrig take_screenshot --out=x`, not
 * `devrig --out=x take_screenshot`), which is where it reads correctly anyway.
 */
abstract class DevrigToolCliktCommand(
    name: String,
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand?,
    help: String,
    acceptsOut: Boolean,
) : DevrigCliktCommand(name = name, selected = selected, parent = parent, help = help) {
    // No `metavar`: `.path()` supplies its own and overwrites anything passed here, so a metavar argument
    // is dead text that reads as if it were rendering the help it does not reach. Registered by hand
    // (not `by`) so a non-image command declares no `--out` at all — Clikt then refuses it as unknown.
    private val outFlag =
        if (acceptsOut) option("--out", help = DEVRIG_OUT_FLAG_HELP).path(canBeDir = false).also { registerOption(it) }
        else null

    /** The `--out` path, or null when the flag was not given or this command does not accept it; see [renderWithOut]. */
    protected fun outPath(): Path? = outFlag?.value
}

/**
 * The alias-token → canonical-command map the visible tools declare, with a fail-fast guard on a collapse
 * that `toMap()` alone would hide: two tools declaring the SAME alias yield two pairs sharing one key, and
 * `toMap()` silently keeps the last, so the alias would resolve to whichever tool the factory happened to
 * list last and the uniqueness check downstream (`aliases().keys`) would never see the duplicate. Detecting
 * it on the pair LIST — before the map exists — turns that into a loud construction-time failure instead.
 */
fun toolAliasMap(tools: List<CliToolSpec>): Map<String, List<String>> {
    val pairs = tools
        .filterNot { it.cli.hidden }
        .flatMap { spec -> spec.cli.aliases.map { alias -> alias to spec.cli.name } }
    val duplicates = pairs.map { it.first }.groupBy { it }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "devrig tool alias(es) declared by more than one tool: $duplicates" }
    return pairs.associate { (alias, name) -> alias to listOf(name) }
}

/**
 * devrig's root command. Its subcommands are the hand-written lifecycle verbs plus ONE generated command
 * per CLI-visible `steroid_*` tool ([schemaToolCliCommands]): adding a tool to `devrigToolSpecs(...)` adds
 * its `devrig <tool>` subcommand here — no command class, no dispatch arm, no name list.
 */
class DevrigRootCommand(
    selected: SelectedDevrigCommand,
) : DevrigCliktCommand(
    name = "devrig",
    selected = selected,
    parent = null,
    invokeWithoutSubcommand = true,
) {
    private val versionFlag by option("--version", "-v", help = "print the devrig version and exit").flag()

    private val tools = devrigCliTools()

    init {
        val backend = BackendCommand(selected, this)
        subcommands(
            // `mcp` is the canonical, advertised spelling. `mpc` is the original
            // (mis-spelled) subcommand kept as a hidden alias so existing agent
            // registrations that launch `devrig mpc` keep working — see issue #85.
            McpCommand(selected, this, name = "mcp", hidden = false),
            McpCommand(selected, this, name = "mpc", hidden = true),
            backend,
            ProjectCommand(selected, this),
            InstallCommand(selected, this),
            *schemaToolCliCommands(selected, this, tools).toTypedArray(),
            HelpCommand(selected, this),
            VersionCommand(selected, this),
        )
        // Clikt resolves a token to one command (`associateBy { it.commandName }`) and expands an alias
        // only when no subcommand claims the token, so a duplicate would silently shadow rather than fail.
        val tokens = registeredSubcommandNames() + aliases().keys
        val duplicates = tokens.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "devrig subcommand token(s) declared more than once: $duplicates" }
    }

    /**
     * The alias tokens the tools declare ([com.jonnyzzz.mcpSteroid.mcp.CliCommandSpec.aliases]), expanded to
     * the canonical command. Clikt's own alias expansion is used deliberately: the alias reaches the SAME
     * generated command, so there is exactly one grammar per tool and an alias cannot drift into a second
     * one of its own.
     */
    override fun aliases(): Map<String, List<String>> = toolAliasMap(tools)

    override fun run() {
        val options = options()
        if (versionFlag) {
            select(DevrigCommand.DevrigCommandVersion(debug = options.debug, json = options.json))
        } else {
            select(DevrigCommand.DevrigCommandHelp(debug = options.debug, json = options.json))
        }
    }
}

private class McpCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
    name: String,
    hidden: Boolean,
) : DevrigCliktCommand(name, selected, parent, hidden = hidden) {
    override fun run() {
        val options = options()
        select(DevrigCommand.MCP(debug = options.debug, json = options.json))
    }
}

private class ProjectCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("project", selected, parent) {
    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandProject(debug = options.debug, json = options.json))
    }
}

private class InstallCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("install", selected, parent) {
    private val agent by argument("agent").optional()
    // `install devrig` flags the install scripts SEND by design (a forward contract a future devrig
    // may use); accepted there and IGNORED today (registration derives everything from the running
    // binary). Rejected for other targets.
    private val installScript: String? by option("--install-script")
    private val jdkHome: String? by option("--jdk-home")
    private val checkFlag by option(
        "--check",
        help = "read-only dry-run: report the registration diff + IDE reachability, change nothing " +
            "(exit 1 if install would change anything)",
    ).flag()

    override fun run() {
        val options = options()
        val agent = agent ?: run {
            // Bare `devrig install`: overview mode (#277). Target-specific flags still need a target —
            // silently ignoring them would hide a typo like `devrig install --check claude` gone wrong.
            if (checkFlag || installScript != null || jdkHome != null) {
                throw UsageError("--check / --install-script / --jdk-home require an install target (claude / codex / gemini / plugin / devrig / config)")
            }
            select(DevrigCommand.DevrigCommandInstallOverview(debug = options.debug, json = options.json))
            return
        }
        if (agent == "devrig") {
            if (checkFlag) throw UsageError("--check is only valid for an agent install (claude / codex / gemini) or 'devrig install plugin'")
            // --install-script / --jdk-home are IGNORED here (not stored): the command always derives
            // the install tree + JDK from the running binary, so it behaves identically with or
            // without them. The install scripts keep SENDING them by design — a forward contract a
            // future devrig may use (issue #398).
            select(DevrigCommand.DevrigCommandInstallDevrig(debug = options.debug, json = options.json))
            return
        }
        if (agent == "config") {
            if (checkFlag || installScript != null || jdkHome != null) {
                throw UsageError("--check / --install-script / --jdk-home are not valid with 'devrig install config'")
            }
            select(DevrigCommand.DevrigCommandInstallConfig(debug = options.debug, json = options.json))
            return
        }
        if (agent == "plugin") {
            if (installScript != null || jdkHome != null) {
                throw UsageError("--install-script / --jdk-home are only valid with 'devrig install devrig'")
            }
            select(DevrigCommand.DevrigCommandInstallPlugin(check = checkFlag, debug = options.debug, json = options.json))
            return
        }
        val target = AiAgentCli.parse(agent)
            ?: throw UsageError("agent must be one of: claude, codex, gemini, devrig, plugin, config")
        if (installScript != null || jdkHome != null) {
            throw UsageError("--install-script / --jdk-home are only valid with 'devrig install devrig'")
        }
        select(DevrigCommand.DevrigCommandInstall(target, check = checkFlag, debug = options.debug, json = options.json))
    }
}

private class HelpCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("help", selected, parent) {
    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandHelp(debug = options.debug, json = options.json))
    }
}

private class VersionCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("version", selected, parent) {
    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandVersion(debug = options.debug, json = options.json))
    }
}

private class BackendCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("backend", selected, parent, invokeWithoutSubcommand = true) {
    init {
        subcommands(
            BackendDownloadCommand(selected, this),
            BackendStartCommand(selected, this),
            BackendStopCommand(selected, this),
            BackendProvisionCommand(selected, this),
        )
    }

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackend(debug = options.debug, json = options.json))
    }
}

private class BackendDownloadCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("download", selected, parent) {
    private val id by argument("id").optional()
    private val version by option("--version", help = "IDE version to download")

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackendDownload(id = id, version = version, debug = options.debug, json = options.json))
    }
}

private class BackendStartCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("start", selected, parent) {
    private val id by argument("id").optional()
    private val version by option("--version", help = "IDE version to start")

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackendStart(id = id, version = version, debug = options.debug, json = options.json))
    }
}

private class BackendStopCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("stop", selected, parent) {
    private val id by argument("id").optional()
    private val version by option("--version", help = "IDE version to stop")

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackendStop(id = id, version = version, debug = options.debug, json = options.json))
    }
}

private class BackendProvisionCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("provision", selected, parent) {
    private val id by argument("id").optional()

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackendProvision(id = id, debug = options.debug, json = options.json))
    }
}

/**
 * Dispatches [command] to its handler. Only [AgentCliNotLaunchableException] is handled here;
 * [CliUserFacingException] (including the [ManagedBackendLockException] /
 * [ManagedBackendValidationException] the backend commands throw) propagates to
 * [runCliWithLastResortHandling], which owns the message-only rendering and the logging that goes with
 * it. Callers that invoke this directly must wrap it the same way `mainImpl2` does.
 */
fun DevrigServices.runCli(command: DevrigCommand): Int {
    return try {
        when (command) {
            is DevrigCommand.MCP -> error("runCli called with DevrigCommand.MCP")
            // ONE arm for every generated tool command, whatever the tool: parsing and running are separate
            // lifecycle phases, and the second one lives in its own layer ([runGeneratedToolCommand]).
            is DevrigCommand.RunTool -> runGeneratedToolCommand(command)
            is DevrigCommand.DevrigCommandHelp -> {
                val generated = command.generatedHelp
                if (generated == null) printHelp(mcpStdout) else printCommandHelp(mcpStdout, generated)
            }
            is DevrigCommand.DevrigCommandVersion -> printVersion(mcpStdout)
            is DevrigCommand.DevrigCommandParseError -> {
                System.err.println(command.text)
                64
            }
            is DevrigCommand.DevrigCommandBackend -> runBackendCommand(command)
            is DevrigCommand.DevrigCommandBackendDownload -> runBackendDownloadCommand(command)
            is DevrigCommand.DevrigCommandBackendStart -> runBackendStartCommand(command)
            is DevrigCommand.DevrigCommandBackendStop -> runBackendStopCommand(command)
            is DevrigCommand.DevrigCommandBackendProvision -> runBackendProvisionCommand(command)
            is DevrigCommand.DevrigCommandProject -> runProjectCommand(command)
            is DevrigCommand.DevrigCommandInstall -> runInstallCommand(command)
            is DevrigCommand.DevrigCommandInstallOverview -> runInstallOverviewCommand()
            is DevrigCommand.DevrigCommandInstallDevrig -> runInstallDevrigCommand()
            is DevrigCommand.DevrigCommandInstallConfig -> runInstallConfigCommand()
            is DevrigCommand.DevrigCommandInstallPlugin -> runInstallPluginCommand(command)
        }
    } catch (e: AgentCliNotLaunchableException) {
        // #342: a missing/unspawnable agent CLI must read as guidance, not a raw stacktrace. Handled here
        // rather than as a CliUserFacingException because the report is more than a message — it renders
        // per-agent install guidance.
        reportAgentCliNotLaunchable(e, System.err)
    }
}

fun unknownArguments(tokens: List<String>, hint: String? = null): Int {
    System.err.println("Unknown argument(s): ${tokens.joinToString(" ")}")
    hint?.let { System.err.println(it) }
    printHelp(System.err)
    return 64
}
