package scooper.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.twotone.Clear
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.slf4j.LoggerFactory
import org.koin.compose.koinInject
import scooper.data.PaginationMode
import scooper.data.ViewMode
import scooper.ui.components.IconButton
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.viewmodels.AppsViewModel
import scooper.ui.theme.*

@Suppress("unused")
private val logger = LoggerFactory.getLogger("scooper.ui.Search")


@OptIn(ExperimentalComposeUiApi::class, FlowPreview::class)
@Composable
fun SearchBar(show: Boolean = true, focusRequester: Int = 0, onResetFocusRequester: () -> Unit = {}) {
    val colors = MaterialTheme.colors
    if (!show) return

    val appsViewModel: AppsViewModel = koinInject()
    val state by appsViewModel.container.stateFlow.collectAsState()
    val viewMode by remember { derivedStateOf { state.viewMode } }
    val paginationMode by remember { derivedStateOf { state.filter.paginationMode } }

    val buckets = mutableListOf("")
    buckets.addAll(state.buckets.map { it.name })

    val sortOptions = listOf(
        "updated" to "Recently Updated",
        "added" to "Recently Added",
        "name" to "Name"
    )

    var expandBucket by remember { mutableStateOf(false) }
    var expandSort by remember { mutableStateOf(false) }
    var selectedItem by rememberSaveable { mutableStateOf(-1) }
    var bucket by rememberSaveable { mutableStateOf("") }
    var sortBy by rememberSaveable(state.filter.scope) { mutableStateOf("updated") }
    var sortOrder by rememberSaveable(state.filter.scope) { mutableStateOf("desc") }
    var queryText by rememberSaveable(state.filter.scope) { mutableStateOf("") }

    LaunchedEffect(state.filter.selectedBucket, state.filter.sort, state.filter.sortOrder, state.filter.query, buckets.size) {
        bucket = state.filter.selectedBucket
        sortBy = state.filter.sort
        sortOrder = state.filter.sortOrder
        queryText = state.filter.query
        selectedItem = buckets.indexOf(state.filter.selectedBucket)
    }

    LaunchedEffect(queryText) {
        snapshotFlow { queryText }
            .distinctUntilChanged()
            .debounce(400)
            .collectLatest {
                appsViewModel.applyFilters(query = it)
            }
    }

    // Search input with icon
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (isFocused) colors.primary else colors.inputBorder
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val inputFocusRequester = remember { FocusRequester() }

    // External focus trigger via focusRequester counter
    LaunchedEffect(focusRequester) {
        if (focusRequester > 0) {
            inputFocusRequester.requestFocus()
            onResetFocusRequester()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp)
            .background(colors.surface)
            .border(width = 1.dp, color = colors.borderDefault)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Search input with icon
        Row(
            modifier = Modifier.widthIn(min = 120.dp, max = 300.dp).height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.inputBackground)
                .border(width = 1.dp, color = borderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                "Search",
                modifier = Modifier.size(16.dp),
                tint = if (isFocused) colors.primary else colors.textPlaceholder,
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (queryText.isEmpty()) {
                    Text(
                        "Search packages...",
                        style = typography.subtitle2.copy(
                            color = colors.textPlaceholder,
                        ),
                    )
                }
                BasicTextField(
                    queryText,
                    onValueChange = { queryText = it },
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onPreviewKeyEvent {
                            if (it.key == Key.Enter) {
                                appsViewModel.applyFilters(queryText, bucket = bucket)
                                return@onPreviewKeyEvent true
                            }
                            if (it.key == Key.Escape) {
                                focusManager.clearFocus()
                                return@onPreviewKeyEvent true
                            }
                            false
                        },
                    singleLine = true,
                    textStyle = typography.subtitle2.copy(
                        color = colors.onSurface,
                    ),
                    interactionSource = interactionSource,
                )
            }
            if (queryText.isNotEmpty()) {
                IconButton(
                    onClick = {
                        queryText = ""
                        inputFocusRequester.requestFocus()
                    },
                    modifier = Modifier.cursorHand().padding(horizontal = 2.dp),
                    rippleRadius = 10.dp,
                ) {
                    Icon(Icons.TwoTone.Clear, "", modifier = Modifier.size(14.dp), tint = colors.textMuted)
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // Installed/Updates segmented control
        val currentScope = state.filter.scope
        val isInstalledScope = currentScope == "installed" || currentScope == "updates"
        if (isInstalledScope) {
            InstalledUpdatesToggle(
                showOnlyUpdates = currentScope == "updates",
                updateCount = state.updateCount,
                onScopeChange = { appsViewModel.applyFilters(scope = it) },
            )
            Spacer(Modifier.width(12.dp))
        }

        // Bucket filter dropdown
        BucketFilterDropdown(
            buckets = buckets,
            selectedBucket = bucket,
            selectedIndex = selectedItem,
            expanded = expandBucket,
            onExpandedChange = { expandBucket = it },
            onSelected = { idx, title ->
                expandBucket = false
                selectedItem = idx
                bucket = title
                appsViewModel.applyFilters(bucket = bucket)
            },
        )

        Spacer(Modifier.width(12.dp))

        // Sort filter dropdown
        SortFilterDropdown(
            sortOptions = sortOptions,
            sortBy = sortBy,
            sortOrder = sortOrder,
            expanded = expandSort,
            onExpandedChange = { expandSort = it },
            onSortChange = { key, order ->
                expandSort = false
                sortBy = key
                sortOrder = order
                appsViewModel.applyFilters(sort = key, sortOrder = order)
            },
        )

        Spacer(Modifier.width(12.dp))

        // View mode & Pagination mode toggles
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // View mode toggle
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.inputBackground)
                    .border(1.dp, colors.borderDefault, RoundedCornerShape(8.dp))
                    .padding(3.dp),
            ) {
                Tooltip("List View", position = TooltipPosition.Top) {
                    IconButton(
                        onClick = { appsViewModel.setViewMode(ViewMode.List) },
                        modifier = Modifier.size(28.dp).cursorHand(),
                        rippleRadius = 12.dp,
                    ) {
                        Icon(
                            painterResource("list.svg"), "List View",
                            modifier = Modifier.size(14.dp),
                            tint = if (viewMode == ViewMode.List) colors.primary else colors.textMuted
                        )
                    }
                }
                Tooltip("Grid View", position = TooltipPosition.Top) {
                    IconButton(
                        onClick = { appsViewModel.setViewMode(ViewMode.Grid) },
                        modifier = Modifier.size(28.dp).cursorHand(),
                        rippleRadius = 12.dp,
                    ) {
                        Icon(
                            painterResource("layout-grid.svg"), "Grid View",
                            modifier = Modifier.size(14.dp),
                            tint = if (viewMode == ViewMode.Grid) colors.primary else colors.textMuted
                        )
                    }
                }
            }

            // Pagination mode toggle
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.inputBackground)
                    .border(1.dp, colors.borderDefault, RoundedCornerShape(8.dp))
                    .padding(3.dp),
            ) {
                Tooltip("Waterfall Scroll", position = TooltipPosition.Top) {
                    IconButton(
                        onClick = { appsViewModel.applyFilters(paginationMode = PaginationMode.Waterfall) },
                        modifier = Modifier.size(28.dp).cursorHand(),
                        rippleRadius = 12.dp,
                    ) {
                        Icon(
                            painterResource("scroll.svg"), "Waterfall",
                            modifier = Modifier.size(14.dp),
                            tint = if (paginationMode == PaginationMode.Waterfall) colors.primary else colors.textMuted
                        )
                    }
                }
                Tooltip("Paginated", position = TooltipPosition.Top) {
                    IconButton(
                        onClick = { appsViewModel.applyFilters(paginationMode = PaginationMode.Pagination) },
                        modifier = Modifier.size(28.dp).cursorHand(),
                        rippleRadius = 12.dp,
                    ) {
                        Icon(
                            painterResource("book-open.svg"), "Pagination",
                            modifier = Modifier.size(14.dp),
                            tint = if (paginationMode == PaginationMode.Pagination) colors.primary else colors.textMuted
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        RefreshScoopButton()
    }
}

@Composable
private fun InstalledUpdatesToggle(
    showOnlyUpdates: Boolean,
    updateCount: Long,
    onScopeChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colors
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.inputBackground)
            .border(1.dp, colors.borderDefault, RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // All Installed tab
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (!showOnlyUpdates) colors.surface else Color.Transparent,
            border = BorderStroke(1.dp, if (!showOnlyUpdates) colors.borderDefault else Color.Transparent),
            elevation = if (!showOnlyUpdates) 1.dp else 0.dp,
            modifier = Modifier.fillMaxHeight().cursorHand().clickable { onScopeChange("installed") }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                Text("All Installed", style = typography.subtitle2.copy(fontWeight = FontWeight.Medium, color = if (!showOnlyUpdates) colors.textTitle else colors.textMuted))
            }
        }
        Spacer(Modifier.width(4.dp))
        // Updates tab
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (showOnlyUpdates) colors.surface else Color.Transparent,
            border = BorderStroke(1.dp, if (showOnlyUpdates) colors.primary.copy(alpha = 0.3f) else Color.Transparent),
            elevation = if (showOnlyUpdates) 1.dp else 0.dp,
            modifier = Modifier.fillMaxHeight().cursorHand().clickable { onScopeChange("updates") }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Updates", style = typography.subtitle2.copy(fontWeight = FontWeight.Medium, color = if (showOnlyUpdates) colors.primaryHover else colors.textMuted))
                    if (updateCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (showOnlyUpdates) colors.primaryBadgeBg else colors.unselectedBadgeBg)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                "$updateCount",
                                style = typography.caption.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp, color = if (showOnlyUpdates) colors.primaryHover else colors.sidebarTextMedium),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BucketFilterDropdown(
    buckets: List<String>,
    selectedBucket: String,
    selectedIndex: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (index: Int, bucket: String) -> Unit,
) {
    val colors = MaterialTheme.colors
    Box {
        Row(
            modifier = Modifier.height(36.dp).width(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.inputBackground)
                .border(width = 1.dp, color = colors.inputBorder, RoundedCornerShape(8.dp))
                .cursorHand()
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource("filter.xml"), "", modifier = Modifier.requiredSize(14.dp), tint = colors.textMuted)
            Spacer(Modifier.width(6.dp))
            Text(
                selectedBucket.ifBlank { "All Buckets" },
                style = typography.subtitle2.copy(color = colors.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.requiredSize(16.dp), tint = colors.textMuted)
        }

        DropdownMenu(
            expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.width(140.dp).cursorHand(),
            offset = DpOffset(x = 0.dp, y = 4.dp),
        ) {
            buckets.forEachIndexed { idx, title ->
                val isSelected = idx == selectedIndex
                var hover by remember { mutableStateOf(false) }
                Box {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(3.dp)
                                .height(24.dp)
                                .background(colors.primary, RoundedCornerShape(999.dp))
                                .zIndex(1f)
                        )
                    }
                    DropdownMenuItem(
                        onClick = { onSelected(idx, title) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 44.dp)
                            .background(
                                color = when {
                                    isSelected -> colors.primarySubtle
                                    hover -> colors.primarySubtle.copy(alpha = 0.55f)
                                    else -> colors.surface
                                }
                            )
                            .onHover { hover = it },
                    ) {
                        Text(
                            title.ifBlank { "All Buckets" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = typography.button.copy(
                                color = when {
                                    isSelected -> colors.primary
                                    hover -> colors.primary
                                    else -> colors.onSurface
                                },
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortFilterDropdown(
    sortOptions: List<Pair<String, String>>,
    sortBy: String,
    sortOrder: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortChange: (sortBy: String, sortOrder: String) -> Unit,
) {
    val colors = MaterialTheme.colors
    Box {
        Row(
            modifier = Modifier.height(36.dp).width(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.inputBackground)
                .border(width = 1.dp, color = colors.inputBorder, RoundedCornerShape(8.dp))
                .cursorHand()
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource("sort.xml"), "", modifier = Modifier.requiredSize(14.dp), tint = colors.textMuted)
            Spacer(Modifier.width(6.dp))
            Text(
                sortOptions.find { it.first == sortBy }?.second ?: "Sort By",
                style = typography.subtitle2.copy(color = colors.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painterResource(if (sortOrder == "asc") "arrow-up-narrow-wide.svg" else "arrow-down-wide-narrow.svg"),
                "",
                modifier = Modifier.requiredSize(14.dp),
                tint = colors.textMuted
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.requiredSize(16.dp), tint = colors.textMuted)
        }

        DropdownMenu(
            expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.width(180.dp).cursorHand(),
            offset = DpOffset(x = 0.dp, y = 4.dp),
        ) {
            sortOptions.forEach { (key, label) ->
                val isSelected = key == sortBy
                var hover by remember { mutableStateOf(false) }
                Box {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(3.dp)
                                .height(24.dp)
                                .background(colors.primary, RoundedCornerShape(999.dp))
                                .zIndex(1f)
                        )
                    }
                    DropdownMenuItem(
                        onClick = {
                            val nextSortOrder = when {
                                key == sortBy -> sortOrder
                                key == "name" -> "asc"
                                else -> "desc"
                            }
                            onSortChange(key, nextSortOrder)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 44.dp)
                            .background(
                                color = when {
                                    isSelected -> colors.primarySubtle
                                    hover -> colors.primarySubtle.copy(alpha = 0.55f)
                                    else -> colors.surface
                                }
                            )
                            .onHover { hover = it },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                style = typography.button.copy(
                                    color = when {
                                        isSelected -> colors.primary
                                        hover -> colors.primary
                                        else -> colors.onSurface
                                    },
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                            )
                            if (isSelected) {
                                Spacer(Modifier.width(8.dp))
                                var sortOrderHover by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .offset(x = 8.dp)
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (sortOrderHover) colors.primarySubtle else Color.Transparent)
                                        .cursorHand()
                                        .onHover { sortOrderHover = it }
                                        .clickable {
                                            val newOrder = if (sortOrder == "asc") "desc" else "asc"
                                            onSortChange(key, newOrder)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painterResource(if (sortOrder == "asc") "arrow-up-narrow-wide.svg" else "arrow-down-wide-narrow.svg"),
                                        if (sortOrder == "asc") "Ascending" else "Descending",
                                        modifier = Modifier.size(16.dp),
                                        tint = colors.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
