package scooper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.*
import org.koin.compose.koinInject
import scooper.data.Theme
import scooper.ui.components.*
import scooper.util.*
import scooper.ui.theme.*
import scooper.util.form_builder.*
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.viewmodels.SettingsViewModel


val navItems =
    listOf(AppRoute.Settings.General, AppRoute.Settings.UI, AppRoute.Settings.Cleanup, AppRoute.Settings.About)

@Suppress("UNCHECKED_CAST")
@Composable
fun SettingScreen() {
    val colors = MaterialTheme.colors
    val navigator = LocalBackStack.current as BackStack<AppRoute>
    val currentRoute = navigator.current.value as AppRoute.Settings
    Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
        Row {
            NavBar(
                navItems,
                currentRoute,
                onBack = { navigator.popUntil { routes -> routes.all { it.value !is AppRoute.Settings } } },
                onClick = { navigator.push(it) },
            )
            when (currentRoute) {
                AppRoute.Settings.General -> GeneralSettings()
                AppRoute.Settings.UI -> UISettings()
                AppRoute.Settings.Cleanup -> CleanupContainer()
                AppRoute.Settings.About -> AboutSection()
            }
        }
    }
}


data class FormChangedState(
    val hasChanged: MutableState<Boolean>,
    val discard: () -> Unit,
    val markSaved: () -> Unit,
)

@Composable
fun rememberFormChanged(
    formState: FormState<out BaseState<out Any>>,
    restoreOnDispose: Boolean = true,
): FormChangedState {
    val hasChanged = remember { mutableStateOf(false) }

    // Snapshot of the actually-saved config, only updated via markSaved()
    val savedValues = remember {
        mutableStateOf(formState.fields.associate { it.name to it.value })
    }

    // Compare current form values against saved config on every change
    val allValues = formState.fields.map { it.value }.toTypedArray()
    LaunchedEffect(*allValues) {
        hasChanged.value = formState.fields.any { field ->
            savedValues.value[field.name] != field.value
        }
    }
    LaunchedEffect(Unit) { hasChanged.value = false }

    DisposableEffect(Unit) {
        onDispose {
            if (hasChanged.value && restoreOnDispose) {
                savedValues.value.forEach { (name, value) ->
                    formState.getState<BaseState<Any>>(name).value = value
                }
                hasChanged.value = false
            }
            formState.hideErrors()
        }
    }

    return FormChangedState(
        hasChanged = hasChanged,
        discard = {
            savedValues.value.forEach { (name, value) ->
                formState.getState<BaseState<Any>>(name).value = value
            }
            hasChanged.value = false
        },
        markSaved = {
            savedValues.value = formState.fields.associate { it.name to it.value }
            hasChanged.value = false
        },
    )
}

@Composable
fun SettingsTitle(title: String, subtitle: String) {
    val colors = MaterialTheme.colors
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.h5.copy(
                fontWeight = FontWeight.Bold,
                color = colors.textTitle
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.body2.copy(color = colors.textBody)
        )
    }
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.borderDefault),
        elevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            content()
        }
    }
}

@Composable
fun GeneralSettings(settingsViewModel: SettingsViewModel = koinInject()) {
    val colors = MaterialTheme.colors
    val formState = settingsViewModel.scoopFormState
    val proxyTypeState: ChoiceState = formState.getState("proxyType")
    val proxyState: TextFieldState = formState.getState("proxy")
    val ariaState: SwitchState = formState.getState("aria2Enabled")

    val formChangedState = rememberFormChanged(formState)
    val hasChanged by formChangedState.hasChanged
    LaunchedEffect(proxyTypeState.value) {
        proxyState.value = when (proxyTypeState.value) {
            "default" -> ""
            "none" -> ""
            else -> proxyState.value
        }
    }

    SettingContainer(onApply = {
        if (formState.validate()) {
            settingsViewModel.writeScoopConfig()
            formChangedState.markSaved()
        }
    }, onDiscard = {
        formChangedState.discard()
    }, applyEnabled = hasChanged) {
        SettingsTitle("General Settings", "Manage core configuration for Scoop.")

        SettingsCard {
            PrefRow(
                "Proxy",
                subtitle = "By default, Scoop will use the proxy settings from Internet Options, but with anonymous authentication.",
                nestedContent = {
                    if (proxyTypeState.value == "custom") {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            PrefTextField(
                                value = proxyState.value,
                                onValueChange = { proxyState.change(it) },
                                label = "Proxy Address:",
                                placeholder = "[username:password@]host:port",
                                isError = proxyState.hasError,
                                errorMessage = proxyState.errorMessage
                            )
                        }
                    }
                }
            ) {
                val choices = proxyTypeState.choices
                ExposedDropdownMenu(
                    choices.values.toList(),
                    selected = choices[proxyTypeState.value]!!,
                    onItemSelected = { label ->
                        proxyTypeState.value = choices.filterValues { it == label }.keys.first()
                    })
            }
            Divider(color = colors.divider)
            PrefRow(
                title = "Enable Aria2",
                subtitle = "Aria2c will be used for downloading of artifacts to speed up transfers.",
                modifier = Modifier.cursorHand(),
                onClick = { ariaState.update(!ariaState.value) }
            ) {
                Switch(
                    ariaState.value,
                    onCheckedChange = { ariaState.update(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = colors.primary)
                )
            }
        }
    }
}

