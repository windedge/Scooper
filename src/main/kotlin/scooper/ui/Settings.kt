package scooper.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.*
import org.koin.java.KoinJavaComponent.get
import scooper.data.Theme
import scooper.ui.components.*
import scooper.util.*
import scooper.util.form_builder.*
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.viewmodels.SettingsViewModel
import java.awt.Desktop
import java.net.URI


val navItems =
    listOf(AppRoute.Settings.General, AppRoute.Settings.UI, AppRoute.Settings.Cleanup, AppRoute.Settings.About)

@Suppress("UNCHECKED_CAST")
@Composable
fun SettingScreen() {
    val navigator = LocalBackStack.current as BackStack<AppRoute>
    val currentRoute = navigator.current.value as AppRoute.Settings
    Surface(modifier = Modifier.fillMaxSize()) {
        Row {
            NavBar(
                navItems,
                currentRoute,
                onBack = { navigator.popUntil { routes -> routes.all { it.value !is AppRoute.Settings } } },
                onClick = { navigator.push(it) },
            )
            Spacer(modifier = Modifier.width(10.dp))
            when (currentRoute) {
                AppRoute.Settings.General -> GeneralSettings()
                AppRoute.Settings.UI -> UISettings()
                AppRoute.Settings.Cleanup -> CleanupContainer()
                AppRoute.Settings.About -> AboutSection()
            }
        }
    }
}


@Suppress("NOTHING_TO_INLINE")
@Composable
inline fun rememberFormChanged(
    formState: FormState<out BaseState<out Any>>,
    restoreOnDispose: Boolean = true
): MutableState<Boolean> {
    val allStates = formState.fields.map { it.value }.toTypedArray()
    val hasChanged = remember { mutableStateOf(false) }
    LaunchedEffect(*allStates) { hasChanged.value = true }
    LaunchedEffect(Unit) { hasChanged.value = false }

    LaunchedEffect(hasChanged.value) {
        if (!hasChanged.value && restoreOnDispose) {
            formState.takeSnapshot()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (hasChanged.value && restoreOnDispose) {
                formState.restoreSnapshot()
                hasChanged.value = false
            }
            formState.hideErrors()
        }
    }

    return hasChanged
}

@Composable
fun GeneralSettings(settingsViewModel: SettingsViewModel = get(SettingsViewModel::class.java)) {
    val formState = settingsViewModel.scoopFormState
    val proxyTypeState: ChoiceState = formState.getState("proxyType")
    val proxyState: TextFieldState = formState.getState("proxy")
    val ariaState: SwitchState = formState.getState("aria2Enabled")

    var hasChanged by rememberFormChanged(formState)
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
            hasChanged = false
        }
    }, applyEnabled = hasChanged) {
        Column {
            PrefRow(
                "Proxy",
                subtitle = "By default, Scoop will use the proxy settings from Internet Options, but with anonymous authentication.",
                nestedContent = {
                    if (proxyTypeState.value == "custom") {
                        Column {
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
            Divider()
            PrefRow(
                title = "Enable Aria2",
                subtitle = "Aria2c will be used for downloading of artifacts.",
                modifier = Modifier.cursorHand().toggleable(ariaState.value, onValueChange = {
                    ariaState.update(it)
                })
            ) {
                Switch(
                    ariaState.value,
                    onCheckedChange = {
                        ariaState.update(it)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = colors.primary)
                )
            }
        }
    }
}

@Composable
fun UISettings(settingsViewModel: SettingsViewModel = get(SettingsViewModel::class.java)) {
    val formState = settingsViewModel.uiFormState
    val refreshState: SwitchState = formState.getState("refreshOnStartup")
    val themeState: ChoiceState = formState.getState("theme")

    LaunchedEffect(themeState.value) {
        val theme = Theme.valueOf(themeState.value)
        settingsViewModel.switchTheme(theme)
    }

    var formChanged by rememberFormChanged(formState)
    SettingContainer(onApply = {
        if (formState.validate()) {
            settingsViewModel.writeUIConfig()
            formChanged = false
        }
    }, applyEnabled = formChanged) {
        Column {
            PrefRow("Reload apps after startup",
                subtitle = "Run \"scoop update\" after startup.",
                modifier = Modifier.cursorHand()
                    .toggleable(refreshState.value) {
                        refreshState.value = !refreshState.value
                    }) {
                Switch(
                    refreshState.value,
                    onCheckedChange = { refreshState.update(it) },
                    modifier = Modifier.cursorHand(),
                    colors = SwitchDefaults.colors(checkedThumbColor = colors.primary)
                )
            }
            Divider()
            PrefRow(title = "Switch Theme") {
                val choices = themeState.choices
                ExposedDropdownMenu(
                    choices.values.toList(),
                    selected = choices[themeState.value]!!,
                    onItemSelected = { label ->
                        themeState.value = choices.filterValues { it == label }.keys.first()
                    })
            }
        }
    }
}

@Composable
fun AboutSection() {
    SettingContainer {
        Column {
            PrefRow(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource("logo.svg"),
                        contentDescription = "logo",
                        modifier = Modifier.size(30.dp),
                        tint = colors.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        BuildConfig.APP_NAME,
                        modifier = Modifier,
                        style = MaterialTheme.typography.h6
                    )
                }
            })
            Divider()
            PrefRow(title = {
                Text("Version: %s".format(BuildConfig.APP_VERSION))
            }) {
                val url = "https://github.com/windedge/Scooper"
                IconButton(
                    onClick = { Desktop.getDesktop().browse(URI.create(url)) },
                    modifier = Modifier.cursorHand()
                ) {
                    Tooltip(url) { Icon(painterResource("github-fill.svg"), "github") }
                }
            }

        }
    }
}
