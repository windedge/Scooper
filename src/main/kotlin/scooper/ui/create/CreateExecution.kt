package scooper.ui.create

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import scooper.viewmodels.ActiveExecution
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.viewmodels.ExecutionStep
import scooper.viewmodels.executionSummaryLineText
import scooper.viewmodels.runningStepTitle
import scooper.viewmodels.formatStepDuration
import scooper.viewmodels.visibleExecutionSteps
import scooper.ui.icons.*
import scooper.ui.theme.*


/** Modifier for the expanded step list: left inset plus a faint 1dp guide line. */
@Composable
internal fun executionDetailIndent(): Modifier {
    val lineColor = colors.divider
    return Modifier
        .drawBehind {
            drawLine(
                color = lineColor,
                start = Offset(1.dp.toPx(), 0f),
                end = Offset(1.dp.toPx(), size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        // The trailing inset matches the header row's padding so trailing
        // content (e.g. the step duration) does not hug the detail edge.
        .padding(start = 12.dp, end = 10.dp, top = 2.dp, bottom = 2.dp)
}
@Composable
internal fun ActiveExecutionCard(active: ActiveExecution) {
    // Transient run card (openchamber style): one fold covers the whole run.
    // Collapsed by default, it shows only the summary line (live activity text
    // plus TypingDots, or "Working..." before the first event); clicking it
    // reveals every step detail under a 1dp guide line. The card leaves the
    // chat list between runs, so the fold state resets then.
    var activitiesExpanded by remember { mutableStateOf(false) }
    var activitiesHovered by remember { mutableStateOf(false) }
    val visibleSteps = visibleExecutionSteps(active.steps, activitiesExpanded)
    Surface(
        color = colors.inputBackground,
        contentColor = colors.textTitle,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.borderDefault),
        // Content-hugging width: the collapsed toolbar is exactly as wide as
        // its summary line, not the whole conversation column.
        modifier = Modifier
            .wrapContentWidth(align = Alignment.Start)
            .widthIn(max = 560.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Hover highlight and click live on a fixed-height header Box that
            // spans the full toolbar width: the grey hover fill reaches the
            // rounded border with no background-colored gap, which the summary
            // row's own padding would leave. The content keeps its insets via
            // inner padding; running + expanded just renders the plain row.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (activitiesHovered) colors.backgroundHover else Color.Transparent)
                    .cursorHand()
                    .onHover { activitiesHovered = it }
                    .clickable { activitiesExpanded = !activitiesExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                ExecutionSummaryLine(
                    text = executionSummaryLineText(
                        active.steps,
                        running = true,
                        // Expanded detail renders the live text inside the step
                        // block; the summary line stays on the generic wording.
                        expanded = activitiesExpanded,
                        currentActivity = active.currentActivity,
                    ),
                    expanded = activitiesExpanded,
                    running = true,
                    fillMaxWidth = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (activitiesExpanded) {
                Column(
                    modifier = executionDetailIndent(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    visibleSteps.forEach { step ->
                        ExecutionStepBlock(
                            step = step,
                            liveActivity = if (step.isRunning) active.currentActivity else null,
                        )
                    }
                }
            }
        }
    }
}
/**
 * Rendered text lines of the markdown body shown before an agent reply collapses.
 * The body uses the caption style with 1.5x line height (see MarkdownText).
 */
private const val AGENT_BODY_COLLAPSED_LINES = 4

/**
 * Agent reply body with a local expand/collapse toggle: over-long explanations
 * render at most [AGENT_BODY_COLLAPSED_LINES] lines until the user clicks
 * "Show more". Error bubbles never use this (they keep the full rendering), and
 * the toggle state is pure UI state, the ViewModel data stays untouched.
 */
@Composable
internal fun CollapsibleAgentBody(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    val density = LocalDensity.current
    // MarkdownText renders paragraphs with caption.fontSize * 1.5 line height, so
    // 4 lines plus a small slack fit exactly; anything taller gets clipped.
    val collapsedMaxHeight =
        with(density) { (typography.caption.fontSize * 1.5f * AGENT_BODY_COLLAPSED_LINES).toDp() } + 1.dp
    val collapsedMaxPx = with(density) { collapsedMaxHeight.roundToPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (expanded) {
                    Modifier
                } else {
                    Modifier.heightIn(max = collapsedMaxHeight).clipToBounds()
                },
            ),
    ) {
        MarkdownText(
            text = text,
            modifier = Modifier.onSizeChanged { size ->
                // The clipped column reports exactly the cap height when its
                // content overflows, and less than the cap when it fits.
                overflowing = size.height >= collapsedMaxPx - 1
            },
        )
    }
    if (overflowing) {
        androidx.compose.foundation.text.selection.DisableSelection {
            Text(
                if (expanded) "Show less" else "Show more",
                style = typography.caption.copy(
                    color = colors.primary,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .cursorHand()
                    .clickable { expanded = !expanded }
                    .padding(top = 2.dp),
            )
        }
    }
}
@Composable
private fun StepPhaseTag(phase: String, isFailed: Boolean = false) {
    val (bg, fg, border) = if (isFailed) {
        Triple(
            colors.dangerBg,
            colors.dangerDefault,
            colors.dangerDefault.copy(alpha = 0.35f),
        )
    } else {
        phaseColors(phase)
    }
    Text(
        phase.uppercase(),
        style = typography.overline.copy(
            fontWeight = FontWeight.Bold,
            color = fg,
            fontSize = typography.overline.fontSize * 0.85f,
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(min = 84.dp)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
/**
 * The openchamber-style one-line run summary with a trailing chevron. The
 * running variant prepends TypingDots; the finished variant tints the mark
 * red on failure and stays a plain ghost caption otherwise.
 */
@Composable
internal fun ExecutionSummaryLine(
    text: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    failed: Boolean = false,
    // Filled rows (running toolbar) pass true so the chevron pins to the
    // row's trailing edge; content-sized callers keep the row at text width.
    fillMaxWidth: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        if (running) TypingDots()
        Text(
            text,
            style = typography.caption.copy(
                fontWeight = FontWeight.SemiBold,
                color = if (failed) colors.dangerDefault else colors.textBody,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (fillMaxWidth) Modifier.weight(1f) else Modifier,
        )
        if (!running) {
            // Mirror the spacer the running variant gets from TypingDots so
            // the chevron keeps the same inset against the row edge.
            Spacer(Modifier.width(2.dp))
        }
        Icon(
            if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
            contentDescription = "Toggle execution details",
            modifier = Modifier.size(12.dp),
            tint = colors.textMuted,
        )
    }
}
@Composable
internal fun ExecutionStepBlock(
    step: ExecutionStep,
    isFailed: Boolean = false,
    liveActivity: String? = null,
) {
    // Whole-run fold lives on the card level (see ActiveExecutionCard / the
    // finalized steps block); a step block always renders all its activity lines.
    val titleColor = when {
        isFailed -> colors.dangerDefault
        step.isRunning -> colors.primary
        else -> colors.textBody
    }
    Column(
        modifier = if (step.isRunning) {
            // Running step keeps only vertical breathing room: no horizontal indent,
            // phase rows align flush with completed steps on the card surface.
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        } else {
            Modifier
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepPhaseTag(phase = step.phase, isFailed = isFailed)
            // Phase title stays on one line: shrink with ellipsis so the duration
            // remains pinned to the right edge instead of being pushed to a wrapped
            // second line.
            // A running assist step mirrors its live tool activity in the header
            // instead of the static start title; settled steps keep their title.
            Text(
                if (step.isRunning) {
                    runningStepTitle(step.phase, liveActivity, step.title)
                } else {
                    step.title
                },
                style = typography.caption.copy(
                    color = titleColor,
                    fontWeight = if (step.isRunning || isFailed) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!step.isRunning && step.durationMs > 0) {
                Text(
                    formatStepDuration(step.durationMs),
                    style = typography.caption.copy(color = colors.textMuted),
                    modifier = Modifier.wrapContentWidth(),
                )
            }
        }
        // Thought row would sit here at the same indent as the tool lines
        // below (icon + "Thought"/"Thinking" + collapsed one-line summary,
        // expanded height-capped at 320dp). The engine event pipeline has no
        // reasoning channel yet, so no host state carries a ThoughtLine; see
        // the TODO in ExecutionModels.thoughtLineFromText.
        // TODO(engine): surface model reasoning deltas (e.g. an
        // onThoughtActivity callback next to onToolActivity in
        // ScoopManifestGenerator) so this row can render.
        // Plain activity lines (no chip backdrop): the expanded detail already
        // sits on a guide-line indent, a second background layer is noise.
        step.toolActivities.forEach { line ->
            Text(
                line,
                style = typography.caption.copy(
                    color = if (line.contains("✖")) colors.dangerDefault else colors.textMuted,
                    fontSize = typography.caption.fontSize * 0.92f,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        liveActivity?.let { activity ->
            Text(
                activity,
                style = typography.caption.copy(
                    color = colors.primary,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (!step.summary.isNullOrBlank()) {
            Text(
                "• ${step.summary}",
                style = typography.caption.copy(color = colors.textBody),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
@Composable
private fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 450, delayMillis = index * 140, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.textMuted.copy(alpha = alpha)),
            )
        }
    }
}