package scooper.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/** Result of fetching model list from remote OpenAI-compatible endpoint. */
data class FetchModelsResult(
    val ok: Boolean,
    val models: List<String> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Service to query available models via `GET {baseUrl}/v1/models` from an OpenAI-compatible endpoint.
 */
open class AiModelsFetcher(
    private val connectTimeoutMs: Long = 5000,
    private val requestTimeoutMs: Long = 10000,
    private val httpClient: HttpClient? = null,
) {
    private val client: HttpClient by lazy {
        httpClient ?: HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build()
    }

    internal fun resolveBaseUrl(provider: String, baseUrl: String?): String? {
        if (baseUrl.isNullOrBlank()) return null
        return ScoopAiBridge.normalizeAiBaseUrl(baseUrl).ifEmpty { null }
    }

    open suspend fun fetchModels(
        provider: String,
        apiKey: String,
        baseUrl: String?,
    ): FetchModelsResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext FetchModelsResult(
                ok = false,
                errorMessage = "API key is required. Please fill it in Settings.",
            )
        }

        val base = resolveBaseUrl(provider, baseUrl)
        if (base == null) {
            return@withContext FetchModelsResult(
                ok = false,
                errorMessage = "Base URL is required for provider '$provider'.",
            )
        }

        try {
            // First attempt: GET {base}/v1/models
            var res = sendGet("$base/v1/models", apiKey)
            if (res.statusCode() == 404 || res.statusCode() == 405 || res.statusCode() == 501) {
                // Fallback attempt: GET {base}/models
                res = sendGet("$base/models", apiKey)
            }

            when (res.statusCode()) {
                200 -> {
                    val rawIds = parseModelIds(res.body())
                    val sortedModels = rawIds.filter { it.isNotBlank() }.distinct().sorted()
                    if (sortedModels.isEmpty()) {
                        FetchModelsResult(
                            ok = true,
                            models = emptyList(),
                            errorMessage = "No models found in the endpoint response.",
                        )
                    } else {
                        FetchModelsResult(ok = true, models = sortedModels)
                    }
                }
                401, 403 -> FetchModelsResult(
                    ok = false,
                    errorMessage = "Authentication failed (${res.statusCode()}). Check your API Key.",
                )
                429 -> FetchModelsResult(
                    ok = false,
                    errorMessage = "Rate limited (429). Too many requests.",
                )
                in 500..599 -> FetchModelsResult(
                    ok = false,
                    errorMessage = "Server error (${res.statusCode()}): ${res.body().take(120)}",
                )
                else -> FetchModelsResult(
                    ok = false,
                    errorMessage = "Failed to fetch models (HTTP ${res.statusCode()}): ${res.body().take(120)}",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            FetchModelsResult(ok = false, errorMessage = "Host not found: ${e.message}")
        } catch (e: HttpTimeoutException) {
            FetchModelsResult(ok = false, errorMessage = "Request timed out after ${requestTimeoutMs}ms")
        } catch (e: ConnectException) {
            FetchModelsResult(ok = false, errorMessage = "Connection refused: ${e.message}")
        } catch (e: Throwable) {
            FetchModelsResult(ok = false, errorMessage = e.message ?: "Failed to connect to endpoint")
        }
    }

    private fun sendGet(url: String, apiKey: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .GET()
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    internal fun parseModelIds(json: String): List<String> {
        return runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            root["data"]?.jsonArray?.mapNotNull { el ->
                (el as? JsonObject)?.get("id")?.jsonPrimitive?.content
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
