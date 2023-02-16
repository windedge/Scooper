package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.data.ScoopConfig
import scooper.data.Theme
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.util.Scoop
import scooper.util.form_builder.*

data class SettingsState(
    val uiConfig: UIConfig = UIConfig(),
)

class SettingsViewModel : ContainerHost<SettingsState, SideEffect> {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    override val container: Container<SettingsState, SideEffect> = coroutineScope.container(
        SettingsState(
            uiConfig = ConfigRepository.getConfig()
        )
    ) {
        val scoopConfig = Scoop.readScoopConfig()
        scoopFormState.setData(scoopConfig)
        uiFormState.setData(it.uiConfig)
    }

    fun switchTheme(theme: Theme) = intent {
        reduce { state.copy(uiConfig = state.uiConfig.copy(theme = theme)) }
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
            SwitchState("aria2Enabled"),
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
        Scoop.writeScoopConfig(config)
    }

    fun writeUIConfig() {
        val config = uiFormState.getData(UIConfig::class)
        ConfigRepository.setConfig(config)
    }

}