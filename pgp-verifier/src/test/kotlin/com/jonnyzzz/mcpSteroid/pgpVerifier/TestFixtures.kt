package com.jonnyzzz.mcpSteroid.pgpVerifier

import java.io.File

internal fun fixture(name: String): File {
    val url = Thread.currentThread().contextClassLoader.getResource("fixtures/$name")
        ?: error("Missing fixture resource: fixtures/$name")
    return File(url.toURI())
}
