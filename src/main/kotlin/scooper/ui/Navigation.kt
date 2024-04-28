package scooper.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.twotone.Clear
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.java.KoinJavaComponent.get
import scooper.taskqueue.Task
import scooper.taskqueue.TaskQueue
import scooper.taskqueue.toTitle
import scooper.ui.components.IconButton
import scooper.ui.components.Tooltip
import scooper.util.Scoop
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.util.onHover
import scooper.viewmodels.AppsViewModel
import sh.calvin.reorderable.ReorderableColumn

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

        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                modifier = Modifier.height(30.dp).cursorHand(),
                onClick = { navigator.push(AppRoute.Settings.General) }) {
                Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Settings, "Settings", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Settings")
                }
            }

            DropdownMenuItem(
                modifier = Modifier.height(30.dp).cursorHand(),
                onClick = { navigator.push(AppRoute.Settings.Cleanup) }) {
                Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource("cleaning_services_black_24dp.svg"),
                        "Cleanup",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Cleanup")
                }
            }

            DropdownMenuItem(
                modifier = Modifier.height(30.dp).cursorHand(),
                onClick = { navigator.push(AppRoute.Output) }) {
                Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource("CommandLine.svg"), "View Logs", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("View Logs")
                }
            }

            DropdownMenuItem(
                modifier = Modifier.height(30.dp).cursorHand(),
                onClick = { navigator.push(AppRoute.Settings.About) }) {
                Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, "About", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("About")
                }
            }

        }
    }
}

@Composable
fun RefreshScoopButton() {
    val appsViewModel: AppsViewModel = koinInject()
    val taskQueue: TaskQueue = koinInject()
    val runningTask by taskQueue.runningTaskFlow.collectAsState(null)
    val scope = rememberCoroutineScope { Dispatchers.Default }
    Box(modifier = Modifier.width(30.dp), contentAlignment = Alignment.Center) {
        if (runningTask != null) {
            val queuedTasks by taskQueue.pendingTasksFlow.map { tasks -> tasks.filterNot { it is Task.Refresh } }
                .collectAsState(listOf())
            val runningTaskSize = queuedTasks.size + if (runningTask !is Task.Refresh) 1 else 0

            var showQueueTasks by remember { mutableStateOf(false) }
            val clickIndicator = if (runningTaskSize > 0) Modifier.cursorLink() else Modifier
            IconButton(
                onClick = { showQueueTasks = true }, Modifier.then(clickIndicator),
                rippleRadius = 20.dp, enabled = runningTaskSize > 0,
            ) {
                Box(Modifier.size(30.dp)) {
                    if (runningTask is Task.Refresh) {
                        CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.Center), strokeWidth = 2.dp)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.align(Alignment.BottomCenter))
                    }

                    if (runningTaskSize > 0) {
                        val position = if (runningTask is Task.Refresh) {
                            Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-10).dp)
                        } else {
                            Modifier.align(Alignment.Center).offset(y = (-5).dp)
                        }
                        Badge(modifier = Modifier.then(position), backgroundColor = colors.primary) {
                            Text("$runningTaskSize")
                        }
                    }

                    DropdownMenu(showQueueTasks, onDismissRequest = { showQueueTasks = false }) {
                        TaskRow(runningTask!!, true, onCancel = {
                            scope.launch { Scoop.stop() }
                        })

                        ReorderableColumn(queuedTasks, onSettle = { from, to ->
                            queuedTasks.getOrNull(from)?.let {
                                scope.launch { taskQueue.moveTask(it.name, to) }
                            }
                        }) { _, task, isDragging ->
                            key(task.id) {
                                val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                                Surface(elevation = elevation) {
                                    TaskRow(task, false, Modifier.draggableHandle(), onCancel = {
                                        scope.launch { taskQueue.cancelTask(task.name) }
                                    })
                                }

                            }
                        }
                    }
                }
            }
        } else {
            Tooltip("Refreshing Scoop") {
                IconButton(
                    onClick = { appsViewModel.queuedUpdateApps() },
                    modifier = Modifier.cursorLink(),
                    rippleRadius = 20.dp
                ) {
                    Icon(
                        painterResource("sync.svg"),
                        "refresh",
                        modifier = Modifier.height(30.dp),
                        tint = colors.primary
                    )
                }
            }

        }
    }
}


@Composable
private fun TaskRow(task: Task, isRunning: Boolean, draggableHandle: Modifier? = null, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.height(40.dp).width(250.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        if (isRunning) {
            LinearProgressIndicator(
                modifier = Modifier.height(5.dp).width(18.dp).padding(start = 5.dp)
            )
        } else {
            Icon(
                painterResource("drag_indicator.svg"),
                contentDescription = "Reorder Task",
                modifier = Modifier.size(18.dp).then(draggableHandle?.cursorHand() ?: Modifier)
            )
        }
        Text(
            text = task.toTitle(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        var hover by remember { mutableStateOf(false) }
        val tint = if (hover) colors.error else colors.onSecondary
        if (isRunning) {
            IconButton(
                onClick = { onCancel() },
                modifier = Modifier.cursorHand().padding(horizontal = 5.dp).onHover { hover = it },
                rippleRadius = 12.dp,
            ) {
                Icon(
                    painterResource("stop.svg"),
                    "",
                    modifier = Modifier.size(20.dp),
                    tint = tint
                )
            }

        } else {
            IconButton(
                onClick = { onCancel() },
                modifier = Modifier.cursorHand().padding(horizontal = 5.dp).onHover { hover = it },
                rippleRadius = 12.dp,
            ) {
                Icon(
                    Icons.TwoTone.Clear,
                    "",
                    modifier = Modifier.size(20.dp),
                    tint = tint
                )
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
            .background(color = if (highlight) colors.primary else Color.Unspecified)
            .cursorHand()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {

        var style = MaterialTheme.typography.subtitle1
        val scale = 1.0
        if (highlight) {
            style = style.copy(fontSize = style.fontSize * scale, color = colors.onPrimary)
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
*/
