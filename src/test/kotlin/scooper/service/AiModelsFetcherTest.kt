package scooper.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
