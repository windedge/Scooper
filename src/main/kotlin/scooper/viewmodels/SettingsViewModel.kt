package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.data.ScoopConfig
import scooper.data.Theme
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.util.ScoopConfigManager
import scooper.util.form_builder.*

data class SettingsState(
    val uiConfig: UIConfig = UIConfig(),
)

class SettingsViewModel(
    private val configRepository: ConfigRepository,
) : ContainerHost<SettingsState, SettingsSideEffect>, AutoCloseable {

    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    override val container: Container<SettingsState, SettingsSideEffect> = coroutineScope.container(
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

    val scoopFormState = FormState(
        fields = listOf(
            ChoiceState(
                "proxyType", initial = "default", validators = listOf(Validators.ValidChoice()), choices = mapOf(
                    "default" to "Default",
                    "none" to "None",
                    "custom" to "Custom"
                )
            ),
            TextFieldState(
                "proxy",
                transform = {
                    transformProxy(it)
                },
                validators = listOf(
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
            ChoiceState("theme", validators = listOf(), choices = Theme.values().associate { it.name to it.name }),
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

    fun writeUIConfig() {
        val formConfig = uiFormState.getData(UIConfig::class)
        val currentConfig = container.stateFlow.value.uiConfig
        val config = currentConfig.copy(
            refreshOnStartup = formConfig.refreshOnStartup,
            theme = formConfig.theme,
        )
        configRepository.setConfig(config)
    }

    override fun close() {
        supervisorJob.cancel()
    }
}
