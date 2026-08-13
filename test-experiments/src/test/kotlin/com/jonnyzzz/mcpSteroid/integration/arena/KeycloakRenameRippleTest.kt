/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The rename-method experiment of the keycloak-semantic family: one cross-module method rename on
 * Keycloak, run in both arms of a single agent.
 *
 * There is a second, pre-existing class named `KeycloakRenameTest` in
 * `com.jonnyzzz.mcpSteroid.integration.tests` — do not confuse the two. That class scores from the
 * agent's self-reported markers, and its prompt names the refactoring API the agent is expected to use.
 * This class instead grades a PSI post-condition captured before and after the run — alias left behind,
 * missed call sites, over-reach onto a same-named method of another type, and reference conservation —
 * backed by a scoped compile gate and a hidden reflection consumer, and its prompt deliberately does
 * NOT name the mechanism the agent must use to satisfy that consumer.
 *
 * The TeamCity config for this family runs `-PtestFilter=*KeycloakRenameRippleTest.<agent>*`, which
 * glob-matches the `<agent> with mcp` and `<agent> without mcp` methods [RippleScenarioBaseTest]
 * defines for a given agent. The task specification lives in [RippleCases]; the oracle that grades
 * pre/post semantic state lives in [SemanticRippleOracle] and [RippleTarget].
 */
class KeycloakRenameRippleTest : RippleScenarioBaseTest() {
    override val case: RippleCase get() = RippleCases.renameMethodWide
}