@Composable
fun UISettings(settingsViewModel: SettingsViewModel = koinInject()) {
    val colors = MaterialTheme.colors
    val formState = settingsViewModel.uiFormState
    val refreshState: SwitchState = formState.getState("refreshOnStartup")
    val themeState: ChoiceState = formState.getState("theme")

    LaunchedEffect(themeState.value) {
        val theme = Theme.valueOf(themeState.value)
        settingsViewModel.switchTheme(theme)
    }

    val formChangedState = rememberFormChanged(formState)
    val formChanged by formChangedState.hasChanged
    SettingContainer(onApply = {
        if (formState.validate()) {
            settingsViewModel.writeUIConfig()
            formChangedState.markSaved()
        }
    }, onDiscard = {
        formChangedState.discard()
    }, applyEnabled = formChanged) {
        SettingsTitle("UI Settings", "Customize the application appearance.")

        SettingsCard {
            PrefRow("Reload apps after startup",
                subtitle = "Run \"scoop update\" after startup.",
                modifier = Modifier.cursorHand(),
                onClick = { refreshState.update(!refreshState.value) }
            ) {
                Switch(
                    refreshState.value,
                    onCheckedChange = { refreshState.update(it) },
                    modifier = Modifier.cursorHand(),
                    colors = SwitchDefaults.colors(checkedThumbColor = colors.primary)
                )
            }
            Divider(color = colors.divider)
            PrefRow(title = "Switch Theme") {
                val choices = themeState.choices
                ExposedDropdownMenu(
                    choices.values.toList(),
                    selected = choices[themeState.value]!!,
                    onItemSelected = { label ->
                        themeState.value = choices.filterValues { it == label }.keys.first()
                    })
            }
            Divider(color = colors.divider)
            // FPS toggle (not saved to config)
            val showFpsState = LocalShowFps.current
            val showFps by showFpsState
            PrefRow(
                title = "Show FPS",
                subtitle = "Display frame rate in the status bar. This setting is not persisted.",
                modifier = Modifier.cursorHand(),
                onClick = { showFpsState.value = !showFpsState.value }
            ) {
                Switch(
                    showFps,
                    onCheckedChange = { showFpsState.value = it },
                    modifier = Modifier.cursorHand(),
                    colors = SwitchDefaults.colors(checkedThumbColor = colors.primary)
                )
            }
        }
    }
}

@Composable
fun AboutSection() {
    val colors = MaterialTheme.colors
    SettingContainer {
        SettingsTitle("About", "Information about this application.")

        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(
                    painterResource("logo.svg"),
                    contentDescription = "logo",
                    modifier = Modifier.size(36.dp),
                    tint = colors.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    BuildConfig.APP_NAME,
                    style = MaterialTheme.typography.h6.copy(fontWeight = FontWeight.Bold, color = colors.textTitle)
                )
            }
            Divider(color = colors.divider)
            PrefRow(title = {
                Text("Version: ${BuildConfig.APP_VERSION}", color = Slate700)
            }) {
                val url = "https://github.com/windedge/Scooper"
                TextButton(
                    onClick = { safeBrowse(url) },
                    modifier = Modifier.cursorHand()
                ) {
                    Text("GitHub", color = colors.primary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource("github-fill.svg"), "github", modifier = Modifier.size(16.dp), tint = colors.primary)
                }
            }
        }
    }
}
