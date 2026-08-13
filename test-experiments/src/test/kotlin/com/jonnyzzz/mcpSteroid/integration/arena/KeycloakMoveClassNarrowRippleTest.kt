/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The fan-out ablation of [KeycloakMoveClassWideRippleTest]: the same kind, ambiguity still above the
 * family's floor, and a ripple confined to 3 files where the wide twin spans 50. Read only against
 * that wide twin, never on its own: if the arms separate on the wide member and not here, fan-out is
 * the operative variable; if they separate on both, it is not.
 *
 * Unlike its wide twin, `ClientAdapter` carries no non-code justification for a move over a rename —
 * neither its simple name nor its fully-qualified name is load-bearing outside `.java` sources. It is
 * still a move, not a rename, because this case exists to compare the move kind's wide and narrow
 * members against each other, the same way the family's other kinds do.
 */
class KeycloakMoveClassNarrowRippleTest : RippleScenarioBaseTest() {
    override val case: RippleCase get() = RippleCases.moveClassNarrow
}
