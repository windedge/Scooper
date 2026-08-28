package scooper.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
 */
@Composable
fun ExposedDropdownMenu(
    items: List<String>,
    modifier: Modifier = Modifier,
    selected: String = items[0],
    onItemSelected: (String) -> Unit,
    textFill: Boolean = false,
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
                        Box(if (textFill) Modifier.weight(1f) else Modifier) { innerTextField() }
                        Spacer(modifier = Modifier.width(8.dp))
                        val rotation by animateFloatAsState(if (expanded) 180F else 0F)
                        Icon(
                            Lucide.ChevronDown,
                            contentDescription = "Dropdown Arrow",
                            modifier = Modifier.rotate(rotation),
                            tint = colors.textMuted,
                        )
                    }
                }
            )
        },
        dropdownMenu = { boxWidth, itemHeight ->
            Box(
                Modifier
                    .width(boxWidth)
                    .wrapContentSize(Alignment.TopStart)
            ) {
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    items.forEach { item ->
                        var hover by remember { mutableStateOf(false) }
                        DropdownMenuItem(
                            modifier = Modifier
                                .height(itemHeight)
                                .width(boxWidth)
                                .background(if (hover) colors.primarySubtle else colors.surface)
                                .onHover { hover = it }
                                .cursorHand(),
                            onClick = {
                                expanded = false
                                onItemSelected(item)
                            }
                        ) {
                            Text(item, color = colors.onSurface)
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

    val filteredItems = remember(items, searchQuery) {
        if (searchQuery.isBlank()) items
        else items.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

    val isCustomQuery = showCustomQueryOption && searchQuery.isNotBlank() &&
        items.none { it.equals(searchQuery.trim(), ignoreCase = true) }

    val density = LocalDensity.current
    val positionProvider = remember(density, offset) {
        SmartDropdownPositionProvider(
            contentOffset = offset,
            density = density,
            verticalMargin = 16.dp,
        )
    }

    val longestItemLength = remember(items, searchQuery) {
        maxOf(
            items.maxOfOrNull { it.length } ?: 0,
            searchQuery.length + 6,
            16,
        )
    }
    val calculatedWidth = remember(longestItemLength, minWidth, maxWidth) {
        val estimated = (longestItemLength * 7.5f + 56f).dp
        estimated.coerceIn(minWidth, maxWidth)
    }

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
                                contentDescription = "Search",
                                modifier = Modifier.size(13.dp),
                                tint = colors.textMuted,
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search or type model...",
                                        style = typography.caption.copy(color = colors.textPlaceholder),
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocusRequester),
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
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(12.dp),
                                        tint = colors.textMuted,
                                    )
                                }
                            }
                        }
                    }

                    if (onFetch != null) {
                        val fetchTransition = rememberInfiniteTransition()
                        val fetchSpinAngle by fetchTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 800, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart,
                            ),
                        )

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
                                contentDescription = "Refresh models",
                                modifier = Modifier.size(14.dp).then(
                                    if (loading) Modifier.rotate(fetchSpinAngle) else Modifier
                                ),
                                tint = colors.primary,
                            )
                        }
                    }
                }

                Divider(color = colors.divider, modifier = Modifier.padding(vertical = 2.dp))

                // 2. Custom Option
                if (isCustomQuery) {
                    var customHover by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .background(if (customHover) colors.primarySubtle.copy(alpha = 0.6f) else colors.surface)
                            .onHover { customHover = it }
                            .cursorHand()
                            .clickable {
                                onItemSelected(searchQuery.trim())
                                onDismissRequest()
                            }
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
                            text = "Use \"${searchQuery.trim()}\"",
                            style = typography.caption.copy(
                                color = colors.primary,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Divider(color = colors.divider, modifier = Modifier.padding(vertical = 2.dp))
                }

                // 3. List with Scrollbar
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(end = 8.dp),
                    ) {
                        when {
                            loading && items.isEmpty() -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = colors.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Loading models...",
                                        style = typography.caption.copy(color = colors.textMuted),
                                    )
                                }
                            }
                            items.isEmpty() -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        "No remote models found",
                                        style = typography.caption.copy(color = colors.textTitle, fontWeight = FontWeight.Medium),
                                    )
                                    Text(
                                        "Configure Base URL in Settings > AI, or type above.",
                                        style = typography.caption.copy(color = colors.textMuted),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            filteredItems.isEmpty() -> {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "No matching models in list",
                                        style = typography.caption.copy(color = colors.textMuted),
                                    )
                                }
                            }
                            else -> {
                                filteredItems.forEach { item ->
                                    var itemHover by remember { mutableStateOf(false) }
                                    val isSelected = item == selectedItem
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(32.dp)
                                            .background(
                                                if (isSelected) colors.primarySubtle
                                                else if (itemHover) colors.primarySubtle.copy(alpha = 0.45f)
                                                else colors.surface,
                                            )
                                            .onHover { itemHover = it }
                                            .cursorHand()
                                            .clickable {
                                                onItemSelected(item)
                                                onDismissRequest()
                                            }
                                            .padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            item,
                                            style = typography.caption.copy(
                                                color = if (isSelected) colors.primary else colors.textTitle,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (isSelected) {
                                            Spacer(Modifier.width(8.dp))
                                            Icon(
                                                Lucide.CircleCheck,
                                                contentDescription = "Selected",
                                                modifier = Modifier.size(14.dp),
                                                tint = colors.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SafeVerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }

                // 4. Footer
                if (items.isNotEmpty()) {
                    Divider(color = colors.divider, modifier = Modifier.padding(vertical = 2.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (searchQuery.isBlank()) "${items.size} models available"
                            else "${filteredItems.size} of ${items.size} models",
                            style = typography.caption.copy(color = colors.textMuted),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Model dropdown for Settings with text field + Fetch button + trailing content slot.
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

    Column(modifier = modifier.widthIn(min = 360.dp).fillMaxWidth(0.92f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.inputBackground)
                    .padding(start = 12.dp, end = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.body2.copy(color = colors.textPlaceholder),
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.body2.copy(color = colors.textTitle),
                            interactionSource = interactionSource,
                            cursorBrush = SolidColor(colors.primary),
                        )
                    }

                    Spacer(Modifier.width(4.dp))
                    val rotation by animateFloatAsState(if (expanded) 180f else 0f)
                    Box(
                        modifier = Modifier
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
                            contentDescription = "Dropdown",
                            modifier = Modifier.size(15.dp).rotate(rotation),
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
                    items = models,
                    selectedItem = value,
                    onItemSelected = onValueChange,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    loading = loading,
                    onFetch = onFetchModels,
                    error = error,
                    offset = DpOffset(0.dp, 4.dp),
                )
            }

            // Fetch Button (with spinning animation)
            val fetchTransition = rememberInfiniteTransition()
            val fetchSpinAngle by fetchTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )

            OutlinedButton(
                onClick = {
                    onFetchModels()
                    expanded = true
                },
                enabled = !loading,
                modifier = Modifier.height(36.dp).cursorHand(),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, colors.inputBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    backgroundColor = colors.inputBackground,
                    contentColor = colors.textTitle,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(
                    Lucide.RefreshCw,
                    contentDescription = "Fetch models",
                    modifier = Modifier.size(14.dp).then(
                        if (loading) Modifier.rotate(fetchSpinAngle) else Modifier
                    ),
                    tint = colors.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (loading) "Fetching..." else "Fetch",
                    style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
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
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isHovered by remember { mutableStateOf(false) }

    val displayLabel = if (selectedModel.isNotBlank()) selectedModel else "Select model"

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
                    contentDescription = "Select model",
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
            offset = DpOffset(0.dp, 4.dp),
        )
    }
}
