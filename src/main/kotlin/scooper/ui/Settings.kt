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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import scooper.data.PaginationMode
import scooper.data.Theme
import scooper.data.UIConfig
import scooper.data.ViewMode
import scooper.ui.components.*
import scooper.ui.icons.*
import scooper.util.*
import scooper.ui.components.SectionCard
import scooper.util.tr
import scooper.ui.theme.*
import scooper.util.form_builder.*
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.viewmodels.SettingsViewModel
import kotlin.math.roundToInt


val navItems =
    listOf(AppRoute.Settings.General, AppRoute.Settings.UI, AppRoute.Settings.About)

@Suppress("UNCHECKED_CAST")
@Composable
fun SettingScreen() {
    val colors = MaterialTheme.colors
    val navigator = LocalBackStack.current as BackStack<AppRoute>
    val currentRoute = navigator.current.value as AppRoute.Settings
    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
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

// SettingsCard has been unified into SectionCard in components/

@Composable
fun GeneralSettings(settingsViewModel: SettingsViewModel = koinInject()) {
    val colors = MaterialTheme.colors
    val formState = settingsViewModel.scoopFormState
    val proxyTypeState: ChoiceState = formState.getState("proxyType")
    val proxyState: TextFieldState = formState.getState("proxy")
    val ariaState: SwitchState = formState.getState("aria2Enabled")

    val formChangedState = rememberFormChanged(formState)
    val hasChanged by formChangedState.hasChanged

    // Localized dropdown labels: ChoiceState.choices in the ViewModel stores
    // raw msgids, so re-run tr() here; tr() reads Strings.current (a Compose
    // state), so the labels follow locale switches automatically.
    val proxyTypeChoices = mapOf(
        "default" to tr("Default"),
        "none" to tr("None"),
        "custom" to tr("Custom"),
    )

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
        SettingsTitle(tr("General Settings"), tr("Manage core configuration for Scoop."))

        SectionCard {
            PrefRow(
                tr("Proxy"),
                subtitle = tr("By default, Scoop will use the proxy settings from Internet Options, but with anonymous authentication."),
                nestedContent = {
                    if (proxyTypeState.value == "custom") {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            PrefTextField(
                                value = proxyState.value,
                                onValueChange = { proxyState.change(it) },
                                label = tr("Proxy Address:"),
                                placeholder = tr("[username:password@]host:port"),
                                isError = proxyState.hasError,
                                // errorMessage holds a raw msgid (see SettingsViewModel
                                // scoopFormState comment); translate it at render time so
                                // the message follows locale switches. The literal
                                // comparison keeps the msgid extractable by gettext
                                // (tr(proxyState.errorMessage) alone would not).
                                errorMessage = if (proxyState.errorMessage == "Invalid proxy address.") {
                                    tr("Invalid proxy address.")
                                } else {
                                    tr(proxyState.errorMessage)
                                }
                            )
                        }
                    }
                }
            ) {
                val choices = proxyTypeChoices
                ExposedDropdownMenu(
                    choices.values.toList(),
                    selected = choices[proxyTypeState.value]!!,
                    onItemSelected = { label ->
                        proxyTypeState.value = choices.filterValues { it == label }.keys.first()
                    })
            }
            Divider(color = colors.divider)
            PrefRow(
                title = tr("Enable Aria2"),
                subtitle = tr("Aria2c will be used for downloading of artifacts to speed up transfers."),
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

    // Reload latest config from DB when entering the screen
    LaunchedEffect(Unit) {
        settingsViewModel.reloadUIConfig()
    }

    val themeState: ChoiceState = formState.getState("theme")
    val viewModeState: ChoiceState = formState.getState("viewMode")
    val paginationModeState: ChoiceState = formState.getState("paginationMode")
    val refreshOnStartupState: SwitchState = formState.getState("refreshOnStartup")
    val periodicRefreshState: SwitchState = formState.getState("periodicRefreshEnabled")
    val intervalState: ChoiceState = formState.getState("autoRefreshIntervalMinutes")

    // Localized dropdown labels: ChoiceState.choices in the ViewModel stores
    // raw msgids, so re-run tr()/displayName() here; both read Strings.current
    // (a Compose state), so the labels follow locale switches automatically.
    val themeChoices = Theme.entries.associate { it.name to it.displayName() }
    val viewModeChoices = ViewMode.entries.associate { it.name to it.displayName() }
    val paginationModeChoices = PaginationMode.entries.associate { it.name to it.displayName() }
    val intervalChoices = mapOf(
        "30" to tr("{{n}} min", "n" to "30"),
        "60" to tr("{{n}} hour", "n" to "1"),
        "120" to tr("{{n}} hours", "n" to "2"),
        "240" to tr("{{n}} hours", "n" to "4"),
    )

    LaunchedEffect(themeState.value) {
        val theme = Theme.valueOf(themeState.value)
        settingsViewModel.switchTheme(theme)
    }

    LaunchedEffect(viewModeState.value) {
        val mode = ViewMode.valueOf(viewModeState.value)
        settingsViewModel.switchViewMode(mode)
    }

    LaunchedEffect(paginationModeState.value) {
        val mode = PaginationMode.valueOf(paginationModeState.value)
        settingsViewModel.switchPaginationMode(mode)
    }

    val showTrayIconState: SwitchState = formState.getState("showTrayIcon")

    val formChangedState = rememberFormChanged(formState)
    val formChanged by formChangedState.hasChanged

    val settingsState by settingsViewModel.container.stateFlow.collectAsState()

    // Font size scale — managed separately from form_builder
    var fontSizeScale by remember { mutableStateOf(settingsState.uiConfig.fontSizeScale) }
    LaunchedEffect(fontSizeScale) {
        settingsViewModel.switchFontSizeScale(fontSizeScale)
    }

    // Installed CJK font families for the "Interface Font" dropdown; enumerated
    // off the UI thread because it walks the whole system font manager.
    var cjkFonts by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            cjkFonts = listInstalledCjkFontFamilies()
        }
    }

    SettingContainer(onApply = {
        if (formState.validate()) {
            settingsViewModel.writeUIConfig(fontSizeScale = fontSizeScale)
            formChangedState.markSaved()
        }
    }, onDiscard = {
        formChangedState.discard()
    }, applyEnabled = formChanged) {
        SettingsTitle(tr("UI Settings"), tr("Customize the application appearance."))

        SectionCard {
            // Auto Refresh section
            var autoRefreshExpanded by remember { mutableStateOf(false) }
            val autoRefreshEnabled = refreshOnStartupState.value || periodicRefreshState.value

            // Sync expanded state after config is loaded from DB
            LaunchedEffect(autoRefreshEnabled) {
                if (autoRefreshEnabled) autoRefreshExpanded = true
            }

            val onMasterToggle: () -> Unit = {
                if (autoRefreshExpanded) {
                    // Collapse and disable both sub-options
                    refreshOnStartupState.value = false
                    periodicRefreshState.value = false
                    autoRefreshExpanded = false
                } else {
                    autoRefreshExpanded = true
                }
            }
            PrefRow(
                title = tr("Auto Refresh"),
                subtitle = tr("When enabled, Scooper will automatically run \"scoop update\" to check for updates."),
                modifier = Modifier.cursorHand(),
                onClick = onMasterToggle,
            ) {
                Switch(
                    autoRefreshExpanded,
                    onCheckedChange = { onMasterToggle() },
                    modifier = Modifier.cursorHand(),
                    colors = SwitchDefaults.colors(checkedThumbColor = colors.primary)
                )
            }
            if (autoRefreshExpanded) {
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    PrefRow(
                        title = tr("Refresh on Startup"),
                        subtitle = tr("Run \"scoop update\" once after startup."),
                        modifier = Modifier.cursorHand(),
                        onClick = { refreshOnStartupState.update(!refreshOnStartupState.value) },
                    ) {
                        Switch(
                            refreshOnStartupState.value,
                            onCheckedChange = { refreshOnStartupState.update(it) },
                            modifier = Modifier.cursorHand(),
                            colors = SwitchDefaults.colors(checkedThumbColor = colors.primary)
                        )
                    }
                    Divider(color = colors.divider)
                    PrefRow(
                        title = tr("Periodic Refresh"),
                        subtitle = tr("Periodically run \"scoop update\" at a fixed interval."),
                        modifier = Modifier.cursorHand(),
                        onClick = { periodicRefreshState.update(!periodicRefreshState.value) },
                    ) {
                        Switch(
                            periodicRefreshState.value,
                            onCheckedChange = { periodicRefreshState.update(it) },
                            modifier = Modifier.cursorHand(),
                            colors = SwitchDefaults.colors(checkedThumbColor = colors.primary)
                        )
                    }
                    if (periodicRefreshState.value) {
                        Divider(color = colors.divider)
                        PrefRow(
                            title = tr("Interval"),
                            subtitle = tr("How often to check for updates."),
                        ) {
                            val choices = intervalChoices
                            ExposedDropdownMenu(
                                choices.values.toList(),
                                selected = choices[intervalState.value]!!,
                                onItemSelected = { label ->
                                    intervalState.value = choices.filterValues { it == label }.keys.first()
                            })
                        }
                    }
                }
            }

            Divider(color = colors.divider)
            PrefRow(title = tr("Language"),
                subtitle = tr("Choose the display language."),
            ) {
                val locales = scooper.util.supportedLocales
                val currentLocaleTag = settingsState.uiConfig.locale
                val selectedLocale = locales.find { it.locale.toLanguageTag() == currentLocaleTag } ?: locales.first()
                ExposedDropdownMenu(
                    locales.map { it.displayName },
                    selected = selectedLocale.displayName,
                    onItemSelected = { label ->
                        val locale = locales.first { it.displayName == label }
                        settingsViewModel.switchLocale(locale.locale.toLanguageTag())
                    })
            }
            Divider(color = colors.divider)
            PrefRow(
                title = tr("Interface Font"),
                subtitle = tr("Choose the font used to render the interface. Changes take effect immediately."),
            ) {
                val defaultFontLabel = tr("Default")
                val currentFontFamily = settingsState.uiConfig.fontFamily
                val fontItems = listOf(defaultFontLabel) + cjkFonts
                // Widen the menu so the longest font name fits on a single line:
                // measured text width + DropdownMenuItem horizontal padding +
                // scrollbar reserve + a small buffer.
                val textMeasurer = rememberTextMeasurer()
                val itemTextStyle = MaterialTheme.typography.subtitle1
                val density = LocalDensity.current
                val fontMenuMinWidth = remember(fontItems, itemTextStyle, textMeasurer, density) {
                    with(density) {
                        fontItems.maxOf { textMeasurer.measure(it, itemTextStyle).size.width }.toDp() +
                                32.dp + 12.dp + 4.dp
                    }
                }
                // +1 accounts for the leading "Default" entry; 0 when not found.
                val selectedFontIndex = cjkFonts.indexOf(currentFontFamily) + 1
                ExposedDropdownMenu(
                    items = fontItems,
                    selected = currentFontFamily.ifBlank { defaultFontLabel },
                    onItemSelected = { label ->
                        settingsViewModel.switchFontFamily(if (label == defaultFontLabel) "" else label)
                    },
                    itemContent = { label ->
                        Text(
                            label,
                            color = colors.onSurface,
                            fontFamily = if (label == defaultFontLabel) null else fontFamilyForPreview(label),
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    menuMinWidth = fontMenuMinWidth,
                    showScrollbar = true,
                    selectedIndex = selectedFontIndex,
                )
            }
            Divider(color = colors.divider)
            PrefRow(
                title = tr("Font Size"),
                subtitle = tr("Adjust the application font size. Changes take effect immediately."),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Slider(
                        value = fontSizeScale,
                        onValueChange = { fontSizeScale = (it * 10).roundToInt() / 10f },
                        valueRange = 0.8f..1.5f,
                        modifier = Modifier.width(120.dp),
                        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                    )
                    Text(
                        String.format("%.1fx", fontSizeScale),
                        style = MaterialTheme.typography.body2,
                        color = colors.onSurface,
                        modifier = Modifier.width(36.dp),
                    )
                }
            }
            Divider(color = colors.divider)
            PrefRow(title = tr("Switch Theme")) {
                val choices = themeChoices
                ExposedDropdownMenu(
                    choices.values.toList(),
                    selected = choices[themeState.value]!!,
                    onItemSelected = { label ->
                        themeState.value = choices.filterValues { it == label }.keys.first()
                    })
            }
            Divider(color = colors.divider)
            PrefRow(title = tr("Default View Mode"),
                subtitle = tr("Choose how packages are displayed in the list."),
            ) {
                val choices = viewModeChoices
                ExposedDropdownMenu(
                    choices.values.toList(),
                    selected = choices[viewModeState.value]!!,
                    onItemSelected = { label ->
                        viewModeState.value = choices.filterValues { it == label }.keys.first()
                    })
            }
            Divider(color = colors.divider)
            PrefRow(title = tr("Default Pagination Mode"),
                subtitle = tr("Choose how packages are paginated."),
            ) {
                val choices = paginationModeChoices
                ExposedDropdownMenu(
                    choices.values.toList(),
                    selected = choices[paginationModeState.value]!!,
                    onItemSelected = { label ->
                        paginationModeState.value = choices.filterValues { it == label }.keys.first()
                    })
            }
            Divider(color = colors.divider)
            PrefRow(
                title = tr("System Tray Icon"),
                subtitle = tr("Keep a tray icon in the taskbar. Closing the window hides it to tray instead of exiting."),
                modifier = Modifier.cursorHand(),
                onClick = { showTrayIconState.update(!showTrayIconState.value) }
            ) {
                Switch(
                    showTrayIconState.value,
                    onCheckedChange = { showTrayIconState.update(it) },
                    modifier = Modifier.cursorHand(),
                    colors = SwitchDefaults.colors(checkedThumbColor = colors.primary)
                )
            }
            Divider(color = colors.divider)
            // FPS toggle (not saved to config)
            val showFpsState = LocalShowFps.current
            val showFps by showFpsState
            PrefRow(
                title = tr("Show FPS"),
                subtitle = tr("Display frame rate in the status bar. This setting is not persisted."),
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
        SettingsTitle(tr("About"), tr("Information about this application."))

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(
                    rememberPainterResource("logo.svg"),
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
                Text(tr("Version: {{version}}", "version" to BuildConfig.APP_VERSION), color = Slate700)
            }) {
                val url = "https://github.com/windedge/Scooper"
                TextButton(
                    onClick = { safeBrowse(url) },
                    modifier = Modifier.cursorHand()
                ) {
                    Text(tr("GitHub"), color = colors.primary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    Icon(Lucide.Github, "github", modifier = Modifier.size(16.dp), tint = colors.primary)
                }
            }
        }
    }
}
