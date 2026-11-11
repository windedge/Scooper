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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import scooper.util.LocalizedText
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/** Result of a one-shot AI endpoint connectivity test (Settings.AI "Test Connection").
 *  [detail] stores msgid/args or verbatim output; rendering translates (Settings.kt rule). */
data class AiConnectionTestResult(
    val ok: Boolean,
    val category: AiErrorCategory,
    val detail: LocalizedText = LocalizedText(),
    val latencyMs: Long,
    val testedEndpoint: String,
    val modelCount: Int? = null,
    val configuredModelAvailable: Boolean? = null,
)

/**
 * Direct JDK-HttpClient connectivity probe for the AI endpoint configured in
 * Settings.AI. Deliberately independent of scoop-ai / Koog so the user can
 * validate credentials and base URL before ever running a manifest generation.
 *
 * The probe follows the protocol of the provider, taken from the engine table
 * [ProviderEndpoints]:
 *  1. OpenAI family (deepseek / openai / openrouter / openai-compatible):
 *     `GET {base}/{modelsPath}` with a bearer token. A 404/405/501 answer
 *     falls back to a minimal `POST {base}/{chatPath}`.
 *  2. Anthropic: `GET {base}/v1/models` with `x-api-key` and
 *     `anthropic-version`, falling back to `POST {base}/v1/messages`.
 *  3. Ollama: `GET {base}/api/tags` without authentication, falling back to
 *     `POST {base}/api/chat`.
 *
 * Every call runs on [Dispatchers.IO] and honors connect/request timeouts.
 */
