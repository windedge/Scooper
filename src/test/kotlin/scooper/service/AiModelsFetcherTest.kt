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

class AiModelsFetcherTest {

    private class StubServer {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()

        var modelsStatus = 200
        var modelsBody = """{"data":[{"id":"gpt-4o"},{"id":"deepseek-chat"},{"id":"opencode/deepseek-v4-flash"}]}"""

        val port: Int get() = server.address.port
        val baseUrl: String get() = "http://127.0.0.1:$port"

        fun start() {
            server.createContext("/") { exchange: HttpExchange ->
                val method = exchange.requestMethod
                val path = exchange.requestURI.path
                requests.add("$method $path")
                val (status, response) =
                    if (method == "GET" && (path.endsWith("/v1/models") || path.endsWith("/models"))) {
                        modelsStatus to modelsBody
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

    private val server = StubServer()

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.stop()

    private val fetcher = AiModelsFetcher()

    @Test
    fun `fetchModels 200 returns sorted model list`() = runBlocking {
        val result = fetcher.fetchModels("openai-compatible", "sk-test", server.baseUrl)

        assertTrue(result.ok)
        assertEquals(listOf("deepseek-chat", "gpt-4o", "opencode/deepseek-v4-flash"), result.models)
        assertFalse(result.models.isEmpty())
    }

    @Test
    fun `fetchModels with missing API key returns error`() = runBlocking {
        val result = fetcher.fetchModels("openai-compatible", "", server.baseUrl)

        assertFalse(result.ok)
        assertTrue(result.errorMessage!!.contains("API key"))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `fetchModels with missing baseUrl for openai-compatible returns error`() = runBlocking {
        val result = fetcher.fetchModels("openai-compatible", "sk-test", "")

        assertFalse(result.ok)
        assertTrue(result.errorMessage!!.contains("Base URL"))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `fetchModels with 401 returns auth error`() = runBlocking {
        server.modelsStatus = 401
        server.modelsBody = """{"error":{"message":"Invalid API key"}}"""

        val result = fetcher.fetchModels("openai-compatible", "sk-test", server.baseUrl)

        assertFalse(result.ok)
        assertTrue(result.errorMessage!!.contains("401") || result.errorMessage!!.contains("Authentication") || result.errorMessage!!.contains("Invalid API key"))
    }

    @Test
    fun `fetchModels strips trailing slash and v1 from baseUrl`() = runBlocking {
        val result = fetcher.fetchModels("openai-compatible", "sk-test", "${server.baseUrl}/v1/")

        assertTrue(result.ok)
        assertEquals(1, server.requests.size)
        assertEquals("GET /v1/models", server.requests.first())
    }

    // ---------------------------------------------------------------
    // Provider aware protocol dispatch
    // ---------------------------------------------------------------

    private class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()
    }

    private class ProtocolStubServer {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<RecordedRequest>()

        /** Returns status and body for the requested path. */
        var handler: (String) -> Pair<Int, String> = { 404 to """{"error":"not found"}""" }

        val port: Int get() = server.address.port
        val baseUrl: String get() = "http://127.0.0.1:$port"

        fun start() {
            server.createContext("/") { exchange: HttpExchange ->
                val path = exchange.requestURI.path
                requests.add(
                    RecordedRequest(
                        method = exchange.requestMethod,
                        path = path,
                        headers = exchange.requestHeaders.toMap(),
                    ),
                )
                val (status, response) = handler(path)
                val bytes = response.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private val protocolServer = ProtocolStubServer()

    @BeforeEach
    fun startProtocolServer() = protocolServer.start()

    @AfterEach
    fun stopProtocolServer() = protocolServer.stop()

    @Test
    fun `resolveBaseUrl uses the official default endpoint when the user base URL is empty`() {
        assertEquals("https://api.deepseek.com", fetcher.resolveBaseUrl("deepseek", ""))
        assertEquals("https://api.deepseek.com", fetcher.resolveBaseUrl("deepseek", null))
        assertEquals("https://api.openai.com", fetcher.resolveBaseUrl("openai", ""))
        assertEquals("https://api.anthropic.com", fetcher.resolveBaseUrl("anthropic", ""))
        assertEquals("http://localhost:11434", fetcher.resolveBaseUrl("ollama", ""))
        assertEquals("https://openrouter.ai", fetcher.resolveBaseUrl("openrouter", ""))
        assertNull(fetcher.resolveBaseUrl("openai-compatible", ""))
    }

    @Test
    fun `deepseek requests the models path without a version prefix`() = runBlocking {
        protocolServer.handler = { path ->
            if (path == "/models") 200 to """{"data":[{"id":"deepseek-chat"}]}""" else 404 to """{"error":"not found"}"""
        }

        val result = fetcher.fetchModels("deepseek", "sk-test", protocolServer.baseUrl)

        assertTrue(result.ok)
        assertEquals(listOf("deepseek-chat"), result.models)
        assertEquals(listOf("GET /models"), protocolServer.requests.map { "${it.method} ${it.path}" })
        assertEquals("Bearer sk-test", protocolServer.requests.first().header("Authorization"))
    }

    @Test
    fun `openai requests the versioned models path`() = runBlocking {
        protocolServer.handler = { path ->
            if (path == "/v1/models") 200 to """{"data":[{"id":"gpt-4o"}]}""" else 404 to """{"error":"not found"}"""
        }

        val result = fetcher.fetchModels("openai", "sk-test", protocolServer.baseUrl)

        assertTrue(result.ok)
        assertEquals(listOf("gpt-4o"), result.models)
        assertEquals(listOf("GET /v1/models"), protocolServer.requests.map { "${it.method} ${it.path}" })
        assertEquals("Bearer sk-test", protocolServer.requests.first().header("Authorization"))
    }

    @Test
    fun `openrouter keeps the api prefix of the user base URL`() = runBlocking {
        protocolServer.handler = { path ->
            if (path == "/api/v1/models") 200 to """{"data":[{"id":"openai/gpt-4o"}]}""" else 404 to """{"error":"not found"}"""
        }

        val result = fetcher.fetchModels("openrouter", "sk-test", "${protocolServer.baseUrl}/api/v1")

        assertTrue(result.ok)
        assertEquals(listOf("openai/gpt-4o"), result.models)
        assertEquals(
            listOf("GET /api/v1/models"),
            protocolServer.requests.map { "${it.method} ${it.path}" },
        )
    }

    @Test
    fun `openai-compatible falls back to the unversioned models path on 404`() = runBlocking {
        protocolServer.handler = { path ->
            when (path) {
                "/v1/models" -> 404 to """{"error":"not found"}"""
                "/models" -> 200 to """{"data":[{"id":"gpt-4o"}]}"""
                else -> 404 to """{"error":"not found"}"""
            }
        }

        val result = fetcher.fetchModels("openai-compatible", "sk-test", protocolServer.baseUrl)

        assertTrue(result.ok)
        assertEquals(listOf("gpt-4o"), result.models)
        assertEquals(
            listOf("GET /v1/models", "GET /models"),
            protocolServer.requests.map { "${it.method} ${it.path}" },
        )
    }

    @Test
    fun `anthropic sends the key and the protocol version header`() = runBlocking {
        protocolServer.handler = { path ->
            if (path == "/v1/models") 200 to """{"data":[{"id":"claude-opus-4-1"}]}""" else 404 to """{"error":"not found"}"""
        }

        val result = fetcher.fetchModels("anthropic", "sk-test", protocolServer.baseUrl)

        assertTrue(result.ok)
        assertEquals(listOf("claude-opus-4-1"), result.models)
        val request = protocolServer.requests.first()
        assertEquals("GET /v1/models", "${request.method} ${request.path}")
        assertEquals("sk-test", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `anthropic parses the model ids of the list models response`() = runBlocking {
        protocolServer.handler = { path ->
            if (path == "/v1/models") {
                200 to """{"data":[{"type":"model","id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"}],"has_more":false}"""
            } else {
                404 to """{"error":"not found"}"""
            }
        }

        val result = fetcher.fetchModels("anthropic", "sk-test", protocolServer.baseUrl)

        assertTrue(result.ok)
        assertEquals(listOf("claude-sonnet-4-5"), result.models)
    }

    @Test
    fun `ollama parses the model names of the tags endpoint without authentication`() = runBlocking {
        protocolServer.handler = { path ->
            if (path == "/api/tags") {
                200 to """{"models":[{"name":"llama3.2:latest","model":"llama3.2:latest"},{"name":"qwen2.5:7b","model":"qwen2.5:7b"}]}"""
            } else {
                404 to """{"error":"not found"}"""
            }
        }

        val result = fetcher.fetchModels("ollama", "", protocolServer.baseUrl)

        assertTrue(result.ok)
        assertEquals(listOf("llama3.2:latest", "qwen2.5:7b"), result.models)
        val request = protocolServer.requests.first()
        assertEquals("GET /api/tags", "${request.method} ${request.path}")
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `ollama does not require an api key`() = runBlocking {
        protocolServer.handler = { path ->
            if (path == "/api/tags") 200 to """{"models":[{"name":"llama3.2:latest"}]}""" else 404 to """{"error":"not found"}"""
        }

        val result = fetcher.fetchModels("ollama", "", protocolServer.baseUrl)

        assertTrue(result.ok)
        assertEquals(listOf("llama3.2:latest"), result.models)
    }
}
