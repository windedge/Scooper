package scooper.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * TDD tests for [AiConnectionTester], a JDK-HttpClient connectivity probe for
 * the Settings.AI endpoint. Uses an in-process [HttpServer] stub so no real
 * network is touched; the tester's default JDK client connects to 127.0.0.1.
 */
class AiConnectionTesterTest {

    /** Tiny in-process stub. Records every request (method+path, auth, body). */
    private class StubServer {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<StubRequest>()

        var modelsStatus = 200
        var modelsBody = """{"data":[]}"""
        var chatHandler: (StubRequest) -> Pair<Int, String> = { _ -> 200 to """{}""" }

        val port: Int get() = server.address.port
        val baseUrl: String get() = "http://127.0.0.1:$port"

        fun start() {
            server.createContext("/") { exchange: HttpExchange ->
                val method = exchange.requestMethod
                val path = exchange.requestURI.path
                val auth = exchange.requestHeaders.getFirst("Authorization")
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val req = StubRequest(method, path, auth, body)
                requests.add(req)
                val (status, response) =
                    if (method == "GET" && path.endsWith("/v1/models")) {
                        modelsStatus to modelsBody
                    } else if (method == "POST" && path.endsWith("/v1/chat/completions")) {
                        chatHandler(req)
                    } else {
                        404 to """{"error":"not found"}"""
                    }
                val bytes = response.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private data class StubRequest(
        val method: String,
        val path: String,
        val auth: String?,
        val body: String,
    )

    private val server = StubServer()

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.stop()

    private fun tester() = AiConnectionTester()

    private suspend fun probe(
        provider: String = "openai-compatible",
        apiKey: String = "sk-test-12345678",
        baseUrl: String? = server.baseUrl,
        model: String? = null,
    ) = tester().test(provider, apiKey, baseUrl, model)

    // ---------------------------------------------------------------
    // GET /v1/models success: model listing + configured-model matching
    // ---------------------------------------------------------------

    @Test
    fun `GET models 200 returns ok with model count and model-prefix availability`() = runBlocking {
        server.modelsStatus = 200
        server.modelsBody = """{"data":[{"id":"gpt-4o"},{"id":"model/deepseek-v4-flash"}]}"""

        val result = probe(model = "deepseek-v4-flash")

        assertTrue(result.ok)
        assertEquals(2, result.modelCount)
        assertEquals(true, result.configuredModelAvailable)
        assertEquals(AiErrorCategory.UNKNOWN, result.category)
        assertEquals("${server.baseUrl}/v1/models", result.testedEndpoint)
    }

    @Test
    fun `GET models 200 reports configured model NOT in list when unmatched`() = runBlocking {
        server.modelsStatus = 200
        server.modelsBody = """{"data":[{"id":"gpt-4o"}]}"""

        val result = probe(model = "deepseek-chat")

        assertTrue(result.ok)
        assertEquals(1, result.modelCount)
        assertEquals(false, result.configuredModelAvailable)
    }

    // ---------------------------------------------------------------
    // GET /v1/models error statuses
    // ---------------------------------------------------------------

    @Test
    fun `GET models 401 maps to AUTH`() = runBlocking {
        server.modelsStatus = 401
        server.modelsBody = """{"error":{"message":"bad key"}}"""

        val result = probe()

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.AUTH, result.category)
    }

    @Test
    fun `GET models 429 maps to RATE_LIMIT`() = runBlocking {
        server.modelsStatus = 429
        server.modelsBody = """{"error":{"message":"too many"}}"""

        val result = probe()

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.RATE_LIMIT, result.category)
    }

    @Test
    fun `GET models 500 maps to SERVER`() = runBlocking {
        server.modelsStatus = 500
        server.modelsBody = """{"error":"boom"}"""

        val result = probe()

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.SERVER, result.category)
    }

    // ---------------------------------------------------------------
    // Fallback: GET /v1/models 404 -> POST /v1/chat/completions
    // ---------------------------------------------------------------

