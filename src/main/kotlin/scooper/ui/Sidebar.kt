package scooper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.typography

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import scooper.util.cursorHand
import scooper.util.noRippleClickable
import scooper.util.onHover
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.ui.theme.*

@Suppress("UNCHECKED_CAST")
@Composable
fun SidebarNav(
    updateCount: Long,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colors
    val navigator = LocalBackStack.current as BackStack<AppRoute>
    val currentRoute = navigator.current.value

    val focusRequesters = remember { List(5) { FocusRequester() } }
    var focusedIndex by remember { mutableStateOf(-1) }

    // Sidebar items in visual order
    val items = remember(updateCount) {
        listOf(
            SidebarItem("Discover") { navigator.popupAllAndPush(AppRoute.Apps(scope = "")) },
            SidebarItem("Installed", badge = if (updateCount > 0) updateCount.toInt() else null,
                onBadgeClick = { navigator.popupAllAndPush(AppRoute.Apps(scope = "updates")) }) {
                navigator.popupAllAndPush(AppRoute.Apps(scope = "installed"))
            },
            SidebarItem("Buckets") { navigator.popupAllAndPush(AppRoute.Buckets) },
            SidebarItem("Cleanup") { navigator.popupAllAndPush(AppRoute.Cleanup) },
            SidebarItem("Settings") { navigator.push(AppRoute.Settings.General) },
        )
    }

    // Sync focusedIndex with current route when navigating by mouse
    val selectedIndex = remember(currentRoute) {
        when (currentRoute) {
            is AppRoute.Apps -> if (currentRoute.scope.isEmpty()) 0
                else if (currentRoute.scope == "installed" || currentRoute.scope == "updates") 1
                else -1
            is AppRoute.Buckets -> 2
            is AppRoute.Cleanup -> 3
            is AppRoute.Settings -> 4
            else -> -1
        }
    }

    // After switching tabs, put focus back on the selected sidebar item.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in focusRequesters.indices) {
            focusRequesters[selectedIndex].requestFocus()
        }
    }

    Column(
        modifier = modifier.fillMaxHeight().width(220.dp)
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || focusedIndex !in 0..4) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        val next = if (focusedIndex <= 0) 4 else focusedIndex - 1
                        focusRequesters[next].requestFocus()
                        true
                    }
                    Key.DirectionDown -> {
                        val next = if (focusedIndex >= 4) 0 else focusedIndex + 1
                        focusRequesters[next].requestFocus()
                        true
                    }
                    Key.Enter -> {
                        items[focusedIndex].action()
                        true
                    }
                    Key.Escape -> {
                        focusedIndex = -1
                        false
                    }
                    else -> false
                }
            }
            .background(colors.sidebarBackground)
            .border(width = 1.dp, color = colors.sidebarBorder)
            .padding(vertical = 8.dp),
    ) {
        // Logo
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource("logo.svg"),
                    contentDescription = "Scooper",
                    modifier = Modifier.size(18.dp),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "Scooper",
                style = typography.h6.copy(
                    fontWeight = FontWeight.Bold,
                    color = colors.textTitle,
                ),
            )
        }

        Spacer(Modifier.height(8.dp))

        // LIBRARY section
        SectionHeader("LIBRARY")
        SidebarNavItem(
            label = "Discover",
            selected = currentRoute is AppRoute.Apps && currentRoute.scope.isEmpty(),
            focused = focusedIndex == 0,
            focusRequester = focusRequesters[0],
            onFocused = { focusedIndex = 0 },
            onClick = { items[0].action() },
        ) {
            Icon(painterResource("package-search.svg"), "Discover", modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
        SidebarNavItem(
            label = "Installed",
            badge = if (updateCount > 0) updateCount.toInt() else null,
            selected = currentRoute is AppRoute.Apps && (currentRoute.scope == "installed" || currentRoute.scope == "updates"),
            focused = focusedIndex == 1,
            focusRequester = focusRequesters[1],
            onFocused = { focusedIndex = 1 },
            onClick = { items[1].action() },
            onBadgeClick = { navigator.popupAllAndPush(AppRoute.Apps(scope = "updates")) },
        ) {
            Icon(painterResource("package-check.svg"), "Installed", modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.height(16.dp))

        // Sources section
        SectionHeader("SOURCES")
        SidebarNavItem(
            label = "Buckets",
            selected = currentRoute is AppRoute.Buckets,
            focused = focusedIndex == 2,
            focusRequester = focusRequesters[2],
            onFocused = { focusedIndex = 2 },
            onClick = { items[2].action() },
        ) {
            Icon(painterResource("component.svg"), "Buckets", modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.height(16.dp))

        // Tools section
        SectionHeader("TOOLS")
        SidebarNavItem(
            label = "Cleanup",
            selected = currentRoute is AppRoute.Cleanup,
            focused = focusedIndex == 3,
            focusRequester = focusRequesters[3],
            onFocused = { focusedIndex = 3 },
            onClick = { items[3].action() },
        ) {
            Icon(painterResource("brush-cleaning.svg"), "Cleanup", modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.weight(1f))

        // Settings at bottom
        SidebarNavItem(
            label = "Settings",
            selected = currentRoute is AppRoute.Settings,
            focused = focusedIndex == 4,
            focusRequester = focusRequesters[4],
            onFocused = { focusedIndex = 4 },
            onClick = { items[4].action() },
        ) {
            Icon(painterResource("settings-2.svg"), "Settings", modifier = Modifier.size(18.dp))
        }
    }
}

private data class SidebarItem(
    val label: String,
    val badge: Int? = null,
    val onBadgeClick: (() -> Unit)? = null,
    val action: () -> Unit,
)

@Composable
private fun SectionHeader(text: String) {
    val colors = MaterialTheme.colors
    Text(
        text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        style = typography.caption.copy(
            color = colors.sidebarTextLight,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
        ),
    )
}

@Composable
private fun SidebarNavItem(
    label: String,
    modifier: Modifier = Modifier,
    badge: Int? = null,
    selected: Boolean = false,
    focused: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    onBadgeClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colors
    var hovered by remember { mutableStateOf(false) }
    val bgColor = when {
        selected -> colors.sidebarSelectedBg
        focused -> colors.sidebarHoverBg
        hovered -> colors.sidebarHoverBg
        else -> Color.Transparent
    }
    val borderColor = when {
        selected -> colors.borderDefault
        focused -> colors.borderDefault.copy(alpha = 0.5f)
        hovered -> colors.borderHover
        else -> Color.Transparent
    }
    val textColor = when {
        selected -> colors.sidebarSelectedText
        focused -> colors.textTitle
        hovered -> colors.textTitle
        else -> colors.sidebarTextMedium
    }
    val iconColor = when {
        selected -> colors.sidebarSelectedIcon
        focused -> colors.sidebarTextMedium
        hovered -> colors.sidebarTextMedium
        else -> colors.sidebarTextLight
    }

    Box(
        modifier = modifier.fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 12.dp)
            .then(
                if (selected) Modifier.shadow(2.dp, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) onFocused() }
                        .focusable()
                } else {
                    Modifier
                }
            )
            .background(color = bgColor)
            .border(width = 1.dp, color = borderColor, RoundedCornerShape(8.dp))
            .onHover { hovered = it }
            .cursorHand()
            .pointerInput(Unit) {
                detectTapGestures {
                    focusRequester?.requestFocus()
                    onClick()
                }
            }
            .padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            CompositionLocalProvider(LocalContentColor provides iconColor) {
                icon()
            }
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = typography.body2.copy(
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                ),
            )
            if (badge != null) {
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .then(if (onBadgeClick != null) Modifier.cursorHand().pointerInput(Unit) {
                            detectTapGestures { onBadgeClick() }
                        } else Modifier)
                ) {
                    Badge(
                        backgroundColor = if (selected) colors.sidebarBadgeBg else colors.borderDefault,
                        contentColor = if (selected) colors.sidebarBadgeText else colors.sidebarTextMedium,
                    ) {
                        Text(
                            "$badge",
                            style = typography.overline,
                        )
                    }
                }
            }
        }
    }
}
