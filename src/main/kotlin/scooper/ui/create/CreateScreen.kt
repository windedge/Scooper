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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.koinInject
import scooper.ui.AppRoute
import scooper.ui.components.ConfirmDialog
import scooper.ui.components.DialogTextField
import scooper.ui.components.ExposedDropdownMenu
import scooper.ui.components.CompactModelSelector
import scooper.ui.components.SelectableContainer
import scooper.ui.icons.*
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.ui.theme.warningDefault
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.parseAnsiColors
import scooper.service.AiErrorAction
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.viewmodels.ChatMessage
import scooper.viewmodels.ChatRole
import scooper.viewmodels.CreateState
import scooper.viewmodels.CreateViewModel
import scooper.viewmodels.SaveGitHint
import scooper.viewmodels.SaveResult
import scooper.viewmodels.TestResult
import scooper.viewmodels.isNameConflictOnly

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

    // Auto-scroll chat when messages / manifest change
    LaunchedEffect(state.messages.size, state.manifestJson, state.running, state.testResult, state.saveResult) {
        // Cards after the chat: manifest preview, plus the test result and save result panels.
        val cardItems = when {
            state.manifestJson.isBlank() -> 0
            else -> 1 + (if (state.testResult != null) 1 else 0) + (if (state.saveResult != null) 1 else 0)
        }
        val itemCount = state.messages.size + cardItems
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface),
    ) {
        CreateTopBar(
            running = state.running,
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
                onRegenerate = viewModel::onRegenerate,
                onGoToDiscover = { navigator.popupAllAndPush(AppRoute.Apps(scope = "")) },
                onDismissSaveResult = viewModel::onDismissSaveResult,
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
                            modifier = Modifier.fillMaxWidth(),
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
    running: Boolean,
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
            StatusPill(running = running)
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
private fun StatusPill(running: Boolean) {
    val bg = if (running) colors.primarySubtle else colors.inputBackground
    val border = if (running) colors.primary.copy(alpha = 0.35f) else colors.borderDefault
    val textColor = if (running) colors.primary else colors.textBody
    val dotColor = if (running) colors.primary else colors.textMuted

    Row(
        modifier = Modifier
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            if (running) "Running" else "Idle",
            style = typography.caption.copy(
                fontWeight = FontWeight.SemiBold,
                color = textColor,
            ),
        )
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
    onRegenerate: () -> Unit,
    onGoToDiscover: () -> Unit,
    onDismissSaveResult: () -> Unit,
    onAction: (AiErrorAction) -> Unit,
) {
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    val isRunningPhase = state.running &&
                        message.role == ChatRole.Agent &&
                        message.phase != null &&
                        message.phase.equals(state.currentPhase, ignoreCase = true) &&
                        message.id == state.messages.lastOrNull { it.role == ChatRole.Agent }?.id

                    ChatBubble(
                        message = message,
                        currentActivity = if (isRunningPhase) state.currentActivity else null,
                        isRunning = isRunningPhase,
                        onAction = onAction,
                    )
                }
                if (state.manifestJson.isNotBlank()) {
                    item(key = "manifest-card") {
                        ManifestPreviewCard(
                            appName = state.suggestedAppName ?: "app",
                            manifestJson = state.manifestJson,
                            enabled = !state.running && !state.testing,
                            testing = state.testing,
                            issues = state.manifestIssues,
                            exhausted = state.manifestExhausted,
                            invalidJson = state.manifestInvalidJson,
                            onCopyDone = onCopyDone,
                            onTestInstall = onTestInstall,
                            onSave = onSave,
                            onRegenerate = onRegenerate,
                        )
                    }
                    state.testResult?.let { result ->
                        item(key = "test-result-panel") {
                            TestResultPanel(result)
                        }
                    }
                    state.saveResult?.let { result ->
                        item(key = "save-result-panel") {
                            SaveResultPanel(
                                result = result,
                                onGoToDiscover = onGoToDiscover,
                                onDismiss = onDismissSaveResult,
                            )
                        }
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

@Composable
private fun ChatBubble(
    message: ChatMessage,
    currentActivity: String? = null,
    isRunning: Boolean = false,
    onAction: (AiErrorAction) -> Unit,
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
        if (!isUser && message.phase != null) {
            PhaseTag(
                phase = message.phase,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            message.text,
                            style = typography.caption.copy(
                                color = colors.onSurface,
                                fontFamily = if (isUser) FontFamily.Monospace else FontFamily.Default,
                                lineHeight = typography.caption.fontSize * 1.5f,
                            ),
                        )
                        if (!message.note.isNullOrBlank()) {
                            Text(
                                message.note,
                                style = typography.caption.copy(
                                    color = colors.textMuted,
                                    lineHeight = typography.caption.fontSize * 1.45f,
                                ),
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
                        if (message.toolActivities.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                            ) {
                                message.toolActivities.forEach { toolLog ->
                                    Text(
                                        toolLog,
                                        style = typography.caption.copy(
                                            color = colors.textMuted,
                                            fontSize = typography.caption.fontSize * 0.92f,
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    )
                                }
                            }
                        }
                        if (!message.summary.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "• ${message.summary}",
                                style = typography.caption.copy(
                                    color = colors.textBody,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = typography.caption.fontSize * 0.95f,
                                ),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (isRunning) {
                            Spacer(Modifier.height(4.dp))
                            Box(modifier = Modifier.padding(top = 2.dp)) {
                                TypingDots()
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
        "analyze", "done" -> Triple(
            colors.updateDefault.copy(alpha = 0.12f),
            colors.updateDefault,
            colors.updateDefault.copy(alpha = 0.35f),
        )
        "generate", "refine", "notice", "warning" -> Triple(
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
    issues: List<String> = emptyList(),
    exhausted: Boolean = false,
    invalidJson: Boolean = false,
    onCopyDone: () -> Unit,
    onTestInstall: () -> Unit,
    onSave: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    val testName = "$appName-scooper-test"
    val scroll = rememberScrollState()

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
                        testing -> colors.primary
                        hasNameConflictOnly || exhausted || invalidJson -> colors.warningDefault
                        else -> colors.updateDefault
                    }
                    val statusText = when {
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = if (expanded) 2000.dp else 360.dp)
                    .background(colors.inputBackground),
            ) {
                SelectableContainer {
                    Text(
                        manifestJson,
                        style = typography.body2.copy(
                            color = colors.onSurface,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = typography.body2.fontSize * 1.55f,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .padding(14.dp),
                    )
                }
            }

            if (issues.isNotEmpty()) {
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
                SmallActionButton(
                    label = "Test Install",
                    onClick = onTestInstall,
                    enabled = enabled && !testing && !invalidJson,
                    primary = true,
                    loading = testing,
                )
                SmallActionButton(
                    label = "Save to Bucket",
                    onClick = onSave,
                    enabled = enabled && !testing && !invalidJson,
                )
                SmallActionButton(
                    label = "Regenerate",
                    onClick = onRegenerate,
                    enabled = enabled && !testing,
                )
            }
        }
    }
}

@Composable
private fun TestResultPanel(result: TestResult) {
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
    // Local state avoids Orbit intent race: Send can fire before reduce applies inputUrl/inputNote.
    var urlText by remember { mutableStateOf(state.inputUrl) }
    var noteText by remember { mutableStateOf(state.inputNote) }
    var urlFocused by remember { mutableStateOf(false) }
    var noteFocused by remember { mutableStateOf(false) }
    val focused = urlFocused || noteFocused
    val focusRequester = remember { FocusRequester() }

    // Sync when external state is cleared/rewritten (e.g. after successful startGeneration)
    LaunchedEffect(state.inputUrl) {
        if (state.inputUrl != urlText && !urlFocused) {
            urlText = state.inputUrl
        }
    }
    LaunchedEffect(state.inputNote) {
        if (state.inputNote != noteText && !noteFocused) {
            noteText = state.inputNote
        }
    }

    fun submit() {
        if (state.running) return
        onSend(urlText, noteText)
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
                value = urlText,
                onValueChange = { value ->
                    urlText = value
                    onUrlChange(value)
                },
                enabled = !state.running,
                singleLine = true,
                textStyle = typography.body2.copy(
                    fontWeight = FontWeight.Medium,
                    color = colors.textTitle,
                ),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { urlFocused = it.isFocused },
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (urlText.isEmpty()) {
                            Text(
                                "https://github.com/owner/repo",
                                style = typography.body2.copy(color = colors.textPlaceholder),
                            )
                        }
                        inner()
                    }
                },
            )
            val dividerColor = colors.borderDefault
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp),
            ) {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = strokeWidth,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                )
            }
            BasicTextField(
                value = noteText,
                onValueChange = { value ->
                    noteText = value
                    onNoteChange(value)
                },
                enabled = !state.running,
                singleLine = true,
                textStyle = typography.body2.copy(color = colors.textTitle),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .onFocusChanged { noteFocused = it.isFocused },
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (noteText.isEmpty()) {
                            Text(
                                "Optional note (e.g. prefer portable zip, GUI app)...",
                                style = typography.body2.copy(color = colors.textPlaceholder),
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
                enabled = !state.running,
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
                        enabled = urlText.isNotBlank(),
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

