package scooper.service

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import scooper.util.getString
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads files using JVM HttpClient with progress callbacks and hash verification.
 * Used as a replacement for scoop's built-in downloader when aria2 is not enabled.
 */
class ManifestDownloader {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Parses download URL(s) and hash(es) from the manifest JSON for the current architecture.
     * Supports both single file and multi-file manifests (url/hash as arrays).
     */
    fun parseDownloadInfo(manifestJson: JsonObject): DownloadInfo? {
        val arch = detectArch()

        val archObj = manifestJson["architecture"]?.jsonObject
        if (archObj != null) {
            val archBlock = archObj[arch]?.jsonObject ?: archObj["64bit"]?.jsonObject
            if (archBlock != null) {
                parseDownloadItems(archBlock)?.let { return DownloadInfo(it) }
            }
        }

        parseDownloadItems(manifestJson)?.let { return DownloadInfo(it) }
        return null
    }

    private fun parseDownloadItems(json: JsonObject): List<DownloadItem>? {
        val urls = parseStringOrArray(json["url"])
        if (urls.isEmpty()) return null

        val hashes = parseStringOrArray(json["hash"])
        return urls.mapIndexed { index, url ->
            DownloadItem(
                url = url,
                hash = hashes.getOrNull(index)?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun parseStringOrArray(element: JsonElement?): List<String> {
        return when (element) {
            null -> emptyList()
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            else -> listOfNotNull(element.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank))
        }
    }

    /**
     * Downloads a file to the given destination with progress callbacks.
     * Uses a HEAD request first to get Content-Length, then streams the download with real-time progress.
     * @return the downloaded file, or null on failure
     */
    fun download(
        url: String,
        destFile: File,
        hash: String? = null,
        onProgress: (percent: Int) -> Unit = {},
    ): File? {
        return try {
            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parent, destFile.name + ".download")

            // HEAD request to get Content-Length
            val contentLengthFromHead = try {
                val headRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build()
                val headResponse = client.send(headRequest, HttpResponse.BodyHandlers.discarding())
                headResponse.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
            } catch (_: Exception) {
                null
            }

            // Stream download
            val getRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build()
            val response = client.send(getRequest, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299) {
                logger.error("Download failed: HTTP ${response.statusCode()} for $url")
                return null
            }

            val digest = hash?.let { MessageDigest.getInstance("SHA-256") }
            val downloaded = AtomicLong(0)
            val buffer = ByteArray(8192)
            val contentLength = contentLengthFromHead
                ?: response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()

            onProgress(0)

            tempFile.outputStream().use { out ->
                response.body().use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        digest?.update(buffer, 0, bytesRead)
                        val total = downloaded.addAndGet(bytesRead.toLong())
                        if (contentLength != null && contentLength > 0) {
                            val percent = (total * 100 / contentLength).toInt().coerceAtMost(99)
                            onProgress(percent)
                        } else if (total > 0) {
                            // Keep a deterministic progress state even without Content-Length
                            onProgress(1)
                        }
                    }
                }
            }

            // Hash verification
            if (hash != null && digest != null) {
                val computed = digest.digest().joinToString("") { "%02x".format(it) }
                if (!computed.equals(hash, ignoreCase = true)) {
                    logger.error("Hash mismatch: expected=$hash actual=$computed for $url")
                    tempFile.delete()
                    return null
                }
            }

            tempFile.renameTo(destFile)
            onProgress(100)
            destFile
        } catch (e: Exception) {
            logger.error("Download error: ${e.message}", e)
            null
        }
    }

    private fun detectArch(): String {
        val arch = System.getProperty("os.arch", "").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("64") -> "64bit"
            else -> "32bit"
        }
    }
}

data class DownloadInfo(
    val items: List<DownloadItem>,
)

data class DownloadItem(
    val url: String,
    val hash: String?,
)
