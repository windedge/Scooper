package scooper.service

import com.scoopai.tools.cache.ScoopCacheManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.taskqueue.TaskQueue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * TDD tests for ScoopClient.prewarmTestInstallCache.
 * Verifies cache pre-warming from scoop-ai download-phase files or finalized entries
 * to test-install specific {testApp}#{ver}#{hash}{ext} names, using hardlink or copy fallback.
 */
class ScoopClientPrewarmCacheTest {

    private val logStream = ScoopLogStream()
    private val client = ScoopClient(logStream, TaskQueue())

    @TempDir
    lateinit var tempDir: Path

    private fun writeManifest(version: String, url: String, extra: String = ""): File {
        val mf = tempDir.resolve("manifest-${System.nanoTime()}.json").toFile()
        mf.writeText(
            """
            {
                "version": "$version",
                "url": "$url",
                "hash": "0123456789abcdef",
                $extra
            }
            """.trimIndent()
        )
        return mf
    }

    private fun writeArchManifest(version: String, url64: String, url32: String): File {
        val mf = tempDir.resolve("manifest-arch-${System.nanoTime()}.json").toFile()
        mf.writeText(
            """
            {
                "version": "$version",
                "architecture": {
                    "64bit": { "url": "$url64", "hash": "deadbeef" },
                    "32bit": { "url": "$url32", "hash": "cafebabe" }
                }
            }
            """.trimIndent()
        )
        return mf
    }