    @Test
    fun `GET models 404 falls back to chat completion which succeeds with max_tokens and bearer`() = runBlocking {
        server.modelsStatus = 404
        server.modelsBody = """{"error":"no models endpoint"}"""

        val result = probe(apiKey = "sk-real-key-9999", model = "some-model")

        assertTrue(result.ok)
        assertNull(result.modelCount)
        assertEquals("${server.baseUrl}/v1/chat/completions", result.testedEndpoint)
        // The stub must have received a chat completion with max_tokens:1, the model,
        // and the bearer auth header.
        val chat = server.requests.first { it.method == "POST" }
        assertTrue(chat.body.contains(""""max_tokens":1"""), "chat body should carry max_tokens:1")
        assertTrue(chat.body.contains("some-model"), "chat body should carry the model id")
        assertEquals("Bearer sk-real-key-9999", chat.auth)
    }

    @Test
    fun `GET models 404 and chat 404 mentioning model maps to MODEL_NOT_FOUND`() = runBlocking {
        server.modelsStatus = 404
        server.modelsBody = """{"error":"not found"}"""
        server.chatHandler = { _ ->
            404 to """{"error":{"message":"The model 'x' does not exist"}}"""
        }

        val result = probe()

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.MODEL_NOT_FOUND, result.category)
    }

    @Test
    fun `chat fallback 401 maps to AUTH`() = runBlocking {
        server.modelsStatus = 404
        server.chatHandler = { _ -> 401 to """{"error":"unauthorized"}""" }

        val result = probe()

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.AUTH, result.category)
    }

    // ---------------------------------------------------------------
    // Base URL normalization
    // ---------------------------------------------------------------

    @Test
    fun `baseUrl ending in v1 is normalized so stub receives v1-models not v1-v1-models`() = runBlocking {
        server.modelsStatus = 200
        server.modelsBody = """{"data":[{"id":"a"}]}"""

        val result = probe(baseUrl = "${server.baseUrl}/v1")

        assertTrue(result.ok)
        // Only one request, to /v1/models (not /v1/v1/models).
        assertEquals(1, server.requests.size)
        assertEquals("GET", server.requests[0].method)
        assertTrue(server.requests[0].path.endsWith("/v1/models"))
        assertFalse(server.requests[0].path.endsWith("/v1/v1/models"))
    }

    // ---------------------------------------------------------------
    // Connectivity failures
    // ---------------------------------------------------------------

    @Test
    fun `connection to closed port maps to REFUSED`() {
        // Grab a port from a short-lived server, then close it so the address is refused.
        val closed = StubServer()
        closed.start()
        val deadUrl = closed.baseUrl
        closed.stop()

        val result = runBlocking { probe(baseUrl = deadUrl) }

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.REFUSED, result.category)
    }

    // ---------------------------------------------------------------
    // CONFIG guards (no networking)
    // ---------------------------------------------------------------

    @Test
    fun `empty apiKey maps to CONFIG without any request reaching the stub`() = runBlocking {
        val result = probe(apiKey = "")

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.CONFIG, result.category)
        assertTrue(server.requests.isEmpty(), "no request should reach the stub")
    }

    @Test
    fun `openai-compatible with empty baseUrl maps to CONFIG`() = runBlocking {
        val result = probe(baseUrl = "")

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.CONFIG, result.category)
        assertTrue(server.requests.isEmpty(), "no request should reach the stub")
    }

    // ---------------------------------------------------------------
    // resolveBaseUrl pure-function decisions
    // ---------------------------------------------------------------

    @Test
    fun `resolveBaseUrl returns null when baseUrl empty`() {
        val tester = tester()
        assertNull(tester.resolveBaseUrl(provider = "openai-compatible", baseUrl = ""))
        assertNull(tester.resolveBaseUrl(provider = "openai-compatible", baseUrl = null))
    }

    @Test
    fun `resolveBaseUrl strips trailing v1 path`() {
        val tester = tester()
        assertEquals(
            "http://example.test",
            tester.resolveBaseUrl(provider = "openai-compatible", baseUrl = "http://example.test/v1"),
        )
        assertEquals(
            "http://example.test",
            tester.resolveBaseUrl(
                provider = "openai-compatible",
                baseUrl = "http://example.test/v1/chat/completions",
            ),
        )
    }
}
