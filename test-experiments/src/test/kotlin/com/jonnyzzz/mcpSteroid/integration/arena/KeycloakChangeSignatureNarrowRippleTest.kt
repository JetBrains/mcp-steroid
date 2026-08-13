/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The fan-out ablation of [KeycloakChangeSignatureWideRippleTest]: the same kind, ambiguity still far
 * above the family's floor, and a ripple confined to a single file where the wide twin spans 49.
 * Read only against that wide twin, never on its own: if the arms separate on the wide member and
 * not here, fan-out is the operative variable; if they separate on both, it is not. A null result on
 * this case alone proves nothing about ambiguity, because ambiguity was never varied to compare
 * against — only fan-out was.
 */
class KeycloakChangeSignatureNarrowRippleTest : RippleScenarioBaseTest() {
    override val case: RippleCase get() = RippleCases.changeSignatureNarrow
}
