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
import java.net.Authenticator
import java.net.CookieHandler
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

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

    // ---------------------------------------------------------------
    // Provider aware protocol dispatch
    // ---------------------------------------------------------------

    private class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()
    }

    /** Stub server recording method, path, headers and body of every request. */
    private class ProtocolStubServer {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<RecordedRequest>()

        /** Returns status and body for the requested method and path. */
        var handler: (String, String) -> Pair<Int, String> = { _, _ -> 404 to """{"error":"not found"}""" }

        val port: Int get() = server.address.port
        val baseUrl: String get() = "http://127.0.0.1:$port"

        fun start() {
            server.createContext("/") { exchange: HttpExchange ->
                val method = exchange.requestMethod
                val path = exchange.requestURI.path
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                requests.add(RecordedRequest(method, path, exchange.requestHeaders.toMap(), body))
                val (status, response) = handler(method, path)
                val bytes = response.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun stop() = server.stop(0)
    }

    /**
     * HttpClient stub that records the outgoing requests and answers with a
     * canned response, so a probe against a public default endpoint never
     * touches the network.
     */
    private class RecordingHttpClient(
        private val responder: (HttpRequest) -> Pair<Int, String>,
    ) : HttpClient() {
        val requests = mutableListOf<HttpRequest>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            requests.add(request)
            val (status, body) = responder(request)
            return FakeResponse(status, body) as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<java.time.Duration> = Optional.empty()
        override fun followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
        override fun newWebSocketBuilder(): WebSocket.Builder = throw UnsupportedOperationException()
    }

    private class FakeResponse(
        private val status: Int,
        private val body: String,
    ) : HttpResponse<String> {
        override fun statusCode(): Int = status
        override fun request(): HttpRequest? = null
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): java.net.http.HttpHeaders =
            java.net.http.HttpHeaders.of(emptyMap<String, List<String>>()) { _, _ -> true }
        override fun body(): String = body
        override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()
        override fun uri(): URI = URI.create("http://localhost")
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    private val protocolServer = ProtocolStubServer()

    @BeforeEach
    fun startProtocolServer() = protocolServer.start()

    @AfterEach
    fun stopProtocolServer() = protocolServer.stop()

    @Test
    fun `resolveBaseUrl uses the official default endpoint when the user base URL is empty`() {
        val tester = tester()
        assertEquals("https://api.deepseek.com", tester.resolveBaseUrl("deepseek", ""))
        assertEquals("https://api.deepseek.com", tester.resolveBaseUrl("deepseek", null))
        assertEquals("https://api.openai.com", tester.resolveBaseUrl("openai", ""))
        assertEquals("https://api.anthropic.com", tester.resolveBaseUrl("anthropic", ""))
        assertEquals("http://localhost:11434", tester.resolveBaseUrl("ollama", ""))
        assertEquals("https://openrouter.ai", tester.resolveBaseUrl("openrouter", ""))
        assertNull(tester.resolveBaseUrl("openai-compatible", ""))
    }

    @Test
    fun `deepseek with empty baseUrl probes the official models path and falls back to the official chat path`() = runBlocking {
        val recorder = RecordingHttpClient { request ->
            if (request.method() == "GET") 404 to """{"error":"not found"}""" else 200 to """{}"""
        }
        val tester = AiConnectionTester(httpClient = recorder)

        val result = tester.test("deepseek", "sk-test", "", null)

        assertTrue(result.ok)
        assertEquals(
            listOf("https://api.deepseek.com/models", "https://api.deepseek.com/chat/completions"),
            recorder.requests.map { it.uri().toString() },
        )
        assertEquals("https://api.deepseek.com/chat/completions", result.testedEndpoint)
    }

    @Test
    fun `openrouter keeps the api prefix of the user base URL on the chat fallback`() = runBlocking {
        val recorder = RecordingHttpClient { request ->
            if (request.method() == "GET") 404 to """{"error":"not found"}""" else 200 to """{}"""
        }
        val tester = AiConnectionTester(httpClient = recorder)

        val result = tester.test("openrouter", "sk-test", "https://example.com/api/v1", null)

        assertTrue(result.ok)
        assertEquals(
            listOf(
                "https://example.com/api/v1/models",
                "https://example.com/api/v1/chat/completions",
            ),
            recorder.requests.map { it.uri().toString() },
        )
    }

    @Test
    fun `openai-compatible with empty baseUrl keeps the CONFIG message`() = runBlocking {
        val result = probe(baseUrl = "")

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.CONFIG, result.category)
        assertEquals(
            "Base URL is required for provider '{{provider}}'. Fill it in Settings > AI.",
            result.detail.msgid,
            "unexpected detail msgid: ${result.detail.msgid}",
        )
        assertEquals("openai-compatible", result.detail.args.single().second)
    }

    @Test
    fun `anthropic sends the key and the protocol version header without a bearer token`() = runBlocking {
        protocolServer.handler = { method, path ->
            if (method == "GET" && path == "/v1/models") {
                200 to """{"data":[{"type":"model","id":"claude-opus-4-1"}]}"""
            } else {
                404 to """{"error":"not found"}"""
            }
        }

        val result = tester().test("anthropic", "sk-test", protocolServer.baseUrl, "claude-opus-4-1")

        assertTrue(result.ok)
        assertEquals(1, result.modelCount)
        val request = protocolServer.requests.first()
        assertEquals("GET /v1/models", "${request.method} ${request.path}")
        assertEquals("sk-test", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `anthropic falls back to the messages endpoint with max_tokens when the listing fails`() = runBlocking {
        protocolServer.handler = { method, path ->
            when {
                method == "GET" && path == "/v1/models" -> 404 to """{"error":"not found"}"""
                method == "POST" && path == "/v1/messages" -> 200 to """{"id":"msg_1"}"""
                else -> 404 to """{"error":"not found"}"""
            }
        }

        val result = tester().test("anthropic", "sk-test", protocolServer.baseUrl, "claude-opus-4-1")

        assertTrue(result.ok)
        assertEquals("${protocolServer.baseUrl}/v1/messages", result.testedEndpoint)
        val post = protocolServer.requests.first { it.method == "POST" }
        assertEquals("sk-test", post.header("x-api-key"))
        assertEquals("2023-06-01", post.header("anthropic-version"))
        assertNull(post.header("Authorization"))
        assertTrue(post.body.contains(""""max_tokens":1"""), "body should carry max_tokens:1: ${post.body}")
        assertTrue(post.body.contains("claude-opus-4-1"), "body should carry the model id")
        assertTrue(post.body.contains("ping"), "body should carry the ping message")
    }

    @Test
    fun `anthropic chat fallback 401 maps to AUTH`() = runBlocking {
        protocolServer.handler = { method, path ->
            when {
                method == "GET" -> 404 to """{"error":"not found"}"""
                path == "/v1/messages" -> 401 to """{"error":"invalid x-api-key"}"""
                else -> 404 to """{"error":"not found"}"""
            }
        }

        val result = tester().test("anthropic", "sk-test", protocolServer.baseUrl, null)

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.AUTH, result.category)
    }

    @Test
    fun `anthropic chat fallback 404 mentioning model maps to MODEL_NOT_FOUND`() = runBlocking {
        protocolServer.handler = { method, path ->
            when {
                method == "GET" -> 404 to """{"error":"not found"}"""
                path == "/v1/messages" -> 404 to """{"error":{"message":"model: not found"}}"""
                else -> 404 to """{"error":"not found"}"""
            }
        }

        val result = tester().test("anthropic", "sk-test", protocolServer.baseUrl, null)

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.MODEL_NOT_FOUND, result.category)
    }

    @Test
    fun `anthropic chat fallback 500 maps to SERVER`() = runBlocking {
        protocolServer.handler = { method, path ->
            when {
                method == "GET" -> 404 to """{"error":"not found"}"""
                path == "/v1/messages" -> 500 to """{"error":"boom"}"""
                else -> 404 to """{"error":"not found"}"""
            }
        }

        val result = tester().test("anthropic", "sk-test", protocolServer.baseUrl, null)

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.SERVER, result.category)
    }

    @Test
    fun `ollama probes the tags endpoint without authentication`() = runBlocking {
        protocolServer.handler = { method, path ->
            if (method == "GET" && path == "/api/tags") {
                200 to """{"models":[{"name":"llama3.2:latest"},{"name":"qwen2.5:7b"}]}"""
            } else {
                404 to """{"error":"not found"}"""
            }
        }

        val result = tester().test("ollama", "", protocolServer.baseUrl, null)

        assertTrue(result.ok)
        assertEquals(2, result.modelCount)
        val request = protocolServer.requests.first()
        assertEquals("GET /api/tags", "${request.method} ${request.path}")
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `ollama falls back to the chat endpoint without authentication`() = runBlocking {
        protocolServer.handler = { method, path ->
            when {
                method == "GET" && path == "/api/tags" -> 404 to """{"error":"not found"}"""
                method == "POST" && path == "/api/chat" -> 200 to """{"message":{"content":"pong"}}"""
                else -> 404 to """{"error":"not found"}"""
            }
        }

        val result = tester().test("ollama", "", protocolServer.baseUrl, "llama3.2:latest")

        assertTrue(result.ok)
        assertEquals("${protocolServer.baseUrl}/api/chat", result.testedEndpoint)
        val post = protocolServer.requests.first { it.method == "POST" }
        assertNull(post.header("Authorization"))
        assertTrue(post.body.contains(""""stream":false"""), "body should disable streaming: ${post.body}")
        assertTrue(post.body.contains("llama3.2:latest"), "body should carry the model id")
        assertTrue(post.body.contains("ping"), "body should carry the ping message")
    }

    @Test
    fun `ollama does not require an api key`() = runBlocking {
        protocolServer.handler = { method, path ->
            if (method == "GET" && path == "/api/tags") {
                200 to """{"models":[{"name":"llama3.2:latest"}]}"""
            } else {
                404 to """{"error":"not found"}"""
            }
        }

        val result = tester().test("ollama", "", protocolServer.baseUrl, null)

        assertTrue(result.ok)
        assertEquals(AiErrorCategory.UNKNOWN, result.category)
        assertEquals(1, result.modelCount)
    }

    @Test
    fun `ollama chat fallback 500 maps to SERVER`() = runBlocking {
        protocolServer.handler = { method, path ->
            when {
                method == "GET" -> 404 to """{"error":"not found"}"""
                path == "/api/chat" -> 500 to """{"error":"boom"}"""
                else -> 404 to """{"error":"not found"}"""
            }
        }

        val result = tester().test("ollama", "", protocolServer.baseUrl, null)

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.SERVER, result.category)
    }

    @Test
    fun `unknown provider maps to CONFIG without any request`() = runBlocking {
        val result = tester().test("not-a-provider", "sk-test", protocolServer.baseUrl, null)

        assertFalse(result.ok)
        assertEquals(AiErrorCategory.CONFIG, result.category)
        assertTrue(protocolServer.requests.isEmpty(), "no request should reach the stub")
    }
}
