package scooper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.KeyboardArrowRight
import androidx.compose.material.icons.twotone.List
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import scooper.AppRoute
import scooper.util.navigation.core.BackStack
import scooper.util.cursorHand
import scooper.util.onHover

@Composable
fun SideBar(navigator: BackStack<AppRoute>) {
    Surface(
        Modifier.fillMaxHeight().width(180.dp),
        elevation = 3.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.defaultMinSize(10.dp).padding(vertical = 2.dp)
        ) {
            val selectedItem = remember { mutableStateOf("Apps") }
            val route = navigator.current.value
            NavItem(
                "Apps",
                icon = Icons.TwoTone.Home,
                selectItem = selectedItem,
                selected = route is AppRoute.Apps && route.scope == "",
                onClick = { navigator.replace(AppRoute.Apps(scope = "")) }
            )
            NavItem(
                "Installed",
                indent = 40,
                icon = Icons.TwoTone.KeyboardArrowRight,
                selectItem = selectedItem,
                selected = route is AppRoute.Apps && route.scope == "installed",
                onClick = { navigator.replace(AppRoute.Apps(scope = "installed")) },
            )
            NavItem(
                "Updates",
                indent = 40,
                icon = Icons.TwoTone.KeyboardArrowRight,
                selectItem = selectedItem,
                selected = route is AppRoute.Apps && route.scope == "updates",
                onClick = { navigator.replace(AppRoute.Apps(scope = "updates")) },
            )
            Divider(Modifier.height(1.dp))
            NavItem(
                "Buckets",
                icon = Icons.TwoTone.List,
                selectItem = selectedItem,
                selected = route == AppRoute.Buckets,
                onClick = { navigator.replace(AppRoute.Buckets) }
            )
            Divider(Modifier.height(1.dp))
            NavItem(
                "Settings",
                icon = Icons.TwoTone.Settings,
                selectItem = selectedItem,
                selected = route == AppRoute.Settings,
                onClick = { navigator.replace(AppRoute.Settings) }
            )
            Divider(Modifier.height(1.dp))
        }
    }
}

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
        default = default.onHover { hover = it }.clickable {
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
