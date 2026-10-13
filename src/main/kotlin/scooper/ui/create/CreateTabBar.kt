package scooper.ui.create

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.ui.icons.Lucide
import scooper.ui.icons.X
import scooper.ui.theme.primarySubtle
import scooper.ui.theme.inputBackground
import scooper.ui.theme.borderDefault
import scooper.ui.theme.textMuted
import scooper.ui.theme.textBody
import scooper.util.cursorHand
import scooper.viewmodels.CreateTabInfo

/**
 * Browser-style tab bar for Create sessions, rendered inside the Create page
 * right below its header. One chip per tab with title, close button and
 * active highlight. Horizontally scrollable when crowded.
 *
 * New sessions are created only via the header "+ New Session" button, which
 * routes through CreateTabController.createTab; the bar intentionally has no
 * duplicate entry point.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CreateTabBar(
    tabs: List<CreateTabInfo>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabScrollState = rememberScrollState()
    val density = LocalDensity.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(tabScrollState)
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    // horizontalScroll only consumes x deltas (tilted wheel or
                    // trackpad pan); a plain vertical wheel is dropped, so route
                    // y notches into the horizontal state browser-style: wheel
                    // down scrolls forward (reveals tabs on the right). Consume
                    // them only while the strip can still move so an idle strip
                    // keeps forwarding the wheel to its ancestors.
                    val notches = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                    if (notches != 0f) {
                        val canMove =
                            if (notches > 0f) tabScrollState.canScrollForward else tabScrollState.canScrollBackward
                        if (canMove) {
                            val stepPx = with(density) { 48.dp.toPx() }
                            tabScrollState.dispatchRawDelta(notches * stepPx)
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val isActive = tab.tabId == activeTabId
                // Full-title tooltip only when the label actually overflows and
                // gets ellipsized; a fully visible title needs no tooltip.
                var titleTruncated by remember(tab.tabId, tab.title) { mutableStateOf(false) }
                Tooltip(tab.title, position = TooltipPosition.Bottom, enabled = titleTruncated) {
                    Surface(
                        color = if (isActive) colors.primarySubtle else colors.inputBackground,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isActive) colors.primary.copy(alpha = 0.6f) else colors.borderDefault,
                        ),
                        modifier = Modifier
                            .cursorHand()
                            .clickable { onSelect(tab.tabId) },
                    ) {
                        // Custom layout so the chip clamps to [120, 200]dp while the
                        // title keeps its natural width when it fits. The natural
                        // width comes from maxIntrinsicWidth, the only sanctioned
                        // way to pre-query a Measurable: the regular Layout does not
                        // allow measuring the same Measurable twice (unlike the
                        // deprecated MultiMeasureLayout), so a loose first measure
                        // + fixed remeasure used to crash with
                        // "measure() may not be called multiple times on the same
                        // Measurable". Long titles ellipsize at the width left
                        // after the close icon and onTextLayout refreshes
                        // titleTruncated for the tooltip.
                        Layout(
                            content = {
                                Text(
                                    tab.title,
                                    style = typography.caption.copy(
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isActive) colors.primary else colors.textBody,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { titleTruncated = it.hasVisualOverflow },
                                )
                                Icon(
                                    Lucide.X,
                                    contentDescription = "Close tab",
                                    modifier = Modifier
                                        .size(13.dp)
                                        .clickable { onClose(tab.tabId) },
                                    tint = colors.textMuted,
                                )
                            },
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        ) { measurables, constraints ->
                            val spacing = 6.dp.roundToPx()
                            val icon = measurables[1].measure(Constraints())
                            val naturalTextWidth = measurables[0].maxIntrinsicWidth(0)
                            val natural = naturalTextWidth + spacing + icon.width
                            val minPx = 120.dp.roundToPx()
                            val maxPx = 200.dp.roundToPx()
                            val target = natural.coerceIn(minPx, maxPx)
                            // textWidth is always >= 0: natural already covers
                            // icon + spacing, and even the 120dp floor far
                            // exceeds icon + spacing.
                            val textWidth = target - spacing - icon.width
                            val text = measurables[0].measure(
                                constraints.copy(minWidth = textWidth, maxWidth = textWidth)
                            )
                            val h = maxOf(text.height, icon.height)
                            layout(target, h) {
                                text.placeRelative(0, Alignment.CenterVertically.align(text.height, h))
                                icon.placeRelative(
                                    target - icon.width,
                                    Alignment.CenterVertically.align(icon.height, h),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
