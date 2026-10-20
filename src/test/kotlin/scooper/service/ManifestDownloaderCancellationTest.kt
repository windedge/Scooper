package scooper.service

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.util.OperationCancelledException
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pre-download runs in-process over java.net.http.HttpClient, so it cannot be stopped by
 * killing subprocesses: it aborts cooperatively when shouldCancel reports the TaskQueue flag.
 */
class ManifestDownloaderCancellationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `download aborts before any request when already cancelled`() {
        val downloader = ManifestDownloader()
        val dest = tempDir.resolve("never.bin").toFile()

        assertThrows(OperationCancelledException::class.java) {
            downloader.download(
                url = "http://127.0.0.1:1/never",
                destFile = dest,
                hash = null,
                onProgress = {},
                shouldCancel = { true },
            )
        }
        assertFalse(dest.exists())
    }

    @Test
    fun `download aborts mid-stream and removes the partial file`() {
        val body = ByteArray(4 * 1024 * 1024) { 1 }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/big") { exchange ->
            exchange.responseHeaders.add("Content-Length", body.size.toString())
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { out ->
                var offset = 0
                while (offset < body.size) {
                    val n = minOf(64 * 1024, body.size - offset)
                    out.write(body, offset, n)
                    out.flush()
                    offset += n
                }
            }
        }
        server.start()
        try {
            val downloader = ManifestDownloader()
            val dest = tempDir.resolve("big.bin").toFile()
            val progressCalls = AtomicInteger(0)
            val cancel = AtomicBoolean(false)

            assertThrows(OperationCancelledException::class.java) {
                downloader.download(
                    url = "http://127.0.0.1:${server.address.port}/big",
                    destFile = dest,
                    hash = null,
                    onProgress = { if (progressCalls.incrementAndGet() >= 2) cancel.set(true) },
                    shouldCancel = { cancel.get() },
                )
            }
            assertFalse(dest.exists())
            assertFalse(File(dest.parentFile, dest.name + ".download").exists())
        } finally {
            server.stop(0)
        }
    }
}
