/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The move-class experiment of the keycloak-semantic family: `ResourceType` moves from
 * `org.keycloak.models.workflow` to `org.keycloak.models.workflow.resource`, run in both arms of a
 * single agent.
 *
 * The fourth kind, and the first where the transformation is not visible in the diff of any single
 * declaration: the type's body does not change, only its package. [MoveClass] documents why this
 * target could never have been a rename instead — the simple name `ResourceType` is load-bearing in
 * 39 theme-message and realm-JSON files, so a rename would break those files while a move leaves them
 * untouched. `P1_MOVED` — read back from the post-condition script as the conjunction of "the new
 * fully-qualified name resolves" and "the old one no longer does" — is what a satisfied P1 to P4 alone
 * cannot see: a copy-plus-forwarding-shell would leave every call site resolving under both names and
 * pass every reference-count check while still not being a move.
 */
class KeycloakMoveClassWideRippleTest : RippleScenarioBaseTest() {
    override val case: RippleCase get() = RippleCases.moveClassWide
}