    private fun writeMultiUrlManifest(version: String, urls: List<String>): File {
        val mf = tempDir.resolve("manifest-multi-${System.nanoTime()}.json").toFile()
        val urlJson = urls.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"$it\"" }
        mf.writeText(
            """
            {
                "version": "$version",
                "url": $urlJson,
                "hash": ["a", "b"]
            }
            """.trimIndent()
        )
        return mf
    }

    // ---------------------------------------------------------------
    // Helper to compute target name via reflection (cacheFileName is private)
    // ---------------------------------------------------------------
    private fun computeTargetName(testApp: String, version: String, url: String): String {
        val method = ScoopClient::class.java.getDeclaredMethod(
            "cacheFileName",
            App::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        val fakeApp = App(name = testApp, latestVersion = version)
        return method.invoke(client, fakeApp, version, url) as String
    }

    // ---------------------------------------------------------------
    // Test cases
    // ---------------------------------------------------------------

    @Test
    fun `prewarms from download-phase file (agent download name) to correct test cache name`() {
        val cacheDir = tempDir.resolve("cache1").toFile().also { it.mkdirs() }
        val url = "https://example.com/myapp-1.0.msi"
        val dlName = ScoopCacheManager.downloadCacheName(url)
        val source = File(cacheDir, dlName)
        source.writeText("FAKE-MSI-DATA-FOR-TEST")

        val manifest = writeManifest("1.0", url)
        val count = client.prewarmTestInstallCache("myapp-test", manifest, cacheDir)

        assertEquals(1, count, "should prewarm one file")

        val targetName = computeTargetName("myapp-test", "1.0", url)
        assertTrue(targetName.contains("myapp-test#1.0#"), "target should use testApp#ver#hash")
        val target = File(cacheDir, targetName)
        assertTrue(target.exists(), "target cache file must exist")
        assertEquals("FAKE-MSI-DATA-FOR-TEST", target.readText(), "content must match (link or copy)")
    }

    @Test
    fun `prewarms from already-finalized standard name (any app#ver#hash) via glob scan`() {
        val cacheDir = tempDir.resolve("cache2").toFile().also { it.mkdirs() }
        val url = "https://example.com/tool.zip#/inner.7z"
        // simulate a finalized entry from real app name (as agent would after finalizeCacheEntry)
        val hash = ScoopCacheManager.urlHash(url)
        val mExt = ScoopCacheManager.urlExtension(url)
        val finalizedOther = File(cacheDir, "realtool#2.5#$hash$mExt")
        finalizedOther.writeText("FINALIZED-ZIP-DATA")

        val manifest = writeManifest("2.5", url)
        val count = client.prewarmTestInstallCache("tool-test", manifest, cacheDir)

        assertEquals(1, count)
        val targetName = computeTargetName("tool-test", "2.5", url)
        val target = File(cacheDir, targetName)
        assertTrue(target.exists())
        assertEquals("FINALIZED-ZIP-DATA", target.readText())
    }

    @Test
    fun `returns 0 and does not throw when source download file missing`() {
        val cacheDir = tempDir.resolve("cache3").toFile().also { it.mkdirs() }
        val url = "https://example.com/missing.exe"
        // do not create any source file

        val manifest = writeManifest("9.9", url)
        val count = client.prewarmTestInstallCache("miss-test", manifest, cacheDir)

        assertEquals(0, count)
        // ensure no exception propagated, and no garbage target created
        val files = cacheDir.listFiles { f -> f.name.startsWith("miss-test#") } ?: emptyArray()
        assertEquals(0, files.size)
    }

    @Test
    fun `skips and does not overwrite when target cache file already exists`() {
        val cacheDir = tempDir.resolve("cache4").toFile().also { it.mkdirs() }
        val url = "https://example.com/keep.exe"
        val dlName = ScoopCacheManager.downloadCacheName(url)
        val source = File(cacheDir, dlName)
        source.writeText("SOURCE-V1")

        val manifest = writeManifest("3.0", url)
        val targetName = computeTargetName("keep-test", "3.0", url)
        val target = File(cacheDir, targetName)
        target.writeText("PRE-EXISTING-DATA")  // simulate already warmed or previous

        val count = client.prewarmTestInstallCache("keep-test", manifest, cacheDir)

        assertEquals(0, count, "should skip existing target")
        assertEquals("PRE-EXISTING-DATA", target.readText(), "must not overwrite")
    }

    @Test
    fun `handles architecture 32bit and 64bit urls (collects both)`() {
        val cacheDir = tempDir.resolve("cache5").toFile().also { it.mkdirs() }
        val url64 = "https://ex.com/app-64.exe"
        val url32 = "https://ex.com/app-32.exe"

        // create only one source, for 64
        val dl64 = ScoopCacheManager.downloadCacheName(url64)
        File(cacheDir, dl64).writeText("WIN64-EXE")

        val manifest = writeArchManifest("4.1", url64, url32)
        val count = client.prewarmTestInstallCache("arch-test", manifest, cacheDir)

        // should succeed at least for the 64 one (32 source missing -> skipped)
        assertEquals(1, count)

        val t64 = File(cacheDir, computeTargetName("arch-test", "4.1", url64))
        assertTrue(t64.exists())
        assertEquals("WIN64-EXE", t64.readText())

        // 32 should not create target
        val t32 = File(cacheDir, computeTargetName("arch-test", "4.1", url32))
        assertTrue(!t32.exists() || t32.length() == 0L)
    }

    @Test
    fun `handles multi-file url array in manifest`() {
        val cacheDir = tempDir.resolve("cache6").toFile().also { it.mkdirs() }
        val urlA = "https://ex.com/part1.bin"
        val urlB = "https://ex.com/part2.bin"

        val dlA = ScoopCacheManager.downloadCacheName(urlA)
        File(cacheDir, dlA).writeText("PART-ONE")

        // only A source present, B missing
        val manifest = writeMultiUrlManifest("1.5", listOf(urlA, urlB))
        val count = client.prewarmTestInstallCache("multi-test", manifest, cacheDir)

        assertEquals(1, count)
        assertTrue(File(cacheDir, computeTargetName("multi-test", "1.5", urlA)).exists())
    }

    @Test
    fun `returns 0 gracefully on malformed manifest (no crash)`() {
        val cacheDir = tempDir.resolve("cache7").toFile().also { it.mkdirs() }
        val bad = tempDir.resolve("bad.json").toFile()
        bad.writeText("{ not valid json at all ")

        val count = client.prewarmTestInstallCache("bad-test", bad, cacheDir)
        assertEquals(0, count)
    }
}
