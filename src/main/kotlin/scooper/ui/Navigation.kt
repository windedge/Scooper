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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import scooper.taskqueue.Task
import scooper.taskqueue.TaskQueue
import scooper.taskqueue.toTitle
import scooper.ui.components.IconButton
import scooper.ui.components.Tooltip
import scooper.service.ScoopService
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.util.onHover
import scooper.viewmodels.AppsViewModel
import sh.calvin.reorderable.ReorderableColumn

@Suppress("UNCHECKED_CAST")
@Composable
fun ToolbarRow(show: Boolean = true, modifier: Modifier = Modifier) {
    if (!show) return

    Surface(
        modifier = modifier.then(Modifier.fillMaxWidth().height(48.dp)),
        elevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RefreshScoopButton()
                MoreActionsButton()
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RefreshScoopButton() {
    val appsViewModel: AppsViewModel = koinInject()
    val taskQueue: TaskQueue = koinInject()
    val scoopService: ScoopService = koinInject()
    val runningTask by taskQueue.runningTaskFlow.collectAsState(null)
    val scope = rememberCoroutineScope { Dispatchers.Default }
    Box(modifier = Modifier.width(42.dp), contentAlignment = Alignment.Center) {
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
                Box(Modifier.size(42.dp)) {
                    if (runningTask is Task.Refresh) {
                        CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.Center), strokeWidth = 2.dp)
                    } else {
                        val progress by taskQueue.progressFlow.collectAsState(null)
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = progress!! / 100f,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }

                    if (runningTaskSize > 0) {
                        val position = if (runningTask is Task.Refresh) {
                            Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = 2.dp)
                        } else {
                            Modifier.align(Alignment.Center)
                        }
                        Badge(modifier = position, backgroundColor = colors.primary) {
                            Text("$runningTaskSize")
                        }
                    }

                    DropdownMenu(showQueueTasks, onDismissRequest = { showQueueTasks = false }) {
                        TaskRow(runningTask!!, true, onCancel = {
                            scope.launch { scoopService.stop() }
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
            var isCtrlPressed by remember { mutableStateOf(false) }
            Tooltip("Refreshing Scoop") {
                IconButton(
                    onClick = {
                        if (isCtrlPressed) {
                            appsViewModel.scheduleReloadApps()
                        } else {
                            appsViewModel.scheduleUpdateApps()
                        }
                    },
                    modifier = Modifier.cursorLink().onPointerEvent(PointerEventType.Press) {
                        isCtrlPressed = it.keyboardModifiers.isCtrlPressed
                    },
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
                Icon(painterResource("stop.svg"), "", modifier = Modifier.size(20.dp), tint = tint)
            }

        } else {
            IconButton(
                onClick = { onCancel() },
                modifier = Modifier.cursorHand().padding(horizontal = 5.dp).onHover { hover = it },
                rippleRadius = 12.dp,
            ) {
                Icon(Icons.TwoTone.Clear, "", modifier = Modifier.size(20.dp), tint = tint)
            }
        }
    }
}