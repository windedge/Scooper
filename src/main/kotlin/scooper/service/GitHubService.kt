package scooper.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

/**
 * GitHub related operations:
 * - extract owner/repo from URLs
 * - fetch release notes
 * - fetch raw file content (e.g. manifest)
 */
class GitHubService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    companion object {
        private val GITHUB_URL_REGEX =
            """https?://github\.com/([^/]+)/([^/]+)/?.*""".toRegex()
        private val GITHUB_BLOB_REGEX =
            """https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)""".toRegex()

        /** Extract owner/repo from a GitHub URL, or null if not a GitHub URL. */
        fun extractRepo(url: String?): Pair<String, String>? {
            if (url.isNullOrBlank()) return null
            val match = GITHUB_URL_REGEX.find(url) ?: return null
            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")
            return owner to repo
        }

        fun isGitHubUrl(url: String?): Boolean = extractRepo(url) != null
    }

    /**
     * Fetch the latest release for the given GitHub repo.
     * @param appUrl the manifest download URL (used to extract owner/repo)
     * @return the latest release, or null if not a GitHub URL or on failure
     */
    suspend fun fetchLatestRelease(appUrl: String?): GitHubRelease? {
        val (owner, repo) = extractRepo(appUrl) ?: return null
        return fetchLatestRelease(owner, repo)
    }

    /**
     * Fetch recent releases for the given GitHub repo.
     * @param appUrl the manifest download URL (used to extract owner/repo)
     * @param limit max number of releases to return
     * @return list of releases, or empty if not a GitHub URL or on failure
     */
    suspend fun fetchReleases(appUrl: String?, limit: Int = 5): List<GitHubRelease> {
        val (owner, repo) = extractRepo(appUrl) ?: return emptyList()
        return fetchReleases(owner, repo, limit)
    }

    suspend fun fetchLatestRelease(owner: String, repo: String): GitHubRelease? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/$owner/$repo/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                json.decodeFromString<GitHubRelease>(response.body())
            } else {
                rateLimitMessage(response)?.let { throw IllegalStateException(it) }
                logger.debug("GitHub API returned ${response.statusCode()} for $owner/$repo releases/latest")
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch latest release for $owner/$repo: ${e.message}")
            if (e is IllegalStateException) throw e
            null
        }
    }

    suspend fun fetchReleases(owner: String, repo: String, limit: Int = 5): List<GitHubRelease> {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/$owner/$repo/releases?per_page=$limit"))
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                json.decodeFromString<List<GitHubRelease>>(response.body())
            } else {
                rateLimitMessage(response)?.let { throw IllegalStateException(it) }
                logger.debug("GitHub API returned ${response.statusCode()} for $owner/$repo releases")
                emptyList()
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch releases for $owner/$repo: ${e.message}")
            if (e is IllegalStateException) throw e
            emptyList()
        }
    }

    private fun rateLimitMessage(response: HttpResponse<String>): String? {
        val status = response.statusCode()
        val body = response.body()
        val headers = response.headers()
        val isRateLimited = status == 429 ||
                (status == 403 && (
                        body.contains("API rate limit exceeded", ignoreCase = true) ||
                                body.contains("secondary rate limit", ignoreCase = true) ||
                                headers.firstValue("X-RateLimit-Remaining").orElse(null) == "0"
                        ))

        if (!isRateLimited) return null

        val retryAfterSeconds = headers.firstValue("Retry-After").orElse(null)?.toLongOrNull()
        val resetAtEpochSeconds = headers.firstValue("X-RateLimit-Reset").orElse(null)?.toLongOrNull()

        val waitSeconds = when {
            retryAfterSeconds != null && retryAfterSeconds > 0 -> retryAfterSeconds
            resetAtEpochSeconds != null -> (resetAtEpochSeconds - Instant.now().epochSecond).coerceAtLeast(0)
            else -> null
        }

        return if (waitSeconds != null) {
            "GitHub API rate limit exceeded. Please try again in ${formatWaitDuration(waitSeconds)}."
        } else {
            "GitHub API rate limit exceeded. Please try again later."
        }
    }

    private fun formatWaitDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0) append("${minutes}m ")
            append("${seconds}s")
        }.trim()
    }

    /** Build the GitHub releases page URL from a manifest/download URL. */
    fun buildReleasesPageUrl(appUrl: String?): String? {
        val (owner, repo) = extractRepo(appUrl) ?: return null
        return "https://github.com/$owner/$repo/releases"
    }

    /**
     * Fetch raw file content from a GitHub URL.
     * Supports:
     * - https://github.com/{owner}/{repo}/blob/{branch}/{path}
     * - https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}
     */
    suspend fun fetchRawFile(gitHubUrl: String?): String? {
        if (gitHubUrl.isNullOrBlank()) return null
        val rawUrl = toRawGitHubUrl(gitHubUrl) ?: return null

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(rawUrl))
                .header("Accept", "text/plain")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                response.body()
            } else {
                rateLimitMessage(response)?.let { throw IllegalStateException(it) }
                logger.debug("GitHub raw fetch returned ${response.statusCode()} for $rawUrl")
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch raw file for $gitHubUrl: ${e.message}")
            if (e is IllegalStateException) throw e
            null
        }
    }

    fun toRawGitHubUrl(url: String): String? {
        if (url.startsWith("https://raw.githubusercontent.com/")) return url

        val blobMatch = GITHUB_BLOB_REGEX.find(url)
        if (blobMatch != null) {
            val owner = blobMatch.groupValues[1]
            val repo = blobMatch.groupValues[2]
            val branch = blobMatch.groupValues[3]
            val filePath = blobMatch.groupValues[4]
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$filePath"
        }

        // If it's just repository URL, no file path can be inferred.
        return null
    }
}

@Serializable
data class GitHubRelease(
    val tag_name: String = "",
    val name: String? = null,
    val body: String? = null,
    val html_url: String = "",
    val published_at: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
)
