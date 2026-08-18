package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import scooper.data.ScoopConfig
import scooper.data.Theme
import scooper.data.UIConfig
import scooper.data.PaginationMode
import scooper.data.ViewMode
import scooper.repository.ConfigRepository
import scooper.util.ScoopConfigManager
import scooper.util.form_builder.*

data class SettingsState(
    val uiConfig: UIConfig = UIConfig(),
)

class SettingsViewModel(
    private val configRepository: ConfigRepository,
) : ContainerHost<SettingsState, AppSideEffect>, AutoCloseable {

    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    override val container: Container<SettingsState, AppSideEffect> = coroutineScope.container(
        SettingsState(uiConfig = configRepository.getConfig())
    ) {
        scoopFormState.setData(ScoopConfigManager.readScoopConfig())
        uiFormState.setData(state.uiConfig)
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

    override fun close() {
        supervisorJob.cancel()
    }
}
