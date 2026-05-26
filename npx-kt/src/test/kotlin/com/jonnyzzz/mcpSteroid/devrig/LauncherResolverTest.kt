/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LauncherResolverTest {

    @Test
    fun `resolves linux launcher from synthetic product-info`(
        @TempDir tempDir: Path,
    ) {
        val bundle = writeBundle(tempDir)

        val resolved = LauncherResolver(HostOs.LINUX).resolve(bundle)

        assertEquals("bin/idea.sh", resolved.launcherPath)
        assertEquals(bundle.resolve("bin/idea.sh").normalize(), resolved.launcherAbsolutePath)
        assertEquals("IC", resolved.productCode)
        assertEquals("IC-253.1", resolved.buildNumber)
    }

    @Test
    fun `resolves macOS launcher from product-info under Contents Resources`(
        @TempDir tempDir: Path,
    ) {
        val bundle = tempDir.resolve("IntelliJ IDEA CE.app")
        writeProductInfo(bundle.resolve("Contents/Resources/product-info.json"))

        val resolved = LauncherResolver(HostOs.MAC).resolve(bundle)

        assertEquals("Contents/MacOS/idea", resolved.launcherPath)
        assertEquals(bundle.resolve("Contents/MacOS/idea").normalize(), resolved.launcherAbsolutePath)
    }

    @Test
    fun `resolves macOS launcher relative to Contents Resources when product-info uses parent path`(
        @TempDir tempDir: Path,
    ) {
        val bundle = tempDir.resolve("IntelliJ IDEA.app")
        writeProductInfo(
            path = bundle.resolve("Contents/Resources/product-info.json"),
            macLauncherPath = "../MacOS/idea",
        )

        val resolved = LauncherResolver(HostOs.MAC).resolve(bundle)

        assertEquals("Contents/MacOS/idea", resolved.launcherPath)
        assertEquals(bundle.resolve("Contents/MacOS/idea").normalize(), resolved.launcherAbsolutePath)
    }

    @Test
    fun `resolves windows launcher from synthetic product-info`(
        @TempDir tempDir: Path,
    ) {
        val bundle = writeBundle(tempDir)

        val resolved = LauncherResolver(HostOs.WINDOWS).resolve(bundle)

        assertEquals("bin/idea64.exe", resolved.launcherPath)
    }

    @Test
    fun `missing product-info fails clearly`(
        @TempDir tempDir: Path,
    ) {
        val error = assertFailsWith<IllegalStateException> {
            LauncherResolver(HostOs.LINUX).resolve(tempDir.resolve("missing"))
        }

        assertTrue(error.message!!.contains("product-info.json not found"), error.message)
    }

    @Test
    fun `missing host launch entry fails clearly`(
        @TempDir tempDir: Path,
    ) {
        val bundle = tempDir.resolve("bundle")
        Files.createDirectories(bundle)
        Files.writeString(
            bundle.resolve("product-info.json"),
            """{"productCode":"IC","buildNumber":"IC-253.1","launch":[{"os":"Linux","launcherPath":"bin/idea.sh"}]}""",
        )

        val error = assertFailsWith<IllegalStateException> {
            LauncherResolver(HostOs.WINDOWS).resolve(bundle)
        }

        assertTrue(error.message!!.contains("no launch entry for Windows"), error.message)
    }

    private fun writeBundle(tempDir: Path): Path {
        val bundle = tempDir.resolve("idea-IC-253.1")
        writeProductInfo(bundle.resolve("product-info.json"))
        return bundle
    }

    private fun writeProductInfo(path: Path, macLauncherPath: String = "Contents/MacOS/idea") {
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            """
            {
              "productCode": "IC",
              "buildNumber": "IC-253.1",
              "launch": [
                { "os": "macOS", "launcherPath": "$macLauncherPath", "javaExecutablePath": "Contents/jbr/Contents/Home/bin/java" },
                { "os": "Linux", "launcherPath": "bin/idea.sh", "javaExecutablePath": "jbr/bin/java" },
                { "os": "Windows", "launcherPath": "bin/idea64.exe", "javaExecutablePath": "jbr/bin/java.exe" }
              ]
            }
            """.trimIndent(),
        )
    }
}
