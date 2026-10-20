package scooper.service

import com.scoopai.tools.cache.ScoopCacheManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.data.Bucket
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
    fun `ignores aria2 partial control files when prewarming from finalized entries`() {
        val cacheDir = tempDir.resolve("cache8").toFile().also { it.mkdirs() }
        val url = "https://example.com/tool2.zip#/inner.7z"
        val hash = ScoopCacheManager.urlHash(url)
        val mExt = ScoopCacheManager.urlExtension(url)
        // both a valid finalized entry and an interrupted aria2 control file with the same hash
        File(cacheDir, "realtool#2.5#$hash$mExt.aria2").writeText("ARIA2-PARTIAL-DATA")
        File(cacheDir, "realtool#2.5#$hash$mExt").writeText("GOOD-DATA")

        val manifest = writeManifest("2.5", url)
        val count = client.prewarmTestInstallCache("tool2-test", manifest, cacheDir)

        assertEquals(1, count)
        val target = File(cacheDir, computeTargetName("tool2-test", "2.5", url))
        assertTrue(target.exists(), "target must be created from the valid entry, not the aria2 control file")
        assertEquals("GOOD-DATA", target.readText(), "must link the real cache file, not the .aria2 partial")
    }

    @Test
    fun `returns 0 gracefully on malformed manifest (no crash)`() {
        val cacheDir = tempDir.resolve("cache7").toFile().also { it.mkdirs() }
        val bad = tempDir.resolve("bad.json").toFile()
        bad.writeText("{ not valid json at all ")

        val count = client.prewarmTestInstallCache("bad-test", bad, cacheDir)
        assertEquals(0, count)
    }

    // ---------------------------------------------------------------
    // preDownloadIfNeeded prewarm integration (formal install path)
    // ---------------------------------------------------------------

    /** Downloader stub that counts network downloads and writes a marker file. */
    private class CountingDownloader : ManifestDownloader() {
        var downloadCalls = 0
        val downloadedUrls = mutableListOf<String>()

        override fun download(
            url: String,
            destFile: File,
            hash: String?,
            onProgress: (Int) -> Unit,
            shouldCancel: () -> Boolean,
        ): File? {
            downloadCalls++
            downloadedUrls.add(url)
            destFile.parentFile?.mkdirs()
            destFile.writeText("NETWORK-DOWNLOADED:$url")
            onProgress(100)
            return destFile
        }
    }

    /** Client with an isolated root dir and a counting downloader; aria2 toggleable. */
    private fun newClient(downloader: CountingDownloader, root: File, aria2Enabled: Boolean = false): ScoopClient {
        val configFile = root.resolve("scoop-config.json")
        configFile.writeText("""{"aria2-enabled": $aria2Enabled}""")
        return ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            downloader = downloader,
            rootDirOverride = root,
            scoopConfigFile = configFile,
        )
    }

    /** Write a manifest into the isolated bucket dir so findManifest(app) locates it. */
    private fun writeBucketManifest(root: File, appName: String, content: String): File {
        val bucketDir = root.resolve("buckets/testbucket/bucket").also { it.mkdirs() }
        return bucketDir.resolve("$appName.json").also { it.writeText(content) }
    }

    /** 64bit-only manifest whose url/hash are arrays (multi-item download info). */
    private fun archMultiItemManifest(version: String, urls: List<String>, hashes: List<String>): String {
        val urlJson = urls.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val hashJson = hashes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return """
            {
                "version": "$version",
                "architecture": {
                    "64bit": { "url": $urlJson, "hash": $hashJson }
                }
            }
        """.trimIndent()
    }

    @Test
    fun `preDownload links engine download-phase file and makes zero network downloads`() {
        val downloader = CountingDownloader()
        val root = tempDir.resolve("root-a").toFile().also { it.mkdirs() }
        val client = newClient(downloader, root)
        val cacheDir = client.cacheDir.also { it.mkdirs() }
        val url = "https://example.com/myapp-1.0.zip#/inner.7z"

        // Engine download-phase file, as produced by the scoop-ai generation flow
        File(cacheDir, ScoopCacheManager.downloadCacheName(url)).writeText("ENGINE-DATA")

        writeBucketManifest(root, "myapp", writeManifest("1.0", url).readText())
        val app = App(name = "myapp", latestVersion = "1.0", bucket = Bucket("testbucket"))

        runBlocking { client.preDownloadIfNeeded(app) }

        assertEquals(0, downloader.downloadCalls, "prewarm hit must avoid network download")
        val targetName = computeTargetName("myapp", "1.0", url)
        val target = File(cacheDir, targetName)
        assertTrue(target.exists(), "formal cache entry must exist after prewarm")
        assertEquals("ENGINE-DATA", target.readText(), "content must come from the engine file")
    }

    @Test
    fun `preDownload falls back to network download when no cache source exists`() {
        val downloader = CountingDownloader()
        val root = tempDir.resolve("root-b").toFile().also { it.mkdirs() }
        val client = newClient(downloader, root)
        val cacheDir = client.cacheDir.also { it.mkdirs() }
        val url = "https://example.com/only-network.exe"
        // no source file in cache at all

        writeBucketManifest(root, "onlyapp", writeManifest("2.0", url).readText())
        val app = App(name = "onlyapp", latestVersion = "2.0", bucket = Bucket("testbucket"))

        runBlocking { client.preDownloadIfNeeded(app) }

        assertEquals(1, downloader.downloadCalls, "must fall back to exactly one network download")
        assertEquals(listOf(url), downloader.downloadedUrls)
        val target = File(cacheDir, computeTargetName("onlyapp", "2.0", url))
        assertTrue(target.exists())
        assertTrue(target.readText().startsWith("NETWORK-DOWNLOADED:"))
    }

    @Test
    fun `preDownload reuses another app finalized entry for same url via suffix scan`() {
        val downloader = CountingDownloader()
        val root = tempDir.resolve("root-c").toFile().also { it.mkdirs() }
        val client = newClient(downloader, root)
        val cacheDir = client.cacheDir.also { it.mkdirs() }
        val url = "https://example.com/shared-3.0.msi"
        val hash = ScoopCacheManager.urlHash(url)
        val ext = ScoopCacheManager.urlExtension(url)

        // Finalized entry under a different app name, same URL hash suffix
        File(cacheDir, "otherapp#3.0#$hash$ext").writeText("FINALIZED-OTHER-APP")

        writeBucketManifest(root, "thisapp", writeManifest("3.0", url).readText())
        val app = App(name = "thisapp", latestVersion = "3.0", bucket = Bucket("testbucket"))

        runBlocking { client.preDownloadIfNeeded(app) }

        assertEquals(0, downloader.downloadCalls)
        val target = File(cacheDir, computeTargetName("thisapp", "3.0", url))
        assertTrue(target.exists())
        assertEquals("FINALIZED-OTHER-APP", target.readText())
    }

    @Test
    fun `preDownload skips existing formal entry without re-linking (idempotent)`() {
        val downloader = CountingDownloader()
        val root = tempDir.resolve("root-d").toFile().also { it.mkdirs() }
        val client = newClient(downloader, root)
        val cacheDir = client.cacheDir.also { it.mkdirs() }
        val url = "https://example.com/idem-4.0.exe"

        // Engine source present too: a re-link would clobber the pre-existing target
        File(cacheDir, ScoopCacheManager.downloadCacheName(url)).writeText("ENGINE-NEWER")
        val target = File(cacheDir, computeTargetName("idem", "4.0", url))
        target.writeText("PRE-EXISTING-FORMAL")

        writeBucketManifest(root, "idem", writeManifest("4.0", url).readText())
        val app = App(name = "idem", latestVersion = "4.0", bucket = Bucket("testbucket"))

        runBlocking { client.preDownloadIfNeeded(app) }

        assertEquals(0, downloader.downloadCalls)
        assertEquals("PRE-EXISTING-FORMAL", target.readText(), "existing formal entry must not be overwritten")
    }

    @Test
    fun `preDownload prewarms every item of a multi-url arch manifest`() {
        val downloader = CountingDownloader()
        val root = tempDir.resolve("root-e").toFile().also { it.mkdirs() }
        val client = newClient(downloader, root)
        val cacheDir = client.cacheDir.also { it.mkdirs() }
        val urlA = "https://ex.com/part-a.bin"
        val urlB = "https://ex.com/part-b.bin"

        File(cacheDir, ScoopCacheManager.downloadCacheName(urlA)).writeText("PART-A")
        File(cacheDir, ScoopCacheManager.downloadCacheName(urlB)).writeText("PART-B")

        writeBucketManifest(root, "multiitem", archMultiItemManifest("5.0", listOf(urlA, urlB), listOf("ha", "hb")))
        val app = App(name = "multiitem", latestVersion = "5.0", bucket = Bucket("testbucket"))

        runBlocking { client.preDownloadIfNeeded(app) }

        assertEquals(0, downloader.downloadCalls, "all items prewarmed, no network needed")
        assertTrue(File(cacheDir, computeTargetName("multiitem", "5.0", urlA)).exists())
        assertTrue(File(cacheDir, computeTargetName("multiitem", "5.0", urlB)).exists())
    }

    @Test
    fun `preDownload never uses aria2 control file as prewarm source (regression)`() {
        val downloader = CountingDownloader()
        val root = tempDir.resolve("root-f").toFile().also { it.mkdirs() }
        val client = newClient(downloader, root)
        val cacheDir = client.cacheDir.also { it.mkdirs() }
        val url = "https://example.com/tool-6.0.zip#/inner.7z"
        val hash = ScoopCacheManager.urlHash(url)
        val ext = ScoopCacheManager.urlExtension(url)

        // Only an interrupted aria2 control file matches the url suffix
        File(cacheDir, "otherapp#6.0#$hash$ext.aria2").writeText("ARIA2-CONTROL")

        writeBucketManifest(root, "aria2app", writeManifest("6.0", url).readText())
        val app = App(name = "aria2app", latestVersion = "6.0", bucket = Bucket("testbucket"))

        runBlocking { client.preDownloadIfNeeded(app) }

        assertEquals(1, downloader.downloadCalls, "aria2 leftover must not satisfy prewarm")
        assertFalse(
            File(cacheDir, computeTargetName("aria2app", "6.0", url)).run {
                exists() && readText() == "ARIA2-CONTROL"
            },
            "formal entry must contain network data, not the aria2 control file",
        )
    }

    @Test
    fun `preDownload prewarms even when aria2 is enabled (cache lookup is downloader-agnostic)`() {
        val downloader = CountingDownloader()
        val root = tempDir.resolve("root-g").toFile().also { it.mkdirs() }
        val client = newClient(downloader, root, aria2Enabled = true)
        val cacheDir = client.cacheDir.also { it.mkdirs() }
        val url = "https://example.com/warm-7.0.zip#/inner.7z"

        // Engine download-phase file exists: with aria2 on, prewarm must still
        // link it into the formal cache entry instead of bailing out early.
        File(cacheDir, ScoopCacheManager.downloadCacheName(url)).writeText("ENGINE-ARIA2-DATA")

        writeBucketManifest(root, "warmapp", writeManifest("7.0", url).readText())
        val app = App(name = "warmapp", latestVersion = "7.0", bucket = Bucket("testbucket"))

        runBlocking { client.preDownloadIfNeeded(app) }

        assertEquals(0, downloader.downloadCalls, "prewarm hit must avoid network download even with aria2 enabled")
        val target = File(cacheDir, computeTargetName("warmapp", "7.0", url))
        assertTrue(target.exists(), "formal cache entry must be created despite aria2 being enabled")
        assertEquals("ENGINE-ARIA2-DATA", target.readText(), "hardlink/copy from engine source must happen")
    }
}
