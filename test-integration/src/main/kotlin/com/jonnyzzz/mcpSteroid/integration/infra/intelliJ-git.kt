/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val INTELLIJ_MASTER_GIT_CLONE_LINUX_ZIP_URL =
    "https://buildserver.labs.intellij.net/repository/download/ijplatform_master_Service_PrepareGitClonePerOS_Linux/.lastSuccessful/ultimate-git-clone-linux.zip"
const val INTELLIJ_MASTER_BRANCH = "master"

private const val INTELLIJ_REPO_CACHE_SUBDIR = "intellij-master-git-clone"
private const val INTELLIJ_GIT_CLONE_ZIP_NAME = "ultimate-git-clone-linux.zip"
private const val INTELLIJ_GIT_CLONE_ZIP_OVERRIDE_PROPERTY = "test.integration.intellij.git.clone.zip.path"
private const val INTELLIJ_GIT_CLONE_ZIP_OVERRIDE_ENV = "MCP_STEROID_INTELLIJ_GIT_CLONE_ZIP_PATH"
private const val INTELLIJ_GIT_CLONE_ZIP_SEARCH_ROOTS_PROPERTY = "test.integration.intellij.git.clone.zip.search.roots"
private const val INTELLIJ_GIT_CLONE_ZIP_SEARCH_ROOTS_ENV = "MCP_STEROID_INTELLIJ_GIT_CLONE_ZIP_SEARCH_ROOTS"
private const val INTELLIJ_CHECKOUT_OVERRIDE_PROPERTY = "test.integration.intellij.checkout.dir"
private const val INTELLIJ_CHECKOUT_OVERRIDE_ENV = "MCP_STEROID_INTELLIJ_CHECKOUT_DIR"
private const val INTELLIJ_GIT_CLONE_ZIP_AUTH_HEADER_PROPERTY = "test.integration.intellij.git.clone.zip.auth.header"
private const val INTELLIJ_GIT_CLONE_ZIP_AUTH_HEADER_ENV = "MCP_STEROID_INTELLIJ_GIT_CLONE_ZIP_AUTH_HEADER"
private const val TEAMCITY_UNAUTHORIZED = 401
private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000
private const val DOWNLOAD_READ_TIMEOUT_MS = 120_000
private const val DOWNLOAD_MAX_ATTEMPTS = 3
private const val DOWNLOAD_RETRY_DELAY_MS = 5_000L

fun intelliJGitCloneZipInCache(cacheDir: File): File =
    File(File(cacheDir, INTELLIJ_REPO_CACHE_SUBDIR), INTELLIJ_GIT_CLONE_ZIP_NAME)

/**
 * Ensure the IntelliJ master git-clone archive exists under repo-cache.
 *
 * Cache layout:
 * - {cacheDir}/intellij-master-git-clone/ultimate-git-clone-linux.zip
 *
 * We intentionally do not re-download when the ZIP already exists: updating in-container via
 * `git fetch` against configured remotes is much cheaper than refetching the full archive.
 */
fun ensureIntelliJGitCloneZipInCache(
    cacheDir: File,
    zipUrl: String = INTELLIJ_MASTER_GIT_CLONE_LINUX_ZIP_URL,
): File {
    val zipFile = intelliJGitCloneZipInCache(cacheDir)
    if (zipFile.isFile) {
        println("[INTELLIJ-GIT] Using cached archive: $zipFile")
        return zipFile
    }

    zipFile.parentFile.mkdirs()

    val configuredZip = resolveConfiguredZipPath()
    if (configuredZip != null) {
        println("[INTELLIJ-GIT] Using configured IntelliJ ZIP: $configuredZip")
        copyZipToCache(configuredZip, zipFile)
        return zipFile
    }

    val discoveredZip = discoverZipInSearchRoots(cacheDir, searchDepth = 5)
    if (discoveredZip != null) {
        println("[INTELLIJ-GIT] Reusing discovered local IntelliJ ZIP: $discoveredZip")
        copyZipToCache(discoveredZip, zipFile)
        return zipFile
    }

    val downloadFailure = try {
        println("[INTELLIJ-GIT] Downloading IntelliJ git clone ZIP: $zipUrl -> $zipFile")
        downloadFileWithGuestAuthFallback(zipUrl, zipFile)
        return zipFile
    } catch (e: Exception) {
        println("[INTELLIJ-GIT] Download failed: ${e.message}")
        e
    }

    val localCheckout = resolveConfiguredCheckoutDir()
    if (localCheckout != null) {
        println("[INTELLIJ-GIT] Building IntelliJ ZIP from local checkout: $localCheckout")
        buildZipFromLocalCheckout(localCheckout, zipFile, branch = INTELLIJ_MASTER_BRANCH)
        return zipFile
    }

    error(
        buildString {
            append("Failed to resolve IntelliJ git clone ZIP.")
            append(" Download URL failed: $zipUrl (${downloadFailure.message}).")
            append(" Set $INTELLIJ_GIT_CLONE_ZIP_OVERRIDE_PROPERTY or ")
            append("$INTELLIJ_GIT_CLONE_ZIP_OVERRIDE_ENV to a local ZIP path, or set ")
            append("$INTELLIJ_CHECKOUT_OVERRIDE_PROPERTY / $INTELLIJ_CHECKOUT_OVERRIDE_ENV ")
            append("to a local Ultimate checkout directory for ZIP generation.")
        }
    )
}

