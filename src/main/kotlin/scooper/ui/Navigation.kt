package scooper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.twotone.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.koin.java.KoinJavaComponent.get
import scooper.ui.components.IconButton
import scooper.ui.components.Tooltip
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.util.noRippleClickable
import scooper.util.onHover
import scooper.viewmodels.AppsViewModel

@Suppress("UNCHECKED_CAST")
@Composable
fun NavHeader(show: Boolean = true, modifier: Modifier = Modifier) {
    if (!show) return

    val currentRoute = (LocalBackStack.current as BackStack<AppRoute>).current.value

    Surface(
        modifier = modifier.then(Modifier.fillMaxWidth().padding(bottom = 4.dp).height(65.dp)),
        elevation = 5.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom
            ) {
                Menu()

                Row(
                    modifier = Modifier.height(35.dp).padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchBar(currentRoute is AppRoute.Apps)
                    RefreshScoopButton()
                    MoreActionsButton()
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
fun MoreActionsButton() {
    val navigator = LocalBackStack.current as BackStack<AppRoute>
    var open by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }, Modifier.cursorLink(), rippleRadius = 20.dp) {
        Icon(painterResource("more.svg"), "More Actions", modifier = Modifier.height(30.dp), tint = colors.primary)

        // val menuTextStyle = LocalTextStyle.current.copy(fontSize = 10.sp)
        // CompositionLocalProvider(LocalTextStyle provides menuTextStyle) {
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                modifier = Modifier.height(30.dp).cursorHand(),
                onClick = { navigator.push(AppRoute.Settings) }) {
                Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Settings, "Settings", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Settings")
                }
            }
        }
        // }
    }
}

@Composable
fun RefreshScoopButton() {
    val appsViewModel: AppsViewModel = get(AppsViewModel::class.java)
    val state by appsViewModel.container.stateFlow.collectAsState()
    Box(modifier = Modifier.width(30.dp), contentAlignment = Alignment.Center) {
        if (state.refreshing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Tooltip("Refreshing Scoop") {
                IconButton(
                    onClick = { appsViewModel.queuedUpdateApps() },
                    modifier = Modifier.cursorLink(),
                    rippleRadius = 20.dp
                ) {
                    Icon(painterResource("sync.svg"), null, modifier = Modifier.height(30.dp), tint = colors.primary)
                }
            }

        }
    }
}


@Suppress("UNCHECKED_CAST")
@Composable
fun Menu(modifier: Modifier = Modifier) {
    val appsViewModel: AppsViewModel = get(AppsViewModel::class.java)
    val navigator = LocalBackStack.current as BackStack<AppRoute>

    Row(
        modifier = modifier.then(Modifier.fillMaxHeight()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        remember { AppRoute.Apps("") }.MenuItem("All") {
            appsViewModel.resetFilter()
            navigator.popupAllAndPush(AppRoute.Apps(scope = ""))
        }

        remember { AppRoute.Apps("installed") }.MenuItem("Installed") {
            appsViewModel.resetFilter()
            navigator.popupAllAndPush(AppRoute.Apps(scope = "installed"))
        }

        remember { AppRoute.Apps("updates") }.MenuItem("Updates") {
            appsViewModel.resetFilter()
            navigator.popupAllAndPush(AppRoute.Apps(scope = "updates"))
        }

        Divider(
            modifier = Modifier.height(30.dp).width(1.2.dp).offset(y = (-2).dp)
        )

        remember { AppRoute.Buckets }.MenuItem("Buckets") {
            navigator.popupAllAndPush(AppRoute.Buckets)
        }
    }
}


@Suppress("UNCHECKED_CAST")
@Composable
fun AppRoute.MenuItem(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 5.dp, vertical = 8.dp),
    onClick: () -> Unit = {}
) {
    val currentRoute = (LocalBackStack.current as BackStack<AppRoute>).current.value
    val highlight = currentRoute == this
    val default = Modifier.clip(shape = RoundedCornerShape(5.dp)).width(90.dp)
    Box(
        modifier = modifier
            .then(default)
            .background(color = if (highlight) colors.primaryVariant else Color.Unspecified)
            .cursorHand()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {

        var style = MaterialTheme.typography.subtitle1
        val scale = 1.0
        if (highlight) {
            style = style.copy(fontSize = style.fontSize * scale)
        }

        Text(text, modifier = Modifier.padding(contentPadding), style = style)
    }
}

/*
@Composable
fun SideBar(navigator: BackStack<AppRoute>) {
    val appsViewModel: AppsViewModel = get(AppsViewModel::class.java)
    Surface(
        Modifier.fillMaxHeight().width(180.dp),
        elevation = 1.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.defaultMinSize(10.dp).padding(vertical = 1.dp)
        ) {
            val selectedItem = remember { mutableStateOf("Apps") }
            val route = navigator.current.value
            NavItem(
                "Apps",
                icon = Icons.TwoTone.Home,
                selectItem = selectedItem,
                selected = route is AppRoute.Apps && route.scope == "",
                onClick = {
                    appsViewModel.resetFilter()
                    navigator.popupAllAndPush(AppRoute.Apps(scope = ""))
                }
            )
            NavItem(
                "Installed",
                indent = 40,
                icon = Icons.TwoTone.KeyboardArrowRight,
                selectItem = selectedItem,
                selected = route is AppRoute.Apps && route.scope == "installed",
                onClick = {
                    appsViewModel.resetFilter()
                    navigator.popupAllAndPush(AppRoute.Apps(scope = "installed"))
                },
            )
            NavItem(
                "Updates",
                indent = 40,
                icon = Icons.TwoTone.KeyboardArrowRight,
                selectItem = selectedItem,
                selected = route is AppRoute.Apps && route.scope == "updates",
                onClick = {
                    appsViewModel.resetFilter()
                    navigator.popupAllAndPush(AppRoute.Apps(scope = "updates"))
                },
            )
            Divider(Modifier.height(1.dp))
            NavItem(
                "Buckets",
                icon = Icons.TwoTone.List,
                selectItem = selectedItem,
                selected = route == AppRoute.Buckets,
                onClick = { navigator.popupAllAndPush(AppRoute.Buckets) }
            )
            Divider(Modifier.height(1.dp))
            NavItem(
                "Settings",
                icon = Icons.TwoTone.Settings,
                selectItem = selectedItem,
                selected = route == AppRoute.Settings,
                onClick = { navigator.popupAllAndPush(AppRoute.Settings) }
            )
            Divider(Modifier.height(1.dp))
        }
    }
}
*/

@Composable
fun NavItem(
    text: String = "",
    modifier: Modifier = Modifier,
    selectItem: MutableState<String>,
    selected: Boolean = false,
    indent: Int = 30,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    var hover by remember { mutableStateOf(false) }
    val highlight = hover || selected
    var default = Modifier
        .fillMaxWidth()
        .padding(2.dp)
        .height(35.dp)
        .cursorHand()
        .background(color = if (highlight) colors.primary else Color.Unspecified)
    if (onClick != null) {
        default = default.onHover { hover = it }.noRippleClickable {
            onClick.invoke()
            selectItem.value = text
        }
    }

    val combined = modifier.then(default)
    Row(
        combined,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(indent.dp))

        val color = if (highlight) colors.onPrimary else colors.onSurface
        if (icon != null) {
            Icon(
                icon,
                "",
                modifier = Modifier.size(20.dp),
                tint = if (highlight) colors.onPrimary else colors.onSecondary
            )
        }
        Text(text, color = color)
    }
}
