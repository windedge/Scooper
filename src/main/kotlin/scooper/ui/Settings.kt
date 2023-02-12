package scooper.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.*
import org.koin.java.KoinJavaComponent.get
import scooper.data.Theme
import scooper.ui.components.ExposedDropdownMenu
import scooper.ui.components.TextField
import scooper.util.*
import scooper.util.form_builder.*
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.viewmodels.SettingsViewModel


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
                currentRoute,
                onBack = { navigator.popUntil { routes -> routes.all { it.value !is AppRoute.Settings } } },
                onClick = { navigator.push(it) })
            Spacer(modifier = Modifier.width(10.dp))
            when (currentRoute) {
                AppRoute.Settings.General -> GeneralSettings()
                AppRoute.Settings.UI -> UISettings()
                // AppRoute.Settings.Cleanup -> TODO()
                // AppRoute.Settings.About -> TODO()
                else -> Text(currentRoute.menuText)
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
fun PrefTextField(
    value: String,
    onValueChange: (String) -> Unit = {},
    label: String? = null,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorMessage: String? = null,
) {

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(label)
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            isError = isError,
            modifier = modifier.then(Modifier.widthIn(min = 100.dp).fillMaxWidth(0.6f).height(30.dp)),
            singleLine = true,
            contentPadding = PaddingValues(5.dp),
        )
        if (isError && errorMessage != null) {
            Text(errorMessage, color = colors.error)
        }
    }
}


@Composable
fun PrefRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    nestedContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    PrefRow(
        title = { Text(title) },
        modifier = modifier,
        subtitle = subtitle?.let { { Text(subtitle) } },
        nestedContent = nestedContent,
        content = content
    )
}


@Composable
fun PrefRow(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    nestedContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.then(Modifier.fillMaxWidth().padding(10.dp))) {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.6f)) {
                ProvideTextStyle(MaterialTheme.typography.subtitle1) {
                    title()
                }

                ProvideTextStyle(MaterialTheme.typography.subtitle2.copy(color = colors.onSecondary)) {
                    subtitle?.invoke()
                }
            }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                content()
            }
        }
        if (nestedContent != null) {
            val padding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            Box(modifier = Modifier.paddingIfHeight(padding)) {
                ProvideTextStyle(MaterialTheme.typography.body1) {
                    nestedContent()
                }
            }
        }
    }
}


@Composable
fun SettingContainer(
    modifier: Modifier = Modifier,
    onApply: (() -> Unit)? = null,
    applyEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier.widthIn(max = 850.dp).fillMaxWidth()
                .heightIn(min = 300.dp)
                .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 10.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(modifier = Modifier.padding(top = 10.dp), elevation = 4.dp, shape = RoundedCornerShape(6.dp)) {
                content()
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = { onApply?.invoke() }, modifier = Modifier.cursorHand(), enabled = applyEnabled) {
                Text("Apply")
            }
        }

        VerticalScrollbar(
            rememberScrollbarAdapter(scrollState),
            modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd)
        )
    }
}

@Composable
fun NavBar(activeRoute: AppRoute.Settings, onBack: () -> Unit = {}, onClick: (AppRoute.Settings) -> Unit = {}) {
    Surface(modifier = Modifier.fillMaxHeight().width(IntrinsicSize.Max).requiredWidthIn(min = 150.dp)) {
        Column {
            IconButton(onClick = onBack, modifier = Modifier.padding(5.dp)) {
                Icon(
                    Icons.TwoTone.ArrowBack, "", Modifier.size(30.dp).cursorLink(), tint = colors.primary
                )
            }

            Spacer(modifier = Modifier.height(15.dp).fillMaxWidth())
            // Divider()

            navItems.forEach { route ->
                NavItem(route.menuText, selected = activeRoute == route, onClick = { onClick(route) })
            }
        }
    }
}

@Composable
fun NavItem(menu: String, modifier: Modifier = Modifier, selected: Boolean, onClick: () -> Unit = {}) {
    var default = Modifier.fillMaxWidth().height(50.dp).cursorHand().clickable(onClick = onClick)
    if (selected) {
        default = default.background(
            color = colors.primaryVariant,
            shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
        )
    }
    Box(
        modifier = modifier.then(default), contentAlignment = Alignment.CenterStart
    ) {
        Text(menu, modifier = Modifier.padding(start = 15.dp), style = MaterialTheme.typography.subtitle1)
    }
}
