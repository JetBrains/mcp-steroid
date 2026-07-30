/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.MissingOption
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.output.ParameterFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization tests for Clikt 4.4.0 (`npx-kt/build.gradle.kts`:
 * `implementation("com.github.ajalt.clikt:clikt:4.4.0")`) — the exact version resolved for `:npx-kt`,
 * confirmed by inspecting the resolved jar/sources in the Gradle cache (`clikt-jvm-4.4.0-*.jar`).
 *
 * These tests assert Clikt's OWN behavior, not ours — that is deliberate, not a smell to clean up later.
 * The schema-driven CLI design (`SchemaCliBinding.kt`, built in a later task) rests on three specific,
 * previously unverified claims about how Clikt 4.4.0 behaves. Every test below names the design decision
 * it protects: if a future Clikt upgrade changes the underlying behavior, one of these tests fails loudly
 * instead of the CLI silently degrading. None of them exercise our own production code — `SchemaCliBinding`
 * does not exist yet — every `CliktCommand` here is throwaway and defined only in this file.
 */
class CliktBehaviorContractTest {

    // ---- Fact 1: an eager --help short-circuits before required-option finalization ----

    /** Has a required option and relies on Clikt's own default eager `-h`/`--help` (added automatically
     * by `CliktCommand` unless the command registers a conflicting name itself — see
     * `CliktCommand.createContext`). No custom help handling: this is Clikt's out-of-the-box behavior. */
    private class RequiredOptionCommand : CliktCommand() {
        // Registered directly (no `val ... by`, since neither test below completes a successful parse
        // to read the value back) — only the requiredness is under test here.
        init { registerOption(option("--code").required()) }
        override fun run() {}
    }

    @Test
    fun `eager help short-circuits before a required option is finalized`() {
        // Design decision this protects: Task 5 deletes PR #351's `optionalizeRequired` workaround by
        // restoring plain `.required()` and making `--help` an eager option. That is only safe if Clikt
        // finalizes eager options (whose action throws PrintHelpMessage) BEFORE it validates that every
        // required option was supplied. If Clikt ever finalized required options first, `devrig <tool>
        // --help` would abort with "missing required option" instead of printing help.
        val command = RequiredOptionCommand()

        assertFailsWith<PrintHelpMessage> {
            command.parse(listOf("--help"))
        }
    }

    @Test
    fun `the same required option still fires without --help present`() {
        // Negative control for the test above. Without this, "eager help short-circuits" could pass
        // vacuously if the option were not actually required (or not actually enforced) — this proves
        // there IS a required-option conflict for --help to short-circuit past.
        val command = RequiredOptionCommand()

        assertFailsWith<MissingOption> {
            command.parse(emptyList())
        }
    }

    // ---- Fact 2: what MissingOption exposes about the missing parameter ----

    /** Two names on purpose ("-c" is shorter than "--code") so the assertion below distinguishes
     * `longestName()` from "the first declared name" or "the name last on the command line". */
    private class MultiNameRequiredOptionCommand : CliktCommand() {
        init { registerOption(option("-c", "--code").required()) }
        override fun run() {}
    }

    @Test
    fun `MissingOption exposes only the longest declared option name, not the Option object`() {
        // Design decision this protects: Task 6's error-wording hook (substituting a curated
        // cliMissingHint for Clikt's default message) must key off whatever MissingOption actually
        // exposes. Reading Clikt 4.4.0's source (parameters/options/TransformAll.kt `required()` and
        // core/exceptions.kt `MissingOption`/`UsageError`) shows `required()` throws
        // `MissingOption(option)`, but UsageError's constructor immediately reduces that `Option` to
        // `option.longestName()` — a plain String — and discards the Option object. A catch site can
        // reach ONLY: `paramName: String?` (the option's longest declared name), the inherited
        // `context: Context?`, and `formatMessage(Localization, ParameterFormatter): String` (also
        // inherited from UsageError) — never the Option itself, its metavar, or its other declared
        // names. If this ever changed to also expose the Option, Task 6 could key on richer data; if
        // `paramName` were removed, Task 6 would need an entirely different keying scheme.
        val command = MultiNameRequiredOptionCommand()

        val error = assertFailsWith<MissingOption> {
            command.parse(emptyList())
        }

        assertEquals("--code", error.paramName)
        // MissingOption never sets Throwable.message; only formatMessage() renders text, and it needs a
        // Localization + ParameterFormatter to do so — exactly the two arguments a catch site must supply.
        assertNull(error.message)
        val context = error.context
        assertTrue(context != null, "Parser.parse must attach the Context to a thrown UsageError")
        val rendered = error.formatMessage(context.localization, ParameterFormatter.Plain)
        assertTrue("--code" in rendered, "rendered message should mention the option's longest name: $rendered")
    }

