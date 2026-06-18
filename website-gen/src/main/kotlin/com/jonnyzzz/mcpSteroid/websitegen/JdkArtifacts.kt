/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.websitegen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayInputStream

/** Operating system family of a JDK build. `ALPINE_LINUX` is the musl variant; `LINUX` is glibc. */
@Serializable
enum class JdkOs { LINUX, ALPINE_LINUX, MACOS, WINDOWS }

/** CPU architecture of a JDK build. */
@Serializable
enum class JdkArch { X64, AARCH64 }

/** Archive container the JDK ships in. [extension] is the conventional file-name suffix. */
@Serializable
enum class ArchiveType(val extension: String) { TAR_GZ("tar.gz"), ZIP("zip") }

/** The (os, arch) a JDK build targets. */
@Serializable
data class JdkPlatform(val os: JdkOs, val arch: JdkArch)

/**
 * A fully-resolved JDK build, ready for #113's installer-script generation. Every field is COMPUTED
 * from the live vendor sources — none are hand-pinned:
 *  - [version]        — the vendor-native version string. NOT comparable across vendors: Corretto reports
 *                       the 5-part Amazon build (`25.0.3.9.1`), Azul the 3-part Java version (`25.0.3`).
 *  - [featureVersion] — the Java feature version (e.g. `25`). Comparable across vendors; use this, not
 *                       [version], for "is this JDK 25?" checks.
 *  - [url]            — the resolved, version-pinned download URL (e.g. Corretto's `latest` alias
 *                       followed to its versioned resource).
 *  - [fileName]       — the archive's file name (the [url] basename), e.g. for `curl -o <fileName>`.
 *  - [size]           — the byte length of the downloaded archive.
 *  - [sha256]         — lowercase hex over the downloaded bytes (Azul also cross-checks the published hash).
 *  - [javaHome]       — the path to `JAVA_HOME` (the directory whose `bin/` holds `java`), discovered by
 *                       scanning the archive entries, so macOS's `…/Contents/Home` and the Linux/Windows
 *                       top-level dir are handled uniformly. Always **archive-relative, forward-slash, no
 *                       leading or trailing slash** (ZIP/TAR entries are `/`-separated even for Windows
 *                       builds) — Windows consumers translate separators.
 */
@Serializable
data class JdkArtifact(
    val platform: JdkPlatform,
    val vendor: String,
    val version: String,
    val featureVersion: Int,
    val archive: ArchiveType,
    val url: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val javaHome: String,
)

/** The whole JDK data model: one [JdkArtifact] per supported platform. */
@Serializable
data class JdkModel(val jdks: List<JdkArtifact>)

private val jdkJson = Json { ignoreUnknownKeys = true }

/** The archive file name = the URL basename, minus any query string. */
private fun fileNameOf(url: String): String = url.substringAfterLast('/').substringBefore('?')

// ── archive scanning: compute JAVA_HOME ──────────────────────────────────────────────────────────

internal fun archiveEntryNames(bytes: ByteArray, archive: ArchiveType): List<String> = when (archive) {
    ArchiveType.TAR_GZ ->
        TarArchiveInputStream(GzipCompressorInputStream(ByteArrayInputStream(bytes))).use { tis ->
            generateSequence { tis.nextEntry }.map { it.name }.toList()
        }
    ArchiveType.ZIP ->
        ZipArchiveInputStream(ByteArrayInputStream(bytes)).use { zis ->
            generateSequence { zis.nextEntry }.map { it.name }.toList()
        }
}

/**
 * Compute the archive-relative `JAVA_HOME` by finding the `bin/java` (or `bin/java.exe`) launcher and
 * returning the directory that contains its `bin/`. Works for every layout we ship:
 *  - Linux/Alpine/Windows: `amazon-corretto-…-linux-x64/bin/java`     -> `amazon-corretto-…-linux-x64`
 *  - macOS:                `amazon-corretto-25.jdk/Contents/Home/bin/java` -> `amazon-corretto-25.jdk/Contents/Home`
 */
internal fun findJavaHome(bytes: ByteArray, archive: ArchiveType): String {
    val names = archiveEntryNames(bytes, archive).map { it.trimEnd('/') }
    val launchers = names.filter {
        it == "bin/java" || it == "bin/java.exe" || it.endsWith("/bin/java") || it.endsWith("/bin/java.exe")
    }
    require(launchers.isNotEmpty()) {
        "Archive has no bin/java[.exe] entry; cannot compute JAVA_HOME (first entries=${names.take(8)})"
    }
    // A JDK may bundle a nested JRE (`<root>/jre/bin/java`) whose entry can appear before the real
    // launcher; pick the SHALLOWEST `bin/java` (fewest path segments) so we get the JDK root, not its
    // nested jre — independent of archive entry order.
    val launcher = launchers.minBy { it.count { c -> c == '/' } }

    val idx = launcher.lastIndexOf("/bin/")
    return if (idx < 0) "" else launcher.substring(0, idx)
}

