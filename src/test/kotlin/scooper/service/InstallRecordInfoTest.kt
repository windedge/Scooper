package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.InstallOrigin
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * Scoop records the install source in apps/<name>/current, preferring
 * scoop-install.json and falling back to the legacy install.json
 * (lib/manifest.ps1). A recorded url wins over a recorded bucket, matching
 * scoop-update's target selection.
 */
class InstallRecordInfoTest {

    private val client = ScoopClient(ScoopLogStream(), TaskQueue())

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    /** Write [json] as <root>/apps/<name>/current/<fileName> and return the current dir. */
    private fun writeRecord(
        name: String,
        fileName: String,
        json: String,
        root: File = tempDir,
    ): File {
        val current = root.resolve("apps/$name/current")
        current.mkdirs()
        current.resolve(fileName).writeText(json)
        return current
    }

    private fun bucketRecord(bucket: String = "ladle"): String =
        """{"bucket":"$bucket","architecture":"64bit"}"""

    private fun urlRecord(url: String = "C:\\Users\\user\\scooper\\direct-install\\app.json"): String {
        val escaped = url.replace("\\", "\\\\")
        return """{"url":"$escaped","architecture":"64bit"}"""
    }

    // ---------------------------------------------------------------
    // readInstallRecord: format and origin resolution
    // ---------------------------------------------------------------

    @Test
    fun `new format bucket record yields Bucket and the bucket name`() {
        val dir = writeRecord("lightning", "scoop-install.json", bucketRecord("ladle"))
        val info = client.readInstallRecord(dir.toPath())
        assertEquals(InstallOrigin.Bucket, info.origin)
        assertEquals("ladle", info.bucket)
    }

    @Test
    fun `legacy install json record still yields Bucket`() {
        val dir = writeRecord("lightning", "install.json", bucketRecord("extras"))
        val info = client.readInstallRecord(dir.toPath())
        assertEquals(InstallOrigin.Bucket, info.origin)
        assertEquals("extras", info.bucket)
    }

    @Test
    fun `url record yields DirectFile`() {
        val dir = writeRecord("lightning", "scoop-install.json", urlRecord())
        val info = client.readInstallRecord(dir.toPath())
        assertEquals(InstallOrigin.DirectFile, info.origin)
        assertNull(info.bucket)
    }

    @Test
    fun `url wins over bucket when both are recorded`() {
        // scoop-update prefers the recorded url when picking a reinstall target.
        val dir = writeRecord(
            "lightning",
            "scoop-install.json",
            """{"bucket":"ladle","url":"C:\\\\temp\\\\lightning.json"}""",
        )
        val info = client.readInstallRecord(dir.toPath())
        assertEquals(InstallOrigin.DirectFile, info.origin)
    }

    @Test
    fun `scoop-install json wins when both record files exist`() {
        writeRecord("lightning", "install.json", bucketRecord("ladle"))
        val dir = writeRecord("lightning", "scoop-install.json", urlRecord())
        val info = client.readInstallRecord(dir.toPath())
        assertEquals(InstallOrigin.DirectFile, info.origin)
    }

    @Test
    fun `missing record files yield Unknown`() {
        val dir = tempDir.resolve("apps/lightning/current").apply { mkdirs() }
        val info = client.readInstallRecord(dir.toPath())
        assertEquals(InstallOrigin.Unknown, info.origin)
        assertNull(info.bucket)
    }

    @Test
    fun `missing app dir yields Unknown`() {
        val info = client.readInstallRecord(tempDir.resolve("apps/absent/current").toPath())
        assertEquals(InstallOrigin.Unknown, info.origin)
        assertNull(info.bucket)
    }

    @Test
    fun `corrupt json yields Unknown`() {
        val dir = writeRecord("lightning", "scoop-install.json", """{"bucket": "ladle",""")
        val info = client.readInstallRecord(dir.toPath())
        assertEquals(InstallOrigin.Unknown, info.origin)
        assertNull(info.bucket)
    }

    @Test
    fun `record without url or bucket yields Unknown`() {
        val dir = writeRecord("lightning", "scoop-install.json", """{"architecture":"64bit"}""")
        val info = client.readInstallRecord(dir.toPath())
        assertEquals(InstallOrigin.Unknown, info.origin)
    }

    @Test
    fun `bom prefixed record is parsed`() {
        val dir = writeRecord("lightning", "install.json", "\uFEFF" + bucketRecord("main"))
        assertEquals(InstallOrigin.Bucket, client.readInstallRecord(dir.toPath()).origin)
    }

    // ---------------------------------------------------------------
    // isLocalManifestInstall: origin based, per scope
    // ---------------------------------------------------------------

    @Test
    fun `legacy install json with a url is a local manifest install`() {
        val localRoot = tempDir.resolve("local")
        writeRecord("lightning", "install.json", urlRecord(), root = localRoot)
        assertTrue(
            client.isLocalManifestInstall(
                name = "lightning",
                global = false,
                rootDir = localRoot.toPath(),
                globalRootDir = tempDir.resolve("global").toPath(),
            ),
        )
    }

    @Test
    fun `bucket record is not a local manifest install`() {
        val localRoot = tempDir.resolve("local")
        writeRecord("lightning", "scoop-install.json", bucketRecord(), root = localRoot)
        assertFalse(
            client.isLocalManifestInstall(
                name = "lightning",
                global = false,
                rootDir = localRoot.toPath(),
                globalRootDir = tempDir.resolve("global").toPath(),
            ),
        )
    }

    @Test
    fun `missing record is not a local manifest install`() {
        val localRoot = tempDir.resolve("local").apply { resolve("apps/lightning/current").mkdirs() }
        assertFalse(
            client.isLocalManifestInstall(
                name = "lightning",
                global = false,
                rootDir = localRoot.toPath(),
                globalRootDir = tempDir.resolve("global").toPath(),
            ),
        )
    }

    @Test
    fun `global scope record resolves under the global root`() {
        val globalRoot = tempDir.resolve("global")
        writeRecord("lightning", "scoop-install.json", urlRecord(), root = globalRoot)
        assertTrue(
            client.isLocalManifestInstall(
                name = "lightning",
                global = true,
                rootDir = tempDir.resolve("local").toPath(),
                globalRootDir = globalRoot.toPath(),
            ),
        )
    }
}