    // ---- Fact 3: programmatic registerOption/registerArgument still works ----

    /** Builds delegates via the option()/argument() builder functions and registers them by hand with
     * registerOption/registerArgument instead of Kotlin property delegation (`by option()`) — the exact
     * shape SchemaCliBinding needs, because it builds one binding per schema parameter in a runtime loop
     * over metadata, not as a fixed set of compile-time properties. */
    private class ProgrammaticBindingCommand : CliktCommand() {
        val nameOption = option("--name").required()
        val countArgument = argument("count")

        init {
            registerOption(nameOption)
            registerArgument(countArgument)
        }

        override fun run() {}
    }

    @Test
    fun `registerOption and registerArgument accept delegates built outside property delegation`() {
        // Design decision this protects: SchemaCliBinding cannot use `val x by option()` (the parameter
        // set is only known at runtime from ToolSchema.asCliParams()), so it must build each delegate with
        // the plain option()/argument() functions and register it explicitly. PR #351 already relied on
        // this; this test pins it against a Clikt upgrade rather than re-discovering it via a production
        // failure.
        val command = ProgrammaticBindingCommand()

        command.parse(listOf("--name", "widget", "3"))

        assertEquals("widget", command.nameOption.value)
        assertEquals("3", command.countArgument.value)
    }

    /** An optional boolean bound with nullableFlag(), registered the same way SchemaCliBinding would. */
    private class NullableFlagCommand : CliktCommand() {
        val verbose = option("--verbose").nullableFlag()
        init { registerOption(verbose) }
        override fun run() {}
    }

    /** Contrast case: plain flag() (used nowhere in the schema-driven design) normalizes absence to
     * `false` instead of preserving it as "not provided". */
    private class PlainFlagCommand : CliktCommand() {
        val verbose = option("--verbose").flag()
        init { registerOption(verbose) }
        override fun run() {}
    }

    @Test
    fun `nullableFlag stays null when absent, and true (never false) when present`() {
        // Design decision this protects: open_project's `trust_project` tool default is `true`.
        // SchemaCliBinding binds an optional CLI boolean with `nullableFlag()`, not `flag()`, and
        // `SchemaCliBinding.appendTo` skips emitting a JSON key when the bound value is null — so an
        // absent `--trust-project` on the CLI must leave the tool's own default in force. If
        // nullableFlag() ever normalized "absent" to `false` (like flag() does), every CLI invocation
        // that omits an optional boolean would silently flip that parameter to false.
        val absent = NullableFlagCommand()
        absent.parse(emptyList())
        assertNull(absent.verbose.value)

        val present = NullableFlagCommand()
        present.parse(listOf("--verbose"))
        assertEquals(true, present.verbose.value)
    }

    @Test
    fun `plain flag(), unlike nullableFlag(), normalizes absence to false`() {
        // Negative control for the test above: demonstrates the exact behavior SchemaCliBinding avoids
        // by choosing nullableFlag() over the more common flag(). Without this control, a future reader
        // could mistake nullableFlag() for an arbitrary choice rather than a deliberate one.
        val command = PlainFlagCommand()

        command.parse(emptyList())

        assertEquals(false, command.verbose.value)
    }
}
