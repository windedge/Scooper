package scooper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import scooper.ui.icons.*
import androidx.compose.runtime.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import scooper.service.ScoopSearchApp
import scooper.service.ScoopSearchSort
import scooper.ui.components.IconButton
import scooper.ui.components.Link
import scooper.ui.components.OnBottomReached
import scooper.ui.components.Tooltip
import scooper.ui.theme.*
import scooper.ui.components.SelectableContainer
import scooper.util.bottomBorder
import scooper.util.cursorHand
import scooper.util.noRippleClickable
import scooper.util.onHover
import scooper.util.safeBrowse
import scooper.viewmodels.ScoopSearchViewModel
import scooper.util.tr
import scooper.util.trn

@OptIn(FlowPreview::class)
@Composable
fun ScoopSearchScreen(
    viewModel: ScoopSearchViewModel = koinInject(),
) {
    val state by viewModel.container.stateFlow.collectAsState()
    var queryText by remember { mutableStateOf(state.query) }
    var selectedApp by remember { mutableStateOf<ScoopSearchApp?>(null) }

    LaunchedEffect(Unit) {
        snapshotFlow { queryText }
            .debounce(400)
            .collect {
                selectedApp = null
                viewModel.onSearch(it)
            }
    }

    LaunchedEffect(state.results, selectedApp) {
        if (selectedApp == null) return@LaunchedEffect
        val exists = state.results.any {
            it.Name == selectedApp!!.Name && it.Metadata.Repository == selectedApp!!.Metadata.Repository
        }
        if (!exists) selectedApp = null
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(colors.surface)
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 0.dp),
    ) {
        // Page title
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("Search Online"),
                style = typography.h5.copy(
                    fontWeight = FontWeight.Bold,
                    color = colors.textTitle,
                )
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))

        // Search input
        SearchInput(
            query = queryText,
            onQueryChange = { queryText = it },
        )

        Spacer(Modifier.height(20.dp))

        // Content area
        Box(Modifier.weight(1f)) {
            when {
                state.query.isBlank() && !state.searching -> {
                    // Idle state: show search syntax guide
                    SearchSyntaxGuide()
                }
                state.searching && state.results.isEmpty() -> {
                    // Initial loading
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    }
                }
                state.results.isEmpty() && state.query.isNotBlank() -> {
                    // No results
                    val errorMsg = state.errorMessage
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (errorMsg != null) {
                                Text(
                                    errorMsg,
                                    style = typography.body1.copy(color = colors.error),
                                )
                            } else {
                                Text(
                                    tr("No results found for \"{{query}}\"", "query" to state.query),
                                    style = typography.body1.copy(color = colors.textMuted),
                                )
                            }
                        }
                    }
                }
                else -> {
                    val currentApp = selectedApp
                    SearchResultsList(
                        results = state.results,
                        totalCount = state.totalCount,
                        query = state.query,
                        loadingMore = state.searching,
                        showBucketName = state.showBucketName,
                        sort = state.sort,
                        officialOnly = state.officialOnly,
                        distinctOnly = state.distinctOnly,
                        onSortChange = viewModel::setSort,
                        onOfficialOnlyChange = viewModel::setOfficialOnly,
                        onDistinctOnlyChange = viewModel::setDistinctOnly,
                        onShowBucketNameChange = viewModel::setShowBucketName,
                        onLoadMore = { viewModel.loadMore() },
                        selectedApp = selectedApp,
                        onAppClick = { selectedApp = it },
                        detailPanel = {
                            Box {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = currentApp != null,
                                    enter = slideInHorizontally(initialOffsetX = { it }),
                                    exit = slideOutHorizontally(targetOffsetX = { it }),
                                ) {
                                    if (currentApp != null) {
                                        ScoopSearchDetailPanel(
                                            app = currentApp,
                                            onClose = { selectedApp = null },
                                            onInstall = { app, bucketName -> viewModel.installSearchApp(app, bucketName) },
                                            isInstalling = state.installingApps.contains(currentApp.Name),
                                            isInstalled = state.installedAppNames.contains(currentApp.Name.lowercase()),
                                            isBucketInstalled = state.localBucketNames.contains(currentApp.Metadata.Repository.substringAfterLast("/").lowercase()),
                                            modifier = Modifier.padding(start = 4.dp),
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    scooper.ui.components.OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().height(44.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && query.isNotEmpty()) {
                    onQueryChange("")
                    return@onPreviewKeyEvent true
                }
                false
            },
        placeholder = {
            Text(
                tr("Search packages..."),
                style = typography.subtitle2.copy(color = colors.textPlaceholder),
            )
        },
        leadingIcon = {
            Icon(
                Lucide.Search,
                tr("Search"),
                modifier = Modifier.size(18.dp),
                tint = colors.textPlaceholder,
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(
                    onClick = {
                        onQueryChange("")
                        focusRequester.requestFocus()
                    },
                    modifier = Modifier.cursorHand().padding(horizontal = 2.dp),
                    rippleRadius = 10.dp,
                ) {
                    Icon(Lucide.X, "", modifier = Modifier.size(14.dp), tint = colors.textMuted)
                }
            }
        } else null,
        singleLine = true,
        textStyle = typography.subtitle2.copy(color = colors.onSurface),
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.inputBorder,
            backgroundColor = colors.inputBackground,
            cursorColor = colors.primary,
        ),
    )
}

@Composable
private fun SearchOptionsMenu(
    sort: ScoopSearchSort,
    officialOnly: Boolean,
    distinctOnly: Boolean,
    showBucketName: Boolean,
    onSortChange: (ScoopSearchSort) -> Unit,
    onOfficialOnlyChange: (Boolean) -> Unit,
    onDistinctOnlyChange: (Boolean) -> Unit,
    onShowBucketNameChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val bucketLabel = if (officialOnly) tr("Official buckets") else tr("All buckets")

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            Surface(
                modifier = Modifier.cursorHand().clickable { expanded = true },
                shape = RoundedCornerShape(8.dp),
                color = colors.inputBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Lucide.SlidersHorizontal,
                        tr("Search options"),
                        modifier = Modifier.size(14.dp),
                        tint = colors.textBody,
                    )
                    Text(
                        "${sort.displayName()}, $bucketLabel",
                        style = typography.subtitle2.copy(color = colors.textTitle),
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(260.dp).background(colors.surface),
            ) {
                Text(
                    tr("Sorting"),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = typography.caption.copy(color = colors.textBody),
                )
                ScoopSearchSort.values().forEach { option ->
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            onSortChange(option)
                        },
                    ) {
                        Text(
                            option.displayName(),
                            style = typography.body2.copy(
                                color = if (option == sort) colors.primary else colors.onSurface,
                                fontWeight = if (option == sort) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        )
                    }
                }

                Divider(color = colors.divider)

                Text(
                    tr("Filtering"),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = typography.caption.copy(color = colors.textBody),
                )
                ToggleMenuItem(
                    text = tr("Official buckets only"),
                    checked = officialOnly,
                    onCheckedChange = onOfficialOnlyChange,
                )
                ToggleMenuItem(
                    text = tr("Distinct manifests only"),
                    checked = distinctOnly,
                    onCheckedChange = onDistinctOnlyChange,
                )

                Divider(color = colors.divider)

                Text(
                    tr("Option"),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = typography.caption.copy(color = colors.textBody),
                )
                ToggleMenuItem(
                    text = tr("Show bucket name"),
                    checked = showBucketName,
                    onCheckedChange = onShowBucketNameChange,
                )
            }
        }
    }
}

