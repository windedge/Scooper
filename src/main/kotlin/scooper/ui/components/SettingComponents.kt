package scooper.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import scooper.ui.AppRoute
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.onHover

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
    val colors = MaterialTheme.colors
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
            Text(errorMessage, color = MaterialTheme.colors.error)
        }
    }
}


@Composable
fun PrefRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    nestedContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    PrefRow(
        title = { Text(title) },
        modifier = modifier,
        subtitle = subtitle?.let { { Text(subtitle) } },
        nestedContent = nestedContent,
        onClick = onClick,
        content = content
    )
}


@Composable
fun PrefRow(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    nestedContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    val colors = MaterialTheme.colors
    val rowModifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier = modifier.then(Modifier.fillMaxWidth().padding(vertical = 16.dp))) {
        Row(
            modifier = rowModifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 24.dp)) {
                ProvideTextStyle(MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Medium)) {
                    title()
                }

                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    ProvideTextStyle(MaterialTheme.typography.body2.copy(color = colors.textBody)) {
                        subtitle()
                    }
                }
            }
            if (content != null) {
                Box(contentAlignment = Alignment.CenterEnd) {
                    this.content()
                }
            }
        }
        if (nestedContent != null) {
            Spacer(Modifier.height(12.dp))
            ProvideTextStyle(MaterialTheme.typography.body1) {
                nestedContent()
            }
        }
    }
}

@Composable
fun SettingContainer(
    modifier: Modifier = Modifier,
    onApply: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    applyEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val colors = MaterialTheme.colors
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start,
        ) {
            content()

            if (onApply != null) {
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onDiscard?.invoke() },
                        enabled = applyEnabled,
                        modifier = Modifier.height(36.dp).cursorHand(),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (applyEnabled) colors.borderDefault else colors.divider),
                        elevation = null,
                        colors = ButtonDefaults.outlinedButtonColors(
                            disabledContentColor = colors.textMuted,
                        )
                    ) {
                        Text("Discard", fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { onApply() },
                        enabled = applyEnabled,
                        modifier = Modifier.height(36.dp).cursorHand(),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (applyEnabled) colors.primary else colors.textMuted
                        ),
                        shape = RoundedCornerShape(6.dp),
                        elevation = null,
                    ) {
                        Text("Apply Changes", color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }

        VerticalScrollbar(
            rememberScrollbarAdapter(scrollState),
            modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd)
        )
    }
}

@Composable
fun NavBar(
    navItems: List<AppRoute.Settings>,
    activeRoute: AppRoute.Settings,
    onBack: () -> Unit = {},
    onClick: (AppRoute.Settings) -> Unit = {}
) {
    val colors = MaterialTheme.colors
    Column(
        modifier = Modifier.fillMaxHeight().width(240.dp)
            .background(colors.settingsSidebarBg)
            .border(width = 1.dp, color = colors.sidebarBorder)
    ) {
        // Back button area
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(40.dp).cursorHand(),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.sidebarTextMedium),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Back", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
            }
        }

        Divider(color = colors.divider)

        // Section label
        Text(
            "SETTINGS",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            style = MaterialTheme.typography.overline.copy(
                fontWeight = FontWeight.Bold,
                color = colors.textBody,
                letterSpacing = 1.5.sp,
            ),
        )

        // Nav items
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            navItems.forEachIndexed { index, route ->
                if (index > 0) {
                    Spacer(Modifier.height(8.dp))
                }
                SettingsNavItem(
                    route.menuText,
                    selected = activeRoute == route,
                    onClick = { onClick(route) }
                )
            }
        }
    }
}

@Composable
private fun SettingsNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colors
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        selected -> colors.sidebarSelectedBg
        hovered -> colors.borderHover // slate-300
        else -> Color.Transparent
    }
    val border = when {
        selected -> colors.borderDefault
        hovered -> colors.borderHover // slate-300
        else -> Color.Transparent
    }
    val color = when {
        selected -> colors.sidebarSelectedText
        hovered -> colors.textTitle
        else -> colors.sidebarTextMedium
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .cursorHand()
            .onHover { hovered = it }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            label,
            style = MaterialTheme.typography.body2.copy(
                fontWeight = FontWeight.Medium,
                color = color,
            ),
        )
    }
}
