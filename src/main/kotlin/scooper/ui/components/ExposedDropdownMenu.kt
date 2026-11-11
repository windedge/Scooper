package scooper.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import scooper.ui.icons.*
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.util.tr
import scooper.util.trn

/** Every whitespace-separated token must appear in the item (case-insensitive, order free). */
internal fun filterByTokens(items: List<String>, query: String): List<String> {
    val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return items
    return items.filter { item -> tokens.all { item.contains(it, ignoreCase = true) } }
}

/**
 * Keyboard highlight index for the model menu: unset only while the list is
 * empty; otherwise a valid row index.
 */
internal const val MENU_HIGHLIGHT_UNSET = -1

/**
 * Row the menu opens on: the selected entry's index in the selectable rows
 * (custom "use query" row included), or the first row when it is absent.
 * Exact match, mirroring how the checkmark row is rendered.
 */
internal fun initialMenuHighlightIndex(selected: String, entries: List<String>): Int {
    val index = entries.indexOfFirst { it == selected }
    return if (index >= 0) index else 0
}

/** Keeps the keyboard highlight inside 0 until size-1; an empty list maps to unset. */
internal fun clampMenuIndex(index: Int, size: Int): Int =
    if (size <= 0) MENU_HIGHLIGHT_UNSET else index.coerceIn(0, size - 1)

/**
 * Panel width for the popup: use minWidth when positive (a tracked anchor
 * width or a fixed floor), fall back to fallbackWidth otherwise, and never
 * exceed maxWidth. Long entries ellipsize instead of stretching the panel.
 */
internal fun popupPanelWidth(minWidth: Dp, fallbackWidth: Dp, maxWidth: Dp): Dp =
    (if (minWidth <= 0.dp) fallbackWidth else minWidth).coerceAtMost(maxWidth)

/**
 * Horizontal slack added to the widest item text when sizing the plain
 * dropdown panel: both side paddings plus a safety margin for the
 * right-side insets of DropdownMenuContent.
 */
internal val DROPDOWN_PANEL_TEXT_RESERVE: Dp = 40.dp

/**
 * Panel width for the plain ExposedDropdownMenu: never narrower than the
 * anchor field, never narrower than the longest item text plus the content
 * reserve, so display names stay on a single line. An unknown longest item
 * width (empty list) keeps the anchor width.
 */
internal fun dropdownPanelWidth(anchorWidth: Dp, longestItemWidth: Dp): Dp =
    if (longestItemWidth <= 0.dp) anchorWidth
    else maxOf(anchorWidth, longestItemWidth + DROPDOWN_PANEL_TEXT_RESERVE)

/**
 * Safe wrapper for VerticalScrollbar that protects against Compose Desktop's
 * IntrinsicSize.Max / Unbounded measurement bug (Size out of range).
 */