// ── Amazon Corretto (GPG-signed; ETag-cached) ────────────────────────────────────────────────────

private const val CORRETTO_KEY_URL = "https://apt.corretto.aws/corretto.key"
private const val CORRETTO_LATEST = "https://corretto.aws/downloads/latest"
// Pinned fingerprint of the Amazon Corretto release key (RSA-4096). The key is fetched live over HTTPS,
// so we bind verification to this constant rather than trusting whatever the key endpoint serves.
const val CORRETTO_KEY_FINGERPRINT = "6dc3636dae534049c8b94623a122542ab04f24e3"

private data class CorrettoSpec(val platform: JdkPlatform, val archive: ArchiveType, val alias: String)

// The `latest` aliases auto-resolve to the newest Corretto 25 build (currently 25.0.3.9.1) — no pin.
private val CORRETTO_SPECS = listOf(
    CorrettoSpec(JdkPlatform(JdkOs.LINUX, JdkArch.X64), ArchiveType.TAR_GZ, "amazon-corretto-25-x64-linux-jdk.tar.gz"),
    CorrettoSpec(JdkPlatform(JdkOs.LINUX, JdkArch.AARCH64), ArchiveType.TAR_GZ, "amazon-corretto-25-aarch64-linux-jdk.tar.gz"),
    CorrettoSpec(JdkPlatform(JdkOs.ALPINE_LINUX, JdkArch.X64), ArchiveType.TAR_GZ, "amazon-corretto-25-x64-alpine-jdk.tar.gz"),
    CorrettoSpec(JdkPlatform(JdkOs.ALPINE_LINUX, JdkArch.AARCH64), ArchiveType.TAR_GZ, "amazon-corretto-25-aarch64-alpine-jdk.tar.gz"),
    CorrettoSpec(JdkPlatform(JdkOs.MACOS, JdkArch.X64), ArchiveType.TAR_GZ, "amazon-corretto-25-x64-macos-jdk.tar.gz"),
    CorrettoSpec(JdkPlatform(JdkOs.MACOS, JdkArch.AARCH64), ArchiveType.TAR_GZ, "amazon-corretto-25-aarch64-macos-jdk.tar.gz"),
    CorrettoSpec(JdkPlatform(JdkOs.WINDOWS, JdkArch.X64), ArchiveType.ZIP, "amazon-corretto-25-x64-windows-jdk.zip"),
)

private val CORRETTO_VERSION_RE = Regex("""amazon-corretto-(\d[\d.]*\d)""")

private fun resolveCorretto(
    spec: CorrettoSpec,
    cache: Cache,
    http: HttpFetcher,
    correttoKey: ByteArray,
    keyFingerprint: String,
): JdkArtifact {
    val aliasUrl = "$CORRETTO_LATEST/${spec.alias}"
    // The versioned URL behind the `latest` redirect — recorded in the model so installs are reproducible.
    val versionedUrl = http.head(aliasUrl).resolvedUrl
    val version = CORRETTO_VERSION_RE.find(versionedUrl)?.groupValues?.get(1)
        ?: error("Cannot parse Corretto version from resolved URL: $versionedUrl")

    // ETag-validated cache: an unchanged build is a cache hit; a new release re-downloads.
    val bytes = cache.downloadWithEtag(aliasUrl, http)

    // Vendor-natural validation: verify the detached .sig against the Amazon Corretto release key.
    val signature = http.getBytes("$aliasUrl.sig")
    PgpVerifier.verifyDetached(bytes, signature, correttoKey, keyFingerprint)

    return JdkArtifact(
        platform = spec.platform,
        vendor = "corretto",
        version = version,
        featureVersion = version.substringBefore('.').toInt(),
        archive = spec.archive,
        url = versionedUrl,
        fileName = fileNameOf(versionedUrl),
        size = bytes.size.toLong(),
        sha256 = sha256Hex(bytes),
        javaHome = findJavaHome(bytes, spec.archive),
    )
}

// ── Azul Zulu, Windows/aarch64 (Metadata API; GPG-signed) ────────────────────────────────────────

private const val AZUL_PACKAGES = "https://api.azul.com/metadata/v1/zulu/packages/"
// Azul's package signing key (RSA-4096, fingerprint 27BC…B1998361219BD9C9) — the key that signs the
// `signature-binary` detached OpenPGP signatures the Metadata API advertises for each package.
private const val AZUL_KEY_URL = "https://repos.azul.com/azul-repo.key"
// Pinned fingerprint of the Azul package signing key (RSA-4096) — same rationale as Corretto's.
const val AZUL_KEY_FINGERPRINT = "27bc0c8cb3d81623f59bdadcb1998361219bd9c9"

