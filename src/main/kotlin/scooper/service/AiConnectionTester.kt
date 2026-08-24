package scooper.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/** Result of a one-shot AI endpoint connectivity test (Settings.AI "Test Connection"). */
data class AiConnectionTestResult(
    val ok: Boolean,
    val category: AiErrorCategory,
    val detail: String,
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
 * Probe strategy:
 *  1. GET {base}/v1/models - fastest way to confirm auth + reachability, and it
 *     reports the model count so the UI can show whether the configured model
 *     is present.
 *  2. On 404/405/501 (gateways without a models listing) fall back to a minimal
 *     POST {base}/v1/chat/completions.
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
     * Resolve the normalized base URL for [provider]. Returns null when the probe
     * should not contact any endpoint (openai-compatible requires an explicit
     * base URL); deepseek falls back to the official API root. Trailing
     * `/v1` / `/v1/chat/completions` are stripped so the probe builds
     * `/v1/models` without a doubled path.
     */
    internal fun resolveBaseUrl(provider: String, baseUrl: String?): String? {
        if (baseUrl.isNullOrBlank()) {
            return if (provider == "deepseek") "https://api.deepseek.com" else null
        }
        return ScoopAiBridge.normalizeAiBaseUrl(baseUrl).ifEmpty { null }
    }

    suspend fun test(
        provider: String,
        apiKey: String,
        baseUrl: String?,
        model: String?,
    ): AiConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()

        if (apiKey.isBlank()) {
            return@withContext fail(
                category = AiErrorCategory.CONFIG,
                detail = "API key is empty. Fill it in Settings.AI.",
                endpoint = "",
                start = start,
            )
        }

        val base = resolveBaseUrl(provider, baseUrl)
        if (base == null) {
            return@withContext fail(
                category = AiErrorCategory.CONFIG,
                detail = "Base URL is required for provider '$provider'. Fill it in Settings.AI.",
                endpoint = "",
                start = start,
            )
        }

        try {
            val modelsRes = send("GET", "$base/v1/models", apiKey, null)
            // Branch order matters: 5xx wins over the 404/405 fallback below.
            when (modelsRes.statusCode()) {
                200 -> modelsOk(modelsRes, model, start, base)
                401, 403 -> fail(AiErrorCategory.AUTH, readable(modelsRes), "$base/v1/models", start)
                429 -> fail(AiErrorCategory.RATE_LIMIT, readable(modelsRes), "$base/v1/models", start)
                in 500..599 -> fail(AiErrorCategory.SERVER, readable(modelsRes), "$base/v1/models", start)
                404, 405, 501 -> chatFallback(base, apiKey, model, start)
                else -> fail(AiErrorCategory.BAD_PATH, readable(modelsRes), "$base/v1/models", start)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            exceptionResult(e, start, base)
        }
    }

    private fun modelsOk(
        res: HttpResponse<String>,
        model: String?,
        start: Long,
        base: String,
    ): AiConnectionTestResult {
        val ids = parseModelIds(res.body())
        val configuredAvailable = model?.takeIf { it.isNotBlank() }?.let { m ->
            ids.any { it == m || it.startsWith("model/$m") }
        }
        return AiConnectionTestResult(
            ok = true,
            category = AiErrorCategory.UNKNOWN, // ok=true carries the success signal
            detail = "Endpoint reachable; ${ids.size} model(s) listed.",
            latencyMs = elapsedMs(start),
            testedEndpoint = "$base/v1/models",
            modelCount = ids.size,
            configuredModelAvailable = configuredAvailable,
        )
    }

    private fun chatFallback(
        base: String,
        apiKey: String,
        model: String?,
        start: Long,
    ): AiConnectionTestResult {
        return try {
            val body = chatBody(model)
            val res = send("POST", "$base/v1/chat/completions", apiKey, body)
            when (res.statusCode()) {
                200 -> AiConnectionTestResult(
                    ok = true,
                    category = AiErrorCategory.UNKNOWN,
                    detail = "No models listing; chat completions reachable.",
                    latencyMs = elapsedMs(start),
                    testedEndpoint = "$base/v1/chat/completions",
                )

                401, 403 ->
                    fail(AiErrorCategory.AUTH, readable(res), "$base/v1/chat/completions", start)

                404 -> if ("model" in res.body().lowercase()) {
                    fail(AiErrorCategory.MODEL_NOT_FOUND, readable(res), "$base/v1/chat/completions", start)
                } else {
                    fail(AiErrorCategory.BAD_PATH, readable(res), "$base/v1/chat/completions", start)
                }

                in 500..599 ->
                    fail(AiErrorCategory.SERVER, readable(res), "$base/v1/chat/completions", start)

                else ->
                    fail(AiErrorCategory.BAD_PATH, readable(res), "$base/v1/chat/completions", start)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            exceptionResult(e, start, base)
        }
    }

    private fun fail(
        category: AiErrorCategory,
        detail: String,
        endpoint: String,
        start: Long,
    ): AiConnectionTestResult = AiConnectionTestResult(
        ok = false,
        category = category,
        detail = detail,
        latencyMs = elapsedMs(start),
        testedEndpoint = endpoint,
    )

    private fun exceptionResult(e: Throwable, start: Long, base: String): AiConnectionTestResult {
        val category = when (e) {
            is UnknownHostException -> AiErrorCategory.NETWORK_DNS
            is HttpTimeoutException -> AiErrorCategory.TIMEOUT
            is ConnectException -> AiErrorCategory.REFUSED
            else -> AiErrorCategory.UNKNOWN
        }
        return fail(category, e.message ?: e.javaClass.simpleName, "$base/v1/models", start)
    }

    private fun send(
        method: String,
        url: String,
        apiKey: String,
        body: String?,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofMillis(requestTimeoutMs))
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

    /** Parse `data[].id` from a models-listing JSON body; empty list on any parse error. */
    internal fun parseModelIds(json: String): List<String> {
        return runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            root["data"]?.jsonArray?.mapNotNull { el ->
                (el as? JsonObject)?.get("id")?.jsonPrimitive?.content
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
