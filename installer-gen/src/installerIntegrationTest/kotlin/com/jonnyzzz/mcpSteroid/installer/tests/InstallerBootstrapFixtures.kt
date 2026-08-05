/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.tests

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Shared fixtures for the two installer-bootstrap Docker tests (POSIX `install.sh` in
 * [InstallerBootstrapTest] and PowerShell `install.ps1` in [InstallerBootstrapPs1Test]). Kept
 * script-agnostic on purpose: the fake JDK tar.gz + tempdir/sha256/permissions helpers are identical
 * between the two lanes; per-test-class specifics (container images, log prefix, devrig zip shape)
 * stay local to each test.
 */

/** Nginx side-car serving the fixture zip/tar.gz over real HTTP to the install container. */
const val NGINX_IMAGE = "nginx:alpine"

/**
 * Ubuntu with curl + unzip PRE-BAKED (resources/ubuntu-installer/Dockerfile) — issue #443: the
 * tools used to be apt-get-installed in the container ENTRYPOINT at test time, so a stalled
 * runner apt mirror produced a healthy-looking container whose readiness poll could never
 * succeed. Built lazily once per test JVM; BuildKit layer-caches it on the agent afterwards.
 */
val ubuntuInstallerImageId: String by lazy {
    val context = createInstallerWorkDir("ubuntu-installer-image")
    val dockerfile = File(context, "Dockerfile")
    val resource = "ubuntu-installer/Dockerfile"
    val stream = object {}.javaClass.classLoader.getResourceAsStream(resource)
        ?: error("missing test resource: $resource")
    stream.use { input -> dockerfile.outputStream().use { input.copyTo(it) } }
    buildDockerImage(
        logPrefix = "installer-ubuntu-image",
        dockerfilePath = dockerfile,
        timeoutSeconds = 600,
    ).imageId
}

/**
 * Wait for the container's docker-exec transport to serve a trivial command. Replaces the old
 * tool-installation polls (#443): with every tool pre-baked into the image, readiness is only
 * about the exec transport, and a genuine docker fault surfaces with its own error instead of
 * hiding behind "apt-get failed?".
 */
fun awaitContainerReady(c: ContainerDriver, logPrefix: String) {
    val deadline = System.currentTimeMillis() + 60_000
    var lastError: Exception? = null
    while (System.currentTimeMillis() < deadline) {
        val r = try {
            c.startProcessInContainer {
                args("sh", "-c", "echo READY").timeoutSeconds(30).description("container readiness probe")
            }.awaitForProcessFinish()
        } catch (e: Exception) {
            lastError = e
            null
        }
        if (r != null && r.exitCode == 0 && "READY" in r.stdout) {
            println("[$logPrefix] container exec transport ready")
            return
        }
        Thread.sleep(1_000)
    }
    error(
        "container exec transport did not become ready within 60s" +
            (lastError?.let { "; last probe error: $it" } ?: ""),
    )
}

/** HOME with a space catches quoting bugs in the installer scripts + downstream launcher wrappers. */
const val INSTALLER_HOME_DIR = "/home/tester one"

/** Constant baked into the generated scripts + into the content-addressed dir names the tests probe. */
const val INSTALLER_TEST_VERSION = "0.0.0-test"

/**
 * Vendor-native JDK version baked into the synthetic model — deliberately DIFFERENT from
 * [INSTALLER_TEST_VERSION] so the tests prove the JDK install dir is named by the JDK's own version,
 * not the devrig version (jonnyzzz/mcp-steroid#362).
 */
const val INSTALLER_TEST_JDK_VERSION = "25.0.7.7.7"

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun makeWorldReadable(dir: File) {
    dir.walkTopDown().forEach {
        it.setReadable(true, false)
        if (it.isDirectory) it.setExecutable(true, false)
    }
    dir.setExecutable(true, false)
}

fun createInstallerWorkDir(prefix: String): File {
    val d = File.createTempFile(prefix, "").let { it.delete(); File(it.absolutePath + "-dir") }
    d.mkdirs()
    return d
}

/**
 * Fake JDK tar.gz used by BOTH lanes: top dir `jdk/` (matches `javaHome="jdk"` baked into the
 * synthetic model), with an executable `bin/java` sh stub — enough that both install.sh's `-x
 * bin/java` check and install.ps1's `bin/java` fallback (see the pwsh-on-Linux branch in the
 * template) pass.
 */
fun buildFakeJdkTarGz(target: File) {
    val javaStub = "#!/bin/sh\necho 'java-stub 25'\nexit 0\n".toByteArray()
    GZIPOutputStream(FileOutputStream(target)).use { gz ->
        TarWriter(gz).use { tar ->
            tar.putDir("jdk/")
            tar.putDir("jdk/bin/")
            tar.putFile("jdk/bin/java", javaStub, mode = 0b111_101_101) // rwxr-xr-x
        }
    }
}

/**
 * Minimal POSIX (ustar) tar writer — the JDK has no built-in tar. Enough for a few small files with
 * stored unix permission bits so the unpacked `bin/java` keeps its +x bit (install.sh checks `-x`).
 */
class TarWriter(private val out: OutputStream) : AutoCloseable {
    fun putDir(name: String) = writeEntry(name, ByteArray(0), typeFlag = '5', mode = 0b111_101_101)
    fun putFile(name: String, data: ByteArray, mode: Int) = writeEntry(name, data, typeFlag = '0', mode = mode)

    private fun writeEntry(name: String, data: ByteArray, typeFlag: Char, mode: Int) {
        val header = ByteArray(512)
        putString(header, 0, name, 100)
        putOctal(header, 100, mode.toLong(), 8)
        putOctal(header, 108, 0, 8)
        putOctal(header, 116, 0, 8)
        putOctal(header, 124, data.size.toLong(), 12)
        putOctal(header, 136, 0, 12)
        header[156] = typeFlag.code.toByte()
        putString(header, 257, "ustar", 6)
        header[263] = '0'.code.toByte(); header[264] = '0'.code.toByte()
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0
        for (b in header) sum += (b.toInt() and 0xff)
        putOctal(header, 148, sum.toLong(), 7)
        header[155] = ' '.code.toByte()
        out.write(header)
        out.write(data)
        val pad = (512 - data.size % 512) % 512
        if (pad > 0) out.write(ByteArray(pad))
    }

    private fun putString(buf: ByteArray, off: Int, s: String, max: Int) {
        val bytes = s.toByteArray()
        System.arraycopy(bytes, 0, buf, off, minOf(bytes.size, max - 1))
    }

    private fun putOctal(buf: ByteArray, off: Int, value: Long, len: Int) {
        val s = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
        System.arraycopy(s.toByteArray(), 0, buf, off, len - 1)
        buf[off + len - 1] = 0
    }

    override fun close() {
        out.write(ByteArray(1024)) // two zero blocks terminate the archive
        out.flush()
    }
}
