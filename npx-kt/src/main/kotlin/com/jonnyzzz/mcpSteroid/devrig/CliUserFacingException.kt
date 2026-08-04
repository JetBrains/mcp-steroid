/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

/**
 * A failure the user can act on — an unknown backend id, a lock held by another devrig, a malformed
 * argument. The console gets [message] alone: a stack trace here is noise that buries the one line the
 * user needs to read. The trace is not lost — [runCliWithLastResortHandling] logs it, so a bug report
 * still has it.
 *
 * [exit] is the process exit code, so each thrower keeps its own contract instead of collapsing every
 * user error onto one number.
 *
 * Throw this instead of printing-and-returning inside a command handler: the message-only rendering then
 * lives in ONE place, and no handler can forget the logging half of it.
 */
open class CliUserFacingException(message: String, val exit: Int) : RuntimeException(message)
