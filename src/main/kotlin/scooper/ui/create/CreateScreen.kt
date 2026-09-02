package scooper.ui.create

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.koinInject
import scooper.ui.AppRoute
import scooper.ui.components.ConfirmDialog
import scooper.ui.components.DialogTextField
import scooper.ui.components.CompactModelSelector
import scooper.ui.components.ExposedDropdownMenu
import scooper.ui.components.SelectableContainer
import scooper.ui.icons.*
import scooper.ui.theme.*
import scooper.viewmodels.ActiveExecution
import scooper.viewmodels.AskUserRequest
import scooper.viewmodels.QuestionnaireOption
import scooper.viewmodels.QuestionnaireQuestion
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.onHover
import scooper.util.parseAnsiColors
import scooper.service.AiErrorAction
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.viewmodels.ChatMessage
import scooper.viewmodels.ChatRole
import scooper.viewmodels.CreateState
import scooper.viewmodels.CreateViewModel
import scooper.viewmodels.DiffLine
import scooper.viewmodels.DiffLineType
import scooper.viewmodels.ExecutionStep
import scooper.viewmodels.ManifestRevision
import scooper.viewmodels.ReviewActionChip
import scooper.viewmodels.SaveGitHint
import scooper.viewmodels.SaveResult
import scooper.viewmodels.TestResult
import com.scoopai.core.TokenUsage
import scooper.viewmodels.computeDetailedLineDiff
import scooper.viewmodels.executionSummaryCapsuleText
import scooper.viewmodels.formatStepDuration
import scooper.viewmodels.formatTokenFooter
import scooper.viewmodels.isFailedExecutionStep
import scooper.viewmodels.isNameConflictOnly
import scooper.viewmodels.shouldRenderFullExecutionSteps

/**
 * Number of items in the chat LazyColumn, shared by the LazyColumn content and the
 * auto-scroll itemCount so the scroll target index is always valid (proposal 5.2).
 * The manifest preview card joins the chat list only in the single-column (narrow)
 * layout; the wide layout renders it in the right pane instead.
 */
internal fun chatListItemCount(
    messageCount: Int,
    hasActiveExecution: Boolean,
    hasAskUserRequest: Boolean,
    includeManifestCard: Boolean,
    manifestJsonNotBlank: Boolean,
): Int = messageCount +
    (if (hasActiveExecution) 1 else 0) +
    (if (hasAskUserRequest) 1 else 0) +
    (if (includeManifestCard && manifestJsonNotBlank) 1 else 0)

@Suppress("UNCHECKED_CAST")
@Composable
fun CreateScreen(
    viewModel: CreateViewModel = koinInject(),
) {
    val state by viewModel.container.stateFlow.collectAsState()
    val navigator = LocalBackStack.current as BackStack<AppRoute>
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.refreshAiConfig()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface),
    ) {
        CreateTopBar(
            canReset = !state.isEmpty,
            onReset = viewModel::onReset,
            onOpenAiSettings = { navigator.push(AppRoute.Settings.AI) },
        )

        if (!state.aiConfigured) {
            AiConfigBanner(
                configHint = state.configHint,
                onOpenAiSettings = { navigator.push(AppRoute.Settings.AI) },
            )
        }

        if (state.isEmpty) {
            EmptyWorkspace(
                state = state,
                onUrlChange = viewModel::onUrlChange,
                onNoteChange = viewModel::onNoteChange,
                onModelChange = viewModel::onModelChange,
                onFetchModels = viewModel::onFetchModels,
                onSend = { url, note -> viewModel.onSend(url, note) },
                onCancel = { viewModel.onCancel() },
            )
        } else {
            ChatWorkspace(
                state = state,
                listState = listState,
                onUrlChange = viewModel::onUrlChange,
                onNoteChange = viewModel::onNoteChange,
                onModelChange = viewModel::onModelChange,
                onFetchModels = viewModel::onFetchModels,
                onSend = { url, note -> viewModel.onSend(url, note) },
                onCancel = { viewModel.onCancel() },
                onCopyDone = viewModel::onCopyDone,
                onTestInstall = { viewModel.onTestInstall() },
                onSave = { viewModel.onSave() },
                onGoToDiscover = { navigator.popupAllAndPush(AppRoute.Apps(scope = "")) },
                onDismissSaveResult = viewModel::onDismissSaveResult,
                onAnswerAskUser = viewModel::onAnswerAskUser,
                onCancelAskUser = viewModel::onCancelAskUser,
                onFixFromTestFailure = { summary, logTail -> viewModel.onFixFromTestFailure(summary, logTail) },
                onSelectRevision = viewModel::onSelectRevision,
                onRevertToRevision = viewModel::onRevertToRevision,
                onApplyActionChip = viewModel::onApplyActionChip,
                onToggleExecutionExpanded = viewModel::onToggleExecutionExpanded,
                onAction = { action ->
                    if (action == AiErrorAction.OPEN_SETTINGS) {
                        navigator.push(AppRoute.Settings.AI)
                    } else {
                        viewModel.onAction(action)
                    }
                },
            )
        }
    }

    if (state.saveDialogOpen) {
        SaveToBucketDialog(
            appName = state.saveAppName,
            bucket = state.saveBucket,
            bucketOptions = state.bucketOptions,
            commitToGit = state.saveCommitToGit,
            isGitBucket = state.saveBucketIsGit,
            onAppNameChange = viewModel::onSaveAppNameChange,
            onBucketChange = viewModel::onSaveBucketChange,
            onCommitToGitChange = viewModel::onSaveCommitToGitChange,
            onConfirm = viewModel::onConfirmSave,
            onCancel = viewModel::onDismissSaveDialog,
        )
    }

    if (state.overwriteConfirmOpen) {
        ConfirmDialog(
            text = "File exists. Overwrite?",
            title = "Overwrite Manifest",
            confirmText = "Overwrite",
            onConfirm = viewModel::onConfirmOverwrite,
            onCancel = viewModel::onDismissOverwrite,
        )
    }
}

