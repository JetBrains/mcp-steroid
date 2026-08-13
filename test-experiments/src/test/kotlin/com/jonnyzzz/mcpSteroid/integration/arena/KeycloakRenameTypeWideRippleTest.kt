/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The rename-type experiment of the keycloak-semantic family: one cross-module type rename on
 * Keycloak, run in both arms of a single agent.
 *
 * The second case of the family, after [KeycloakRenameRippleTest], which renames a method rather than
 * a type — the ripple travels through imports and file names instead of call sites, exercising a
 * different failure mode while sharing the family's gold format, parsers and compile gate.
 *
 * There is a second, pre-existing class named `KeycloakRenameTest` in
 * `com.jonnyzzz.mcpSteroid.integration.tests` — do not confuse the two. That class scores from the
 * agent's self-reported markers, and its prompt names the refactoring API the agent is expected to use.
 * This class instead grades a PSI post-condition captured before and after the run — alias left behind,
 * missed reference sites, over-reach onto a same-named type in another package, and reference
 * conservation — backed by a scoped compile gate and a hidden reflection consumer, and its prompt
 * deliberately does NOT name the mechanism the agent must use to satisfy that consumer.
 */
class KeycloakRenameTypeWideRippleTest : RippleScenarioBaseTest() {
    override val case: RippleCase get() = RippleCases.renameTypeWide
}