@Composable
private fun ToggleMenuItem(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    DropdownMenuItem(onClick = { onCheckedChange(!checked) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text, style = typography.body2.copy(color = colors.onSurface))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = colors.surface,
                    uncheckedTrackColor = colors.borderHover,
                ),
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<ScoopSearchApp>,
    totalCount: Int,
    query: String,
    loadingMore: Boolean,
    showBucketName: Boolean,
    sort: ScoopSearchSort,
    officialOnly: Boolean,
    distinctOnly: Boolean,
    onSortChange: (ScoopSearchSort) -> Unit,
    onOfficialOnlyChange: (Boolean) -> Unit,
    onDistinctOnlyChange: (Boolean) -> Unit,
    onShowBucketNameChange: (Boolean) -> Unit,
    onLoadMore: () -> Unit,
    selectedApp: ScoopSearchApp? = null,
    onAppClick: (ScoopSearchApp) -> Unit = { },
    detailPanel: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Status line + filter button on same row
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 24.dp, bottom=6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val countLabel = if (query.isNotBlank()) {
                trn(
                    "Found {{count}} result for \"{{query}}\"",
                    "Found {{count}} results for \"{{query}}\"",
                    totalCount, "count" to "$totalCount", "query" to query,
                )
            } else {
                trn("Found {{count}} result", "Found {{count}} results", totalCount, "count" to "$totalCount")
            }
            Text(
                countLabel,
                style = typography.body2.copy(color = colors.textBody),
            )
            Spacer(Modifier.weight(1f))
            SearchOptionsMenu(
                sort = sort,
                officialOnly = officialOnly,
                distinctOnly = distinctOnly,
                showBucketName = showBucketName,
                onSortChange = onSortChange,
                onOfficialOnlyChange = onOfficialOnlyChange,
                onDistinctOnlyChange = onDistinctOnlyChange,
                onShowBucketNameChange = onShowBucketNameChange,
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.weight(1f)) {
                val listState = rememberLazyListState()
                listState.OnBottomReached(3, onLoadMore = onLoadMore)

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(end = 16.dp),
                    state = listState,
                ) {
                items(count = results.size, key = { results[it].Name + it }) { index ->
                    val app = results[index]
                    SearchResultCard(
                        app = app,
                        showBucketName = showBucketName,
                        selected = app.Name == selectedApp?.Name && app.Metadata.Repository == selectedApp?.Metadata?.Repository,
                        onClick = onAppClick,
                    )
                }
                if (loadingMore && results.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        .background(color = colors.surface),
                    adapter = rememberScrollbarAdapter(scrollState = listState),
                )
            }

            detailPanel()
        }
    }
}