@Composable
private fun AskUserBubble(
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
                                    .clickable { currentTab = index }
                                    .padding(vertical = 4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
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
                                .clickable { currentTab = questions.size }
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
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
private fun SaveToBucketDialog(
    appName: String,
    bucket: String,
    bucketOptions: List<String>,
    commitToGit: Boolean,
    isGitBucket: Boolean,
    onAppNameChange: (String) -> Unit,
    onBucketChange: (String) -> Unit,
    onCommitToGitChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    // Layer-based Dialog, not DialogWindow: the bucket dropdown Popup gets clipped inside an
    // OS dialog window and had focus bugs (CMP-9439); on the main window layer it renders fully.
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            color = colors.surface,
            modifier = Modifier.width(420.dp).height(410.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).padding(24.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "App name",
                            style = typography.caption.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textTitle,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        DialogTextField(
                            value = appName,
                            onValueChange = onAppNameChange,
                            placeholder = "e.g. ripgrep",
                            modifier = Modifier
                                .fillMaxWidth()
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                        if (appName.isNotBlank()) onConfirm()
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Letters, digits, '.', '_' and '-' only.",
                            style = typography.caption.copy(color = colors.textMuted),
                        )

                        Spacer(Modifier.height(16.dp))

                        Text(
                            "Bucket",
                            style = typography.caption.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textTitle,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        if (bucketOptions.isNotEmpty()) {
                            val selected = if (bucket in bucketOptions) bucket else bucketOptions.first()
                            ExposedDropdownMenu(
                                items = bucketOptions,
                                selected = selected,
                                onItemSelected = onBucketChange,
                                modifier = Modifier.fillMaxWidth(),
                                textFill = true,
                            )
                        } else {
                            Text(
                                "No buckets available",
                                style = typography.caption.copy(color = colors.dangerDefault),
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .cursorHand()
                                .clickable(enabled = isGitBucket) { onCommitToGitChange(!commitToGit) }
                                .padding(vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = commitToGit && isGitBucket,
                                onCheckedChange = onCommitToGitChange,
                                enabled = isGitBucket,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = colors.primary,
                                    checkmarkColor = Color.White,
                                ),
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(
                                    "Commit to Git repository",
                                    style = typography.caption.copy(
                                        fontWeight = FontWeight.Medium,
                                        color = if (isGitBucket) colors.textTitle else colors.textMuted,
                                    ),
                                )
                                if (!isGitBucket) {
                                    Text(
                                        "Selected bucket is not a Git repository",
                                        style = typography.caption.copy(
                                            color = colors.textMuted,
                                            fontSize = typography.caption.fontSize * 0.9f,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                Divider(color = colors.divider)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.cursorHand()) {
                        Text("Cancel", color = colors.textBody)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.primary),
                        elevation = null,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.cursorHand(),
                    ) {
                        Text("Save", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateTopBar(
    canReset: Boolean,
    onReset: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, colors.borderDefault))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Create",
                style = typography.h5.copy(
                    fontWeight = FontWeight.Bold,
                    color = colors.textTitle,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Generate Scoop manifest with AI from a GitHub URL",
                style = typography.body2.copy(color = colors.textBody),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canReset) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.cursorHand().height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, colors.borderDefault),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textBody),
                    elevation = null,
                ) {
                    Icon(
                        Lucide.Plus,
                        contentDescription = "New Session",
                        modifier = Modifier.size(15.dp),
                        tint = colors.textBody,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "New Session",
                        style = typography.caption.copy(
                            fontWeight = FontWeight.Medium,
                            color = colors.textBody,
                        ),
                    )
                }
            }
            // No Idle badge here: it carried no information next to New Session.
            // While a run is active, the phase is indicated by the execution card
            // in the chat stream and the preview header status pill instead.
            TextButton(
                onClick = onOpenAiSettings,
                modifier = Modifier.cursorLink().height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    Lucide.Settings,
                    contentDescription = "Settings.AI",
                    modifier = Modifier.size(16.dp),
                    tint = colors.textBody,
                )
            }
        }
    }
}

@Composable
private fun AiConfigBanner(
    configHint: String?,
    onOpenAiSettings: () -> Unit,
) {
    val bg = colors.dangerBg
    val border = colors.dangerDefault.copy(alpha = 0.35f)
    val textColor = colors.dangerDefault

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("!", style = typography.caption.copy(fontWeight = FontWeight.Bold, color = textColor))
        Column(Modifier.weight(1f)) {
            Text(
                configHint ?: "AI is not configured. Configure it in Settings > AI, or set SCOOP_AI_API_KEY / SCOOP_AI_BASE_URL environment variables.",
                style = typography.caption.copy(color = textColor, lineHeight = typography.caption.fontSize * 1.45f),
            )
            TextButton(
                onClick = onOpenAiSettings,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.cursorLink().height(28.dp),
            ) {
                Text(
                    "Go to AI Settings",
                    style = typography.caption.copy(fontWeight = FontWeight.Bold, color = textColor),
                )
            }
        }
    }
}

