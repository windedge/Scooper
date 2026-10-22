package scooper.service

import com.scoopai.tools.llm.ProviderEndpoints
import com.scoopai.tools.llm.ProviderEndpoints.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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

/** Result of fetching model list from a remote provider endpoint. */
data class FetchModelsResult(
    val ok: Boolean,
    val models: List<String> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Service to query the model list of an LLM provider.
 *
 * The protocol is taken from the engine table [ProviderEndpoints]: every
 * provider is probed with its own path and authentication headers, so the
 * host probe and the engine client always talk to the same endpoint.
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

    /**
     * Base URL used for the probe: the user supplied one when present,
     * otherwise the official default endpoint of the provider.
     */
    internal fun resolveBaseUrl(provider: String, baseUrl: String?): String? {
        val endpoint = ProviderEndpoints.forProvider(provider)
        val raw = baseUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: endpoint?.defaultBaseUrl
            ?: return null
        return ScoopAiBridge.normalizeAiBaseUrl(raw, provider).ifEmpty { null }
    }

    open suspend fun fetchModels(
        provider: String,
        apiKey: String,
        baseUrl: String?,
    ): FetchModelsResult = withContext(Dispatchers.IO) {
        val endpoint = ProviderEndpoints.forProvider(provider)
        if (endpoint == null) {
            return@withContext FetchModelsResult(
                ok = false,
                errorMessage = ProviderEndpoints.unsupportedProviderMessage(provider),
            )
        }

        if (endpoint.requiresApiKey && apiKey.isBlank()) {
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
            val res = when (provider) {
                "anthropic" -> fetchAnthropicModels(base, apiKey, endpoint)
                "ollama" -> fetchOllamaModels(base, endpoint)
                else -> fetchOpenAiModels(base, apiKey, endpoint)
            }
            interpret(res) { body ->
                if (provider == "ollama") parseOllamaModelIds(body) else parseModelIds(body)
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

    /**
     * OpenAI family (deepseek / openai / openrouter / openai-compatible):
     * the provider path of the protocol table, with the unversioned path as
     * fallback for gateways that only serve one of the two shapes.
     */
    private fun fetchOpenAiModels(base: String, apiKey: String, endpoint: Endpoint): HttpResponse<String> {
        val res = sendGet(modelsUrl(base, endpoint.modelsPath), apiKey, endpoint)
        val fallback = fallbackModelsPath(endpoint.modelsPath)
        if (fallback != null && res.statusCode() in FALLBACK_STATUS_CODES) {
            return sendGet(modelsUrl(base, fallback), apiKey, endpoint)
        }
        return res
    }

    /** Anthropic: `GET {base}/v1/models` with `x-api-key` and `anthropic-version`. */
    private fun fetchAnthropicModels(base: String, apiKey: String, endpoint: Endpoint): HttpResponse<String> =
        sendGet(modelsUrl(base, endpoint.modelsPath), apiKey, endpoint)

    /** Ollama: `GET {base}/api/tags` without authentication. */
    private fun fetchOllamaModels(base: String, endpoint: Endpoint): HttpResponse<String> =
        sendGet(modelsUrl(base, endpoint.modelsPath), "", endpoint)

    private fun sendGet(url: String, apiKey: String, endpoint: Endpoint): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .GET()
        val authHeader = endpoint.authHeader
        if (authHeader != null && apiKey.isNotBlank()) {
            val value = if (endpoint.bearerAuth) "Bearer $apiKey" else apiKey
            builder.header(authHeader, value)
        }
        val versionHeader = endpoint.apiVersionHeader
        val version = endpoint.apiVersion
        if (versionHeader != null && version != null) {
            builder.header(versionHeader, version)
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun interpret(res: HttpResponse<String>, parse: (String) -> List<String>): FetchModelsResult {
        return when (res.statusCode()) {
            200 -> {
                val sortedModels = parse(res.body()).filter { it.isNotBlank() }.distinct().sorted()
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
    }

    /**
     * Join base URL and provider path without duplicating a versioned prefix
     * the base URL already carries (OpenRouter bases end with `api/v1`).
     */
    private fun modelsUrl(base: String, path: String): String {
        val trimmedBase = base.trimEnd('/')
        val trimmedPath = path.trim('/')
        val prefix = trimmedPath.substringBeforeLast('/', "")
        if (prefix.isNotEmpty() && trimmedBase.endsWith("/$prefix", ignoreCase = true)) {
            return "$trimmedBase/${trimmedPath.substringAfterLast('/')}"
        }
        return "$trimmedBase/$trimmedPath"
    }

    /** Unversioned sibling of a versioned models path, `null` when there is none. */
    private fun fallbackModelsPath(path: String): String? = when (path) {
        "v1/models" -> "models"
        "models" -> "v1/models"
        else -> null
    }

    internal fun parseModelIds(json: String): List<String> {
        return runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            root["data"]?.jsonArray?.mapNotNull { el ->
                (el as? JsonObject)?.get("id")?.jsonPrimitive?.content
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Ollama lists the models as `{"models": [{"name": "llama3.2:latest"}]}`. */
    internal fun parseOllamaModelIds(json: String): List<String> {
        return runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            root["models"]?.jsonArray?.mapNotNull { el ->
                (el as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private companion object {
        val FALLBACK_STATUS_CODES = setOf(404, 405, 501)
    }
}