class AiConnectionTester(
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
     * Resolve the normalized base URL for [provider]. Returns null when the
     * probe should not contact any endpoint (openai-compatible requires an
     * explicit base URL); every other provider falls back to its official
     * default endpoint. The normalization is provider aware, so a trailing
     * `/v1` is stripped only for providers that append their own versioned
     * path.
     */
    internal fun resolveBaseUrl(provider: String, baseUrl: String?): String? {
        val endpoint = ProviderEndpoints.forProvider(provider)
        val raw = baseUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: endpoint?.defaultBaseUrl
            ?: return null
        return ScoopAiBridge.normalizeAiBaseUrl(raw, provider).ifEmpty { null }
    }

    suspend fun test(
        provider: String,
        apiKey: String,
        baseUrl: String?,
        model: String?,
    ): AiConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()

        val endpoint = ProviderEndpoints.forProvider(provider)
        if (endpoint == null) {
            return@withContext fail(
                category = AiErrorCategory.CONFIG,
                detail = LocalizedText(raw = ProviderEndpoints.unsupportedProviderMessage(provider)),
                endpoint = "",
                start = start,
            )
        }

        if (endpoint.requiresApiKey && apiKey.isBlank()) {
            return@withContext fail(
                category = AiErrorCategory.CONFIG,
                detail = LocalizedText(msgid = "API key is empty. Fill it in Settings > AI."),
                endpoint = "",
                start = start,
            )
        }

        val base = resolveBaseUrl(provider, baseUrl)
        if (base == null) {
            return@withContext fail(
                category = AiErrorCategory.CONFIG,
                detail = LocalizedText(
                    msgid = "Base URL is required for provider '{{provider}}'. Fill it in Settings > AI.",
                    args = listOf("provider" to provider),
                ),
                endpoint = "",
                start = start,
            )
        }

        try {
            when (provider) {
                "anthropic" -> testAnthropic(base, apiKey, model, start, endpoint)
                "ollama" -> testOllama(base, model, start, endpoint)
                else -> testOpenAi(base, apiKey, model, start, endpoint)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            exceptionResult(e, start, joinUrl(base, endpoint.modelsPath))
        }
    }

    /**
     * OpenAI family probe: model listing first, chat completion as fallback.
     */
    private fun testOpenAi(
        base: String,
        apiKey: String,
        model: String?,
        start: Long,
        endpoint: Endpoint,
    ): AiConnectionTestResult {
        val modelsUrl = joinUrl(base, endpoint.modelsPath)
        val modelsRes = send("GET", modelsUrl, apiKey, null, endpoint)
        // Branch order matters: 5xx wins over the 404/405 fallback below.
        return when (modelsRes.statusCode()) {
            200 -> modelsOk(modelsRes, model, start, modelsUrl)
            401, 403 -> fail(AiErrorCategory.AUTH, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
            429 -> fail(AiErrorCategory.RATE_LIMIT, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
            in 500..599 -> fail(AiErrorCategory.SERVER, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
            404, 405, 501 -> chatFallback(base, apiKey, model, start, endpoint)
            else -> fail(AiErrorCategory.BAD_PATH, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
        }
    }

    /**
     * Anthropic probe: `GET {base}/v1/models` with the Anthropic headers,
     * falling back to a minimal `POST {base}/v1/messages`.
     */
    private fun testAnthropic(
        base: String,
        apiKey: String,
        model: String?,
        start: Long,
        endpoint: Endpoint,
    ): AiConnectionTestResult {
        val modelsUrl = joinUrl(base, endpoint.modelsPath)
        val modelsRes = send("GET", modelsUrl, apiKey, null, endpoint)
        return when (modelsRes.statusCode()) {
            200 -> modelsOk(modelsRes, model, start, modelsUrl)
            401, 403 -> fail(AiErrorCategory.AUTH, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
            429 -> fail(AiErrorCategory.RATE_LIMIT, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
            in 500..599 -> fail(AiErrorCategory.SERVER, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
            404, 405, 501 -> anthropicMessagesFallback(base, apiKey, model, start, endpoint)
            else -> fail(AiErrorCategory.BAD_PATH, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
        }
    }

    /**
     * Ollama probe: `GET {base}/api/tags` without authentication, falling back
     * to a minimal `POST {base}/api/chat`.
     */
    private fun testOllama(
        base: String,
        model: String?,
        start: Long,
        endpoint: Endpoint,
    ): AiConnectionTestResult {
        val modelsUrl = joinUrl(base, endpoint.modelsPath)
        val modelsRes = send("GET", modelsUrl, "", null, endpoint)
        return when (modelsRes.statusCode()) {
            200 -> ollamaModelsOk(modelsRes, model, start, modelsUrl)
            401, 403 -> fail(AiErrorCategory.AUTH, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
            429 -> fail(AiErrorCategory.RATE_LIMIT, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
            in 500..599 -> fail(AiErrorCategory.SERVER, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
            404, 405, 501 -> ollamaChatFallback(base, model, start, endpoint)
            else -> fail(AiErrorCategory.BAD_PATH, detail = LocalizedText(raw = readable(modelsRes)), endpoint = modelsUrl, start = start)
        }
    }

    private fun modelsOk(
        res: HttpResponse<String>,
        model: String?,
        start: Long,
        modelsUrl: String,
    ): AiConnectionTestResult {
        val ids = parseModelIds(res.body())
        val configuredAvailable = model?.takeIf { it.isNotBlank() }?.let { m ->
            ids.any { it == m || it.startsWith("model/$m") }
        }
        return AiConnectionTestResult(
            ok = true,
            category = AiErrorCategory.UNKNOWN, // ok=true carries the success signal
            detail = LocalizedText(
                msgid = "Endpoint reachable; {{count}} model(s) listed.",
                args = listOf("count" to "${ids.size}"),
            ),
            latencyMs = elapsedMs(start),
            testedEndpoint = modelsUrl,
            modelCount = ids.size,
            configuredModelAvailable = configuredAvailable,
        )
    }

    /** Ollama lists the models as `{"models": [{"name": "llama3.2:latest"}]}`. */
    private fun ollamaModelsOk(
        res: HttpResponse<String>,
        model: String?,
        start: Long,
        modelsUrl: String,
    ): AiConnectionTestResult {
        val ids = parseOllamaModelIds(res.body())
        val configuredAvailable = model?.takeIf { it.isNotBlank() }?.let { m ->
            ids.any { it == m || it.startsWith("$m:") || it.startsWith("model/$m") }
        }
        return AiConnectionTestResult(
            ok = true,
            category = AiErrorCategory.UNKNOWN,
            detail = LocalizedText(
                msgid = "Endpoint reachable; {{count}} model(s) listed.",
                args = listOf("count" to "${ids.size}"),
            ),
            latencyMs = elapsedMs(start),
            testedEndpoint = modelsUrl,
            modelCount = ids.size,
            configuredModelAvailable = configuredAvailable,
        )
    }

    private fun chatFallback(
        base: String,
        apiKey: String,
        model: String?,
        start: Long,
        endpoint: Endpoint,
    ): AiConnectionTestResult {
        val chatUrl = joinUrl(base, endpoint.chatPath)
        return try {
            val body = chatBody(model)
            val res = send("POST", chatUrl, apiKey, body, endpoint)
            when (res.statusCode()) {
                200 -> AiConnectionTestResult(
                    ok = true,
                    category = AiErrorCategory.UNKNOWN,
                    detail = LocalizedText(msgid = "No models listing; chat completions reachable."),
                    latencyMs = elapsedMs(start),
                    testedEndpoint = chatUrl,
                )

                401, 403 ->
                    fail(AiErrorCategory.AUTH, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)

                404 -> if ("model" in res.body().lowercase()) {
                    fail(AiErrorCategory.MODEL_NOT_FOUND, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)
                } else {
                    fail(AiErrorCategory.BAD_PATH, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)
                }

                in 500..599 ->
                    fail(AiErrorCategory.SERVER, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)

                else ->
                    fail(AiErrorCategory.BAD_PATH, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            exceptionResult(e, start, chatUrl)
        }
    }

    /**
     * Anthropic fallback: `POST {base}/v1/messages` with the mandatory
     * `max_tokens` field and a single ping message.
     */
    private fun anthropicMessagesFallback(
        base: String,
        apiKey: String,
        model: String?,
        start: Long,
        endpoint: Endpoint,
    ): AiConnectionTestResult {
        val chatUrl = joinUrl(base, endpoint.chatPath)
        return try {
            val body = anthropicChatBody(model, endpoint)
            val res = send("POST", chatUrl, apiKey, body, endpoint)
            when (res.statusCode()) {
                200 -> AiConnectionTestResult(
                    ok = true,
                    category = AiErrorCategory.UNKNOWN,
                    detail = LocalizedText(msgid = "No models listing; messages endpoint reachable."),
                    latencyMs = elapsedMs(start),
                    testedEndpoint = chatUrl,
                )

                401, 403 ->
                    fail(AiErrorCategory.AUTH, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)

                404 -> if ("model" in res.body().lowercase()) {
                    fail(AiErrorCategory.MODEL_NOT_FOUND, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)
                } else {
                    fail(AiErrorCategory.BAD_PATH, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)
                }

                in 500..599 ->
                    fail(AiErrorCategory.SERVER, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)

                else ->
                    fail(AiErrorCategory.BAD_PATH, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            exceptionResult(e, start, chatUrl)
        }
    }

    /** Ollama fallback: `POST {base}/api/chat` without authentication. */
    private fun ollamaChatFallback(
        base: String,
        model: String?,
        start: Long,
        endpoint: Endpoint,
    ): AiConnectionTestResult {
        val chatUrl = joinUrl(base, endpoint.chatPath)
        return try {
            val body = ollamaChatBody(model, endpoint)
            val res = send("POST", chatUrl, "", body, endpoint)
            when (res.statusCode()) {
                200 -> AiConnectionTestResult(
                    ok = true,
                    category = AiErrorCategory.UNKNOWN,
                    detail = LocalizedText(msgid = "No models listing; chat endpoint reachable."),
                    latencyMs = elapsedMs(start),
                    testedEndpoint = chatUrl,
                )

                401, 403 ->
                    fail(AiErrorCategory.AUTH, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)

                404 -> if ("model" in res.body().lowercase()) {
                    fail(AiErrorCategory.MODEL_NOT_FOUND, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)
                } else {
                    fail(AiErrorCategory.BAD_PATH, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)
                }

                in 500..599 ->
                    fail(AiErrorCategory.SERVER, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)

                else ->
                    fail(AiErrorCategory.BAD_PATH, detail = LocalizedText(raw = readable(res)), endpoint = chatUrl, start = start)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            exceptionResult(e, start, chatUrl)
        }
    }

    private fun fail(
        category: AiErrorCategory,
        detail: LocalizedText = LocalizedText(),
        endpoint: String,
        start: Long,
    ): AiConnectionTestResult = AiConnectionTestResult(
        ok = false,
        category = category,
        detail = detail,
        latencyMs = elapsedMs(start),
        testedEndpoint = endpoint,
    )

    private fun exceptionResult(e: Throwable, start: Long, endpoint: String): AiConnectionTestResult {
        val category = when (e) {
            is UnknownHostException -> AiErrorCategory.NETWORK_DNS
            is HttpTimeoutException -> AiErrorCategory.TIMEOUT
            is ConnectException -> AiErrorCategory.REFUSED
            else -> AiErrorCategory.UNKNOWN
        }
        return fail(category, detail = LocalizedText(raw = e.message ?: e.javaClass.simpleName), endpoint = endpoint, start = start)
    }

    private fun send(
        method: String,
        url: String,
        apiKey: String,
        body: String?,
        endpoint: Endpoint,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(requestTimeoutMs))
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
        if (body != null) {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun chatBody(model: String?): String = buildJsonObject {
        put("model", model ?: "")
        put("max_tokens", 1)
        put("stream", false)
        put("messages", buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", "ping")
                },
            )
        })
    }.toString()

    /** Anthropic requires `max_tokens` and rejects a missing model id. */
    private fun anthropicChatBody(model: String?, endpoint: Endpoint): String = buildJsonObject {
        put("model", model?.takeIf { it.isNotBlank() } ?: defaultModel(endpoint))
        put("max_tokens", 1)
        put("messages", buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", "ping")
                },
            )
        })
    }.toString()

    /** Ollama chat request, explicitly non streaming. */
    private fun ollamaChatBody(model: String?, endpoint: Endpoint): String = buildJsonObject {
        put("model", model?.takeIf { it.isNotBlank() } ?: defaultModel(endpoint))
        put("stream", false)
        put("messages", buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", "ping")
                },
            )
        })
    }.toString()

    private fun defaultModel(endpoint: Endpoint): String =
        ProviderEndpoints.defaultModelId(endpoint.provider) ?: ""

    /**
     * Join base URL and provider path without duplicating a versioned prefix
     * the base URL already carries (OpenRouter bases end with `api/v1`).
     */
    internal fun joinUrl(base: String, path: String): String {
        val trimmedBase = base.trimEnd('/')
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return trimmedBase
        for (count in segments.indices.reversed()) {
            val prefix = segments.take(count + 1).joinToString("/")
            if (trimmedBase.endsWith("/$prefix", ignoreCase = true)) {
                val rest = segments.drop(count + 1)
                return if (rest.isEmpty()) trimmedBase else "$trimmedBase/${rest.joinToString("/")}"
            }
        }
        return "$trimmedBase/${segments.joinToString("/")}"
    }

    /** Parse `data[].id` from a models-listing JSON body; empty list on any parse error. */
    internal fun parseModelIds(json: String): List<String> {
        return runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            root["data"]?.jsonArray?.mapNotNull { el ->
                (el as? JsonObject)?.get("id")?.jsonPrimitive?.content
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Parse `models[].name` from an Ollama tags response; empty list on any parse error. */
    internal fun parseOllamaModelIds(json: String): List<String> {
        return runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            root["models"]?.jsonArray?.mapNotNull { el ->
                (el as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    /** Human-readable HTTP failure: status line plus the first 200 body chars. */
    private fun readable(res: HttpResponse<String>): String {
        val body = res.body().trim().take(200)
        return if (body.isEmpty()) "HTTP ${res.statusCode()}" else "HTTP ${res.statusCode()}: $body"
    }
}