@Composable
private fun EmptyWorkspace(
    state: CreateState,
    onUrlChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSend: (url: String, note: String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .border(1.dp, colors.primary.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.primarySubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "Create a Scoop manifest with AI",
                style = typography.h6.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textTitle,
                ),
            )
        }
        Spacer(Modifier.height(20.dp))
        Composer(
            state = state,
            centered = true,
            onUrlChange = onUrlChange,
            onNoteChange = onNoteChange,
            onModelChange = onModelChange,
            onFetchModels = onFetchModels,
            onSend = onSend,
            onCancel = onCancel,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Paste a GitHub repository URL and press Send.\nAfter the manifest is ready, you can Test Install / Save.",
            style = typography.caption.copy(
                color = colors.textMuted,
                lineHeight = 20.sp,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatWorkspace(
    state: CreateState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onUrlChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSend: (url: String, note: String) -> Unit,
    onCancel: () -> Unit,
    onCopyDone: () -> Unit,
    onTestInstall: () -> Unit,
    onSave: () -> Unit,
    onGoToDiscover: () -> Unit,
    onDismissSaveResult: () -> Unit,
    onAnswerAskUser: (String) -> Unit,
    onCancelAskUser: () -> Unit,
    onFixFromTestFailure: (summary: String, logTail: String) -> Unit,
    onSelectRevision: (String?) -> Unit,
    onRevertToRevision: (String) -> Unit,
    onApplyActionChip: (ReviewActionChip) -> Unit,
    onToggleExecutionExpanded: (String) -> Unit,
    onAction: (AiErrorAction) -> Unit,
) {
    @Composable
    fun PreviewArtifactPanel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ManifestPreviewCard(
                appName = state.suggestedAppName ?: "app",
                manifestJson = state.activeManifestJson,
                enabled = !state.running && !state.testing,
                testing = state.testing,
                testSucceeded = state.testResult?.success == true,
                running = state.running,
                issues = state.manifestIssues,
                exhausted = state.manifestExhausted,
                invalidJson = state.manifestInvalidJson,
                revisions = state.revisions,
                selectedRevisionId = state.selectedRevisionId,
                actionChips = state.reviewActionChips,
                onApplyActionChip = onApplyActionChip,
                onSelectRevision = onSelectRevision,
                onRevertToRevision = onRevertToRevision,
                onCopyDone = onCopyDone,
                onTestInstall = onTestInstall,
                onSave = onSave,
            )
            state.testResult?.let { result ->
                TestResultPanel(
                    result = result,
                    onFixWithAi = onFixFromTestFailure,
                )
            }
            state.saveResult?.let { result ->
                SaveResultPanel(
                    result = result,
                    onGoToDiscover = onGoToDiscover,
                    onDismiss = onDismissSaveResult,
                )
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Wide screen shows the Artifact panel in the right pane, but only once a
        // manifest exists; without one the chat column takes the full width.
        val isWideScreen = maxWidth >= 960.dp
        val showArtifactPanel = state.manifestJson.isNotBlank()

        // Auto-scroll chat when messages / manifest / tool activities change. The
        // item count shares chatListItemCount with the LazyColumn content below so
        // the scroll target index is always valid (messages, optional active
        // execution card, optional ask-user card, manifest card in narrow layout).
        LaunchedEffect(
            state.messages.size,
            state.manifestJson,
            state.running,
            state.testResult,
            state.saveResult,
            state.askUserRequest,
            state.activeExecution?.steps?.size,
            state.activeExecution?.steps?.lastOrNull()?.toolActivities?.size,
            state.activeExecution?.currentActivity,
        ) {
            val itemCount = chatListItemCount(
                messageCount = state.messages.size,
                hasActiveExecution = state.activeExecution != null,
                hasAskUserRequest = state.askUserRequest != null,
                includeManifestCard = !isWideScreen,
                manifestJsonNotBlank = state.manifestJson.isNotBlank(),
            )
            if (itemCount > 0) {
                listState.animateScrollToItem(itemCount - 1)
            }
        }

        if (isWideScreen) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left column: Chat stream + Composer
                Column(
                    modifier = Modifier
                        .weight(if (showArtifactPanel) 0.48f else 1f)
                        .fillMaxHeight(),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                            // Chat-app behaviour: short conversations stick to the
                            // composer instead of floating in the middle; the spare
                            // space stays above the first message.
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(state.messages, key = { it.id }) { message ->
                                ChatBubble(
                                    message = message,
                                    onAction = onAction,
                                    expandableExecutionSteps = shouldRenderFullExecutionSteps(state.messages, message.id),
                                    onToggleExecutionExpanded = onToggleExecutionExpanded,
                                )
                            }
                            state.activeExecution?.let { active ->
                                item(key = "active-execution") {
                                    ActiveExecutionCard(active = active)
                                }
                            }
                            state.askUserRequest?.let { request ->
                                item(key = "ask-user-bubble-${request.id}") {
                                    AskUserBubble(
                                        request = request,
                                        onAnswer = onAnswerAskUser,
                                        onCancel = onCancelAskUser,
                                    )
                                }
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }

                    Composer(
                        state = state,
                        centered = false,
                        onUrlChange = onUrlChange,
                        onNoteChange = onNoteChange,
                        onModelChange = onModelChange,
                        onFetchModels = onFetchModels,
                        onSend = onSend,
                        onCancel = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (showArtifactPanel) {
                    // Divider between columns
                    Divider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp),
                        color = colors.borderDefault,
                    )

                    // Right column: Manifest Artifact Panel
                    val rightScrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = 12.dp)
                                .verticalScroll(rightScrollState),
                        ) {
                            PreviewArtifactPanel()
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(rightScrollState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
        } else {
            // Single-column layout for narrower windows or initial generate
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                        // Chat-app behaviour: short conversations stick to the
                        // composer instead of floating in the middle; the spare
                        // space stays above the first message.
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            ChatBubble(
                                message = message,
                                onAction = onAction,
                                expandableExecutionSteps = shouldRenderFullExecutionSteps(state.messages, message.id),
                                onToggleExecutionExpanded = onToggleExecutionExpanded,
                            )
                        }
                        state.activeExecution?.let { active ->
                            item(key = "active-execution") {
                                ActiveExecutionCard(active = active)
                            }
                        }
                        state.askUserRequest?.let { request ->
                            item(key = "ask-user-bubble-${request.id}") {
                                AskUserBubble(
                                    request = request,
                                    onAnswer = onAnswerAskUser,
                                    onCancel = onCancelAskUser,
                                )
                            }
                        }
                        if (state.manifestJson.isNotBlank()) {
                            item(key = "manifest-card") {
                                PreviewArtifactPanel()
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }

                Composer(
                    state = state,
                    centered = false,
                    onUrlChange = onUrlChange,
                    onNoteChange = onNoteChange,
                    onModelChange = onModelChange,
                    onFetchModels = onFetchModels,
                    onSend = onSend,
                    onCancel = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    onAction: (AiErrorAction) -> Unit,
    expandableExecutionSteps: Boolean = true,
    onToggleExecutionExpanded: (String) -> Unit = {},
) {
    val isUser = message.role == ChatRole.User
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
                val isErrorBubble = message.actions.isNotEmpty()
                val stepsCollapsed = message.executionSteps.isNotEmpty() &&
                    !(message.isExecutionExpanded && expandableExecutionSteps)
                SelectableContainer {
                    // Collapsed capsule hugs the body (2dp rhythm); other layouts keep 4dp.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(
                            if (stepsCollapsed) 2.dp else 4.dp,
                        ),
                    ) {
                        // Execution review card (proposal 5.2): rendered ABOVE the bubble
                        // text. Collapsed shows a summary capsule; expanded shows a
                        // clickable capsule on top (collapse entry point, B1) plus the
                        // full step list. Older rounds beyond the recent window stay
                        // fixed to the single capsule. Error messages keep the existing
                        // text/actions/technicalDetails rendering below.
                        if (message.executionSteps.isNotEmpty()) {
                            if (message.isExecutionExpanded && expandableExecutionSteps) {
                                ExecutionSummaryCapsule(
                                    steps = message.executionSteps,
                                    expandable = true,
                                    onClick = { onToggleExecutionExpanded(message.id) },
                                    expanded = true,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    message.executionSteps.forEach { step ->
                                        ExecutionStepBlock(
                                            step = step,
                                            isFailed = isFailedExecutionStep(step, message.actions.isNotEmpty()),
                                        )
                                    }
                                }
                            } else {
                                ExecutionSummaryCapsule(
                                    steps = message.executionSteps,
                                    tokenUsage = message.tokenUsage,
                                    expandable = expandableExecutionSteps,
                                    onClick = { onToggleExecutionExpanded(message.id) },
                                )
                            }
                        }
                        // Separator between the expanded step list and the reply body: a
                        // faint divider plus breathing room so the explanation text does
                        // not stick to the steps. Collapsed capsule omits the divider and
                        // sits tight above the body; error bubbles keep their own layout.
                        if (message.executionSteps.isNotEmpty() && !isErrorBubble &&
                            message.isExecutionExpanded && expandableExecutionSteps
                        ) {
                            Spacer(Modifier.height(3.dp))
                            Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 1.dp)
                            Spacer(Modifier.height(3.dp))
                        }
                        if (!isUser && !isErrorBubble) {
                            // Conversational reply: Markdown subset rendering (proposal 5.5).
                            // Long agent bodies collapse to 4 lines by default with a
                            // local Show more/less toggle; pure UI state, no VM change.
                            CollapsibleAgentBody(text = message.text)
                            // Token footer sits below the collapsible body (and its Show
                            // more toggle) so it stays fully visible in both states.
                            formatTokenFooter(message.tokenUsage)?.let { footer ->
                                Text(
                                    footer,
                                    style = typography.caption.copy(color = colors.textMuted),
                                )
                            }
                        } else {
                            // User instructions stay plain monospace; error bubbles keep the
                            // existing error rendering path (actions / technicalDetails below).
                            Text(
                                message.text,
                                style = typography.caption.copy(
                                    color = colors.onSurface,
                                    fontFamily = if (isUser) FontFamily.Monospace else FontFamily.Default,
                                    lineHeight = typography.caption.fontSize * 1.5f,
                                ),
                            )
                        }
                        message.note?.takeIf { it.isNotBlank() }?.let { note ->
                            Text(
                                note,
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
    }
}

private fun actionLabel(action: AiErrorAction): String = when (action) {
    AiErrorAction.OPEN_SETTINGS -> "Open Settings"
    AiErrorAction.RETRY_FULL -> "Restart"
    AiErrorAction.RETRY_FROM_ANALYZE -> "Continue"
    AiErrorAction.RETRY_FROM_GENERATE -> "Continue"
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
private fun phaseColors(phase: String): Triple<Color, Color, Color> {
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

@Composable
private fun ActiveExecutionCard(active: ActiveExecution) {
    // Transient run card (stage 3 ActiveExecutionCard): auto-expanded while the
    // session runs, with the active phase highlighted and a live tool-activity line.
    Surface(
        color = colors.inputBackground,
        contentColor = colors.textTitle,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.borderDefault),
        modifier = Modifier
            .widthIn(max = 560.dp)
            .wrapContentWidth(align = Alignment.Start),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Working...",
                    style = typography.caption.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.textTitle,
                    ),
                )
            }
            active.steps.forEach { step ->
                ExecutionStepBlock(
                    step = step,
                    liveActivity = if (step.isRunning) active.currentActivity else null,
                )
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
private fun CollapsibleAgentBody(text: String) {
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
        modifier = Modifier
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ExecutionStepBlock(
    step: ExecutionStep,
    isFailed: Boolean = false,
    liveActivity: String? = null,
) {
    val titleColor = when {
        isFailed -> colors.dangerDefault
        step.isRunning -> colors.primary
        else -> colors.textBody
    }
    Column(
        modifier = if (step.isRunning) {
            // Running step keeps its indent rhythm only: no extra background layer,
            // phase rows (title + tool log + summary) render directly on the single
            // card surface of ActiveExecutionCard / ChatBubble.
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
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
            Text(
                step.title,
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
            if (step.isRunning) {
                TypingDots()
            }
        }
        step.toolActivities.forEach { line ->
            Text(
                line,
                style = typography.caption.copy(
                    color = if (line.contains("✖")) colors.dangerDefault else colors.textMuted,
                    fontSize = typography.caption.fontSize * 0.92f,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        liveActivity?.let { activity ->
            Text(
                activity,
                style = typography.caption.copy(
                    color = colors.primary,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.padding(start = 4.dp),
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
private fun ExecutionSummaryCapsule(
    steps: List<ExecutionStep>,
    expandable: Boolean,
    onClick: () -> Unit,
    expanded: Boolean = false,
    tokenUsage: TokenUsage? = null,
) {
    Surface(
        color = colors.surface,
        contentColor = colors.textBody,
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.dp, colors.borderDefault),
        modifier = Modifier
            .wrapContentWidth(align = Alignment.Start)
            .then(
                if (expandable) {
                    Modifier.cursorHand().clickable { onClick() }
                } else {
                    Modifier
                }
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Text(
                "✓",
                style = typography.caption.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.updateDefault,
                ),
            )
            Text(
                executionSummaryCapsuleText(steps, tokenUsage).removePrefix("✓ "),
                style = typography.caption.copy(
                    fontWeight = FontWeight.Medium,
                    color = colors.textBody,
                ),
            )
            if (expandable) {
                Icon(
                    if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = "Toggle execution steps",
                    modifier = Modifier.size(12.dp),
                    tint = colors.textMuted,
                )
            }
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

@Composable
private fun ManifestPreviewCard(
    appName: String,
    manifestJson: String,
    enabled: Boolean,
    testing: Boolean,
    testSucceeded: Boolean = false,
    running: Boolean = false,
    issues: List<String> = emptyList(),
    exhausted: Boolean = false,
    invalidJson: Boolean = false,
    revisions: List<ManifestRevision> = emptyList(),
    selectedRevisionId: String? = null,
    actionChips: List<ReviewActionChip> = emptyList(),
    onApplyActionChip: ((ReviewActionChip) -> Unit)? = null,
    onSelectRevision: ((String?) -> Unit)? = null,
    onRevertToRevision: ((String) -> Unit)? = null,
    onCopyDone: () -> Unit,
    onTestInstall: () -> Unit,
    onSave: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var showDiff by remember { mutableStateOf(false) }
    val testName = "$appName-scooper-test"
    val scroll = rememberScrollState()
    val previewHScroll = rememberScrollState()

    // A new revision (or switching revisions) resets the preview back to the top:
    // the previous JSON may have been scrolled to its bottom, and the user should
    // always start reading from the manifest head.
    LaunchedEffect(manifestJson, selectedRevisionId) {
        scroll.scrollTo(0)
        previewHScroll.scrollTo(0)
    }

    val currentRev = if (selectedRevisionId != null) {
        revisions.firstOrNull { it.id == selectedRevisionId }
    } else {
        revisions.lastOrNull()
    }
    val currentRevIndex = revisions.indexOf(currentRev)
    val previousRev = if (currentRevIndex > 0) revisions[currentRevIndex - 1] else null
    val detailedDiff = remember(previousRev?.manifestJson, currentRev?.manifestJson) {
        if (previousRev != null && currentRev != null) {
            computeDetailedLineDiff(previousRev.manifestJson, currentRev.manifestJson)
        } else {
            emptyList()
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.borderDefault),
        color = colors.surface,
        elevation = 0.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.inputBackground)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Manifest Preview",
                        style = typography.subtitle1.copy(
                            fontWeight = FontWeight.Medium,
                            color = colors.textTitle,
                        ),
                    )
                    val hasNameConflictOnly = isNameConflictOnly(issues, invalidJson)
                    val statusColor = when {
                        running -> colors.primary
                        testing -> colors.primary
                        hasNameConflictOnly || exhausted || invalidJson -> colors.warningDefault
                        else -> colors.updateDefault
                    }
                    val statusText = when {
                        running -> "Updating"
                        testing -> "Installing"
                        hasNameConflictOnly -> "Notice"
                        exhausted || invalidJson -> "Issues"
                        else -> "Ready"
                    }
                    Text(
                        statusText,
                        style = typography.overline.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                        ),
                        modifier = Modifier
                            .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(
                    "suggested: $appName  ·  test: $testName",
                    style = typography.caption.copy(color = colors.textMuted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                    textAlign = TextAlign.End,
                )
            }

            if (revisions.size > 1) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.inputBackground.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Revisions:",
                                style = typography.caption.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textMuted,
                                    fontSize = 12.sp,
                                ),
                            )
                            revisions.forEach { rev ->
                                val isSelected = (selectedRevisionId == null && rev == revisions.lastOrNull()) ||
                                    (selectedRevisionId == rev.id)
                                val badgeColor = if (isSelected) colors.primary else colors.textMuted
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = if (isSelected) colors.primary.copy(alpha = 0.15f) else colors.inputBackground,
                                    border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.borderDefault),
                                    modifier = Modifier
                                        .cursorHand()
                                        .clip(RoundedCornerShape(5.dp))
                                        .clickable { onSelectRevision?.invoke(if (rev == revisions.lastOrNull()) null else rev.id) },
                                ) {
                                    Text(
                                        "v${rev.revisionNumber}",
                                        style = typography.caption.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = badgeColor,
                                            fontSize = 12.sp,
                                        ),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (previousRev != null) {
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = if (showDiff) colors.primary.copy(alpha = 0.15f) else colors.inputBackground,
                                    border = BorderStroke(1.dp, if (showDiff) colors.primary else colors.borderDefault),
                                    modifier = Modifier
                                        .cursorHand()
                                        .clip(RoundedCornerShape(5.dp))
                                        .clickable { showDiff = !showDiff },
                                ) {
                                    Text(
                                        if (showDiff) "View Code" else "View Diff",
                                        style = typography.caption.copy(
                                            color = if (showDiff) colors.primary else colors.textTitle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }

                            if (currentRev?.diffSummary?.isNotEmpty() == true && currentRev.diffSummary != listOf("Initial manifest")) {
                                currentRev.diffSummary.take(2).forEach { change ->
                                    val chipColor = when {
                                        change.startsWith("+") -> colors.updateDefault
                                        change.startsWith("-") -> colors.dangerDefault
                                        else -> colors.warningDefault
                                    }
                                    Text(
                                        change,
                                        style = typography.caption.copy(
                                            color = chipColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(chipColor.copy(alpha = 0.12f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = if (expanded) 2000.dp else 360.dp)
                    .background(colors.inputBackground),
            ) {
                SelectableContainer {
                    if (showDiff && previousRev != null && detailedDiff.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scroll)
                                .padding(vertical = 8.dp),
                        ) {
                            detailedDiff.forEach { line ->
                                val bgColor = when (line.type) {
                                    DiffLineType.ADDED -> colors.updateDefault.copy(alpha = 0.12f)
                                    DiffLineType.DELETED -> colors.dangerDefault.copy(alpha = 0.12f)
                                    DiffLineType.UNCHANGED -> Color.Transparent
                                }
                                val textColor = when (line.type) {
                                    DiffLineType.ADDED -> colors.updateDefault
                                    DiffLineType.DELETED -> colors.dangerDefault
                                    DiffLineType.UNCHANGED -> colors.onSurface
                                }
                                val prefix = when (line.type) {
                                    DiffLineType.ADDED -> "+"
                                    DiffLineType.DELETED -> "-"
                                    DiffLineType.UNCHANGED -> " "
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bgColor)
                                        .padding(horizontal = 10.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        prefix,
                                        style = typography.body2.copy(
                                            color = textColor,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        modifier = Modifier.width(16.dp),
                                    )
                                    Text(
                                        line.text,
                                        style = typography.body2.copy(
                                            color = textColor,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = typography.body2.fontSize * 1.5f,
                                        ),
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            manifestJson,
                            style = typography.body2.copy(
                                color = colors.onSurface,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = typography.body2.fontSize * 1.55f,
                            ),
                            // No soft wrap: overlong urls scroll horizontally instead
                            // of folding mid-token (vertical scrolling is kept).
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scroll)
                                .horizontalScroll(previewHScroll)
                                .padding(14.dp),
                        )
                    }
                }
            }

            if (issues.isNotEmpty() || (invalidJson && actionChips.isNotEmpty())) {
                val hasNameConflictOnly = isNameConflictOnly(issues, invalidJson)
                SelectableContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .border(
                                1.dp,
                                colors.warningDefault.copy(alpha = 0.35f),
                                RoundedCornerShape(8.dp),
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.warningDefault.copy(alpha = 0.08f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            if (hasNameConflictOnly) "Notice: Name conflict detected:" else "Generation finished with issues:",
                            style = typography.caption.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.warningDefault,
                            ),
                        )
                        issues.forEach { issue ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    "•",
                                    style = typography.caption.copy(color = colors.warningDefault),
                                )
                                Text(
                                    issue,
                                    style = typography.caption.copy(
                                        color = colors.textBody,
                                        lineHeight = typography.caption.fontSize * 1.45f,
                                    ),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (!invalidJson) {
                            Text(
                                if (hasNameConflictOnly) {
                                    "Manifest is valid JSON; choose a different name when saving to a bucket."
                                } else {
                                    "Manifest is valid JSON; you may still test / install it."
                                },
                                style = typography.caption.copy(color = colors.textMuted),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        if (actionChips.isNotEmpty() && onApplyActionChip != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                actionChips.take(3).forEach { chip ->
                                    val chipColor = if (chip.isPrimary) colors.primary else colors.warningDefault
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = chipColor.copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, chipColor.copy(alpha = 0.4f)),
                                        modifier = Modifier
                                            .cursorHand()
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable(enabled = enabled && !testing && !running) {
                                                onApplyActionChip(chip)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            chip.label,
                                            style = typography.caption.copy(
                                                color = chipColor,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.sp,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Divider(color = colors.borderDefault)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallActionButton(
                    label = if (expanded) "Collapse" else "Expand",
                    onClick = { expanded = !expanded },
                    enabled = true,
                )
                SmallActionButton(
                    label = "Copy",
                    onClick = {
                        clipboard.setText(AnnotatedString(manifestJson))
                        onCopyDone()
                    },
                    enabled = true,
                )
                // After a successful test install, Save to Bucket becomes the
                // primary action; in every other state Test Install stays primary.
                SmallActionButton(
                    label = "Test Install",
                    onClick = onTestInstall,
                    enabled = enabled && !testing && !invalidJson,
                    primary = !testSucceeded,
                    loading = testing,
                )
                SmallActionButton(
                    label = "Save to Bucket",
                    onClick = onSave,
                    enabled = enabled && !testing && !invalidJson,
                    primary = testSucceeded,
                )
                if (selectedRevisionId != null && selectedRevisionId != revisions.lastOrNull()?.id && onRevertToRevision != null) {
                    SmallActionButton(
                        label = "Revert to this version",
                        onClick = { onRevertToRevision(selectedRevisionId) },
                        enabled = enabled && !testing,
                    )
                }
            }
        }
    }
}

@Composable
private fun TestResultPanel(
    result: TestResult,
    onFixWithAi: ((summary: String, logTail: String) -> Unit)? = null,
) {
    val accent = if (result.success) colors.updateDefault else colors.dangerDefault
    val icon = if (result.success) Lucide.CircleCheck else Lucide.CircleX
    var expanded by remember { mutableStateOf(false) }
    val logText = remember(result.log) {
        result.log.lines().takeLast(100).joinToString("\n")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        color = colors.surface,
        elevation = 0.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accent,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (result.success) {
                            "Test install succeeded: ${result.testName} (uninstalled)"
                        } else {
                            "Test install failed: ${result.testName}"
                        },
                        style = typography.body2.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textTitle,
                        ),
                    )
                    if (result.success && result.prewarmedCount > 0) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${result.prewarmedCount} cache ${if (result.prewarmedCount == 1) "entry" else "entries"} reused",
                            style = typography.caption.copy(color = colors.textMuted),
                        )
                    }
                    if (!result.success && !result.errorMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        // SelectionContainer can only copy laid-out text: ellipsized lines past
                        // maxLines are not selectable, so keep maxLines generous enough that the
                        // visible error stays fully copyable while the panel stays bounded.
                        SelectableContainer {
                            Text(
                                parseAnsiColors(result.errorMessage),
                                style = typography.caption.copy(
                                    color = colors.dangerDefault,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = typography.caption.fontSize * 1.45f,
                                ),
                                maxLines = 12,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (result.log.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.cursorLink().height(28.dp),
                    ) {
                        Text(
                            if (expanded) "Hide log" else "View log",
                            style = typography.caption.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                            ),
                        )
                    }
                    if (!result.success && onFixWithAi != null) {
                        Button(
                            onClick = {
                                val summary = result.errorMessage ?: "Test install failed"
                                val logTail = result.log.takeLast(1000)
                                onFixWithAi(summary, logTail)
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = colors.dangerDefault,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.cursorHand().height(28.dp),
                            elevation = null,
                        ) {
                            Icon(
                                Lucide.RefreshCw,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = Color.White,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Ask AI to fix",
                                style = typography.caption.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                ),
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(expandFrom = Alignment.Top),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .border(1.dp, colors.borderDefault, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.inputBackground),
                    ) {
                        SelectableContainer {
                            Text(
                                parseAnsiColors(logText),
                                style = typography.caption.copy(
                                    color = colors.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = typography.caption.fontSize * 1.45f,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp),
                            )
                        }
                    }
                }
            } else if (!result.success && onFixWithAi != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = {
                            val summary = result.errorMessage ?: "Test install failed"
                            onFixWithAi(summary, "")
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = colors.dangerDefault,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.cursorHand().height(28.dp),
                        elevation = null,
                    ) {
                        Icon(
                            Lucide.RefreshCw,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = Color.White,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Ask AI to fix",
                            style = typography.caption.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyableText(
    text: String,
    modifier: Modifier = Modifier,
) {
    // Static console-style box: monospace + background/border, no click-to-copy.
    // Text selection (drag to select, Ctrl+C to copy) via SelectableContainer,
    // matching the console output / terminal pattern (components/SelectableContainer).
    val shape = RoundedCornerShape(6.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .border(1.dp, colors.borderDefault, shape)
            .clip(shape)
            .background(colors.inputBackground)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SelectableContainer {
                Text(
                    text,
                    style = typography.caption.copy(
                        color = colors.onSurface,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = typography.caption.fontSize * 1.4f,
                    ),
                    // Wrap instead of ellipsis so long paths/commands stay fully selectable.
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SaveResultPanel(
    result: SaveResult,
    onGoToDiscover: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The file is saved either way; failure is only about Discover indexing, so the
    // header shows a success check when indexed and a warning (no success check) when
    // indexedOk=false, matching the warning-colored note below the title.
    val accent = if (result.indexedOk) colors.updateDefault else colors.warningDefault
    val headerIcon = if (result.indexedOk) Lucide.CircleCheck else Lucide.Info

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.45f)),
        color = colors.surface,
        elevation = 0.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    headerIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accent,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "Saved ${result.appName}.json to bucket \"${result.bucket}\"" +
                            if (result.overwritten) " (overwritten)" else "",
                        style = typography.body2.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textTitle,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    CopyableText(
                        text = result.targetPath,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.cursorHand().size(28.dp),
                ) {
                    Icon(
                        Lucide.X,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(16.dp),
                        tint = colors.textMuted,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            val statusNote = when {
                result.indexedOk && result.gitCommitted ->
                    "Indexed for Discover and committed to Git. The app is searchable now."
                result.indexedOk && !result.gitCommitted ->
                    "Indexed for Discover. The app is searchable now."
                !result.indexedOk && result.gitCommitted ->
                    "Committed to Git, but Discover indexing failed."
                else ->
                    "File saved, but Discover indexing failed. The app may not appear in search until reindex."
            }
            Text(
                statusNote,
                style = typography.caption.copy(
                    fontWeight = FontWeight.Medium,
                    color = if (result.indexedOk) colors.updateDefault else colors.warningDefault,
                ),
            )

            result.gitHint?.let { hint ->
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.warningDefault.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.warningDefault.copy(alpha = 0.08f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "This bucket is a Git repository. Commit to keep the change in history:",
                        style = typography.caption.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.warningDefault,
                        ),
                    )
                    CopyableText(
                        text = hint.command,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallActionButton(
                    label = "Open in Discover",
                    onClick = onGoToDiscover,
                    // Indexing failed, so there is nothing searchable to jump to yet.
                    enabled = result.indexedOk,
                    primary = true,
                )
                SmallActionButton(
                    label = "Done",
                    onClick = onDismiss,
                    enabled = true,
                )
            }
        }
    }
}

@Composable
private fun SmallActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    primary: Boolean = false,
    loading: Boolean = false,
) {
    val modifier = Modifier.cursorHand().height(34.dp)
    val padding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    val shape = RoundedCornerShape(6.dp)
    val labelStyle = typography.button.copy(fontWeight = FontWeight.Medium)
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = modifier,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(backgroundColor = colors.primary),
            shape = shape,
            elevation = null,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Text(label, style = labelStyle.copy(color = Color.White))
            }
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            contentPadding = padding,
            shape = shape,
            border = BorderStroke(1.dp, colors.borderDefault),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textTitle),
        ) {
            Text(label, style = labelStyle)
        }
    }
}

@Composable
private fun Composer(
    state: CreateState,
    centered: Boolean,
    onUrlChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSend: (url: String, note: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var inputText by remember { mutableStateOf(state.inputNote.ifBlank { state.inputUrl }) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.inputNote, state.inputUrl) {
        val latest = state.inputNote.ifBlank { state.inputUrl }
        if (latest.isEmpty()) {
            inputText = ""
        } else if (latest != inputText && !focused) {
            inputText = latest
        }
    }

    fun submit() {
        if (state.running || state.testing || inputText.isBlank()) return
        val text = inputText.trim()
        inputText = ""
        onUrlChange("")
        onNoteChange("")
        onSend(text, text)
    }

    Column(
        modifier = modifier
            .then(
                if (centered) {
                    Modifier
                } else {
                    Modifier
                        .border(BorderStroke(1.dp, colors.borderDefault))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                }
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (focused) colors.primary else colors.inputBorder,
                    shape = RoundedCornerShape(12.dp),
                )
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface),
        ) {
            BasicTextField(
                value = inputText,
                onValueChange = { value ->
                    inputText = value
                    onUrlChange(value)
                    onNoteChange(value)
                },
                enabled = !state.running && !state.testing,
                singleLine = false,
                maxLines = 6,
                textStyle = typography.body2.copy(
                    fontWeight = FontWeight.Normal,
                    color = colors.textTitle,
                    lineHeight = 20.sp,
                ),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp, max = 150.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                            !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed
                        ) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (inputText.isEmpty()) {
                            val placeholderText = if (state.manifestJson.isNotBlank()) {
                                "Ask AI to modify this manifest (e.g. remove notes, add shortcuts)..."
                            } else {
                                "Enter GitHub URL (e.g. https://github.com/owner/repo)..."
                            }
                            Text(
                                placeholderText,
                                style = typography.body2.copy(color = colors.textPlaceholder),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactModelSelector(
                selectedModel = state.selectedModel,
                availableModels = state.availableModels,
                loading = state.fetchingModels,
                onModelChange = onModelChange,
                onFetchModels = onFetchModels,
                enabled = !state.running && !state.testing,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.running) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.cursorHand().height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.dangerDefault),
                        border = BorderStroke(1.dp, colors.dangerDefault),
                        elevation = null,
                    ) {
                        Icon(
                            Lucide.X,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colors.dangerDefault,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Cancel",
                            style = typography.button.copy(
                                fontWeight = FontWeight.Medium,
                                color = colors.dangerDefault,
                            ),
                        )
                    }
                } else {
                    Button(
                        onClick = { submit() },
                        enabled = !state.testing && inputText.isNotBlank(),
                        modifier = Modifier.cursorHand().height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.primary),
                        shape = RoundedCornerShape(6.dp),
                        elevation = null,
                    ) {
                        Icon(
                            Lucide.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Send",
                            style = typography.button.copy(
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                            ),
                        )
                    }
                }
            }
        }
    }
}

