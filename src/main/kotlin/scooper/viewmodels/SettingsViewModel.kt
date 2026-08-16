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
import scooper.util.tr

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

    val scoopFormState = FormState(
        fields = listOf(
            ChoiceState(
                "proxyType", initial = "default", validators = listOf(Validators.ValidChoice()), choices = mapOf(
                    "default" to tr("Default"),
                    "none" to tr("None"),
                    "custom" to tr("Custom")
                )
            ),
            TextFieldState(
                "proxy",
                transform = {
                    transformProxy(it)
                },
                validators = listOf(
                    Validators.Custom(tr("Invalid proxy address.")) {
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
                    "30" to tr("{{n}} min", "n" to "30"),
                    "60" to tr("{{n}} hour", "n" to "1"),
                    "120" to tr("{{n}} hours", "n" to "2"),
                    "240" to tr("{{n}} hours", "n" to "4"),
                )
            ),
            ChoiceState("theme", validators = listOf(), choices = mapOf(
                "Auto" to tr("Auto"),
                "Light" to tr("Light"),
                "Dark" to tr("Dark"),
            )),
            ChoiceState("viewMode", validators = listOf(), choices = mapOf(
                "List" to tr("List"),
                "Grid" to tr("Grid"),
            )),
            ChoiceState("paginationMode", validators = listOf(), choices = mapOf(
                "Waterfall" to tr("Waterfall"),
                "Pagination" to tr("Pagination"),
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
