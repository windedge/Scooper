package scooper.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Locale

class MiscKtTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `dirSize sums file sizes in directory`() {
        val dir = tempDir.resolve("app").toFile().apply { mkdirs() }
        File(dir, "a.txt").writeBytes(ByteArray(100))
        File(dir, "b.txt").writeBytes(ByteArray(200))

        assertEquals(300L, dir.dirSize())
    }

    @Test
    fun `dirSize of empty directory is zero`() {
        val dir = tempDir.resolve("empty").toFile().apply { mkdirs() }
        assertEquals(0L, dir.dirSize())
    }

    @Test
    fun `dirSize of missing directory is zero`() {
        val dir = File(tempDir.toFile(), "missing")
        assertEquals(0L, dir.dirSize())
    }

    @Test
    fun `dirSize does not follow directory symlinks`() {
        // Symlink creation may require privileges on some CI environments; skip if so
        val realDir = tempDir.resolve("real").toFile().apply { mkdirs() }
        File(realDir, "big.bin").writeBytes(ByteArray(10_000))
        val link = tempDir.resolve("link")
        try {
            java.nio.file.Files.createSymbolicLink(link, realDir.toPath())
        } catch (_: java.nio.file.FileSystemException) {
            return // no privilege to create symlinks - nothing to assert
        }

        val rootDir = tempDir.toFile()
        // Without following links, only realDir contents are counted
        assertTrue(rootDir.dirSize() >= 10_000L)
        assertTrue(rootDir.dirSize() < 20_000L)
    }

    @Test
    fun `readableSize formats common sizes`() {
        // Locale pinned so the expectations hold regardless of the machine's default locale.
        assertEquals("0 bytes", 0.0.readableSize(Locale.US))
        assertEquals("2 kB", 2048.0.readableSize(Locale.US))
        assertEquals("0 bytes", 0L.readableSize(Locale.US))
        assertEquals("2 kB", 2048L.readableSize(Locale.US))
        assertEquals("2.0 MB", (2L * 1024 * 1024).readableSize(Locale.US))
    }
}
