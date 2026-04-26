package scooper.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.shapes
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import scooper.data.PaginationMode
import scooper.data.ViewMode
import scooper.taskqueue.Task
import scooper.taskqueue.TaskQueue
import scooper.ui.components.PaginationBar
import scooper.viewmodels.AppsViewModel

@Composable
fun AppScreen(scope: String, appsViewModel: AppsViewModel = koinInject()) {
    val taskQueue: TaskQueue = koinInject()
    val state by appsViewModel.container.stateFlow.collectAsState()
    val apps = state.apps
    val filter = state.filter
    val tasks by taskQueue.pendingTasksFlow.collectAsState(listOf())
    val waitingApps = tasks.map { it.name }.toSet()
    val runningTask by taskQueue.runningTaskFlow.collectAsState(null)
    val processingApp = when (runningTask) {
        is Task.Install, is Task.Update, is Task.Uninstall, is Task.Download, is Task.InstallVersion -> runningTask!!.name
        else -> null
    }


    LaunchedEffect(scope) {
        appsViewModel.applyFilters(scope = scope)
    }

    // Version picker dialog
    if (state.versionPickerApp != null) {
        val pickerApp = state.versionPickerApp!!
        VersionPickerDialog(
            app = pickerApp,
            versions = state.versionPickerVersions,
            loading = state.versionPickerLoading,
            error = state.versionPickerError,
            onInstall = { version, global ->
                appsViewModel.scheduleInstallVersion(pickerApp, version, global)
                appsViewModel.dismissVersionPicker()
            },
            onDismiss = { appsViewModel.dismissVersionPicker() },
        )
    }

    Surface(Modifier.fillMaxSize(), elevation = 0.dp, shape = shapes.large) {
        if (apps == null) return@Surface

        Column {
            if (apps.isNotEmpty()) {
                Box(Modifier.weight(1f)) {
                    key(state.viewMode, filter.paginationMode) {
                        when (state.viewMode) {
                            ViewMode.Grid -> AppGrid(
                                apps,
                                filter,
                                processingApp = processingApp,
                                waitingApps = waitingApps,
                                onInstall = appsViewModel::scheduleInstall,
                                onUpdate = appsViewModel::scheduleUpdate,
                                onDownload = appsViewModel::scheduleDownload,
                                onUninstall = appsViewModel::scheduleUninstall,
                                onOpen = appsViewModel::openApp,
                                onCancel = appsViewModel::cancel,
                                onLoadMore = appsViewModel::loadMore,
                                onInstallVersion = appsViewModel::showVersionPicker,
                            )
                            else -> AppList(
                                apps,
                                filter,
                                processingApp = processingApp,
                                waitingApps = waitingApps,
                                onInstall = appsViewModel::scheduleInstall,
                                onUpdate = appsViewModel::scheduleUpdate,
                                onDownload = appsViewModel::scheduleDownload,
                                onUninstall = appsViewModel::scheduleUninstall,
                                onOpen = appsViewModel::openApp,
                                onCancel = appsViewModel::cancel,
                                onLoadMore = appsViewModel::loadMore,
                                onInstallVersion = appsViewModel::showVersionPicker,
                            )
                        }
                    }
                }
                if (state.filter.paginationMode == PaginationMode.Pagination) {
                    PaginationBar(
                        currentPage = state.filter.page,
                        totalPages = ((state.totalCount + state.filter.pageSize - 1) / state.filter.pageSize).toInt().coerceAtLeast(1),
                        pageSize = state.filter.pageSize,
                        onGoToPage = { appsViewModel.goToPage(it) },
                        onPageSizeChange = { appsViewModel.applyFilters(pageSize = it) },
                    )
                }
            } else {
                NoResults()
            }
        }
    }
}

@Composable
fun NoResults() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painterResource("no-results.svg"),
            contentDescription = "No Results",
            modifier = Modifier.size(60.dp), tint = colors.primary
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text("No Results", style = typography.h6, color = colors.primary)
    }
}