@Composable
fun SafeVerticalScrollbar(
    adapter: androidx.compose.foundation.v2.ScrollbarAdapter,
    modifier: Modifier = Modifier,
    style: ScrollbarStyle = LocalScrollbarStyle.current,
) {
    Layout(
        content = {
            VerticalScrollbar(
                adapter = adapter,
                style = style,
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(0, 0) {}
        }
        val safeConstraints = constraints.copy(
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight.coerceIn(0, 16777215) else 1000,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.coerceIn(0, 16777215) else 20,
            minHeight = constraints.minHeight.coerceIn(0, 16777215),
            minWidth = constraints.minWidth.coerceIn(0, 16777215),
        )
        val placeable = measurables.first().measure(safeConstraints)
        layout(placeable.width.coerceIn(0, 16777215), placeable.height.coerceIn(0, 16777215)) {
            placeable.placeRelative(0, 0)
        }
    }
}

/**
 * A basic implementation of the Exposed Dropdown Menu component
 *
 * @param itemContent optional custom rendering for a dropdown item (e.g. to
 *   preview it with its own font); defaults to a plain [Text] with the
 *   onSurface color.
 * @param maxMenuHeight maximum height of the open menu; taller menus scroll.
 * @param menuMinWidth minimum width of the open menu; defaults to a panel that
 *   fits the longest item text (see [dropdownPanelWidth]). Pass a larger value
 *   to let the menu grow wider than the trigger.
 * @param showScrollbar when true, overlays a vertical scrollbar bound to the
 *   menu's scroll state on the end edge of the list.
 * @param selectedIndex index of the currently selected item: it is highlighted
 *   with a primary tint in the open menu and centered in the visible menu area
 *   when the menu opens; defaults to -1 for no highlight (the menu opens
 *   scrolled to the top). Hovering the highlighted item deepens the tint
 *   instead of removing it. With [searchable] the index refers to the original
 *   (unfiltered) [items] list; it is mapped onto the filtered list for
 *   highlighting/centering and falls back to the top when the item was
 *   filtered out.
 * @param searchable when true, renders a search field at the top of the open
 *   menu that live-filters [items] (token match, see [filterByTokens]); the
 *   query is cleared automatically on every open because the menu content is
 *   freshly composed per expansion. Filters to nothing show a single disabled
 *   "No matches found." hint instead of an empty list.
 * @see https://material.io/components/menus#exposed-dropdown-menu
 * @source https://gist.github.com/jossiwolf/0f06894d2c07748041769c64510cd4d5
 */
@Composable
fun ExposedDropdownMenu(
    items: List<String>,
    modifier: Modifier = Modifier,
    selected: String = items[0],
    onItemSelected: (String) -> Unit,
    textFill: Boolean = false,
    itemContent: (@Composable (String) -> Unit)? = null,
    maxMenuHeight: Dp = 320.dp,
    menuMinWidth: Dp = Dp.Unspecified,
    showScrollbar: Boolean = false,
    selectedIndex: Int = -1,
    searchable: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    // Longest option text drives the panel minWidth so no display name wraps.
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val longestItemWidth = items.maxOfOrNull { item ->
        with(density) { textMeasurer.measure(AnnotatedString(item), maxLines = 1).size.width.toDp() }
    } ?: 0.dp
    ExposedDropdownMenuStack(
        textField = {
            BasicTextField(
                value = selected,
                onValueChange = {},
                modifier = modifier,
                readOnly = true,
                textStyle = TextStyle(color = colors.onSurface),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.height(36.dp)
                            .background(color = colors.inputBackground, shape = RoundedCornerShape(8.dp))
                            .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                            .cursorHand()
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(if (textFill) Modifier.weight(1f) else Modifier) { innerTextField() }
                        Spacer(modifier = Modifier.width(8.dp))
                        val rotation by animateFloatAsState(if (expanded) 180F else 0F)
                        Icon(
                            Lucide.ChevronDown,
                            contentDescription = tr("Dropdown Arrow"),
                            modifier = Modifier.rotate(rotation),
                            tint = colors.textMuted,
                        )
                    }
                }
            )
        },
        dropdownMenu = { boxWidth, itemHeight ->
            val panelWidth = if (menuMinWidth != Dp.Unspecified) {
                maxOf(dropdownPanelWidth(boxWidth, longestItemWidth), menuMinWidth)
            } else {
                dropdownPanelWidth(boxWidth, longestItemWidth)
            }
            // Space reserved at the end of the list so the scrollbar never overlaps a label.
            val scrollbarReserve = if (showScrollbar) 12.dp else 0.dp
            val listWidth = panelWidth - scrollbarReserve
            Box(
                Modifier
                    .width(panelWidth)
                    .wrapContentSize(Alignment.TopStart)
            ) {
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    // Material's DropdownMenu wraps its content in a Column with
                    // width(IntrinsicSize.Max); computing that intrinsic width queries
                    // maxIntrinsicHeight of every child under an UNBOUNDED height.
                    // Lazy layouts crash on intrinsic queries, and fillMaxHeight
                    // scrollbars crash on the unbounded height itself. So the menu
                    // uses a plain Column sized to a FIXED height (clamped to
                    // maxMenuHeight): fixed-size modifiers answer intrinsic queries
                    // directly and nothing below them is ever measured unbounded.
                    val density = LocalDensity.current
                    // Reserve space for the search field so it always stays visible
                    // above the list; the list area (and the height clamp) shrinks
                    // by exactly that much.
                    val searchBoxHeight = if (searchable) 44.dp else 0.dp
                    // Search query: the DropdownMenu content is freshly composed on
                    // each expansion, so the query (`remember`) naturally starts
                    // empty every time the menu opens.
                    var query by remember { mutableStateOf("") }
                    val filteredItems = if (searchable && query.isNotBlank()) {
                        filterByTokens(items, query)
                    } else {
                        items
                    }
                    // At least one slot, so the empty-result hint has space too.
                    val listItemCount = maxOf(filteredItems.size, 1)
                    val listHeight = minOf(itemHeight * listItemCount, maxMenuHeight - searchBoxHeight)
                    val menuHeight = listHeight + searchBoxHeight
                    val lastIndex = filteredItems.lastIndex.coerceAtLeast(0)
                    // [selectedIndex] indexes the ORIGINAL (unfiltered) [items]
                    // list; map it into the filtered list so highlight and centering
                    // follow the visible items. When the selected item was filtered
                    // out the mapping yields -1 and the menu opens at the top.
                    val effectiveSelectedIndex = if (selectedIndex in items.indices) {
                        filteredItems.indexOf(items[selectedIndex])
                    } else {
                        -1
                    }
                    // Center the selected item in the visible menu area instead
                    // of pinning it to the top: its center offset minus half the
                    // remaining viewport. ScrollState clamps the value to
                    // [0, maxValue], so items near either end simply stick to the
                    // edge (no selection or index 0 clamps back to the top).
                    val centeredIndex = effectiveSelectedIndex.coerceIn(0, lastIndex)
                    val centeredOffsetPx = with(density) {
                        itemHeight.roundToPx() * centeredIndex -
                                (listHeight.roundToPx() - itemHeight.roundToPx()) / 2
                    }.coerceAtLeast(0)
                    // The DropdownMenu content is freshly composed on each
                    // expansion, so this initial position re-applies every time
                    // the menu opens.
                    val scrollState = rememberScrollState(initial = centeredOffsetPx)
                    // Auto-focus the search field so typing works immediately when
                    // the menu opens.
                    val searchFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(expanded) {
                        if (expanded && searchable) {
                            searchFocusRequester.requestFocus()
                        }
                    }
                    Column(modifier = Modifier.height(menuHeight).width(listWidth)) {
                        if (searchable) {
                            // Search field styled like the app's search input:
                            // rounded inputBackground frame, leading icon and a
                            // placeholder that yields to the query text.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.inputBackground)
                                    .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                                    .focusRequester(searchFocusRequester)
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Lucide.Search,
                                    tr("Search"),
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.textMuted,
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (query.isEmpty()) {
                                        Text(
                                            tr("Search"),
                                            style = MaterialTheme.typography.subtitle2.copy(
                                                color = colors.textPlaceholder,
                                            ),
                                        )
                                    }
                                    BasicTextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.subtitle2.copy(
                                            color = colors.onSurface,
                                        ),
                                    )
                                }
                                if (query.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            query = ""
                                            searchFocusRequester.requestFocus()
                                        },
                                        modifier = Modifier.cursorHand().padding(horizontal = 2.dp),
                                        rippleRadius = 10.dp,
                                    ) {
                                        Icon(Lucide.X, "", modifier = Modifier.size(14.dp), tint = colors.textMuted)
                                    }
                                }
                            }
                        }
                        Box {
                            if (filteredItems.isEmpty()) {
                                // Disabled hint when the query matches nothing: no
                                // click target, so nothing can be selected. Guarded by
                                // [searchable] so an (unusual) empty non-searchable
                                // menu keeps its original blank rendering.
                                if (searchable) {
                                    Box(
                                        modifier = Modifier
                                            .height(itemHeight)
                                            .width(listWidth)
                                            .padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Text(
                                            tr("No matches found."),
                                            color = colors.textMuted,
                                            style = MaterialTheme.typography.body2,
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .height(listHeight)
                                        .width(listWidth)
                                        .verticalScroll(scrollState)
                                ) {
                                    filteredItems.forEachIndexed { index, item ->
                                        var hover by remember { mutableStateOf(false) }
                                        val isSelected = index == effectiveSelectedIndex
                                        DropdownMenuItem(
                                            modifier = Modifier
                                                .height(itemHeight)
                                                .width(listWidth)
                                                .background(
                                                    when {
                                                        // The selected item stays covered by a primary
                                                        // tint; hovering it deepens the tint instead of
                                                        // swapping it out, so the highlight never
                                                        // flickers away under the pointer.
                                                        isSelected && hover -> colors.primary.copy(alpha = 0.28f)
                                                        isSelected -> colors.primary.copy(alpha = 0.20f)
                                                        hover -> colors.primarySubtle
                                                        else -> colors.surface
                                                    }
                                                )
                                                .onHover { hover = it }
                                                .cursorHand(),
                                            onClick = {
                                                expanded = false
                                                onItemSelected(item)
                                            }
                                        ) {
                                            itemContent?.invoke(item) ?: Text(
                                                item,
                                                color = colors.onSurface,
                                                softWrap = false,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                            if (showScrollbar) {
                                SafeVerticalScrollbar(
                                    rememberScrollbarAdapter(scrollState),
                                    modifier = Modifier.align(Alignment.CenterEnd).height(listHeight)
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ExposedDropdownMenuStack(
    textField: @Composable () -> Unit,
    dropdownMenu: @Composable (boxWidth: Dp, itemHeight: Dp) -> Unit
) {
    SubcomposeLayout { constraints ->
        val textFieldPlaceable =
            subcompose(ExposedDropdownMenuSlot.TextField, textField).first().measure(constraints)

        val dropdownPlaceable = subcompose(ExposedDropdownMenuSlot.Dropdown) {
            // Wider panel than the anchor is fine: it only shows in the
            // open popup, and the anchor layout keeps the field width.
            dropdownMenu(textFieldPlaceable.width.toDp(), textFieldPlaceable.height.toDp())
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))

        layout(textFieldPlaceable.width, textFieldPlaceable.height) {
            textFieldPlaceable.placeRelative(0, 0)
            dropdownPlaceable.placeRelative(0, textFieldPlaceable.height)
        }
    }
}

private enum class ExposedDropdownMenuSlot { TextField, Dropdown }

/**
 * Smart popup position provider that automatically pops up or down depending on available space,
 * and maintains safety margins so it never touches the window edges.
 */
@Immutable
class SmartDropdownPositionProvider(
    private val contentOffset: DpOffset = DpOffset(0.dp, 4.dp),
    private val density: Density,
    private val verticalMargin: Dp = 16.dp,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val marginPx = with(density) { verticalMargin.roundToPx() }
        val contentOffsetX = with(density) { contentOffset.x.roundToPx() * (if (layoutDirection == LayoutDirection.Ltr) 1 else -1) }
        val contentOffsetY = with(density) { contentOffset.y.roundToPx() }

        // Horizontal positioning: align left with anchor, fallback to right align or clamp within window
        val leftToAnchorLeft = anchorBounds.left + contentOffsetX
        val rightToAnchorRight = anchorBounds.right - popupContentSize.width + contentOffsetX
        val maxAvailableX = maxOf(marginPx, windowSize.width - popupContentSize.width - marginPx)
        val x = when {
            leftToAnchorLeft + popupContentSize.width <= windowSize.width - marginPx -> maxOf(marginPx, leftToAnchorLeft)
            rightToAnchorRight >= marginPx -> rightToAnchorRight
            else -> maxAvailableX
        }

        // Vertical positioning: check available space below and above anchor
        val spaceBelow = windowSize.height - anchorBounds.bottom - contentOffsetY - marginPx
        val spaceAbove = anchorBounds.top - contentOffsetY - marginPx

        val y = if (spaceBelow >= popupContentSize.height || spaceBelow >= spaceAbove) {
            // Fits below anchor or below has more space
            anchorBounds.bottom + contentOffsetY
        } else {
            // Place above anchor
            anchorBounds.top - popupContentSize.height - contentOffsetY
        }.coerceIn(marginPx, maxOf(marginPx, windowSize.height - popupContentSize.height - marginPx))

        return IntOffset(x, y)
    }
}

/**
 * Universal Searchable Dropdown Popup with auto-focus search, animated fetch, custom query, and scrollbar.
 */
@Composable
fun SearchableDropdownMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<String>,
    selectedItem: String,
    onItemSelected: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    loading: Boolean = false,
    onFetch: (() -> Unit)? = null,
    error: String? = null,
    minWidth: Dp = 220.dp,
    maxWidth: Dp = 480.dp,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    showCustomQueryOption: Boolean = true,
) {
    if (!expanded) return

    val colors = MaterialTheme.colors
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        delay(50)
        searchFocusRequester.requestFocus()
    }
    val filteredItems = remember(items, searchQuery) { filterByTokens(items, searchQuery) }
    val footerText = if (searchQuery.isBlank()) {
        trn("{{count}} model available", "{{count}} models available", items.size, "count" to "${items.size}")
    } else {
        tr(
            "{{shown}} of {{total}} models",
            "shown" to "${filteredItems.size}",
            "total" to "${items.size}",
        )
    }

    val isCustomQuery = showCustomQueryOption && searchQuery.isNotBlank() &&
        items.none { it.equals(searchQuery.trim(), ignoreCase = true) }

    // Keyboard navigation state: highlighted row over the
    // selectable entries (custom "use query" row first, then filtered items).
    // The popup leaves composition when collapsed, so the highlight is
    // re-resolved on every open and whenever the query or model list changes:
    // it starts on the selected entry when the filter keeps it visible,
    // otherwise falls back to the first row.
    val selectableEntries = remember(filteredItems, isCustomQuery, searchQuery) {
        buildList {
            if (isCustomQuery) add(searchQuery.trim())
            addAll(filteredItems)
        }
    }
    var highlightIndex by remember {
        mutableStateOf(initialMenuHighlightIndex(selectedItem, selectableEntries))
    }
    LaunchedEffect(selectableEntries, selectedItem) {
        highlightIndex = initialMenuHighlightIndex(selectedItem, selectableEntries)
    }

    val density = LocalDensity.current
    val positionProvider = remember(density, offset) {
        SmartDropdownPositionProvider(
            contentOffset = offset,
            density = density,
            verticalMargin = 16.dp,
        )
    }

    // Panel width uses minWidth (a tracked anchor or a fixed floor); long
    // model names ellipsize instead of stretching the panel.
    val calculatedWidth = popupPanelWidth(minWidth, 220.dp, maxWidth)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            elevation = 8.dp,
            border = BorderStroke(1.dp, colors.borderDefault),
            color = colors.surface,
            modifier = Modifier.width(calculatedWidth),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                // 1. Search Bar & optional Refresh Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .border(1.dp, colors.inputBorder, RoundedCornerShape(6.dp))
                            .background(colors.inputBackground)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Lucide.Search,
                                contentDescription = tr("Search"),
                                modifier = Modifier.size(13.dp),
                                tint = colors.textMuted,
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    // Keep the placeholder on one line so a
                                    // narrow panel ellipsizes instead of wrapping.
                                    Text(
                                        tr("Search or type model..."),
                                        style = typography.caption.copy(color = colors.textPlaceholder),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocusRequester)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) {
                                                false
                                            } else when (event.key) {
                                                Key.DirectionDown -> {
                                                    highlightIndex = clampMenuIndex(
                                                        highlightIndex + 1,
                                                        selectableEntries.size,
                                                    )
                                                    true
                                                }
                                                Key.DirectionUp -> {
                                                    highlightIndex = clampMenuIndex(
                                                        highlightIndex - 1,
                                                        selectableEntries.size,
                                                    )
                                                    true
                                                }
                                                Key.Enter, Key.NumPadEnter -> {
                                                    if (!event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed &&
                                                        selectableEntries.isNotEmpty()
                                                    ) {
                                                        onItemSelected(selectableEntries[highlightIndex])
                                                        onDismissRequest()
                                                    }
                                                    !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed
                                                }
                                                Key.Escape -> {
                                                    onDismissRequest()
                                                    true
                                                }
                                                else -> false
                                            }
                                        },
                                    singleLine = true,
                                    textStyle = typography.caption.copy(color = colors.textTitle),
                                    cursorBrush = SolidColor(colors.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            if (searchQuery.isNotBlank()) {
                                                onItemSelected(searchQuery.trim())
                                                onDismissRequest()
                                            }
                                        }
                                    ),
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .cursorHand()
                                        .clickable { onSearchQueryChange("") },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Lucide.X,
                                        contentDescription = tr("Clear"),
                                        modifier = Modifier.size(12.dp),
                                        tint = colors.textMuted,
                                    )
                                }
                            }
                        }
                    }

                    if (onFetch != null) {
                        // Spin only while loading; a live infinite transition
                        // would recompose the popup on every frame.
                        val fetchSpinAngle = if (loading) {
                            val fetchTransition = rememberInfiniteTransition(label = "fetchSpin")
                            val angle by fetchTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 800, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart,
                                ),
                                label = "fetchSpinAngle",
                            )
                            angle
                        } else {
                            0f
                        }

                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .cursorHand()
                                .clickable(enabled = !loading) { onFetch() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Lucide.RefreshCw,
                                contentDescription = tr("Refresh models"),
                                modifier = Modifier.size(14.dp).rotate(fetchSpinAngle),
                                tint = colors.primary,
                            )
                        }
                    }
                }

                Divider(color = colors.divider, modifier = Modifier.padding(vertical = 2.dp))

                // 3. List with Scrollbar (virtualized)
                val listState = rememberLazyListState()
                // Keep the highlighted row inside the viewport. Model rows
                // have a fixed 32.dp height; the custom "use query" row
                // lives above the list and never needs scrolling.
                LaunchedEffect(highlightIndex, selectableEntries) {
                    val rowIndex = highlightIndex - (if (isCustomQuery) 1 else 0)
                    if (rowIndex < 0) return@LaunchedEffect
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (visible.none { it.index == rowIndex }) {
                        listState.animateScrollToItem(rowIndex)
                    }
                }
                Box(
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .fillMaxWidth(),
                ) {
                    when {
                        loading && items.isEmpty() -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.primary,
                                )
                                Text(
                                    tr("Fetching models..."),
                                    style = typography.caption.copy(color = colors.textMuted),
                                )
                            }
                        }
                        error != null -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    error,
                                    style = typography.caption.copy(color = MaterialTheme.colors.error),
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (onFetch != null) {
                                    TextButton(
                                        onClick = onFetch,
                                        modifier = Modifier.cursorHand(),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
                                    ) {
                                        Text(tr("Retry"), style = typography.caption)
                                    }
                                }
                            }
                        }
                        filteredItems.isEmpty() -> {
                            if (items.isEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        tr("No remote models found"),
                                        style = typography.caption.copy(color = colors.textTitle, fontWeight = FontWeight.Medium),
                                    )
                                    Text(
                                        tr("Configure Base URL in Settings > AI, or type above."),
                                        style = typography.caption.copy(color = colors.textMuted),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        tr("No matching models in list"),
                                        style = typography.caption.copy(color = colors.textMuted),
                                    )
                                }
                            }
                        }
                        else -> {
                            val customRowOffset = if (isCustomQuery) 1 else 0
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            ) {
                                if (isCustomQuery) {
                                    item(key = "custom_query", contentType = "custom") {
                                        CustomQueryRow(
                                            query = searchQuery.trim(),
                                            highlighted = highlightIndex == 0,
                                            onSelect = {
                                                onItemSelected(searchQuery.trim())
                                                onDismissRequest()
                                            },
                                        )
                                    }
                                }
                                itemsIndexed(
                                    filteredItems,
                                    key = { index, item -> "model:$index:$item" },
                                    contentType = { _, _ -> "model" },
                                ) { index, item ->
                                    ModelRow(
                                        item = item,
                                        selected = item == selectedItem,
                                        highlighted = highlightIndex == index + customRowOffset,
                                        onSelect = {
                                            onItemSelected(item)
                                            onDismissRequest()
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (filteredItems.isNotEmpty()) {
                        SafeVerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }

                // 5. Footer
                if (items.isNotEmpty()) {
                    Divider(color = colors.divider, modifier = Modifier.padding(vertical = 2.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(footerText, style = typography.caption.copy(color = colors.textMuted))
                    }
                }
            }
        }
    }
}

/**
 * The custom "use query" row inside the model menu (fixed 34.dp like before).
 */
@Composable
private fun CustomQueryRow(
    query: String,
    highlighted: Boolean,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colors
    var customHover by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(
                if (highlighted) colors.primarySubtle
                else if (customHover) colors.primarySubtle.copy(alpha = 0.6f)
                else colors.surface
            )
            .onHover { customHover = it }
            .cursorHand()
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Lucide.Plus,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = colors.primary,
        )
        Text(
            text = tr("Use \"{{query}}\"", "query" to query),
            style = typography.caption.copy(
                color = colors.primary,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * A model row inside the menu (fixed 32.dp, ellipsized text, check on the
 * selected entry).
 */
@Composable
private fun ModelRow(
    item: String,
    selected: Boolean,
    highlighted: Boolean,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colors
    var itemHover by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(
                if (selected || highlighted) colors.primarySubtle
                else if (itemHover) colors.primarySubtle.copy(alpha = 0.45f)
                else colors.surface,
            )
            .onHover { itemHover = it }
            .cursorHand()
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            item,
            style = typography.caption.copy(
                color = if (selected || highlighted) colors.primary else colors.textTitle,
                fontWeight = if (selected || highlighted) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Lucide.CircleCheck,
                contentDescription = tr("Selected"),
                modifier = Modifier.size(14.dp),
                tint = colors.primary,
            )
        }
    }
}

/**
 * Model dropdown for Settings with text field + trailing content slot; the
 * in-panel refresh icon is the only fetch entry.
 */
@Composable
fun SearchableModelDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    models: List<String>,
    loading: Boolean,
    error: String? = null,
    onFetchModels: () -> Unit,
    placeholder: String = "deepseek-chat",
    modifier: Modifier = Modifier,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colors
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor = when {
        isFocused -> colors.primary
        else -> colors.inputBorder
    }

    // The trigger sizes to its content instead of stretching over the whole
    // settings row, so the trailing Test button stays right next to it.
    Column(modifier = modifier.widthIn(min = 320.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = 480.dp)
                    .height(36.dp)
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.inputBackground)
                    .padding(start = 12.dp, end = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // The trailing padding reserves the room taken by the arrow.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(end = 28.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.body2.copy(color = colors.textPlaceholder),
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.body2.copy(color = colors.textTitle),
                        interactionSource = interactionSource,
                        cursorBrush = SolidColor(colors.primary),
                    )
                }

                val rotation by animateFloatAsState(if (expanded) 180f else 0f)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .cursorHand()
                        .clickable {
                            expanded = !expanded
                            if (expanded && models.isEmpty() && !loading) {
                                onFetchModels()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.ChevronDown,
                        contentDescription = tr("Dropdown"),
                        modifier = Modifier.size(15.dp).rotate(rotation),
                        tint = colors.textMuted,
                    )
                }

                SearchableDropdownMenuPopup(
                    expanded = expanded,
                    onDismissRequest = {
                        expanded = false
                        searchQuery = ""
                    },
                    items = models,
                    selectedItem = value,
                    onItemSelected = onValueChange,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    loading = loading,
                    onFetch = onFetchModels,
                    error = error,
                    // The settings trigger is a fixed compact field, so an
                    // anchor-tracked panel would be visually too wide.
                    minWidth = 320.dp,
                    maxWidth = 360.dp,
                    offset = DpOffset(0.dp, 4.dp),
                )
            }

            trailingContent()
        }

        if (error != null && !expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                error,
                color = colors.error,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

/**
 * Compact Pill-style Model Selector for the Create / Composer screen.
 */
@Composable
fun CompactModelSelector(
    selectedModel: String,
    availableModels: List<String>,
    loading: Boolean,
    onModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colors
    val localDensity = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isHovered by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0.dp) }

    val displayLabel = if (selectedModel.isNotBlank()) selectedModel else tr("Select model")

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(
                1.dp,
                if (expanded) colors.primary
                else if (isHovered && enabled) colors.primary.copy(alpha = 0.5f)
                else colors.borderDefault,
            ),
            color = if (isHovered && enabled) colors.primarySubtle.copy(alpha = 0.35f) else colors.inputBackground,
            modifier = Modifier
                .height(34.dp)
                .onSizeChanged { anchorWidth = with(localDensity) { it.width.toDp() } }
                .cursorHand()
                .onHover { isHovered = it }
                .clickable(enabled = enabled) {
                    expanded = !expanded
                    if (expanded && availableModels.isEmpty() && !loading) {
                        onFetchModels()
                    }
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Lucide.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = if (selectedModel.isNotBlank()) colors.primary else colors.textMuted,
                )
                Text(
                    text = displayLabel,
                    style = typography.caption.copy(
                        fontWeight = if (selectedModel.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedModel.isNotBlank()) colors.textTitle else colors.textMuted,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                val rotation by animateFloatAsState(if (expanded) 180f else 0f)
                Icon(
                    Lucide.ChevronDown,
                    contentDescription = tr("Select model"),
                    modifier = Modifier.size(13.dp).rotate(rotation),
                    tint = colors.textMuted,
                )
            }
        }

        SearchableDropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
            items = availableModels,
            selectedItem = selectedModel,
            onItemSelected = onModelChange,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            loading = loading,
            onFetch = onFetchModels,
            minWidth = anchorWidth,
            maxWidth = 480.dp,
            offset = DpOffset(0.dp, 4.dp),
        )
    }
}