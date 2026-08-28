package scooper.viewmodels

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.service.AiModelsFetcher
import scooper.service.FetchModelsResult
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge

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
}
