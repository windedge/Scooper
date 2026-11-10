package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import scooper.data.AiConfig
import scooper.data.ScoopConfig
import scooper.data.Theme
import scooper.data.UIConfig
import scooper.data.PaginationMode
import scooper.data.ViewMode
import scooper.repository.ConfigRepository
import scooper.service.AiConnectionTestResult
import scooper.service.AiSettingsStore
import scooper.service.ProviderCatalog
import scooper.service.ScoopAiBridge
import scooper.util.ScoopConfigManager
import scooper.util.form_builder.*

data class SettingsState(
    val uiConfig: UIConfig = UIConfig(),
    val aiTest: AiTestUiState = AiTestUiState(),
    val modelsState: FetchModelsUiState = FetchModelsUiState(),
)

/** UI state backing the Settings.AI "Test Connection" row. */
data class AiTestUiState(
    val testing: Boolean = false,
    val result: AiConnectionTestResult? = null,
)

/** UI state backing the Settings.AI Model dropdown. */
data class FetchModelsUiState(
    val loading: Boolean = false,
    val models: List<String> = emptyList(),
    val error: String? = null,
)

class SettingsViewModel(
    private val configRepository: ConfigRepository,
    private val scoopAiBridge: ScoopAiBridge,
    /** Settings seam (S5); defaults to the bridge's store for existing callers. */
    private val aiSettingsStore: AiSettingsStore? = null,
) : ContainerHost<SettingsState, AppSideEffect>, AutoCloseable {

    /** Settings seam; falls back to the bridge's own store. */
    private val aiSettings: AiSettingsStore get() = aiSettingsStore ?: scoopAiBridge.settingsStore

    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    override val container: Container<SettingsState, AppSideEffect> = coroutineScope.container(
        SettingsState(uiConfig = configRepository.getConfig())
    ) {
        scoopFormState.setData(ScoopConfigManager.readScoopConfig())
        uiFormState.setData(state.uiConfig)
        aiFormState.setData(state.uiConfig.toAiConfig())
    }

    fun switchTheme(theme: Theme) = intent {
        reduce { state.copy(uiConfig = state.uiConfig.copy(theme = theme)) }
    }

    fun switchFontSizeScale(scale: Float) = intent {
        reduce { state.copy(uiConfig = state.uiConfig.copy(fontSizeScale = scale)) }
    }

    fun switchViewMode(viewMode: ViewMode) = intent {
        reduce { state.copy(uiConfig = state.uiConfig.copy(viewMode = viewMode)) }
    }

    fun switchPaginationMode(paginationMode: PaginationMode) = intent {
        reduce { state.copy(uiConfig = state.uiConfig.copy(paginationMode = paginationMode)) }
    }

    fun switchShowTrayIcon(enabled: Boolean) = intent {
        reduce { state.copy(uiConfig = state.uiConfig.copy(showTrayIcon = enabled)) }
    }

    /**
     * Switch the interface language immediately: update the state and write
     * through to the database (same pattern as switchFontFamily), so the
     * choice survives restart without the Apply flow. Not part of
     * uiFormState on purpose; writeUIConfig re-reads the live value from the
     * state flow, so Apply never rolls the language back.
     */
    fun switchLocale(localeTag: String) = intent {
        reduce { state.copy(uiConfig = state.uiConfig.copy(locale = localeTag)) }
        configRepository.setConfig(configRepository.getConfig().copy(locale = localeTag))
    }

    /**
     * Switch the user-selected interface font family immediately: update the
     * state and write through to the database (same pattern as
     * AppsViewModel.setViewMode), so the choice survives restart without the
     * Apply flow. Not part of uiFormState/writeUIConfig on purpose.
     */
    fun switchFontFamily(name: String) = intent {
        reduce { state.copy(uiConfig = state.uiConfig.copy(fontFamily = name)) }
        configRepository.setConfig(configRepository.getConfig().copy(fontFamily = name))
    }

    // ChoiceState.choices values are raw English msgids, NOT the output of
    // tr(...): labels are produced at render time (Settings.kt) by re-running
    // tr()/displayName(), so they follow locale switches and are never frozen
    // by the locale active when this ViewModel is constructed. Keys are stable
    // identifiers, so ChoiceState validators and the dropdown reverse lookup
    // (filter by label) keep working.
    val scoopFormState = FormState(
        fields = listOf(
            ChoiceState(
                "proxyType", initial = "default", validators = listOf(Validators.ValidChoice()), choices = mapOf(
                    "default" to "Default",
                    "none" to "None",
                    "custom" to "Custom",
                )
            ),
            TextFieldState(
                "proxy",
                transform = {
                    transformProxy(it)
                },
                validators = listOf(
                    // Store the raw msgid (translation happens at the display
                    // layer in Settings.kt via tr(proxyState.errorMessage)), so
                    // the message is not frozen at ViewModel construction.
                    Validators.Custom("Invalid proxy address.") {
                        validateProxy(it as String)
                    },
                )
            ),
            SwitchState("aria2Enabled", true),
        )
    )

    val uiFormState = FormState(
        fields = listOf(
            SwitchState("refreshOnStartup"),
            SwitchState("periodicRefreshEnabled"),
            ChoiceState(
                "autoRefreshIntervalMinutes", initial = "120", validators = listOf(), choices = mapOf(
                    "30" to "{{n}} min",
                    "60" to "{{n}} hour",
                    "120" to "{{n}} hours",
                    "240" to "{{n}} hours",
                )
            ),
            ChoiceState("theme", validators = listOf(), choices = mapOf(
                "Auto" to "Auto",
                "Light" to "Light",
                "Dark" to "Dark",
            )),
            ChoiceState("viewMode", validators = listOf(), choices = mapOf(
                "List" to "List",
                "Grid" to "Grid",
            )),
            ChoiceState("paginationMode", validators = listOf(), choices = mapOf(
                "Waterfall" to "Waterfall",
                "Pagination" to "Pagination",
            )),
            SwitchState("showTrayIcon"),
        )
    )

    val aiFormState = FormState(
        fields = listOf(
            ChoiceState(
                "aiProvider",
                initial = "openai-compatible",
                validators = listOf(Validators.ValidChoice()),
                choices = ProviderCatalog.providers.associate { it.id to it.displayName },
            ),
            TextFieldState("aiApiKey"),
            TextFieldState("aiBaseUrl"),
            TextFieldState("aiModel"),
            TextFieldState("aiGithubToken"),
            SwitchState("aiAutoDownload"),
        )
    )

    private fun transformProxy(proxy: String): String {
        val proxyType = scoopFormState.getState<ChoiceState>("proxyType")
        return when (proxyType.value) {
            "default" -> ""
            "none" -> "none"
            else -> proxy
        }
    }

    private fun validateProxy(proxyAddress: String): Boolean {
        val proxyType = scoopFormState.getState<ChoiceState>("proxyType")
        if (proxyType.value != "custom") {
            return true
        }
        val proxyUrl: TextFieldState = scoopFormState.getState("proxy")
        return proxyUrl.validateProxyAddress(proxyAddress)
    }

    fun writeScoopConfig() {
        val config = scoopFormState.getData(ScoopConfig::class)
        ScoopConfigManager.writeScoopConfig(config)
    }

    fun writeUIConfig(fontSizeScale: Float? = null) {
        val formConfig = uiFormState.getData(UIConfig::class)
        val currentConfig = configRepository.getConfig()
        val config = currentConfig.copy(
            refreshOnStartup = formConfig.refreshOnStartup,
            periodicRefreshEnabled = formConfig.periodicRefreshEnabled,
            autoRefreshIntervalMinutes = formConfig.autoRefreshIntervalMinutes,
            theme = formConfig.theme,
            viewMode = formConfig.viewMode,
            paginationMode = formConfig.paginationMode,
            fontSizeScale = fontSizeScale ?: currentConfig.fontSizeScale,
            locale = container.stateFlow.value.uiConfig.locale,
            showTrayIcon = formConfig.showTrayIcon,
        )
        configRepository.setConfig(config)
        intent { reduce { state.copy(uiConfig = config) } }
    }

    fun reloadUIConfig() {
        val config = configRepository.getConfig()
        uiFormState.setData(config)
        intent { reduce { state.copy(uiConfig = config) } }
    }

    fun reloadAiConfig() {
        val config = configRepository.getConfig()
        aiFormState.setData(config.toAiConfig())
        intent { reduce { state.copy(uiConfig = config) } }
    }

    private fun UIConfig.toAiConfig() = AiConfig(
        aiProvider = aiProvider,
        aiApiKey = aiApiKey,
        aiBaseUrl = aiBaseUrl,
        aiModel = aiModel,
        aiGithubToken = aiGithubToken,
        aiAutoDownload = aiAutoDownload,
    )

    fun writeAiConfig() {
        // Read TextFieldState / ChoiceState directly - more reliable than getData reflection
        val provider = aiFormState.getState<ChoiceState>("aiProvider").value.trim()
            .ifBlank { "openai-compatible" }
        val config = configRepository.getConfig().copy(
            aiProvider = provider,
            aiApiKey = aiFormState.getState<TextFieldState>("aiApiKey").value.trim(),
            aiBaseUrl = AiSettingsStore.normalizeAiBaseUrl(
                aiFormState.getState<TextFieldState>("aiBaseUrl").value,
                provider,
            ),
            aiModel = aiFormState.getState<TextFieldState>("aiModel").value.trim(),
            aiGithubToken = aiFormState.getState<TextFieldState>("aiGithubToken").value.trim(),
            aiAutoDownload = aiFormState.getState<SwitchState>("aiAutoDownload").value,
        )
        configRepository.setConfig(config)
        intent {
            reduce { state.copy(uiConfig = config) }
            postSideEffect(AppSideEffect.Toast("AI settings saved", ToastType.SUCCESS))
        }
    }

    /**
     * Drop the probe feedback of the previous provider. The text fields keep
     * their values, only the test result and the fetch error are reset.
     */
    fun onProviderChanged() {
        intent {
            reduce {
                state.copy(
                    aiTest = state.aiTest.copy(result = null),
                    modelsState = state.modelsState.copy(error = null),
                )
            }
        }
    }

    /**
     * Probe the current (possibly unsaved) AI form values with the bridge's
     * connection tester. Flips [AiTestUiState.testing] on, runs the probe off
     * the UI thread, then publishes the [AiConnectionTestResult].
     */
    fun testConnection(provider: String, apiKey: String, baseUrl: String?, model: String?) {
        intent {
            reduce { state.copy(aiTest = state.aiTest.copy(testing = true, result = null)) }
        }
        scoopAiBridge.testConnection(provider, apiKey, baseUrl, model) { result ->
            coroutineScope.launch {
                intent {
                    reduce {
                        state.copy(aiTest = state.aiTest.copy(testing = false, result = result))
                    }
                }
            }
        }
    }

    /**
     * Fetch available model list from the configured endpoint.
     */
    fun fetchModels(provider: String, apiKey: String, baseUrl: String?) {
        intent {
            reduce { state.copy(modelsState = state.modelsState.copy(loading = true, error = null)) }
        }
        scoopAiBridge.fetchModels(provider, apiKey, baseUrl) { result ->
            coroutineScope.launch {
                intent {
                    reduce {
                        state.copy(
                            modelsState = state.modelsState.copy(
                                loading = false,
                                models = if (result.ok) result.models else state.modelsState.models,
                                error = if (!result.ok) result.errorMessage else null,
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun close() {
        supervisorJob.cancel()
    }
}
