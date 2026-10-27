package scooper.ui.create

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.koinInject
import scooper.viewmodels.CreateTabController
import scooper.ui.AppRoute
import scooper.ui.components.ConfirmDialog
import scooper.ui.components.DialogTextField
import scooper.ui.components.ExposedDropdownMenu
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.service.AiErrorAction
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.viewmodels.CreateState
import scooper.viewmodels.CreateViewModel
import scooper.viewmodels.manifestTestSucceeded
import scooper.viewmodels.shouldRenderFullExecutionSteps
import scooper.ui.icons.*
import scooper.ui.theme.*

@Suppress("UNCHECKED_CAST")
@Composable
fun CreateScreen(
    viewModel: CreateViewModel,
    tabController: CreateTabController = koinInject(),
) {
    val state by viewModel.container.stateFlow.collectAsState()
    val tabUiState by tabController.uiState.collectAsState()
    val navigator = LocalBackStack.current as BackStack<AppRoute>
    val scrollState = rememberScrollState()
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
                scrollState = scrollState,
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
        // modify-oriented title and icon; deleting the token falls back to the
        // create copy with no extra state machine.
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
    }
}
@Composable
private fun ChatWorkspace(
    state: CreateState,
    viewModel: CreateViewModel,
    scrollState: ScrollState,
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
                    deployment = state.deployment,
                    saveBucket = state.saveResult?.bucket,
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
        // chat list is a plain scrollable Column, so its settled content height
        // (scrollState.maxValue) is the scroll target; keying on maxValue re-anchors
        // the list once the newly added content is measured.
        LaunchedEffect(
            state.messages.size,
            state.manifestJson,
            state.running,
            state.saveResult,
            state.askUserRequest,
            state.activeExecution?.steps?.size,
            state.activeExecution?.steps?.lastOrNull()?.toolActivities?.size,
            state.activeExecution?.currentActivity,
            scrollState.maxValue,
        ) {
            if (scrollState.maxValue > 0) {
                scrollState.animateScrollTo(scrollState.maxValue)
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
                        // Plain Column keeps the scrollbar thumb length stable: a
                        // LazyColumn reports an average-of-visible-items content size,
                        // so the thumb grows and shrinks while scrolling. fillMaxSize +
                        // verticalScroll keeps the viewport floor so the bottom
                        // arrangement still sticks short chats to the composer.
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = 12.dp)
                                .verticalScroll(scrollState)
                                .padding(vertical = 4.dp),
                            // Chat-app behaviour: short conversations stick to the
                            // composer instead of floating in the middle; the spare
                            // space stays above the first message.
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                        ) {
                            state.messages.forEach { message ->
                                key(message.id) {
                                    ChatBubble(
                                        message = message,
                                        onAction = onAction,
                                        expandableExecutionSteps = shouldRenderFullExecutionSteps(state.messages, message.id),
                                        onToggleExecutionExpanded = viewModel::onToggleExecutionExpanded,
                                    )
                                }
                            }
                            state.activeExecution?.let { active ->
                                key("active-execution") {
                                    ActiveExecutionCard(active = active)
                                }
                            }
                            state.askUserRequest?.let { request ->
                                key("ask-user-bubble-${request.id}") {
                                    AskUserBubble(
                                        request = request,
                                        onAnswer = viewModel::onAnswerAskUser,
                                        onCancel = viewModel::onCancelAskUser,
                                    )
                                }
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scrollState),
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
                    // Plain Column for the same stable-thumb reason as the wide
                    // layout above; the manifest preview joins the flow as a normal
                    // child instead of a lazy item.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 12.dp)
                            .verticalScroll(scrollState)
                            .padding(vertical = 4.dp),
                        // Chat-app behaviour: short conversations stick to the
                        // composer instead of floating in the middle; the spare
                        // space stays above the first message.
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                    ) {
                        state.messages.forEach { message ->
                            key(message.id) {
                                ChatBubble(
                                    message = message,
                                    onAction = onAction,
                                    expandableExecutionSteps = shouldRenderFullExecutionSteps(state.messages, message.id),
                                    onToggleExecutionExpanded = viewModel::onToggleExecutionExpanded,
                                )
                            }
                        }
                        state.activeExecution?.let { active ->
                            key("active-execution") {
                                ActiveExecutionCard(active = active)
                            }
                        }
                        state.askUserRequest?.let { request ->
                            key("ask-user-bubble-${request.id}") {
                                AskUserBubble(
                                    request = request,
                                    onAnswer = viewModel::onAnswerAskUser,
                                    onCancel = viewModel::onCancelAskUser,
                                )
                            }
                        }
                        if (state.manifestJson.isNotBlank()) {
                            key("manifest-card") {
                                PreviewArtifactPanel(fillRemainingHeight = false)
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
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