package scooper.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import scooper.data.App
import scooper.data.AppStatus
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.onHover

private val UpdateGreen @Composable get() = colors.updateDefault
private val UninstallRed @Composable get() = colors.dangerDefault
private val InstallBlue @Composable get() = colors.primary

@Composable
fun ActionButton(
    app: App,
    installing: Boolean,
    waiting: Boolean,
    onInstall: (app: App, global: Boolean) -> Unit,
    onUpdate: (app: App) -> Unit,
    onDownload: (app: App) -> Unit,
    onUninstall: (app: App) -> Unit,
    onOpen: (app: App, shortcutIndex: Int) -> Unit,
    onCancel: (app: App?) -> Unit,
    onInstallVersion: (app: App) -> Unit = { },
) {
    val colors = MaterialTheme.colors
    var expand by remember { mutableStateOf(false) }

    val buttonHeight = 34.dp
    val shape = RoundedCornerShape(6.dp)

    Box {
        when {
            installing || waiting -> {
                var hovered by remember { mutableStateOf(false) }
                val showingCancel = installing || hovered
                Button(
                    onClick = { onCancel(app) },
                    modifier = Modifier.height(buttonHeight).width(120.dp).cursorLink().onHover { hovered = it },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (showingCancel) colors.dangerHover else UninstallRed
                    ),
                    shape = shape,
                    elevation = ButtonDefaults.elevation(defaultElevation = 1.dp),
                ) {
                    if (installing) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (showingCancel) "Cancel" else "Waiting",
                        color = Color.White,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        style = typography.body2,
                    )
                }
            }

            app.updatable -> {
                var mainHovered by remember { mutableStateOf(false) }
                var arrowHovered by remember { mutableStateOf(false) }
                Surface(
                    shape = shape,
                    color = UpdateGreen,
                    elevation = 1.dp,
                    modifier = Modifier.height(buttonHeight).width(120.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .onHover { mainHovered = it }
                                .background(if (mainHovered) colors.updateHover else Color.Transparent)
                                .cursorLink()
                                .clickable { onUpdate(app) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Update", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, style = typography.body2)
                        }
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.2f)))
                        Box(
                            modifier = Modifier.width(28.dp).fillMaxHeight()
                                .onHover { arrowHovered = it }
                                .background(if (arrowHovered) colors.updatePressed else colors.updateHover)
                                .cursorLink()
                                .clickable { expand = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                }
            }

            app.installed && app.hasShortcuts && !app.updatable -> {
                var mainHovered by remember { mutableStateOf(false) }
                var arrowHovered by remember { mutableStateOf(false) }
                Surface(
                    shape = shape,
                    color = Color.White,
                    border = BorderStroke(1.dp, colors.borderDefault),
                    elevation = 1.dp,
                    modifier = Modifier.height(buttonHeight).width(120.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .onHover { mainHovered = it }
                                .background(if (mainHovered) colors.backgroundHover else Color.Transparent)
                                .cursorLink()
                                .clickable { onOpen(app, 0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Open", color = colors.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, style = typography.body2)
                        }
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.borderDefault))
                        Box(
                            modifier = Modifier.width(28.dp).fillMaxHeight()
                                .onHover { arrowHovered = it }
                                .background(if (arrowHovered) colors.divider else colors.backgroundHover)
                                .cursorLink()
                                .clickable { expand = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = colors.textMuted)
                        }
                    }
                }
            }

            app.installed -> {
                var mainHovered by remember { mutableStateOf(false) }
                var arrowHovered by remember { mutableStateOf(false) }
                Surface(
                    shape = shape,
                    color = Color.White,
                    border = BorderStroke(1.dp, colors.borderDefault),
                    elevation = 1.dp,
                    modifier = Modifier.height(buttonHeight).width(120.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .onHover { mainHovered = it }
                                .background(if (mainHovered) colors.backgroundHover else Color.Transparent)
                                .cursorLink()
                                .clickable { onUninstall(app) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Uninstall", color = colors.sidebarTextMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, style = typography.body2)
                        }
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.borderDefault))
                        Box(
                            modifier = Modifier.width(28.dp).fillMaxHeight()
                                .onHover { arrowHovered = it }
                                .background(if (arrowHovered) colors.divider else colors.backgroundHover)
                                .cursorLink()
                                .clickable { expand = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = colors.textMuted)
                        }
                    }
                }
            }

            else -> {
                var mainHovered by remember { mutableStateOf(false) }
                var arrowHovered by remember { mutableStateOf(false) }
                Surface(
                    shape = shape,
                    color = InstallBlue,
                    elevation = 1.dp,
                    modifier = Modifier.height(buttonHeight).width(120.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .onHover { mainHovered = it }
                                .background(if (mainHovered) colors.primaryHover else Color.Transparent)
                                .cursorLink()
                                .clickable { onInstall(app, false) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Install", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, style = typography.body2)
                        }
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.2f)))
                        Box(
                            modifier = Modifier.width(28.dp).fillMaxHeight()
                                .onHover { arrowHovered = it }
                                .background(if (arrowHovered) colors.primaryPressed else colors.primaryHover)
                                .cursorLink()
                                .clickable { expand = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = expand,
            onDismissRequest = { expand = false },
            modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 0.dp).cursorHand(),
        ) {
            if (!app.installed) {
                if (app.status != AppStatus.FAILED) {
                    DropdownMenuItem(
                        onClick = { expand = false; onInstall(app, true) },
                        modifier = Modifier.sizeIn(maxHeight = 28.dp)
                    ) {
                        MenuText("Install Globally")
                    }
                    DropdownMenuItem(
                        onClick = { expand = false; onDownload(app) },
                        modifier = Modifier.sizeIn(maxHeight = 28.dp)
                    ) {
                        MenuText("Download Only")
                    }
                    Divider()
                    DropdownMenuItem(
                        onClick = { expand = false; onInstallVersion(app) },
                        modifier = Modifier.sizeIn(maxHeight = 28.dp)
                    ) {
                        MenuText("Install Version...")
                    }
                }
                if (app.status == AppStatus.FAILED) {
                    DropdownMenuItem(
                        onClick = { expand = false; onUninstall(app) },
                        modifier = Modifier.sizeIn(maxHeight = 28.dp)
                    ) {
                        MenuText("Uninstall")
                    }
                }
            }
            if (app.installed) {
                if (app.updatable && app.hasShortcuts) {
                    app.shortcuts!!.forEachIndexed { index, shortcut ->
                        DropdownMenuItem(
                            onClick = { expand = false; onOpen(app, index) },
                            modifier = Modifier.sizeIn(maxHeight = 28.dp)
                        ) {
                            MenuText("Open ${shortcut.path}")
                        }
                    }
                    Divider()
                }

                DropdownMenuItem(
                    onClick = { expand = false; onUninstall(app) },
                    modifier = Modifier.sizeIn(maxHeight = 28.dp)
                ) {
                    MenuText("Uninstall")
                }

                if (app.updatable) {
                    DropdownMenuItem(
                        onClick = { expand = false; onDownload(app) },
                        modifier = Modifier.sizeIn(maxHeight = 28.dp)
                    ) {
                        MenuText("Download Only")
                    }
                }

                Divider()
                DropdownMenuItem(
                    onClick = { expand = false; onInstallVersion(app) },
                    modifier = Modifier.sizeIn(maxHeight = 28.dp)
                ) {
                    MenuText("Install Version...")
                }
            }
        }
    }
}

@Composable
fun MenuText(text: String) {
    ProvideTextStyle(typography.subtitle2) {
        Text(
            text,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
