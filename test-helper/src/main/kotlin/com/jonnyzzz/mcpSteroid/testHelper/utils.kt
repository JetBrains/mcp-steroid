/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import java.io.File

fun createTempDirectory(prefix: String): File {
    val tempDir = File(System.getProperty("java.io.tmpdir"), "docker-$prefix-${System.currentTimeMillis()}")
    tempDir.mkdirs()
    return tempDir
}


fun String.titleCase() = replaceFirstChar { it.titlecase() }


/**
 * Characters that force a shell-style single-quote wrap so bash/sh treats the
 * arg as a literal. Must cover:
 *  * whitespace (obvious)
 *  * shell quoting characters (' " `)
 *  * glob metacharacters — *, ?, [, ] — otherwise bash expands them against
 *    the CWD at exec time and the arg no longer matches what the caller
 *    wrote (observed failure: `git -c safe.directory=*` was expanded to
 *    `git -c safe.directory=file1 file2 …` before reaching git).
 *  * variable expansion — $ — same class of silent-rewrite bugs
 *  * word splitting / redirection — ; & | < > ( )
 *  * history expansion — ! (zsh / bash interactive; docker exec uses bash
 *    non-interactively so unlikely, but cheap to guard)
 *  * newlines — break bash -c script boundary
 */
private val SHELL_META_CHARS = charArrayOf(
    ' ', '\t', '\n', '\r',
    '"', '\'', '`',
    '*', '?', '[', ']',
    '$',
    ';', '&', '|', '<', '>', '(', ')',
    '!',
)

fun escapeShellArgs(args: List<String>): String =
    args.joinToString(" ") { arg ->
        if (arg.isEmpty() || arg.any { ch -> ch in SHELL_META_CHARS }) {
            "'" + arg.replace("'", "'\\''") + "'"
        } else {
            arg
        }
    }


fun String.truncate(maxLength: Int, ellipsis: String = "..."): String =
    if (length <= maxLength) this
    else take(maxLength - ellipsis.length) + ellipsis
