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
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.ui.layout.ContentScale
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
import scooper.viewmodels.CreateTabController
import scooper.ui.AppRoute
import scooper.ui.components.ConfirmDialog
import scooper.ui.components.DialogTextField
import scooper.ui.components.CompactModelSelector
import scooper.ui.components.ExposedDropdownMenu
import scooper.ui.components.SelectableContainer
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.ui.icons.*
import scooper.ui.theme.*
import scooper.viewmodels.ActiveExecution
import scooper.viewmodels.AskUserRequest
import scooper.viewmodels.QuestionnaireOption
import scooper.viewmodels.QuestionnaireQuestion
import scooper.util.ImageCodec
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
import scooper.viewmodels.CreateViewModel.Companion.MAX_PENDING_IMAGES
import scooper.viewmodels.DiffLine
import scooper.viewmodels.DiffLineType
import scooper.viewmodels.ExecutionStep
import scooper.viewmodels.ManifestRevision
import scooper.viewmodels.ManifestRefSegment
import scooper.viewmodels.ManifestRefSuggestion
import scooper.viewmodels.activeManifestRefToken
import scooper.viewmodels.composerSendEnabled
import scooper.viewmodels.filterRefSuggestions
import scooper.viewmodels.splitManifestRefSegments
import scooper.viewmodels.PendingImage
import scooper.viewmodels.ReviewActionChip
import scooper.viewmodels.SaveGitHint
import scooper.viewmodels.SaveResult
import scooper.viewmodels.computeDetailedLineDiff
import scooper.viewmodels.executionSummaryCapsuleText
import scooper.viewmodels.manifestTestSucceeded
import scooper.viewmodels.installActionEnabled
import scooper.viewmodels.installActionLabel
import scooper.viewmodels.installActionPrimary
import scooper.viewmodels.installActionVisible
import scooper.viewmodels.manifestHasShortcuts
import scooper.viewmodels.openAppActionVisible
import scooper.viewmodels.saveToBucketPrimary
import scooper.viewmodels.formatStepDuration
import scooper.viewmodels.formatTokenFooter
import scooper.viewmodels.DOWNLOAD_DETAIL_MAX
import scooper.viewmodels.elideUrlMiddle
import scooper.viewmodels.isFailedExecutionStep
import scooper.viewmodels.isNameConflictOnly
import scooper.viewmodels.saveUpdatedHintText
import scooper.viewmodels.shouldRenderFullExecutionSteps
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.FilenameFilter
import javax.imageio.ImageIO
import org.slf4j.LoggerFactory

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
    viewModel: CreateViewModel,
    tabController: CreateTabController = koinInject(),
) {
    val state by viewModel.container.stateFlow.collectAsState()
    val tabUiState by tabController.uiState.collectAsState()
    val navigator = LocalBackStack.current as BackStack<AppRoute>
    val listState = rememberLazyListState()
    // Tick bumped on every reset; input composable requests keyboard effect when it changes.
    // Also bumped on tab switch so the Composer re-requests focus for the new tab.
    var inputFocusTick by remember { mutableStateOf(0) }
    LaunchedEffect(tabUiState.activeTabId) { inputFocusTick++ }

    // Composer prefill (Discover "Chat with AI" handoff seam): when the active
    // tab has a pending prefill, push it into the composer input and select the
    // @bucket/app token so the handoff is visible at a glance. The text rides
    // with the tick so the composer write never waits on the VM state round
    // trip; consume afterwards so it is not reapplied.
    var prefillSelectTick by remember { mutableStateOf(0) }
    var prefillSelectText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tabUiState.activeTabId, tabUiState.tabs) {
        val activeTabId = tabUiState.activeTabId
        val prefill = tabUiState.tabs
            .firstOrNull { it.tabId == tabUiState.activeTabId }
            ?.composerPrefill
        if (prefill != null && activeTabId != null) {
            viewModel.onNoteChange(prefill)
            prefillSelectText = prefill
            prefillSelectTick++
            inputFocusTick++
            tabController.consumeComposerPrefill(activeTabId)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAiConfig()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface),
    ) {
        // Header block: title row plus session tabs share one bordered box, so
        // the bottom border doubles as the divider between header and content.
        // New sessions are created from the header "+ New Session" button only.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, colors.borderDefault)),
        ) {
            CreateTopBar(
                onNewSession = tabController::createTab,
                onOpenAiSettings = { navigator.push(AppRoute.Settings.AI) },
            )
            // Hide the tab strip while there is a single session; it only
            // carries switching/closing affordances relevant to multiple tabs.
            if (tabUiState.tabs.size > 1) {
                CreateTabBar(
                    tabs = tabUiState.tabs,
                    activeTabId = tabUiState.activeTabId,
                    onSelect = tabController::selectTab,
                    onClose = tabController::closeTabRequest,
                )
            }
        }

        if (!state.aiConfigured) {
            AiConfigBanner(
                configHint = state.configHint,
                onOpenAiSettings = { navigator.push(AppRoute.Settings.AI) },
            )
        }

        if (state.isEmpty) {
            EmptyWorkspace(
                state = state,
                viewModel = viewModel,
                inputFocusTick = inputFocusTick,
                prefillSelectTick = prefillSelectTick,
                prefillSelectText = prefillSelectText,
            )
        } else {
            ChatWorkspace(
                state = state,
                viewModel = viewModel,
                listState = listState,
                inputFocusTick = inputFocusTick,
                prefillSelectTick = prefillSelectTick,
                prefillSelectText = prefillSelectText,
                onGoToDiscover = { navigator.popupAllAndPush(AppRoute.Apps(scope = "")) },
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
            refHint = state.saveRefHint,
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

    // Closing a guarded tab asks for confirmation; rendered with the tab bar
    // so the dialog only exists while the Create page is visible.
    if (tabUiState.pendingCloseTabId != null) {
        ConfirmDialog(
            text = "Discard this session? Unsaved work cannot be recovered.",
            title = "Close Tab",
            confirmText = "Discard",
            destructiveConfirm = true,
            onConfirm = tabController::confirmCloseTab,
            onCancel = tabController::cancelCloseTab,
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
private fun SaveToBucketDialog(
    appName: String,
    bucket: String,
    bucketOptions: List<String>,
    commitToGit: Boolean,
    isGitBucket: Boolean,
    refHint: String? = null,
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
            modifier = Modifier.width(420.dp),
        ) {
            // fillMaxWidth only: since CMP 1.7 the layer Dialog measures content with
            // window-bounded constraints, so fillMaxSize stretches the dialog full-height.
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(24.dp)) {
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
                        if (refHint != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                refHint,
                                style = typography.caption.copy(color = colors.primary),
                            )
                        }

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
    onNewSession: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, start = 24.dp, end = 24.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Row(Modifier.weight(1f)) {
            Text(
                "Create",
                style = typography.h5.copy(
                    fontWeight = FontWeight.Bold,
                    color = colors.textTitle,
                ),
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "Generate Scoop manifest with AI from a GitHub URL",
                style = typography.body2.copy(color = colors.textBody),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .alignByBaseline(),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onNewSession,
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

/**
 * The one Composer instance behind the Create page: wired straight to the
 * tab's [CreateViewModel] so workspaces never forward its callbacks. The
 * key(viewModel) recreates the composer (and its focus state) when the tab
 * controller swaps the active view model.
 */
@Composable
private fun CreateComposerHost(
    state: CreateState,
    viewModel: CreateViewModel,
    inputFocusTick: Int,
    prefillSelectTick: Int,
    prefillSelectText: String?,
    centered: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    key(viewModel) {
        Composer(
            state = state,
            centered = centered,
            inputFocusTick = inputFocusTick,
            prefillSelectTick = prefillSelectTick,
            prefillSelectText = prefillSelectText,
            onUrlChange = viewModel::onUrlChange,
            onNoteChange = viewModel::onNoteChange,
            onModelChange = viewModel::onModelChange,
            onFetchModels = viewModel::onFetchModels,
            onSend = { url, note -> viewModel.onSend(url = url, note = note) },
            onCancel = viewModel::onCancel,
            onAttachImages = viewModel::attachImages,
            onRemovePendingImage = viewModel::removePendingImage,
            onEnsureRefSuggestions = viewModel::ensureManifestRefSuggestions,
            modifier = modifier,
        )
    }
}

@Composable
private fun EmptyWorkspace(
    state: CreateState,
    viewModel: CreateViewModel,
    inputFocusTick: Int,
    prefillSelectTick: Int,
    prefillSelectText: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // While the draft carries a @bucket/app reference the hero switches to
        // modify-oriented copy (the AI reads the referenced manifest); deleting
        // the token falls back to the create copy with no extra state machine.
        val refToken = state.composerManifestRef
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
                    if (refToken == null) Lucide.Plus else Lucide.MessageSquarePlus,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (refToken == null) {
                    "Create a Scoop manifest with AI"
                } else {
                    "Chat about $refToken"
                },
                style = typography.h6.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textTitle,
                ),
            )
        }
        Spacer(Modifier.height(20.dp))
        CreateComposerHost(
            state = state,
            viewModel = viewModel,
            inputFocusTick = inputFocusTick,
            prefillSelectTick = prefillSelectTick,
            prefillSelectText = prefillSelectText,
            centered = true,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            if (refToken == null) {
                "Paste a GitHub repository URL and press Send. After the manifest is ready, you can save it."
            } else {
                "Describe what to change. The AI reads the referenced manifest before answering."
            },
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
    viewModel: CreateViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    inputFocusTick: Int,
    prefillSelectTick: Int,
    prefillSelectText: String?,
    onGoToDiscover: () -> Unit,
    onAction: (AiErrorAction) -> Unit,
) {
    @Composable
    fun PreviewArtifactPanel(
        modifier: Modifier = Modifier,
        fillRemainingHeight: Boolean = false,
    ) {
        Box(modifier = modifier) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ManifestPreviewCard(
                    modifier = if (fillRemainingHeight) Modifier.weight(1f) else Modifier,
                    appName = state.suggestedAppName ?: "app",
                    manifestJson = state.activeManifestJson,
                    enabled = !state.running && !state.testing,
                    testing = state.testing,
                    testSucceeded = manifestTestSucceeded(state.installVerified, state.manifestSaved),
                    manifestSaved = state.manifestSaved,
                    hasSaveResult = state.saveResult != null,
                    installing = state.installing,
                    installedForUse = state.installedForUse,
                    systemInstalled = state.systemInstalled,
                    running = state.running,
                    issues = state.manifestIssues,
                    exhausted = state.manifestExhausted,
                    invalidJson = state.manifestInvalidJson,
                    revisions = state.revisions,
                    selectedRevisionId = state.selectedRevisionId,
                    scrollableCard = fillRemainingHeight,
                    actionChips = state.reviewActionChips,
                    onApplyActionChip = viewModel::onApplyActionChip,
                    onSelectRevision = viewModel::onSelectRevision,
                    onRevertToRevision = viewModel::onRevertToRevision,
                    onCopyDone = viewModel::onCopyDone,
                    onSave = viewModel::onSave,
                    onInstallSaved = viewModel::onInstallSaved,
                    onOpenApp = viewModel::onOpenApp,
                )
                state.saveResult?.let { result ->
                    SaveResultPanel(
                        result = result,
                        onGoToDiscover = onGoToDiscover,
                        onDismiss = viewModel::onDismissSaveResult,
                    )
                }
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
                                    onToggleExecutionExpanded = viewModel::onToggleExecutionExpanded,
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
                                        onAnswer = viewModel::onAnswerAskUser,
                                        onCancel = viewModel::onCancelAskUser,
                                    )
                                }
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }

                    CreateComposerHost(
                        state = state,
                        viewModel = viewModel,
                        inputFocusTick = inputFocusTick,
                        prefillSelectTick = prefillSelectTick,
                        prefillSelectText = prefillSelectText,
                        centered = false,
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

                    // Right column: Manifest Artifact Panel. The Column inside the
                    // panel is not scrollable itself so weight(1f) can stretch the
                    // preview card; scrolling happens inside the card.
                    Box(
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        PreviewArtifactPanel(
                            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                            fillRemainingHeight = true,
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
                                    onToggleExecutionExpanded = viewModel::onToggleExecutionExpanded,
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
                                    onAnswer = viewModel::onAnswerAskUser,
                                    onCancel = viewModel::onCancelAskUser,
                                )
                            }
                        }
                        if (state.manifestJson.isNotBlank()) {
                            item(key = "manifest-card") {
                                PreviewArtifactPanel(fillRemainingHeight = false)
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }

                CreateComposerHost(
                    state = state,
                    viewModel = viewModel,
                    inputFocusTick = inputFocusTick,
                    prefillSelectTick = prefillSelectTick,
                    prefillSelectText = prefillSelectText,
                    centered = false,
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
                // Token usage shares the bottom meta row with the completed-steps
                // capsule; user and error bubbles have no token footer.
                val tokenFooter =
                    if (!isUser && !isErrorBubble) formatTokenFooter(message.tokenUsage) else null
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
                        // Expanded steps sit after the body inside a faint neutral
                        // block so they do not stick to the reply text. Error bubbles
                        // fold their steps too, so they also expand (failure review).
                        if (message.executionSteps.isNotEmpty() &&
                            message.isExecutionExpanded && expandableExecutionSteps
                        ) {
                            Surface(
                                color = colors.divider.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
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
                        }
                        // Bottom meta row: completed-steps capsule on the left, token
                        // usage pushed to the trailing edge. Only the capsule is
                        // clickable to toggle the step list when it is expandable.
                        if (message.executionSteps.isNotEmpty() || tokenFooter != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (message.executionSteps.isNotEmpty()) {
                                    ExecutionSummaryCapsule(
                                        steps = message.executionSteps,
                                        expandable = expandableExecutionSteps,
                                        onClick = { onToggleExecutionExpanded(message.id) },
                                        expanded = message.isExecutionExpanded,
                                        failed = isErrorBubble,
                                    )
                                }
                                tokenFooter?.let { footer ->
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        footer,
                                        style = typography.caption.copy(color = colors.textMuted),
                                    )
                                }
                            }
                        }
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
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(min = 84.dp)
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
        // Lighter chip backdrop than the 0.15f step surface distinguishes
        // tool-call lines from bullet summary lines.
        val chipModifier = Modifier
            .fillMaxWidth()
            .background(colors.divider.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)

        step.toolActivities.forEach { line ->
            Text(
                line,
                style = typography.caption.copy(
                    color = if (line.contains("✖")) colors.dangerDefault else colors.textMuted,
                    fontSize = typography.caption.fontSize * 0.92f,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = chipModifier,
            )
        }
        liveActivity?.let { activity ->
            Text(
                activity,
                style = typography.caption.copy(
                    color = colors.primary,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = chipModifier,
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
    failed: Boolean = false,
) {
    Surface(
        color = colors.surface,
        contentColor = colors.textBody,
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.dp, colors.borderDefault),
        modifier = Modifier
            .wrapContentWidth(align = Alignment.Start)
            .clip(RoundedCornerShape(percent = 50))
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
                if (failed) "✗" else "✓",
                style = typography.caption.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed) colors.dangerDefault else colors.updateDefault,
                ),
            )
            Text(
                executionSummaryCapsuleText(steps, failed = failed).removePrefix(if (failed) "✗ " else "✓ "),
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
    modifier: Modifier = Modifier,
    // Only height-bound cards may scroll internally; a wrap-content card inside
    // an unbounded parent (LazyColumn item) would crash.
    scrollableCard: Boolean = false,
    appName: String,
    manifestJson: String,
    enabled: Boolean,
    testing: Boolean,
    testSucceeded: Boolean = false,
    running: Boolean = false,
    issues: List<String> = emptyList(),
    exhausted: Boolean = false,
    invalidJson: Boolean = false,
    manifestSaved: Boolean = false,
    hasSaveResult: Boolean = false,
    installing: Boolean = false,
    installedForUse: Boolean = false,
    systemInstalled: Boolean = false,
    revisions: List<ManifestRevision> = emptyList(),
    selectedRevisionId: String? = null,
    actionChips: List<ReviewActionChip> = emptyList(),
    onApplyActionChip: ((ReviewActionChip) -> Unit)? = null,
    onSelectRevision: ((String?) -> Unit)? = null,
    onRevertToRevision: ((String) -> Unit)? = null,
    onCopyDone: () -> Unit,
    onSave: () -> Unit,
    onInstallSaved: () -> Unit = {},
    onOpenApp: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var showDiff by remember { mutableStateOf(false) }
    val previewHScroll = rememberScrollState()
    // Scrolls the card body (header / manifest / actions) when the card is
    // height-constrained by the parent panel.
    val cardScroll = rememberScrollState()

    // A new revision (or switching revisions) resets the preview back to the top:
    // the previous JSON may have been scrolled to its bottom, and the user should
    // always start reading from the manifest head.
    LaunchedEffect(manifestJson, selectedRevisionId) {
        cardScroll.scrollTo(0)
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
        modifier = modifier.fillMaxWidth().widthIn(max = 760.dp),
    ) {
        Box {
            Column(
                // Height-bound mode fills the bounded panel height so the weighted
                // preview region can take the remaining space and scroll internally;
                // the header and the action row stay pinned. A card-level
                // verticalScroll here would measure the preview with unbounded height,
                // wrap the full manifest and push the action row below the fold.
                modifier = if (scrollableCard) Modifier.fillMaxSize() else Modifier,
            ) {
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
                    "suggested: $appName",
                    style = typography.caption.copy(color = colors.textMuted),
                    modifier = Modifier.padding(start = 12.dp),
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
                    .then(
                        // Height bound: take the space left between the pinned header
                        // and action row and scroll the manifest inside. Unbounded
                        // parent (LazyColumn item): wrap with a minimum height.
                        if (scrollableCard) Modifier.weight(1f) else Modifier.heightIn(min = 200.dp),
                    )
                    .background(colors.inputBackground),
            ) {
                val previewContent: @Composable () -> Unit = { SelectableContainer {
                    if (showDiff && previousRev != null && detailedDiff.isNotEmpty()) {
                        // No own vertical scrolling: the card-level scroll handles
                        // overflow when the card is height bound.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
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
                            // of folding mid-token. Vertical scrolling is owned by the
                            // card-level scroll.
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(previewHScroll)
                                .padding(14.dp),
                        )
                    }
                } }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (scrollableCard) {
                                Modifier.verticalScroll(cardScroll)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    previewContent()
                }
                if (scrollableCard) {
                    // The thumb is transparent when the content fits, so a short
                    // manifest keeps the preview region clean.
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(cardScroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(previewHScroll),
                        // End padding keeps the horizontal bar clear of the
                        // vertical scrollbar (default thickness 8dp).
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                    )
                }
            }

            if (issues.isNotEmpty() || (invalidJson && actionChips.isNotEmpty())) {
                val hasNameConflictOnly = isNameConflictOnly(issues, invalidJson)
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
                        // Chips row stays outside SelectableContainer below: with
                        // isMouseSelectionBetweenTextEnabled=true its container-level
                        // mouse selection gesture races the chip clickable and eats
                        // the press (Main pass), so only issue rows are selectable.
                        SelectableContainer {
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
                                // Solid fill reads as a button; the former 12% tint
                                // with colored text looked like a weak hyperlink.
                                var chipHovered by remember { mutableStateOf(false) }
                                val chipColor = if (chip.isPrimary) colors.primary else colors.warningDefault
                                val chipFill = when {
                                    !enabled -> chipColor
                                    chipHovered -> if (chip.isPrimary) colors.primaryHover else colors.warningHover
                                    else -> chipColor
                                }
                                val chipContentColor = if (chip.isPrimary) Color.White else colors.onWarning
                                // Padding on the Text keeps the Surface background and the
                                // clickable area the same size.
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = chipFill,
                                    modifier = Modifier
                                        .alpha(if (enabled) 1f else 0.5f)
                                        .cursorHand()
                                        .clip(RoundedCornerShape(6.dp))
                                        .onHover { chipHovered = it }
                                        .clickable(enabled = enabled) {
                                            onApplyActionChip(chip)
                                        },
                                ) {
                                    Text(
                                        chip.label,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        style = typography.caption.copy(
                                            color = chipContentColor,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                        ),
                                    )
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
                    label = "Copy",
                    onClick = {
                        clipboard.setText(AnnotatedString(manifestJson))
                        onCopyDone()
                    },
                    enabled = true,
                )
                // After an AI verify_install passed, Save to Bucket becomes the
                // primary action; once the manifest is saved the button is plain.
                SmallActionButton(
                    label = "Save to Bucket",
                    onClick = onSave,
                    enabled = enabled && !testing && !invalidJson,
                    primary = saveToBucketPrimary(testSucceeded, manifestSaved),
                )
                // Install for real use while the manifest is saved but not yet
                // installed; once installed the button is hidden and Open appears
                // when the manifest declares Start Menu shortcuts.
                if (installActionVisible(installedForUse)) {
                    SmallActionButton(
                        label = installActionLabel(systemInstalled = systemInstalled),
                        onClick = onInstallSaved,
                        enabled = installActionEnabled(manifestJson, installedForUse, running, testing, installing),
                        primary = installActionPrimary(manifestSaved, installedForUse),
                        loading = installing,
                    )
                }
                if (openAppActionVisible(installedForUse, manifestHasShortcuts(manifestJson))) {
                    SmallActionButton(
                        label = "Open",
                        onClick = onOpenApp,
                        enabled = true,
                        primary = false,
                    )
                }
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

            // In-place overwrite of an existing manifest (e.g. the reference save
            // lock); distinguishes an update from a brand-new save on the card.
            saveUpdatedHintText(result)?.let { hint ->
                Spacer(Modifier.height(4.dp))
                Text(
                    hint,
                    style = typography.caption.copy(color = colors.textMuted),
                )
            }

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

/**
 * Decodes attached image bytes once per id. Decoding is opt-in on failure:
 * a corrupt image is skipped instead of crashing the composer.
 */
@Composable
private fun rememberImageBitmap(image: PendingImage): ImageBitmap? =
    remember(image.id) {
        runCatching { image.bytes.decodeToImageBitmap() }.getOrNull()
    }

/** Small pending-image preview with a remove affordance, shown above the input. */
@Composable
private fun PendingImageThumb(
    image: PendingImage,
    onRemove: (String) -> Unit,
    onPreview: (PendingImage) -> Unit,
) {
    val colors = MaterialTheme.colors
    val bitmap = rememberImageBitmap(image) ?: return
    Box(modifier = Modifier.size(60.dp)) {
        Image(
            bitmap = bitmap,
            contentDescription = "Attached image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .cursorHand()
                .clickable { onPreview(image) },
        )
        Surface(
            shape = CircleShape,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.borderDefault),
            modifier = Modifier.align(Alignment.TopEnd).size(18.dp).cursorHand(),
        ) {
            Icon(
                Lucide.X,
                contentDescription = "Remove image",
                tint = colors.textMuted,
                modifier = Modifier.padding(3.dp).clickable { onRemove(image.id) },
            )
        }
    }
}

/**
 * Fullscreen image preview: dim scrim with the image fitted inside, tap anywhere
 * to dismiss. Switch between attached images via the dots at the bottom.
 */
@Composable
private fun ImagePreviewDialog(
    images: List<PendingImage>,
    initialImage: PendingImage,
    onDismiss: () -> Unit,
) {
    val startAt = images.indexOfFirst { it.id == initialImage.id }.takeIf { it >= 0 } ?: 0
    var currentIndex by remember(initialImage.id) { mutableStateOf(startAt) }
    val current = images.getOrNull(currentIndex) ?: initialImage
    val bitmap = rememberImageBitmap(current)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .cursorHand()
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Image preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize(0.85f)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Text(
                "${currentIndex + 1} / ${images.size}" +
                    "  " + current.mimeType +
                    "  " + bitmapWidth(bitmap) + "x" + bitmapHeight(bitmap),
                style = typography.caption.copy(color = Color.White.copy(alpha = 0.7f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
            )
            if (images.size > 1) {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    images.forEachIndexed { index, item ->
                        Box(
                            modifier = Modifier
                                .size(if (index == currentIndex) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (index == currentIndex) 1f else 0.4f))
                                .cursorHand()
                                .clickable { currentIndex = index },
                        )
                    }
                }
            }
        }
    }
}

private fun bitmapWidth(bitmap: ImageBitmap?): Int = bitmap?.width ?: 0

private fun bitmapHeight(bitmap: ImageBitmap?): Int = bitmap?.height ?: 0

/** Picks images through the FlatLaf system file chooser (EDT-only API) and attaches them. */
private fun pickImages(onAttach: (List<PendingImage>) -> Unit) {
    java.awt.EventQueue.invokeLater {
        // FlatLaf SystemFileChooser shows the native Windows IFileDialog with a
        // reliable extension filter and multi-select, unlike Swing/AWT choosers.
        val chooser = com.formdev.flatlaf.util.SystemFileChooser()
        chooser.dialogTitle = "Select image"
        chooser.isMultiSelectionEnabled = true
        chooser.fileSelectionMode = com.formdev.flatlaf.util.SystemFileChooser.FILES_ONLY
        chooser.fileFilter = com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter(
            "Image files (PNG, JPEG)",
            "png", "jpg", "jpeg",
        )
        val chosen = if (chooser.showOpenDialog(null) == com.formdev.flatlaf.util.SystemFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles.toList()
        } else {
            emptyList()
        }
        val picked = chosen
        if (picked.isEmpty()) return@invokeLater
        // Decode + compress off the EDT: loadAndCompress does real image IO.
        Thread({
            val images = picked.mapNotNull { file ->
                ImageCodec.loadAndCompress(file).getOrNull()
                    ?.let { PendingImage(bytes = it.bytes, mimeType = it.mimeType) }
            }
            if (images.isNotEmpty()) onAttach(images.take(MAX_PENDING_IMAGES))
        }, "image-pick-encode").start()
    }
}

/** Compact plus button inside the input box, bottom-right aligned. */
@Composable
private fun InlineAttachButton(enabled: Boolean, onAttachImages: (List<PendingImage>) -> Unit) {
    val colors = MaterialTheme.colors
    Tooltip("Attach image (paste also works: Ctrl+V)", position = TooltipPosition.Top) {
        Box(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(colors.inputBackground)
                .cursorHand()
                .clickable(enabled = enabled) { pickImages(onAttachImages) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.Plus,
                contentDescription = "Attach image",
                modifier = Modifier.size(18.dp),
                tint = if (enabled) colors.textMuted else colors.divider,
            )
        }
    }
}

/** Non-blocking clipboard probe for the key handler: true only when an image flavor is present. */
private fun clipboardHasImage(): Boolean = try {
    Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
} catch (_: Exception) {
    false
}

private fun readClipboardImage(): PendingImage? {
    repeat(8) { attempt ->
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                val img = clipboard.getData(DataFlavor.imageFlavor) as? BufferedImage
                    ?: return@repeat
                val out = ByteArrayOutputStream()
                ImageIO.write(img, "png", out)
                val encoded = ImageCodec.compressBytes(out.toByteArray(), "image/png").getOrNull()
                    ?: return@repeat
                return PendingImage(bytes = encoded.bytes, mimeType = encoded.mimeType)
            }
            return null
        } catch (_: Exception) {
            Thread.sleep(40)
        }
    }
    return null
}

/** View model of the @ completion menu: one filterable app index row. */
private data class RefMenuEntry(
    val suggestion: ManifestRefSuggestion,
    /** "bucket/name", matching the token the user is typing. */
    val label: String,
)

/** Dropdown list shown above the input while an @ token is being typed. */
@Composable
private fun ManifestRefSuggestionMenu(
    suggestions: List<ManifestRefSuggestion>,
    selectedIndex: Int,
    onPick: (ManifestRefSuggestion) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.surface,
        elevation = 6.dp,
        border = BorderStroke(1.dp, colors.borderDefault),
        modifier = Modifier.widthIn(max = 360.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            suggestions.forEachIndexed { index, suggestion ->
                val entry = RefMenuEntry(suggestion, "${suggestion.bucket}/${suggestion.name}")
                val highlighted = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (highlighted) colors.primarySubtle else Color.Transparent)
                        .cursorHand()
                        .clickable { onPick(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.label,
                        style = typography.caption.copy(
                            color = if (highlighted) colors.primary else colors.textTitle,
                            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
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

@Composable
private fun Composer(
    state: CreateState,
    centered: Boolean,
    inputFocusTick: Int,
    prefillSelectTick: Int,
    prefillSelectText: String?,
    onUrlChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSend: (url: String, note: String) -> Unit,
    onCancel: () -> Unit,
    onAttachImages: (List<PendingImage>) -> Unit,
    onRemovePendingImage: (String) -> Unit,
    onEnsureRefSuggestions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Shared so every pending thumbnail opens the same preview strip.
    var previewImage by remember { mutableStateOf<PendingImage?>(null) }

    // @ completion state: keep the selection with the text so the caret word can
    // be detected and replaced; the menu never sends the message on Enter.
    var fieldValue by remember { mutableStateOf(TextFieldValue(state.inputNote.ifBlank { state.inputUrl })) }
    var refMenuIndex by remember { mutableStateOf(-1) }
    var refFieldHeightPx by remember { mutableStateOf(0) }

    // Prefill handoff (Discover "Chat with AI"): land the token in the field
    // fully selected before focus lands, so the handoff is visible at a glance.
    // The prefill carries a trailing space that terminates the @ token; the
    // selection stops before it so typing replaces only the reference.
    // Declaration order matters: this effect runs before the focus effect below.
    var handledSelectTick by remember { mutableStateOf(0) }
    LaunchedEffect(prefillSelectTick) {
        val text = prefillSelectText
        if (prefillSelectTick != handledSelectTick && text != null) {
            handledSelectTick = prefillSelectTick
            // Exclude the trailing space from the highlight so typing replaces
            // only the reference and the separator space survives.
            val selectEnd = (text.length - 1).coerceAtLeast(0)
            fieldValue = TextFieldValue(text, TextRange(0, selectEnd))
        }
    }
    LaunchedEffect(inputFocusTick) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(state.inputNote, state.inputUrl) {
        val latest = state.inputNote.ifBlank { state.inputUrl }
        when {
            // Clearing must win even while focused: after a send the VM resets
            // both inputs to "" and the still-focused field would otherwise
            // keep showing the sent text with an inert Send button.
            latest.isEmpty() -> fieldValue = TextFieldValue("")
            // Text is only pushed in while unfocused so active typing is not
            // clobbered by VM round-trips.
            latest != fieldValue.text && !focused -> fieldValue = TextFieldValue(latest)
        }
    }

    fun submit() {
        // composerSendEnabled covers installing too: Enter during a direct
        // install must not fire a send behind the VM guard and lose the typed
        // text to the reset below. A pending questionnaire unlocks the
        // composer; the VM routes the send to the questionnaire answer.
        val askUserPending = state.askUserRequest != null
        if (!composerSendEnabled(
                running = state.running,
                testing = state.testing,
                askUserPending = askUserPending,
                installing = state.installing,
            )
        ) {
            return
        }
        if (fieldValue.text.isBlank() && state.pendingImages.isEmpty()) return
        val text = fieldValue.text.trim()
        fieldValue = TextFieldValue("")
        refMenuIndex = -1
        onUrlChange("")
        onNoteChange("")
        onSend(text, text)
    }

    val activeRefQuery = activeManifestRefToken(fieldValue.text, fieldValue.selection.min)
    // Starts closed: a stale session (stray @ left in the text after a send)
    // must not swallow Enter in the menu branch and block typing.
    var refMenuOpen by remember { mutableStateOf(false) }
    val refSuggestions = if (activeRefQuery != null) {
        remember(activeRefQuery, state.manifestRefSuggestions) {
            filterRefSuggestions(state.manifestRefSuggestions, activeRefQuery)
        }
    } else {
        emptyList()
    }
    val refMenuVisible = isRefMenuVisible(
        hasActiveToken = activeRefQuery != null,
        hasSuggestions = refSuggestions.isNotEmpty(),
        menuOpen = refMenuOpen,
    )

    fun applyRefSuggestion(suggestion: ManifestRefSuggestion) {
        val query = activeRefQuery ?: return
        val start = fieldValue.selection.min
        val text = fieldValue.text
        val before = text.substring(0, start - query.length)
        val after = text.substring(start)
        val insertion = suggestion.insertion
        val newText = before + insertion + after
        val caret = (before.length + insertion.length).coerceAtMost(newText.length)
        fieldValue = TextFieldValue(newText, TextRange(caret))
        // The completed token no longer matches activeManifestRefToken, but keep
        // the menu shut anyway so re-typing the same prefix starts fresh.
        refMenuOpen = false
        refMenuIndex = -1
        onUrlChange(newText)
        onNoteChange(newText)
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
            if (state.pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.pendingImages.forEach { image ->
                        PendingImageThumb(
                            image = image,
                            onRemove = onRemovePendingImage,
                            onPreview = { previewImage = image },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { value ->
                    fieldValue = value
                    refMenuIndex = -1
                    refMenuOpen = true
                    onUrlChange(value.text)
                    onNoteChange(value.text)
                    // Use the NEW text/selection: typing @ must trigger the lazy
                    // index fetch in the same frame, not one keystroke later.
                    if (activeManifestRefToken(value.text, value.selection.min) != null) {
                        onEnsureRefSuggestions()
                    }
                },
                enabled = composerSendEnabled(
                    running = state.running,
                    testing = state.testing,
                    askUserPending = state.askUserRequest != null,
                    installing = state.installing,
                ),
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
                    .weight(1f)
                    .heightIn(min = 46.dp, max = 150.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        // The @ menu consumes navigation keys first, so its
                        // Enter never reaches the send path below.
                        if (refMenuVisible) {
                            val menuSize = refSuggestions.size
                            when {
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                                    refMenuIndex = (refMenuIndex + 1).mod(menuSize)
                                    true
                                }
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                                    refMenuIndex = (refMenuIndex - 1).mod(menuSize)
                                    true
                                }
                                event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                                    !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed -> {
                                    if (refMenuIndex in 0 until menuSize) {
                                        applyRefSuggestion(refSuggestions[refMenuIndex])
                                    }
                                    true
                                }
                                event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                                    refMenuOpen = false
                                    true
                                }
                                else -> false
                            }
                        } else if (event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                            !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed
                        ) {
                            submit()
                            true
                        } else if (event.type == KeyEventType.KeyDown &&
                            ((event.key == Key.V && event.isCtrlPressed && !event.isShiftPressed) ||
                                (event.key == Key.Insert && event.isShiftPressed))
                        ) {
                            if (!clipboardHasImage()) {
                                false
                            } else {
                                // Read/compress off the UI thread (clipboard retries
                                // sleep); orbit intents are thread-safe to call.
                                scope.launch(Dispatchers.Default) {
                                    readClipboardImage()?.let { onAttachImages(listOf(it)) }
                                }
                                true
                            }
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .onSizeChanged { refFieldHeightPx = it.height },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (fieldValue.text.isEmpty()) {
                            val placeholderText = when {
                                state.askUserRequest != null -> "Type your answer..."
                                state.manifestJson.isNotBlank() ->
                                    "Ask AI to modify this manifest (e.g. remove notes, add shortcuts)..."
                                else -> "Ask anything, paste a GitHub URL, or reference @bucket/app"
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

                        // @ completion menu: floats above the input row inside the
                        // same bubble; lift it by the field height so it opens above
                        // the text instead of covering it.
                        if (refMenuVisible) {
                            Popup(
                                alignment = Alignment.BottomStart,
                                offset = IntOffset(0, -refFieldHeightPx),
                                onDismissRequest = { refMenuOpen = false },
                                properties = PopupProperties(focusable = false),
                            ) {
                                ManifestRefSuggestionMenu(
                                    suggestions = refSuggestions,
                                    selectedIndex = refMenuIndex.coerceIn(0, refSuggestions.lastIndex),
                                    onPick = ::applyRefSuggestion,
                                )
                            }
                        }
                    }
                },
            )
                InlineAttachButton(
                    enabled = !state.running && !state.testing && state.pendingImages.size < MAX_PENDING_IMAGES,
                    onAttachImages = onAttachImages,
                )
            }
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
                if (state.running && state.askUserRequest == null) {
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
                        enabled = !state.testing && (fieldValue.text.isNotBlank() || state.pendingImages.isNotEmpty()),
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
    previewImage?.let { clicked ->
        ImagePreviewDialog(images = state.pendingImages, initialImage = clicked) {
            previewImage = null
        }
    }
}

/** Whether the @ completion popup is currently shown above the composer. */
internal fun isRefMenuVisible(hasActiveToken: Boolean, hasSuggestions: Boolean, menuOpen: Boolean): Boolean =
    hasActiveToken && hasSuggestions && menuOpen
