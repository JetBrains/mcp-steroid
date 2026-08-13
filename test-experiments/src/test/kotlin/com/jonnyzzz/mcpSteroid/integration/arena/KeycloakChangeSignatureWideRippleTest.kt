/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The change-signature experiment of the keycloak-semantic family: one cross-module parameter
 * addition on Keycloak, run in both arms of a single agent.
 *
 * The third case, and the one that forced the family's seam. A rename can be approximated by textual
 * substitution; adding a parameter cannot, because every call site has to gain a real argument. It is
 * also the first case where satisfying P1 to P4 is not enough: an agent that adds an OVERLOAD rather
 * than changing the signature leaves every existing call site resolving and every decoy untouched,
 * and compiles. `P5_ARITY` — per-call-site argument counts, read back from the post-condition script
 * — is what makes that visible, and it is contributed by [ChangeSignature] rather than by the
 * family's shared grading.
 *
 * Its target also carries the family's strongest lexical ambiguity by a wide margin: a thousand other
 * declarations share the method's simple name. [ChangeSignature] documents how P3 reads a decoy set
 * that large without spending a reference search on each one.
 */
class KeycloakChangeSignatureWideRippleTest : RippleScenarioBaseTest() {
    override val case: RippleCase get() = RippleCases.changeSignatureWide
}
