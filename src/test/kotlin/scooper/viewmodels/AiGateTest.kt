package scooper.viewmodels

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.service.AiModelsFetcher
import scooper.service.AiSettingsStore
import scooper.service.FetchModelsResult
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge

// ---------------------------------------------------------------
// AiGate: the single readiness/model/fetch seam shared by the
// Create generate and assist entries (S2 step 4). Readiness reads
// the settings store; model persistence and the /models fetch go
// through the same two collaborators, so there is exactly one
// place that decides whether the AI is usable.
// ---------------------------------------------------------------
class AiGateTest {

    private class FakeConfigRepository(var stored: UIConfig = UIConfig()) : ConfigRepository() {
        override fun getConfig(): UIConfig = stored

        override fun setConfig(config: UIConfig) {
            stored = config
        }
    }

    /** Bridge whose /models call is answered from [result] without any network. */
    private class FakeBridge(
        var result: FetchModelsResult = FetchModelsResult(ok = true, models = listOf("model-a", "model-b")),
        var failure: Exception? = null,
    ) : ScoopAiBridge(
        configRepository = FakeConfigRepository(),
        checkpointService = InMemoryAiCheckpointService(),
        modelsFetcher = AiModelsFetcher(),
    ) {
        var lastProvider: String? = null
        var lastBaseUrl: String? = null

        override fun fetchModels(
            provider: String,
            apiKey: String,
            baseUrl: String?,
            onResult: (FetchModelsResult) -> Unit,
        ) {
            lastProvider = provider
            lastBaseUrl = baseUrl
            val boom = failure
            if (boom != null) onResult(FetchModelsResult(ok = false, errorMessage = boom.message))
            else onResult(result)
        }
    }

    /** Env-free settings read: the tests must not depend on the developer machine env. */
    private fun gate(
        repo: FakeConfigRepository,
        bridge: FakeBridge = FakeBridge(),
    ): AiGate {
        val store = AiSettingsStore(repo)
        return AiGate(
            settings = store,
            bridge = bridge,
            snapshot = { store.readSettings { null } },
        )
    }

    // -------------------------------------------------------------
    // Readiness
    // -------------------------------------------------------------

    @Test
    fun `readiness reports not configured and a hint when nothing is set`() {
        val readiness = gate(FakeConfigRepository()).readiness()
        assertFalse(readiness.configured, "blank settings must not read as configured")
        assertTrue(
            readiness.reason != null,
            "an unconfigured gate must carry a structured reason, got: ${readiness.reason}",
        )
    }

    @Test
    fun `readiness names the missing api key`() {
        val repo = FakeConfigRepository(
            UIConfig(aiProvider = "openai", aiApiKey = "", aiBaseUrl = ""),
        )
        val readiness = gate(repo).readiness()
        assertFalse(readiness.configured)
        assertTrue(
            readiness.reason?.msgid?.contains("API key is missing") == true,
            "expected the api key reason, got: ${readiness.reason}",
        )
    }

    @Test
    fun `readiness names the missing base url for a gateway provider`() {
        val repo = FakeConfigRepository(
            UIConfig(aiProvider = "openai-compatible", aiApiKey = "sk-test", aiBaseUrl = ""),
        )
        val readiness = gate(repo).readiness()
        assertFalse(readiness.configured)
        assertTrue(
            readiness.reason?.msgid?.contains("Base URL is required") == true,
            "expected the base url reason, got: ${readiness.reason}",
        )
    }

    @Test
    fun `readiness is configured and hint free for a complete setting`() {
        val repo = FakeConfigRepository(
            UIConfig(
                aiProvider = "openai-compatible",
                aiApiKey = "sk-test",
                aiBaseUrl = "http://127.0.0.1:8317",
                aiModel = "deepseek-v4-flash",
            ),
        )
        val readiness = gate(repo).readiness()
        assertTrue(readiness.configured, "a complete setting must read as configured")
        assertNull(readiness.reason, "a configured gate must not carry a reason")
    }

    @Test
    fun `readiness rejects an unsupported provider`() {
        val readiness = gate(FakeConfigRepository(UIConfig(aiProvider = "no-such-provider"))).readiness()
        assertFalse(readiness.configured)
        assertTrue(
            readiness.reason != null,
            "an unknown provider must be reported as unready, got: ${readiness.reason}",
        )
    }

    // -------------------------------------------------------------
    // Model read / persist
    // -------------------------------------------------------------

    @Test
    fun `currentModel returns the stored model`() {
        val repo = FakeConfigRepository(UIConfig(aiModel = "deepseek-chat"))
        assertEquals("deepseek-chat", gate(repo).currentModel())
    }

    @Test
    fun `persistModel writes the model through the settings store`() {
        val repo = FakeConfigRepository(UIConfig(aiModel = "old-model"))
        gate(repo).persistModel("new-model")
        assertEquals("new-model", repo.stored.aiModel)
    }

    // -------------------------------------------------------------
    // Model fetch
    // -------------------------------------------------------------

    @Test
    fun `fetchModels returns the remote model list and uses the stored endpoint`() = runBlocking {
        val repo = FakeConfigRepository(
            UIConfig(
                aiProvider = "openai-compatible",
                aiApiKey = "sk-test",
                aiBaseUrl = "http://127.0.0.1:8317/v1/",
            ),
        )
        val bridge = FakeBridge()
        val models = gate(repo, bridge).fetchModels()
        assertEquals(listOf("model-a", "model-b"), models)
        assertEquals("openai-compatible", bridge.lastProvider)
        // normalizeAiBaseUrl strips the versioned suffix before the probe.
        assertEquals("http://127.0.0.1:8317", bridge.lastBaseUrl)
    }

    @Test
    fun `fetchModels throws with the gateway message when the probe fails`() = runBlocking {
        val repo = FakeConfigRepository(UIConfig(aiProvider = "openai-compatible", aiApiKey = "sk-test"))
        val bridge = FakeBridge(failure = IllegalStateException("gateway unreachable"))
        val error = runCatching { gate(repo, bridge).fetchModels() }.exceptionOrNull()
        assertTrue(error is AiModelsFetchException, "expected AiModelsFetchException, got: $error")
        assertEquals("gateway unreachable", error?.message)
    }

    @Test
    fun `fetchModels falls back to a generic message when the probe reports none`() = runBlocking {
        val repo = FakeConfigRepository(UIConfig(aiProvider = "openai-compatible", aiApiKey = "sk-test"))
        val bridge = FakeBridge(result = FetchModelsResult(ok = false, errorMessage = null))
        val error = runCatching { gate(repo, bridge).fetchModels() }.exceptionOrNull()
        assertTrue(error is AiModelsFetchException, "expected AiModelsFetchException, got: $error")
        assertTrue(error?.message?.isNotBlank() == true, "a failure must always carry a message")
    }
}