@Composable
private fun SearchResultCard(
    app: ScoopSearchApp,
    showBucketName: Boolean,
    selected: Boolean = false,
    onClick: (ScoopSearchApp) -> Unit = { },
) {
    val colors = MaterialTheme.colors
    var isHover by remember { mutableStateOf(false) }
    val bgColor = when {
        selected -> colors.primarySubtle
        isHover -> colors.backgroundHover
        else -> Color.Transparent
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .onHover { isHover = it }
            .background(bgColor)
            .cursorHand()
            .clickable { onClick(app) }
            .padding(start = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: name + description + metadata
            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                // Row 1: name + version ... updated date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        app.Name,
                        style = typography.h6.copy(color = colors.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (app.Version.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        val manifestUrl = app.Metadata.manifestUrl
                        if (manifestUrl.isNotEmpty()) {
                            Tooltip(manifestUrl) {
                                Link(
                                    text = app.Version,
                                    painter = null,
                                    onClicked = { safeBrowse(manifestUrl) },
                                )
                            }
                        } else {
                            Text(
                                app.Version,
                                style = typography.overline.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = colors.primary,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (app.Metadata.Committed.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Lucide.Clock,
                                tr("Updated"),
                                modifier = Modifier.size(11.dp),
                                tint = colors.textMuted,
                            )
                            Text(
                                formatCommittedDate(app.Metadata.Committed),
                                style = typography.overline.copy(color = colors.textMuted),
                            )
                        }
                    }
                }

                // Row 2: description
                if (app.Description.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        app.Description,
                        style = typography.body2.copy(color = colors.textBody),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Row 3: license + bucket + homepage
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (app.License.isNotEmpty()) {
                        Tooltip(app.License) {
                            MetadataTag(app.License, maxWidth = 220.dp)
                        }
                    }
                    val bucketName = app.Metadata.Repository.substringAfterLast("/")
                    val bucketUrl = app.Metadata.Repository
                    if (showBucketName && bucketName.isNotEmpty()) {
                        Tooltip(bucketUrl) {
                            MetadataTag(bucketName, maxWidth = 140.dp, modifier = Modifier.cursorHand().clickable { safeBrowse(bucketUrl) })
                        }
                    }
                    if (app.Homepage.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        Tooltip(app.Homepage) {
                            var homepageHover by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.widthIn(max = 400.dp)
                                    .cursorHand()
                                    .noRippleClickable { safeBrowse(app.Homepage) }
                                    .onHover { homepageHover = it }
                                    .let { if (homepageHover) it.bottomBorder(1.dp, color = colors.primary) else it },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    app.Homepage,
                                    style = typography.body1.copy(color = colors.primary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

        }
        Divider(color = colors.divider)
    }
}

private fun formatCommittedDate(isoDate: String): String {
    return try {
        val instant = java.time.Instant.parse(isoDate)
        val date = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val now = java.time.LocalDateTime.now()
        val days = java.time.Duration.between(date, now).toDays()
        when {
            days < 1 -> tr("Updated today")
            days == 1L -> trn("Updated {{n}} day ago", "Updated {{n}} days ago", 1, "n" to "1")
            days < 30 -> trn("Updated {{n}} day ago", "Updated {{n}} days ago", days.toInt(), "n" to "$days")
            days < 365 -> {
                val months = (days / 30).toInt()
                trn("Updated {{n}} month ago", "Updated {{n}} months ago", months, "n" to "$months")
            }
            else -> {
                val years = (days / 365).toInt()
                trn("Updated {{n}} year ago", "Updated {{n}} years ago", years, "n" to "$years")
            }
        }
    } catch (_: Exception) {
        ""
    }
}

@Composable
private fun MetadataTag(text: String, maxWidth: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colors
    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .border(1.dp, colors.borderDefault, RoundedCornerShape(3.dp))
            .background(colors.backgroundHover, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = typography.overline.copy(color = colors.textBody),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchSyntaxGuide(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    tr("Search packages curated by"),
                    style = typography.body2.copy(color = colors.textBody),
                )
                Link(
                    text = "rasa/scoop-directory",
                    painter = null,
                    onClicked = { safeBrowse("https://github.com/rasa/scoop-directory") },
                )
                Text(
                    tr("· Supported search syntax:"),
                    style = typography.body2.copy(color = colors.textBody),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SyntaxRow("firefox", tr("Search name and description"))
                SyntaxRow("firefox portable", tr("Both terms required"))
                SyntaxRow("firefox OR chrome", tr("Either term"))
                SyntaxRow("firefox -esr", tr("Exclude term"))
                SyntaxRow("\"visual studio\"", tr("Exact phrase"))
                SyntaxRow("firefox bucket:Extras", tr("Limit to bucket"))
                SyntaxRow("bucket:Extras", tr("List all in bucket"))
            }
        }
    }
}

@Composable
private fun SyntaxRow(query: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectableContainer {
            Text(
                query,
                style = typography.body2.copy(
                    color = colors.primary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                modifier = Modifier.width(220.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            description,
            style = typography.body2.copy(color = colors.textMuted),
            maxLines = 1,
        )
    }
}