private fun downloadFileWithGuestAuthFallback(url: String, destination: File) {
    val guestAuthUrl = toGuestAuthUrl(url)
    val candidateUrls = buildList {
        add(url)
        if (!guestAuthUrl.isNullOrBlank() && guestAuthUrl != url) {
            add(guestAuthUrl)
        }
    }

    var lastError: Exception? = null

    for ((urlIndex, candidateUrl) in candidateUrls.withIndex()) {
        if (urlIndex > 0) {
            println("[INTELLIJ-GIT] Retrying download via TeamCity guestAuth: $candidateUrl")
        }

        for (attempt in 1..DOWNLOAD_MAX_ATTEMPTS) {
            try {
                if (attempt > 1) {
                    println(
                        "[INTELLIJ-GIT] Download retry $attempt/$DOWNLOAD_MAX_ATTEMPTS: $candidateUrl"
                    )
                }
                downloadFile(candidateUrl, destination)
                return
            } catch (e: DownloadFailedException) {
                lastError = e

                val isUnauthorizedPrimaryUrl =
                    e.statusCode == TEAMCITY_UNAUTHORIZED && candidateUrl == url && !guestAuthUrl.isNullOrBlank()
                if (isUnauthorizedPrimaryUrl) {
                    break
                }

                val shouldRetry = shouldRetryStatusCode(e.statusCode)
                if (!shouldRetry || attempt == DOWNLOAD_MAX_ATTEMPTS) {
                    break
                }
                Thread.sleep(DOWNLOAD_RETRY_DELAY_MS)
            } catch (e: Exception) {
                lastError = e
                if (attempt == DOWNLOAD_MAX_ATTEMPTS) {
                    break
                }
                Thread.sleep(DOWNLOAD_RETRY_DELAY_MS)
            }
        }
    }

    throw (lastError ?: IllegalStateException("Failed downloading IntelliJ git clone ZIP from $url"))
}

private fun downloadFile(url: String, destination: File) {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
        readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        instanceFollowRedirects = true
        val authHeader = resolvePropertyOrEnv(
            INTELLIJ_GIT_CLONE_ZIP_AUTH_HEADER_PROPERTY,
            INTELLIJ_GIT_CLONE_ZIP_AUTH_HEADER_ENV,
        )
        if (!authHeader.isNullOrBlank()) {
            setRequestProperty("Authorization", authHeader)
        }
    }

    try {
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            throw DownloadFailedException(url, statusCode)
        }

        val tempFile = File(destination.parentFile, "${destination.name}.tmp")
        try {
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    } finally {
        connection.disconnect()
    }
}

private fun shouldRetryStatusCode(statusCode: Int): Boolean {
    if (statusCode == 408 || statusCode == 429) return true
    if (statusCode in 500..599) return true
    return false
}

private fun resolveConfiguredZipPath(): File? {
    val configured = resolvePropertyOrEnv(
        INTELLIJ_GIT_CLONE_ZIP_OVERRIDE_PROPERTY,
        INTELLIJ_GIT_CLONE_ZIP_OVERRIDE_ENV,
    )?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val file = File(configured)
    require(file.isFile) { "Configured IntelliJ ZIP path does not exist: $configured" }
    return file
}

private fun discoverZipInSearchRoots(cacheDir: File, searchDepth: Int): File? {
    val explicitRoots = parsePathList(
        resolvePropertyOrEnv(
            INTELLIJ_GIT_CLONE_ZIP_SEARCH_ROOTS_PROPERTY,
            INTELLIJ_GIT_CLONE_ZIP_SEARCH_ROOTS_ENV,
        )
    )

    val userHome = System.getProperty("user.home")?.let(::File)
    val roots = buildList {
        add(cacheDir.parentFile ?: cacheDir)
        add(cacheDir)
        if (userHome != null) add(File(userHome, "Downloads"))
        addAll(explicitRoots)
    }.distinctBy { it.absolutePath }

    for (root in roots) {
        if (!root.exists()) continue

        val directCandidate = File(root, INTELLIJ_GIT_CLONE_ZIP_NAME)
        if (directCandidate.isFile) return directCandidate

        findZipUnderRoot(root, searchDepth)?.let { return it }
    }

    return null
}

private fun findZipUnderRoot(root: File, searchDepth: Int): File? {
    if (!root.isDirectory) return null

    val stream: Stream<java.nio.file.Path> = Files.walk(root.toPath(), searchDepth)
    stream.use { paths ->
        val match = paths
            .filter { Files.isRegularFile(it) && it.fileName.toString() == INTELLIJ_GIT_CLONE_ZIP_NAME }
            .findFirst()
        if (match.isPresent) {
            return match.get().toFile()
        }
    }

    return null
}

