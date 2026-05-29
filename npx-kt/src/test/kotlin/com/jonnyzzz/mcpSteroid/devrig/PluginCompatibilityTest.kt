/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PluginCompatibilityTest {

    @Test
    fun `ideBuildBaseline parses common build forms`() {
        assertEquals(261, ideBuildBaseline("261.24374.151"))
        assertEquals(261, ideBuildBaseline("IU-261.24374.151"))
        assertEquals(253, ideBuildBaseline("253.28294.334"))
        assertEquals(253, ideBuildBaseline("AI-253.4.7"))
        assertEquals(261, ideBuildBaseline("261"))
        assertNull(ideBuildBaseline("snapshot"))
        // Android Studio's marketing version is year-like, not a 3-digit platform baseline.
        assertNull(ideBuildBaseline("2025.3.4.7"))
    }

    @Test
    fun `parsePluginBuildRange reads since and optional until`() {
        assertEquals(PluginBuildRange(261, null), parsePluginBuildRange("""<idea-version since-build="261" />"""))
        assertEquals(
            PluginBuildRange(261, 263),
            parsePluginBuildRange("""<idea-version since-build="261.0" until-build="263.*" />"""),
        )
        assertNull(parsePluginBuildRange("""<idea-version until-build="263.*" />"""))
    }

    @Test
    fun `accepts enforces since and optional until`() {
        val sinceOnly = PluginBuildRange(261, null)
        assertTrue(sinceOnly.accepts("261.24374.151"))
        assertTrue(sinceOnly.accepts("IU-262.1"))
        assertFalse(sinceOnly.accepts("253.28294.334"))
        assertFalse(sinceOnly.accepts("252.1"))
        assertFalse(sinceOnly.accepts("nonsense"))

        val bounded = PluginBuildRange(261, 261)
        assertTrue(bounded.accepts("261.9"))
        assertFalse(bounded.accepts("262.1"))
    }

    @Test
    fun `readBundledPluginBuildRange reads since-build from the nested plugin jar`(@TempDir tempDir: Path) {
        val zip = writePluginZipFixture(tempDir.resolve("ij-plugin.zip"), sinceBuild = "261")
        assertEquals(PluginBuildRange(261, null), readBundledPluginBuildRange(zip))
    }

    @Test
    fun `download list marks compatible and incompatible products against the plugin range`() = runBlocking {
        val rows = collectAvailableBackendDownloads(
            products = listOf(IdeProduct.IntelliJIdea, IdeProduct.IntelliJIdeaCommunity),
            versionResolver = object : AvailableBackendVersionResolver {
                override suspend fun resolveLatestStableRelease(product: IdeProduct): AvailableBackendRelease =
                    when (product) {
                        IdeProduct.IntelliJIdea -> AvailableBackendRelease("2026.1.2", "2026-05-15", "261.24374.151")
                        else -> AvailableBackendRelease("2025.3", "2026-04-30", "253.28294.334")
                    }
            },
            pluginBuildRange = PluginBuildRange(261, null),
        )

        val ultimate = rows.single { it.product == IdeProduct.IntelliJIdea }
        val community = rows.single { it.product == IdeProduct.IntelliJIdeaCommunity }
        assertEquals("261.24374.151", ultimate.build)
        assertEquals(true, ultimate.compatible)
        assertEquals("253.28294.334", community.build)
        assertEquals(false, community.compatible)
    }

    @Test
    fun `download refuses an incompatible build with a clear message`(@TempDir tempDir: Path) = runBlocking {
        val manager = BackendManager(
            homePaths = HomePaths(tempDir.resolve("home")),
            downloader = object : ManagedBackendDownloader {
                override suspend fun resolve(id: BackendId): BackendDownloadResolution =
                    BackendDownloadResolution(
                        product = IdeProduct.IntelliJIdeaCommunity,
                        version = "2025.3",
                        build = "253.28294.334",
                        url = "https://example.invalid/idea-2025.3.dmg",
                    )

                override suspend fun downloadAndUnpack(
                    resolution: BackendDownloadResolution,
                    targetDir: Path,
                ): BackendDownloadArtifact = error("an incompatible build must never be downloaded")
            },
            pluginBuildRange = PluginBuildRange(261, null),
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.download(parseBackendId("idea-community"))
        }
        assertTrue(error.message!!.contains("not compatible"), error.message)
        assertTrue(error.message!!.contains("build >= 261"), error.message)
        assertTrue(error.message!!.contains("253.28294.334"), error.message)
    }

    private fun writePluginZipFixture(zip: Path, sinceBuild: String): Path {
        Files.createDirectories(zip.parent)
        val jarBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { jar ->
                jar.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                jar.write("""<idea-plugin><idea-version since-build="$sinceBuild" /></idea-plugin>""".toByteArray())
                jar.closeEntry()
            }
        }.toByteArray()
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("mcp-steroid/lib/ij-plugin-test.jar"))
            out.write(jarBytes)
            out.closeEntry()
        }
        return zip
    }
}
