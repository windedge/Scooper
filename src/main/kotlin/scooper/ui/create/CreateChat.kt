package scooper.ui.create

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import scooper.ui.components.DialogTextField
import scooper.ui.components.SelectableContainer
import scooper.viewmodels.AskUserRequest
import scooper.viewmodels.QuestionnaireOption
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.service.AiErrorAction
import scooper.viewmodels.ChatMessage
import scooper.viewmodels.ChatRole
import scooper.viewmodels.ManifestRefSegment
import scooper.viewmodels.splitManifestRefSegments
import scooper.viewmodels.PendingImage
import scooper.viewmodels.executionSummaryLineText
import scooper.viewmodels.formatTokenFooter
import scooper.viewmodels.DOWNLOAD_DETAIL_MAX
import scooper.viewmodels.elideUrlMiddle
import scooper.viewmodels.isFailedExecutionStep
import scooper.ui.icons.*
import scooper.ui.theme.*


@Composable
internal fun AskUserBubble(
    request: AskUserRequest,
    onAnswer: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val questions = request.questions
    val isSingleQuestion = questions.size <= 1
    val isDownloadPrompt = remember(request) { request.isDownloadPrompt }

    var currentTab by remember(request.id) { mutableStateOf(0) }
    val answers = remember(request.id) { mutableStateMapOf<String, String>() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PhaseTag(
            phase = if (isDownloadPrompt) "download" else "questionnaire",
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Surface(
            color = colors.surface,
            contentColor = colors.textTitle,
            shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.55f)),
            elevation = 2.dp,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .wrapContentWidth(align = Alignment.Start),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (isDownloadPrompt) Lucide.Box else Lucide.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = colors.primary,
                    )
                    Text(
                        if (isDownloadPrompt) "Download Confirmation" else "AI Questionnaire",
                        style = typography.subtitle2.copy(
                            fontWeight = FontWeight.Bold,
                            color = colors.textTitle,
                        ),
                    )
                }

                // Tab Bar for multi-question questionnaire
                if (!isSingleQuestion) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.inputBackground, RoundedCornerShape(6.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        questions.forEachIndexed { index, q ->
                            val isActive = currentTab == index
                            val isAnswered = answers.containsKey(q.id)
                            val label = q.label ?: "Q${index + 1}"

                            Surface(
                                color = if (isActive) colors.surface else Color.Transparent,
                                shape = RoundedCornerShape(4.dp),
                                border = if (isActive) BorderStroke(1.dp, colors.borderDefault) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .cursorHand()
                                    .clickable { currentTab = index },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isAnswered) colors.updateDefault else colors.textMuted.copy(alpha = 0.5f)
                                            ),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        label,
                                        style = typography.caption.copy(
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isActive) colors.primary else colors.textBody,
                                        ),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }

                        // Submit tab
                        val isSubmitTab = currentTab == questions.size
                        val allDone = questions.all { answers.containsKey(it.id) }
                        Surface(
                            color = if (isSubmitTab) colors.surface else Color.Transparent,
                            shape = RoundedCornerShape(4.dp),
                            border = if (isSubmitTab) BorderStroke(1.dp, colors.borderDefault) else null,
                            modifier = Modifier
                                .weight(1f)
                                .cursorHand()
                                .clickable { currentTab = questions.size },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Lucide.CircleCheck,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (allDone) colors.updateDefault else colors.textMuted,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Submit",
                                    style = typography.caption.copy(
                                        fontWeight = if (isSubmitTab) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSubmitTab) colors.primary else colors.textMuted,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Question Body or Submit Summary
                if (currentTab == questions.size && !isSingleQuestion) {
                    // Summary tab
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Ready to submit answers:",
                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold, color = colors.textTitle),
                        )
                        questions.forEach { q ->
                            val ans = answers[q.id]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.inputBackground, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    q.label ?: q.prompt.take(24),
                                    style = typography.caption.copy(color = colors.textBody),
                                )
                                Text(
                                    ans ?: "Not selected (Auto)",
                                    style = typography.caption.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (ans != null) colors.primary else colors.textMuted,
                                    ),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = {
                                    val summary = questions.joinToString("\n") { q ->
                                        val ans = answers[q.id] ?: "auto decide"
                                        "${q.label ?: q.id}: $ans"
                                    }
                                    onAnswer(summary)
                                },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.cursorHand().height(32.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = colors.primary,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(Lucide.CircleCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Submit All", style = typography.caption.copy(fontWeight = FontWeight.SemiBold))
                            }
                        }
                    }
                } else {
                    val currentQuestion = questions.getOrNull(currentTab) ?: questions.first()

                    // Prompt text
                    SelectableContainer {
                        Text(
                            currentQuestion.prompt,
                            style = typography.body2.copy(
                                color = colors.textTitle,
                                lineHeight = typography.body2.fontSize * 1.45f,
                            ),
                        )
                    }

                    var inputMode by remember(currentQuestion.id) { mutableStateOf(false) }
                    var customInput by remember(currentQuestion.id) { mutableStateOf("") }

                    val allOptions = remember(currentQuestion) {
                        val list = currentQuestion.options.toMutableList()
                        if (currentQuestion.allowOther) {
                            list.add(
                                QuestionnaireOption(
                                    label = "Type something.",
                                    value = "__other__",
                                    description = "Write a custom answer or instruction",
                                )
                            )
                        }
                        list
                    }

                    // Options List
                    if (allOptions.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            allOptions.forEachIndexed { optIndex, opt ->
                                val isOther = opt.value == "__other__"
                                val isSelected = answers[currentQuestion.id] == opt.resolvedValue()

                                if (isOther && inputMode) {
                                    // Expanded custom input card
                                    Surface(
                                        color = colors.inputBackground,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, colors.primary),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clip(CircleShape)
                                                        .background(colors.primary),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        (optIndex + 1).toString(),
                                                        style = typography.caption.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                        ),
                                                    )
                                                }
                                                Text(
                                                    "Type something.",
                                                    style = typography.caption.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = colors.primary,
                                                    ),
                                                )
                                            }

                                                fun submitCustomInput() {
                                                    if (customInput.isNotBlank()) {
                                                        val text = customInput.trim()
                                                        answers[currentQuestion.id] = text
                                                        inputMode = false
                                                        if (isSingleQuestion) {
                                                            onAnswer("${currentQuestion.label ?: "Question"}: user wrote: $text")
                                                        } else {
                                                            if (currentTab < questions.size - 1) {
                                                                currentTab++
                                                            } else {
                                                                currentTab = questions.size
                                                            }
                                                        }
                                                    }
                                                }

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    DialogTextField(
                                                        value = customInput,
                                                        onValueChange = { customInput = it },
                                                        placeholder = "Type your response here...",
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .onPreviewKeyEvent { event ->
                                                                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                                                    submitCustomInput()
                                                                    true
                                                                } else {
                                                                    false
                                                                }
                                                            },
                                                    )
                                                    Button(
                                                        onClick = { submitCustomInput() },
                                                        enabled = customInput.isNotBlank(),
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier.cursorHand().height(32.dp),
                                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                                        colors = ButtonDefaults.buttonColors(
                                                        backgroundColor = colors.primary,
                                                        contentColor = Color.White,
                                                    ),
                                                ) {
                                                    Text("Submit", style = typography.caption.copy(fontWeight = FontWeight.SemiBold))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    OptionCard(
                                        index = optIndex + 1,
                                        label = opt.label,
                                        description = opt.description,
                                        selected = isSelected,
                                        onClick = {
                                            if (isOther) {
                                                inputMode = true
                                            } else {
                                                val answerValue = opt.resolvedValue()
                                                answers[currentQuestion.id] = answerValue
                                                if (isSingleQuestion) {
                                                    val formatted = if (opt.value != null && opt.value != opt.label) {
                                                        "${currentQuestion.label ?: "Question"}: user selected: ${optIndex + 1}. ${opt.label} (${opt.value})"
                                                    } else {
                                                        "${currentQuestion.label ?: "Question"}: user selected: ${optIndex + 1}. ${opt.label}"
                                                    }
                                                    onAnswer(formatted)
                                                } else {
                                                    if (currentTab < questions.size - 1) {
                                                        currentTab++
                                                    } else {
                                                        currentTab = questions.size
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        // No options and no write-in: the composer below is
                        // the only way to answer, so point the user at it.
                        Text(
                            "No options provided. Type your answer in the composer below.",
                            style = typography.caption.copy(color = colors.textMuted),
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun OptionCard(
    index: Int,
    label: String,
    description: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    var isHovered by remember { mutableStateOf(false) }

    val bg = when {
        selected -> colors.primarySubtle
        isHovered -> colors.inputBackground
        else -> colors.surface
    }
    val borderColor = when {
        selected -> colors.primary
        isHovered -> colors.primary.copy(alpha = 0.6f)
        else -> colors.borderDefault
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .cursorHand()
            .onHover { isHovered = it }
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Index badge
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else colors.inputBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    index.toString(),
                    style = typography.caption.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color.White else colors.textBody,
                    ),
                )
            }

            // Text
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    label,
                    style = typography.body2.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) colors.primary else colors.textTitle,
                    ),
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        description,
                        style = typography.caption.copy(
                            color = colors.textMuted,
                            lineHeight = typography.caption.fontSize * 1.35f,
                        ),
                    )
                }
            }

            if (selected) {
                Icon(
                    Lucide.CircleCheck,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.primary,
                )
            }
        }
    }
}
@Composable
internal fun ChatBubble(
    message: ChatMessage,
    onAction: (AiErrorAction) -> Unit,
    expandableExecutionSteps: Boolean = true,
    onToggleExecutionExpanded: (String) -> Unit = {},
) {
    val isUser = message.role == ChatRole.User
    val isErrorBubble = message.actions.isNotEmpty()
    // Token usage renders in the meta row below the bubble; user and error
    // bubbles have no token footer.
    val tokenFooter =
        if (!isUser && !isErrorBubble) formatTokenFooter(message.tokenUsage) else null
    // Shared so every bubble thumbnail swipes through the same preview strip.
    var previewImage by remember { mutableStateOf<PendingImage?>(null) }
    val bubbleShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (isUser) 12.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 12.dp,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Finalized layout mirrors the running card: the ghost summary
        // line sits above the bubble and acts as the accordion header for
        // the expanded step detail below it.
        if (!isUser && message.executionSteps.isNotEmpty()) {
            // Hover container sized to the summary line: the grey
            // fill and click target cover just the text plus chevron
            // (padding lives inside the painted Box).
            var summaryHovered by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (summaryHovered) colors.backgroundHover else Color.Transparent)
                    .then(
                        if (expandableExecutionSteps) {
                            Modifier.cursorHand()
                                .onHover { summaryHovered = it }
                                .clickable { onToggleExecutionExpanded(message.id) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                ExecutionSummaryLine(
                    text = executionSummaryLineText(message.executionSteps, failed = isErrorBubble),
                    expanded = message.isExecutionExpanded,
                    failed = isErrorBubble,
                    fillMaxWidth = false,
                )
            }
        }

        // Expanded run detail sits entirely below the summary line (the summary
        // line above the bubble acts as the accordion header): left indent plus a
        // faint guide line, no card background. Error bubbles fold their steps
        // too, so they also expand (failure review).
        if (!isUser && message.executionSteps.isNotEmpty() &&
            message.isExecutionExpanded && expandableExecutionSteps
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .then(executionDetailIndent()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                message.executionSteps.forEach { step ->
                    ExecutionStepBlock(
                        step = step,
                        isFailed = isFailedExecutionStep(step, message.actions.isNotEmpty()),
                    )
                }
            }
        }

        Surface(
            color = if (isUser) colors.primarySubtle else colors.inputBackground,
            contentColor = colors.textTitle,
            shape = bubbleShape,
            border = BorderStroke(
                1.dp,
                if (isUser) colors.primary.copy(alpha = 0.45f) else colors.borderDefault,
            ),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .wrapContentWidth(
                    align = if (isUser) Alignment.End else Alignment.Start,
                ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Bubble has no click handler, so selecting text inside it is safe.
                // S1: the URL and the optional user note share one SelectableContainer
                // so both can be drag-selected/copied together as a single block.
                SelectableContainer {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isUser && message.images.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                message.images.forEach { image ->
                                    rememberImageBitmap(image)?.let { bitmap ->
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = "Sent image",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .height(120.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .cursorHand()
                                                .clickable { previewImage = image },
                                        )
                                    }
                                }
                            }
                        }
                        // Reply body first (proposal 5.5): Markdown subset for agent
                        // replies with a local 4-line collapse toggle; plain monospace
                        // for user instructions with @bucket/app chips; error bubbles
                        // keep their text rendering path.
                        if (!isUser && !isErrorBubble) {
                            CollapsibleAgentBody(text = message.text)
                        } else if (isUser) {
                            val segments = remember(message.text) { splitManifestRefSegments(message.text) }
                            if (segments.size == 1 && segments[0] is ManifestRefSegment.Text) {
                                // No references: unchanged single Text for selection.
                                BubbleBodyText(message.text, isUser)
                            } else {
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                    segments.forEach { segment ->
                                        when (segment) {
                                            is ManifestRefSegment.Text -> Text(
                                                segment.text,
                                                style = typography.caption.copy(
                                                    color = colors.onSurface,
                                                    fontFamily = FontFamily.Monospace,
                                                    lineHeight = typography.caption.fontSize * 1.5f,
                                                ),
                                            )
                                            is ManifestRefSegment.Ref -> Box(Modifier.padding(horizontal = 1.dp)) {
                                                ManifestRefChip(segment)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            BubbleBodyText(message.text, isUser)
                        }
                        // Token footer moved out of the bubble: it renders as
                        // its own meta line below the bubble (see the chat Column
                        // in ChatBubble). The note and technical details keep their
                        // place inside the body column.
                        message.note?.takeIf { it.isNotBlank() }?.let { rawNote ->
                            Text(
                                // Single-line notes must never be visually tail-clipped. Elide any
                                // URL middle before rendering; idempotent when already elided.
                                if (message.noteSingleLine && rawNote.contains("://"))
                                    elideUrlMiddle(rawNote, DOWNLOAD_DETAIL_MAX)
                                else rawNote,
                                style = typography.caption.copy(
                                    color = colors.textMuted,
                                    lineHeight = typography.caption.fontSize * 1.45f,
                                ),
                                // Download-confirm details must never wrap on screen;
                                // other notes (user requirements, error details) wrap freely.
                                maxLines = if (message.noteSingleLine) 1 else Int.MAX_VALUE,
                                overflow = if (message.noteSingleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                            )
                        }
                        if (!message.technicalDetails.isNullOrBlank()) {
                            var detailsExpanded by remember { mutableStateOf(false) }
                            Spacer(Modifier.height(2.dp))
                            androidx.compose.foundation.text.selection.DisableSelection {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .cursorHand()
                                        .clickable { detailsExpanded = !detailsExpanded }
                                        .padding(vertical = 2.dp, horizontal = 2.dp),
                                ) {
                                    Icon(
                                        if (detailsExpanded) Lucide.ChevronDown else Lucide.ChevronRight,
                                        contentDescription = "Details",
                                        modifier = Modifier.size(12.dp),
                                        tint = colors.textMuted,
                                    )
                                    Text(
                                        if (detailsExpanded) "Hide details" else "Details",
                                        style = typography.caption.copy(
                                            color = colors.textMuted,
                                            fontSize = typography.caption.fontSize * 0.9f,
                                        ),
                                    )
                                }
                            }
                            if (detailsExpanded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(colors.surface.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                        .border(1.dp, colors.divider, RoundedCornerShape(4.dp))
                                        .padding(8.dp),
                                ) {
                                    Text(
                                        message.technicalDetails,
                                        style = typography.caption.copy(
                                            color = colors.textMuted,
                                            fontSize = typography.caption.fontSize * 0.88f,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = typography.caption.fontSize * 1.35f,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                if (message.actions.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        message.actions.forEach { action ->
                            SmallActionButton(
                                label = actionLabel(action),
                                onClick = { onAction(action) },
                                enabled = true,
                            )
                        }
                    }
                }
            }
        }
        // Token usage renders as its own muted meta line below the bubble:
        // it is plain info, not the accordion header, so no hover or click.
        tokenFooter?.let { footer ->
            Text(
                footer,
                style = typography.caption.copy(color = colors.textMuted),
            )
        }
    }
    previewImage?.let {
        ImagePreviewDialog(images = message.images, initialImage = it) {
            previewImage = null
        }
    }
}
private fun actionLabel(action: AiErrorAction): String = when (action) {
    AiErrorAction.OPEN_SETTINGS -> "Open Settings"
    AiErrorAction.RETRY_FULL -> "Restart"
    AiErrorAction.RETRY_FROM_ANALYZE -> "Retry from analyze"
    AiErrorAction.RETRY_FROM_GENERATE -> "Retry from generate"
    AiErrorAction.RETRY_ANSWER -> "Retry"
}
@Composable
private fun PhaseTag(phase: String, modifier: Modifier = Modifier) {
    val (bg, fg, border) = phaseColors(phase)
    Text(
        phase.uppercase(),
        style = typography.overline.copy(
            fontWeight = FontWeight.Bold,
            color = fg,
        ),
        modifier = modifier
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
@Composable
internal fun phaseColors(phase: String): Triple<Color, Color, Color> {
    return when (phase.lowercase()) {
        "research" -> Triple(
            colors.primarySubtle,
            colors.primary,
            colors.primary.copy(alpha = 0.3f),
        )
        "analyze" -> Triple(
            colors.analyzeBg,
            colors.analyzeColor,
            colors.analyzeColor.copy(alpha = 0.35f),
        )
        "done", "verify" -> Triple(
            colors.updateDefault.copy(alpha = 0.12f),
            colors.updateDefault,
            colors.updateDefault.copy(alpha = 0.35f),
        )
        "generate", "assist", "repair", "notice", "warning" -> Triple(
            colors.warningDefault.copy(alpha = 0.12f),
            colors.warningDefault,
            colors.warningDefault.copy(alpha = 0.35f),
        )
        "error" -> Triple(
            colors.dangerBg,
            colors.dangerDefault,
            colors.dangerDefault.copy(alpha = 0.35f),
        )
        else -> Triple(
            colors.inputBackground,
            colors.textBody,
            colors.borderDefault,
        )
    }
}
/** Small inline chip for a @bucket/app reference inside a user bubble. */
@Composable
private fun ManifestRefChip(ref: ManifestRefSegment.Ref) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = colors.primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.4f)),
        modifier = Modifier.cursorHand(),
    ) {
        Text(
            ref.raw,
            style = typography.caption.copy(
                color = colors.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            ),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Plain bubble body text with the original mono/default styling. */
@Composable
private fun BubbleBodyText(text: String, isUser: Boolean) {
    Text(
        text,
        style = typography.caption.copy(
            color = colors.onSurface,
            fontFamily = if (isUser) FontFamily.Monospace else FontFamily.Default,
            lineHeight = typography.caption.fontSize * 1.5f,
        ),
    )
}