@Serializable
private data class AzulPackage(
    @SerialName("download_url") val downloadUrl: String,
    val name: String,
    @SerialName("java_version") val javaVersion: List<Int> = emptyList(),
    @SerialName("package_uuid") val packageUuid: String,
)

@Serializable
private data class AzulSignature(val type: String, val url: String)

@Serializable
private data class AzulDetail(
    @SerialName("download_url") val downloadUrl: String,
    val name: String,
    @SerialName("sha256_hash") val sha256Hash: String,
    @SerialName("java_version") val javaVersion: List<Int> = emptyList(),
    val signatures: List<AzulSignature> = emptyList(),
)

private fun compareVersionLists(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

private fun resolveAzulWindowsArm(cache: Cache, http: HttpFetcher, keyFingerprint: String): JdkArtifact {
    // Resolve the LATEST GA JDK 25 for Windows/aarch64 via the Azul Metadata API — no hardcoded URL.
    val listUrl = AZUL_PACKAGES + "?java_version=25&os=windows&arch=aarch64&archive_type=zip" +
            "&java_package_type=jdk&javafx_bundled=false&latest=true&release_status=ga" +
            "&availability_types=CA&page=1&page_size=100"
    val packages = jdkJson.decodeFromString<List<AzulPackage>>(http.getBytes(listUrl).decodeToString())
    require(packages.isNotEmpty()) { "Azul Metadata API returned no GA JDK 25 win_aarch64 packages: $listUrl" }
    val latest = packages.sortedWith { x, y -> compareVersionLists(x.javaVersion, y.javaVersion) }.last()

    val detail = jdkJson.decodeFromString<AzulDetail>(http.getBytes(AZUL_PACKAGES + latest.packageUuid).decodeToString())

    // Azul exposes no HEAD ETag, but DOES publish a sha256 — content-address the cache by it (the hash is
    // both the cache key and a first-line integrity check on the download).
    val bytes = cache.downloadVerifyingSha256(detail.downloadUrl, detail.sha256Hash, http)

    // Vendor-natural validation, same as Corretto: verify Azul's detached OpenPGP signature against the
    // Azul package signing key. Run every resolve (cheap) so a cache hit is validated too.
    val openpgp = detail.signatures.firstOrNull { it.type.equals("openpgp", ignoreCase = true) }
        ?: error("Azul package ${latest.packageUuid} advertises no OpenPGP signature: ${detail.signatures}")
    PgpVerifier.verifyDetached(bytes, http.getBytes(openpgp.url), http.getBytes(AZUL_KEY_URL), keyFingerprint)

    require(detail.javaVersion.isNotEmpty()) { "Azul package ${latest.packageUuid} has no java_version" }
    return JdkArtifact(
        platform = JdkPlatform(JdkOs.WINDOWS, JdkArch.AARCH64),
        vendor = "azul-zulu",
        version = detail.javaVersion.joinToString("."),
        featureVersion = detail.javaVersion.first(),
        archive = ArchiveType.ZIP,
        url = detail.downloadUrl,
        fileName = fileNameOf(detail.downloadUrl),
        size = bytes.size.toLong(),
        sha256 = detail.sha256Hash.lowercase(),
        javaHome = findJavaHome(bytes, ArchiveType.ZIP),
    )
}

// ── public entry point ───────────────────────────────────────────────────────────────────────────

/**
 * Prepare the data model of all JDKs needed for #113's installer-script generation: Amazon Corretto 25
 * for linux (glibc + musl/alpine) x64/aarch64, macOS x64/aarch64 and windows x64; plus Azul Zulu 25 for
 * windows/aarch64. Every download is validated vendor-naturally — both vendors publish detached
 * OpenPGP signatures (Corretto `.sig`, Azul Metadata API `signature-binary`), verified here against the
 * vendor's signing key — and cached through [cache], so re-runs reuse unchanged builds.
 */
fun resolveAllJdks(
    cache: Cache,
    http: HttpFetcher = KtorHttpFetcher,
    correttoKeyFingerprint: String = CORRETTO_KEY_FINGERPRINT,
    azulKeyFingerprint: String = AZUL_KEY_FINGERPRINT,
): JdkModel {
    val correttoKey = http.getBytes(CORRETTO_KEY_URL)
    val corretto = CORRETTO_SPECS.map { resolveCorretto(it, cache, http, correttoKey, correttoKeyFingerprint) }
    val azul = resolveAzulWindowsArm(cache, http, azulKeyFingerprint)
    return JdkModel(corretto + azul)
}
