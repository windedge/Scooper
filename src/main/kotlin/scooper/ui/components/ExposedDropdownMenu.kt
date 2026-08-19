package scooper.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import scooper.ui.icons.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import scooper.ui.theme.inputBackground
import scooper.ui.theme.inputBorder
import scooper.ui.theme.primarySubtle
import scooper.ui.theme.textMuted
import scooper.ui.theme.textPlaceholder
import scooper.util.tr
import scooper.util.cursorHand
import scooper.util.onHover

/**
 * A basic implementation of the Exposed Dropdown Menu component
 *
 * @param itemContent optional custom rendering for a dropdown item (e.g. to
 *   preview it with its own font); defaults to a plain [Text] with the
 *   onSurface color.
 * @param maxMenuHeight maximum height of the open menu; taller menus scroll.
 * @param menuMinWidth minimum width of the open menu; defaults to the width of
 *   the trigger field. Pass a larger value (or the measured width of the
 *   longest item) to let the menu grow wider than the trigger.
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
 *   menu that live-filters [items] (case-insensitive substring match); the
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
    itemContent: (@Composable (String) -> Unit)? = null,
    maxMenuHeight: Dp = 320.dp,
    menuMinWidth: Dp = Dp.Unspecified,
    showScrollbar: Boolean = false,
    selectedIndex: Int = -1,
    searchable: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
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
                        innerTextField()
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
            val menuWidth =
                if (menuMinWidth != Dp.Unspecified) maxOf(boxWidth, menuMinWidth) else boxWidth
            // Space reserved at the end of the list so the scrollbar never overlaps a label.
            val scrollbarReserve = if (showScrollbar) 12.dp else 0.dp
            val listWidth = menuWidth - scrollbarReserve
            Box(
                Modifier
                    .width(menuWidth)
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
                        items.filter { it.contains(query, ignoreCase = true) }
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
                                VerticalScrollbar(
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
            dropdownMenu(textFieldPlaceable.width.toDp(), textFieldPlaceable.height.toDp())
        }.first().measure(constraints)

        layout(textFieldPlaceable.width, textFieldPlaceable.height) {
            textFieldPlaceable.placeRelative(0, 0)
            dropdownPlaceable.placeRelative(0, textFieldPlaceable.height)
        }
    }
}

private enum class ExposedDropdownMenuSlot { TextField, Dropdown }

@Suppress("unused")
@Composable
fun ExposedDropdownMenuX(
    items: List<String>,
    selected: String = items[0],
    onItemSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    BasicTextField(
        value = selected,
        onValueChange = { },
        modifier = Modifier,
        readOnly = true,
        textStyle = TextStyle(color = colors.onSurface),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.height(36.dp)
                    .background(color = colors.inputBackground, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                innerTextField()
                Spacer(modifier = Modifier.width(8.dp))
                val rotation by animateFloatAsState(if (expanded) 180F else 0F)
                Icon(
                    Lucide.ChevronDown,
                    contentDescription = tr("Dropdown Arrow"),
                    modifier = Modifier.rotate(rotation),
                    tint = colors.textMuted,
                )
                DropdownMenu(
                    expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    items.forEach { label ->
                        DropdownMenuItem(onClick = {
                            onItemSelected(label)
                            expanded = false
                        }) {
                            Text(label, color = colors.onSurface)
                        }
                    }
                }
            }
        }
    )
}
