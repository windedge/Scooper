package scooper.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.twotone.Clear
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.slf4j.LoggerFactory
import org.koin.compose.koinInject
import scooper.ui.components.IconButton
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.viewmodels.AppsViewModel
import scooper.ui.theme.*

@Suppress("unused")
private val logger = LoggerFactory.getLogger("scooper.ui.Search")


@OptIn(ExperimentalComposeUiApi::class, FlowPreview::class)
@Composable
fun SearchBar(show: Boolean = true) {
    val colors = MaterialTheme.colors
    if (!show) return

    val appsViewModel: AppsViewModel = koinInject()
    val state by appsViewModel.container.stateFlow.collectAsState()

    val buckets = mutableListOf("")
    buckets.addAll(state.buckets.map { it.name })

    val sortOptions = listOf(
        "updated" to "Recently Updated",
        "added" to "Recently Added",
        "name" to "Name (A-Z)"
    )

    var expandBucket by remember { mutableStateOf(false) }
    var expandSort by remember { mutableStateOf(false) }
    var selectedItem by rememberSaveable(state.filter.scope) { mutableStateOf(-1) }
    var bucket by rememberSaveable(state.filter.scope) { mutableStateOf("") }
    var sortBy by rememberSaveable(state.filter.scope) { mutableStateOf("updated") }
    var queryText by rememberSaveable(state.filter.scope) { mutableStateOf("") }

    LaunchedEffect(queryText) {
        snapshotFlow { queryText }
            .distinctUntilChanged()
            .debounce(400)
            .collectLatest {
                appsViewModel.applyFilters(query = it)
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
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        val borderColor = if (isFocused) colors.primary else colors.inputBorder
        val inputFocusRequester = remember { FocusRequester() }

        Row(
            modifier = Modifier.weight(1f).height(36.dp)
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

        // Bucket filter dropdown
        Box {
            Row(
                modifier = Modifier.height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.inputBackground)
                    .border(width = 1.dp, color = colors.inputBorder, RoundedCornerShape(8.dp))
                    .cursorHand()
                    .clickable { expandBucket = !expandBucket }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource("filter.xml"), "", modifier = Modifier.size(14.dp), tint = colors.textMuted)
                Spacer(Modifier.width(6.dp))
                Text(
                    bucket.ifBlank { "All Buckets" },
                    style = typography.subtitle2.copy(color = colors.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = colors.textMuted)
            }

            DropdownMenu(
                expandBucket,
                onDismissRequest = { expandBucket = false },
                modifier = Modifier.width(140.dp).cursorHand(),
                offset = DpOffset(x = 0.dp, y = 4.dp),
            ) {
                buckets.forEachIndexed { idx, title ->
                    var hover by remember { mutableStateOf(false) }
                    DropdownMenuItem(
                        onClick = {
                            expandBucket = false
                            selectedItem = idx
                            bucket = title
                            appsViewModel.applyFilters(bucket = bucket)
                        },
                        modifier = Modifier.sizeIn(maxHeight = 36.dp)
                            .background(color = if (hover) colors.primarySubtle else colors.surface)
                            .onHover { hover = it },
                    ) {
                        Text(
                            title.ifBlank { "All Buckets" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = typography.button.copy(
                                color = if (hover) colors.primary else colors.onSurface,
                            ),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // Sort filter dropdown
        Box {
            Row(
                modifier = Modifier.height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.inputBackground)
                    .border(width = 1.dp, color = colors.inputBorder, RoundedCornerShape(8.dp))
                    .cursorHand()
                    .clickable { expandSort = !expandSort }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource("sort.xml"), "", modifier = Modifier.size(14.dp), tint = colors.textMuted)
                Spacer(Modifier.width(6.dp))
                Text(
                    sortOptions.find { it.first == sortBy }?.second ?: "Sort By",
                    style = typography.subtitle2.copy(color = colors.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = colors.textMuted)
            }

            DropdownMenu(
                expandSort,
                onDismissRequest = { expandSort = false },
                modifier = Modifier.width(160.dp).cursorHand(),
                offset = DpOffset(x = 0.dp, y = 4.dp),
            ) {
                sortOptions.forEach { (key, label) ->
                    var hover by remember { mutableStateOf(false) }
                    DropdownMenuItem(
                        onClick = {
                            expandSort = false
                            sortBy = key
                            appsViewModel.applyFilters(sort = sortBy)
                        },
                        modifier = Modifier.sizeIn(maxHeight = 36.dp)
                            .background(color = if (hover) colors.primarySubtle else colors.surface)
                            .onHover { hover = it },
                    ) {
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = typography.button.copy(
                                color = if (hover) colors.primary else colors.onSurface,
                            ),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))
        RefreshScoopButton()
    }
}
