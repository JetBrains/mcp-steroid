/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The fan-out ablation of [KeycloakRenameTypeWideRippleTest]: the same kind, the same lexical
 * ambiguity, and a ripple two orders of magnitude smaller — 12 references in 3 files against 198 in
 * 41. Read only against that wide twin, never on its own: if the arms separate on the wide member
 * and not here, fan-out is the operative variable; if they separate on both, it is not. A null result
 * on this case alone proves nothing about ambiguity, because ambiguity was never varied to compare
 * against — only fan-out was.
 */
class KeycloakRenameTypeNarrowRippleTest : RippleScenarioBaseTest() {
    override val case: RippleCase get() = RippleCases.renameTypeNarrow
}
