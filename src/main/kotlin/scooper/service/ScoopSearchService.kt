package scooper.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import scooper.util.Translatable
import scooper.util.tr
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse


@Serializable
data class ScoopSearchResult(
    val count: Int = 0,
    val value: List<ScoopSearchApp> = emptyList(),
)

@Serializable
data class ScoopSearchApp(
    val Name: String = "",
    val Description: String = "",
    val Homepage: String = "",
    val License: String = "",
    val Version: String = "",
    val Metadata: ScoopSearchMetadata = ScoopSearchMetadata(),
)

@Serializable
data class ScoopSearchMetadata(
    val Repository: String = "",
    val RepositoryStars: Int = 0,
    val Committed: String = "",
    val FilePath: String = "",
) {
    val manifestUrl: String get() {
        if (FilePath.isEmpty() || Repository.isEmpty()) return ""
        // FilePath may be a full GitHub URL or a relative path
        return if (FilePath.startsWith("http")) FilePath
        else "$Repository/blob/master/$FilePath"
    }
}

enum class ScoopSearchSort(val orderBy: String) : Translatable {
    BestMatch("relevance"),
    Name("name"),
    Newest("newest");

    override fun displayName(): String = when (this) {
        BestMatch -> tr("Best match")
        Name -> tr("Name")
        Newest -> tr("Newest")
    }
}

class ScoopSearchService : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    suspend fun search(
        query: String,
        page: Int = 0,
        pageSize: Int = 20,
        sort: ScoopSearchSort = ScoopSearchSort.BestMatch,
        officialOnly: Boolean = false,
        distinctOnly: Boolean = true,
    ): ScoopSearchResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext ScoopSearchResult()

        val body = buildJsonObject {
            put("query", query)
            put("page_offset", page * pageSize)
            put("page_size", pageSize)
            put("sort_by", sort.orderBy)
            put("official_only", officialOnly)
            put("distinct_only", distinctOnly)
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$SEARCH_API_URL/api/search"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8))

        if (response.statusCode() == 200) {
            parseResult(response.body())
        } else {
            logger.error("Search API error: ${response.statusCode()} ${response.body()}")
            throw SearchException("Server returned HTTP ${response.statusCode()}")
        }
    }

    private fun parseResult(responseBody: String): ScoopSearchResult {
        return try {
            json.decodeFromString<ScoopSearchResult>(responseBody)
        } catch (e: Exception) {
            logger.error("Failed to parse search response", e)
            throw SearchException(tr("Invalid server response"))
        }
    }

    override fun close() {
        // HttpClient resources are released when the JVM shuts down
    }

    companion object {
        private const val SEARCH_API_URL = "https://search.scooper.workers.dev"
    }
}

class SearchException(message: String, cause: Throwable? = null) : Exception(message, cause)
