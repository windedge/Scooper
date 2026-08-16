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
import androidx.compose.ui.draw.rotate
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
 *   instead of removing it.
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
                    val menuHeight = minOf(itemHeight * items.size, maxMenuHeight)
                    val lastIndex = items.lastIndex.coerceAtLeast(0)
                    val density = LocalDensity.current
                    // Center the selected item in the visible menu area instead
                    // of pinning it to the top: its center offset minus half the
                    // remaining viewport. ScrollState clamps the value to
                    // [0, maxValue], so items near either end simply stick to the
                    // edge (no selection or index 0 clamps back to the top).
                    val centeredIndex = selectedIndex.coerceIn(0, lastIndex)
                    val centeredOffsetPx = with(density) {
                        itemHeight.roundToPx() * centeredIndex -
                                (menuHeight.roundToPx() - itemHeight.roundToPx()) / 2
                    }.coerceAtLeast(0)
                    // The DropdownMenu content is freshly composed on each
                    // expansion, so this initial position re-applies every time
                    // the menu opens.
                    val scrollState = rememberScrollState(initial = centeredOffsetPx)
                    Box {
                        Column(
                            modifier = Modifier
                                .height(menuHeight)
                                .width(listWidth)
                                .verticalScroll(scrollState)
                        ) {
                            items.forEachIndexed { index, item ->
                                var hover by remember { mutableStateOf(false) }
                                val isSelected = index == selectedIndex
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
                        if (showScrollbar) {
                            VerticalScrollbar(
                                rememberScrollbarAdapter(scrollState),
                                modifier = Modifier.align(Alignment.CenterEnd).height(menuHeight)
                            )
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
