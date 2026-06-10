/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import java.math.BigInteger
import java.security.MessageDigest

/**
 * base62 alphabet (alphanumeric, no `-`/`_`). Unlike URL-safe Base64 the result can never contain or
 * end with `-`, so the hash is safe to embed into ids and names without quoting.
 */
private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

/**
 * Deterministic base62 over `sha256(input.utf8)`: the full 256-bit digest feeds a [BigInteger], rendered
 * in base62. Shared by the IDE self-id and devrig's backend/project hashing so both sides recompute the
 * same value for the same input.
 *
 * The whole digest contributes to the result (nothing is truncated before hashing); callers that only
 * need a short handle should `.take(n)` on the returned string.
 */
fun base62Sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
    return base62(digest)
}

/**
 * Renders the supplied bytes as a base62 string via an unsigned [BigInteger]. Extracted so callers that
 * assemble their own digest (e.g. salted hashes) can reuse only the base62 rendering step.
 */
fun base62(bytes: ByteArray): String {
    var value = BigInteger(1, bytes)
    val base = BigInteger.valueOf(62L)
    if (value == BigInteger.ZERO) return "0"
    val sb = StringBuilder()
    while (value > BigInteger.ZERO) {
        val (q, r) = value.divideAndRemainder(base)
        sb.append(BASE62[r.toInt()])
        value = q
    }
    return sb.toString()
}

/**
 * Renders [bytes] as a fixed-[width] base62 string, least-significant digit first, zero-padded. This is
 * the historical `DevrigProjectRoutingService.projectHash` rendering — kept byte-identical so existing
 * exposed project names do not change. Use [base62Sha256] for new opaque ids.
 */
fun base62FixedWidth(bytes: ByteArray, width: Int): String {
    var value = BigInteger(1, bytes)
    val base = BigInteger.valueOf(62L)
    val sb = StringBuilder(width)
    repeat(width) {
        val (q, r) = value.divideAndRemainder(base)
        sb.append(BASE62[r.toInt()])
        value = q
    }
    return sb.toString()
}

/**
 * Extracts the product code prefix from an IntelliJ build string, e.g. `IU-261.1234` -> `IU`. Returns
 * `null` when [buildNumber] is null or has no `^[A-Z]+-` prefix. Shared so the IDE self-id and devrig
 * compute the same [backendNameFor] product segment for the same build.
 */
fun productCodeFromBuild(buildNumber: String?): String? =
    buildNumber?.let { Regex("""^([A-Z]+)-""").find(it)?.groupValues?.get(1) }

/**
 * R3.3 — the ONE backend_name formula, shared by the in-IDE plugin self-id and devrig's discovery.
 *
 * ```
 * backend_name = "<productCodeLower>-<hash8>"
 *   productCodeLower = productCodeFromBuild(build)?.lowercase() ?: "ide"   // "IU" -> "iu"; fallback "ide"
 *   hash8            = base62Sha256(sourceKey).take(8)
 *   sourceKey        = "pid:<pid>" | "port:<port>" | "managed:<managedId>"
 * ```
 *
 * The pid/port/source/routability live as their own [BackendInfo] fields — never encoded into the id
 * shape. Deterministic and round-trippable: devrig recomputes it per discovered backend to resolve
 * `backend_name -> backend`. One definition for both modules; devrig's `backendNameForMarker/Port/Managed`
 * delegate here.
 */
fun backendNameFor(sourceKey: String, build: String?): String {
    val productCodeLower = productCodeFromBuild(build)?.lowercase() ?: "ide"
    val hash8 = base62Sha256(sourceKey).take(8)
    return "$productCodeLower-$hash8"
}

/** Marker-IDE backend_name: keyed by the IDE's real pid (the only open_project-routable source). */
fun backendNameForMarker(pid: Long, build: String?): String =
    backendNameFor(sourceKey = "pid:$pid", build = build)