private fun resolveConfiguredCheckoutDir(): File? {
    val configured = resolvePropertyOrEnv(
        INTELLIJ_CHECKOUT_OVERRIDE_PROPERTY,
        INTELLIJ_CHECKOUT_OVERRIDE_ENV,
    )?.trim()?.takeIf { it.isNotEmpty() }

    if (configured == null) return null

    val configuredDir = File(configured)
    require(isIntelliJCheckout(configuredDir)) {
        "Configured IntelliJ checkout path is not a valid checkout: $configured"
    }
    return configuredDir
}

private fun isIntelliJCheckout(dir: File): Boolean {
    if (!dir.isDirectory) return false
    if (!File(dir, ".git").isDirectory) return false
    if (!File(dir, "bazel.cmd").isFile) return false
    return true
}

private fun copyZipToCache(source: File, destination: File) {
    if (source.absoluteFile == destination.absoluteFile) return
    val temp = File(destination.parentFile, "${destination.name}.tmp")
    source.inputStream().use { input ->
        temp.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

private fun buildZipFromLocalCheckout(sourceCheckout: File, destinationZip: File, branch: String) {
    require(isIntelliJCheckout(sourceCheckout)) {
        "Cannot build IntelliJ ZIP from invalid checkout: ${sourceCheckout.absolutePath}"
    }

    val tempCloneDir = Files.createTempDirectory("intellij-master-zip-source-").toFile()
    try {
        val sourceUri = sourceCheckout.toURI().toString()
        runCommand(
            command = listOf(
                "git",
                "clone",
                "--depth", "1",
                "--single-branch",
                "--branch", branch,
                sourceUri,
                tempCloneDir.absolutePath,
            ),
            description = "Clone IntelliJ checkout for ZIP packaging",
            timeoutSeconds = 3_600,
        )
        zipDirectory(tempCloneDir, destinationZip)
    } finally {
        tempCloneDir.deleteRecursively()
    }
}

private fun zipDirectory(sourceDir: File, destinationZip: File) {
    require(isIntelliJCheckout(sourceDir)) {
        "Cannot build IntelliJ ZIP from invalid checkout: ${sourceDir.absolutePath}"
    }

    val tempZip = File(destinationZip.parentFile, "${destinationZip.name}.tmp")
    if (tempZip.exists()) tempZip.delete()

    ZipOutputStream(tempZip.outputStream()).use { zipOut ->
        val rootPath = sourceDir.toPath()
        val stream: Stream<java.nio.file.Path> = Files.walk(rootPath)
        stream.use { paths ->
            paths
                .filter { it != rootPath }
                .forEach { path ->
                    val relativePath = rootPath.relativize(path).toString().replace(File.separatorChar, '/')
                    if (Files.isDirectory(path)) {
                        zipOut.putNextEntry(ZipEntry("$relativePath/"))
                        zipOut.closeEntry()
                    } else if (Files.isRegularFile(path)) {
                        zipOut.putNextEntry(ZipEntry(relativePath))
                        Files.newInputStream(path).use { input -> input.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
        }
    }

    Files.move(tempZip.toPath(), destinationZip.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

private fun runCommand(
    command: List<String>,
    description: String,
    timeoutSeconds: Long,
    workDir: File? = null,
) {
    val processBuilder = ProcessBuilder(command)
        .inheritIO()

    if (workDir != null) {
        processBuilder.directory(workDir)
    }

    val process = processBuilder.start()
    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    require(finished) {
        "Timed out while running '$description' after ${timeoutSeconds}s: ${command.joinToString(" ")}"
    }
    val exitCode = process.exitValue()
    require(exitCode == 0) {
        "Failed '$description' with exit code $exitCode: ${command.joinToString(" ")}"
    }
}

private fun parsePathList(raw: String?): List<File> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw
        .split(File.pathSeparator)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(::File)
}

private fun resolvePropertyOrEnv(propertyName: String, envName: String): String? {
    val fromProperty = System.getProperty(propertyName)?.trim()?.takeIf { it.isNotEmpty() }
    if (fromProperty != null) return fromProperty
    return System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun toGuestAuthUrl(url: String): String? {
    if (url.contains("/guestAuth/")) return url

    val uri = URI(url)
    if (!uri.host.contains("buildserver.labs.intellij.net")) return null

    val path = uri.rawPath ?: return null
    if (!path.startsWith("/repository/")) return null

    val guestPath = path.replaceFirst("/repository/", "/guestAuth/repository/")
    return URI(
        uri.scheme,
        uri.rawUserInfo,
        uri.host,
        uri.port,
        guestPath,
        uri.rawQuery,
        uri.rawFragment,
    ).toString()
}

private class DownloadFailedException(
    val url: String,
    val statusCode: Int,
) : IllegalStateException("Failed downloading $url. HTTP $statusCode")
