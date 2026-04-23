package scooper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.typography

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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

    Column(
        modifier = modifier.fillMaxHeight().width(220.dp)
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
            onClick = { navigator.popupAllAndPush(AppRoute.Apps(scope = "")) },
        ) {
            Icon(painterResource("package-search.svg"), "Discover", modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
        SidebarNavItem(
            label = "Installed",
            badge = if (updateCount > 0) updateCount.toInt() else null,
            selected = currentRoute is AppRoute.Apps && (currentRoute.scope == "installed" || currentRoute.scope == "updates"),
            onClick = { navigator.popupAllAndPush(AppRoute.Apps(scope = "installed")) },
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
            onClick = { navigator.popupAllAndPush(AppRoute.Buckets) },
        ) {
            Icon(painterResource("component.svg"), "Buckets", modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.weight(1f))

        // Settings at bottom
        SidebarNavItem(
            label = "Settings",
            selected = currentRoute is AppRoute.Settings,
            onClick = { navigator.push(AppRoute.Settings.General) },
        ) {
            Icon(painterResource("sliders-horizontal.svg"), "Settings", modifier = Modifier.size(18.dp))
        }
    }
}

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
    onClick: () -> Unit = {},
    onBadgeClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colors
    var hovered by remember { mutableStateOf(false) }
    val bgColor = when {
        selected -> colors.sidebarSelectedBg
        hovered -> colors.sidebarHoverBg
        else -> Color.Transparent
    }
    val borderColor = when {
        selected -> colors.borderDefault
        hovered -> colors.borderHover
        else -> Color.Transparent
    }
    val textColor = when {
        selected -> colors.sidebarSelectedText
        hovered -> colors.textTitle
        else -> colors.sidebarTextMedium
    }
    val iconColor = when {
        selected -> colors.sidebarSelectedIcon
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
            .background(color = bgColor)
            .border(width = 1.dp, color = borderColor, RoundedCornerShape(8.dp))
            .onHover { hovered = it }
            .cursorHand()
            .clickable(onClick = onClick)
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
                        .then(if (onBadgeClick != null) Modifier.cursorHand().noRippleClickable { onBadgeClick() } else Modifier)
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
