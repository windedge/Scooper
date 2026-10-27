package scooper.viewmodels

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.service.AiModelsFetcher
import scooper.service.AiSettingsSnapshot
import scooper.service.FetchModelsResult
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ProviderCatalog
import scooper.service.ScoopAiBridge
import scooper.ui.AiProviderForm
import scooper.util.form_builder.ChoiceState
import scooper.util.form_builder.TextFieldState

class SettingsViewModelFetchModelsTest {

    private class FakeConfigRepository : ConfigRepository() {
        private var config = UIConfig()
        override fun getConfig(): UIConfig = config
        override fun setConfig(config: UIConfig) {
            this.config = config
        }
    }

    private class FakeModelsFetcher(
        var result: FetchModelsResult = FetchModelsResult(ok = true, models = listOf("gpt-4o", "deepseek-chat")),
    ) : AiModelsFetcher() {
        override suspend fun fetchModels(provider: String, apiKey: String, baseUrl: String?): FetchModelsResult {
            return result
        }
    }

    @Test
    fun `fetchModels updates modelsState with remote models on success`() = runBlocking {
        val configRepo = FakeConfigRepository()
        val modelsFetcher = FakeModelsFetcher()
        val bridge = ScoopAiBridge(
            configRepository = configRepo,
            checkpointService = InMemoryAiCheckpointService(),
            modelsFetcher = modelsFetcher,
        )
        val vm = SettingsViewModel(configRepo, bridge)

        vm.fetchModels("openai-compatible", "sk-test", "http://localhost:8000")
        
        var state = vm.container.stateFlow.value
        for (i in 0..40) {
            delay(50)
            state = vm.container.stateFlow.value
            if (state.modelsState.models.isNotEmpty()) break
        }

        assertFalse(state.modelsState.loading)
        assertEquals(listOf("gpt-4o", "deepseek-chat"), state.modelsState.models)
        assertEquals(null, state.modelsState.error)

        vm.close()
        bridge.close()
    }

    @Test
    fun `fetchModels updates modelsState with error on failure`() = runBlocking {
        val configRepo = FakeConfigRepository()
        val modelsFetcher = FakeModelsFetcher(
            result = FetchModelsResult(ok = false, errorMessage = "Authentication failed (401)"),
        )
        val bridge = ScoopAiBridge(
            configRepository = configRepo,
            checkpointService = InMemoryAiCheckpointService(),
            modelsFetcher = modelsFetcher,
        )
        val vm = SettingsViewModel(configRepo, bridge)

        vm.fetchModels("openai-compatible", "sk-test", "http://localhost:8000")
        
        var state = vm.container.stateFlow.value
        for (i in 0..40) {
            delay(50)
            state = vm.container.stateFlow.value
            if (state.modelsState.error != null) break
        }

        assertFalse(state.modelsState.loading)
        assertEquals("Authentication failed (401)", state.modelsState.error)

        vm.close()
        bridge.close()
    }

    // ---------------------------------------------------------------
    // Provider aware form
    // ---------------------------------------------------------------

    private fun newViewModel(configRepo: FakeConfigRepository): Pair<SettingsViewModel, ScoopAiBridge> {
        val bridge = ScoopAiBridge(
            configRepository = configRepo,
            checkpointService = InMemoryAiCheckpointService(),
        )
        return SettingsViewModel(configRepo, bridge) to bridge
    }

    @Test
    fun `aiProvider choices list every supported provider with its display name`() {
        val configRepo = FakeConfigRepository()
        val (vm, bridge) = newViewModel(configRepo)

        val choices = vm.aiFormState.getState<ChoiceState>("aiProvider").choices

        assertEquals(6, choices.size)
        assertEquals("DeepSeek", choices["deepseek"])
        assertEquals("OpenAI", choices["openai"])
        assertEquals("Anthropic", choices["anthropic"])
        assertEquals("Ollama", choices["ollama"])
        assertEquals("OpenRouter", choices["openrouter"])
        assertEquals("OpenAI Compatible", choices["openai-compatible"])

        vm.close()
        bridge.close()
    }

    @Test
    fun `ollama hides the api key row`() {
        val hints = AiProviderForm.hints("ollama")

        assertFalse(hints.showApiKey)
        assertFalse(hints.showBaseUrl)
    }

    @Test
    fun `openai-compatible shows the api key and base url rows`() {
        val hints = AiProviderForm.hints("openai-compatible")

        assertTrue(hints.showApiKey)
        assertTrue(hints.showBaseUrl)
    }

    @Test
    fun `api key subtitle names the environment variable of the provider`() {
        assertTrue(AiProviderForm.hints("deepseek").apiKeySubtitle.contains("DEEPSEEK_API_KEY"))
        assertTrue(AiProviderForm.hints("anthropic").apiKeySubtitle.contains("ANTHROPIC_API_KEY"))
        assertTrue(AiProviderForm.hints("openrouter").apiKeySubtitle.contains("OPENROUTER_API_KEY"))
    }

    @Test
    fun `model placeholder falls back to model-id for an unknown provider`() {
        assertEquals("model-id", AiProviderForm.hints("does-not-exist").modelPlaceholder)
    }

    @Test
    fun `model placeholder shows the default model of the provider`() {
        assertEquals(
            ProviderCatalog.defaultModelId("anthropic"),
            AiProviderForm.hints("anthropic").modelPlaceholder,
        )
        assertTrue(AiProviderForm.hints("anthropic").modelSubtitle.contains("Defaults to"))
    }

    @Test
    fun `writeAiConfig keeps the versioned base url of openrouter`() {
        val configRepo = FakeConfigRepository()
        val (vm, bridge) = newViewModel(configRepo)

        vm.aiFormState.getState<ChoiceState>("aiProvider").value = "openrouter"
        vm.aiFormState.getState<TextFieldState>("aiBaseUrl").value = "https://openrouter.ai/api/v1"
        vm.writeAiConfig()

        assertEquals("openrouter", configRepo.getConfig().aiProvider)
        assertEquals("https://openrouter.ai/api/v1", configRepo.getConfig().aiBaseUrl)

        vm.close()
        bridge.close()
    }

    @Test
    fun `writeAiConfig strips the version suffix for openai-compatible`() {
        val configRepo = FakeConfigRepository()
        val (vm, bridge) = newViewModel(configRepo)

        vm.aiFormState.getState<ChoiceState>("aiProvider").value = "openai-compatible"
        vm.aiFormState.getState<TextFieldState>("aiBaseUrl").value = "http://localhost:8000/v1"
        vm.writeAiConfig()

        assertEquals("http://localhost:8000", configRepo.getConfig().aiBaseUrl)

        vm.close()
        bridge.close()
    }

    @Test
    fun `anthropic needs no base url to be ready`() {
        val snapshot = AiSettingsSnapshot(
            provider = "anthropic",
            apiKey = "sk-ant-test",
            baseUrl = "",
            model = "claude-opus-4-1",
            githubToken = "",
        )

        assertNull(snapshot.missingReason())
    }

    @Test
    fun `openai-compatible still needs a base url to be ready`() {
        val snapshot = AiSettingsSnapshot(
            provider = "openai-compatible",
            apiKey = "sk-test",
            baseUrl = "",
            model = "gpt-4o",
            githubToken = "",
        )

        assertNotNull(snapshot.missingReason())
    }
}
