package scooper.ui.create

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import org.koin.compose.koinInject
import scooper.ui.AppRoute
import scooper.ui.components.ConfirmDialog
import scooper.ui.components.DialogTextField
import scooper.ui.icons.ChevronDown
import scooper.ui.icons.Lucide
import scooper.ui.icons.Plus
import scooper.ui.icons.Send
import scooper.ui.icons.Settings
import scooper.ui.icons.X
import scooper.ui.theme.borderDefault
import scooper.ui.theme.dangerBg
import scooper.ui.theme.dangerDefault
import scooper.ui.theme.inputBackground
import scooper.ui.theme.inputBorder
import scooper.ui.theme.primarySubtle
import scooper.ui.theme.textBody
import scooper.ui.theme.textMuted
import scooper.ui.theme.textPlaceholder
import scooper.ui.theme.textTitle
import scooper.ui.theme.updateDefault
import scooper.ui.theme.warningDefault
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.viewmodels.ChatMessage
import scooper.viewmodels.ChatRole
import scooper.viewmodels.CreateState
import scooper.viewmodels.CreateViewModel

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
    LaunchedEffect(state.messages.size, state.manifestJson, state.running) {
        val itemCount = state.messages.size + if (state.manifestJson.isNotBlank()) 1 else 0
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
                onSend = { url, note -> viewModel.onSend(url, note) },
                onCancel = { viewModel.onCancel() },
            )
        } else {
            ChatWorkspace(
                state = state,
                listState = listState,
                onUrlChange = viewModel::onUrlChange,
                onNoteChange = viewModel::onNoteChange,
                onSend = { url, note -> viewModel.onSend(url, note) },
                onCancel = { viewModel.onCancel() },
                onCopyDone = viewModel::onCopyDone,
                onTestInstall = { viewModel.onTestInstall() },
                onSave = { viewModel.onSave() },
                onRegenerate = viewModel::onRegenerate,
            )
        }
    }

    if (state.saveDialogOpen) {
        SaveToBucketDialog(
            appName = state.saveAppName,
            bucket = state.saveBucket,
            bucketOptions = state.bucketOptions,
            onAppNameChange = viewModel::onSaveAppNameChange,
            onBucketChange = viewModel::onSaveBucketChange,
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
    onAppNameChange: (String) -> Unit,
    onBucketChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ConfirmDialog(
        title = "Save to Bucket",
        confirmText = "Save",
        onConfirm = onConfirm,
        onCancel = onCancel,
        state = DialogState(size = DpSize(420.dp, 360.dp)),
    ) {
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
                InlineBucketPicker(
                    selected = selected,
                    options = bucketOptions,
                    onSelectedChange = onBucketChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "No buckets available",
                    style = typography.caption.copy(color = colors.dangerDefault),
                )
            }
        }
    }
}

@Composable
private fun InlineBucketPicker(
    selected: String,
    options: List<String>,
    onSelectedChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevronRotation"
    )

    Column(modifier = modifier) {
        // Trigger row (collapsed state)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(color = colors.inputBackground, shape = RoundedCornerShape(8.dp))
                .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                .cursorHand()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected,
                style = typography.body2.copy(color = colors.textTitle),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Lucide.ChevronDown,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
                tint = colors.textMuted,
            )
        }

        // Expanded in-place list (no Popup)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
                    .background(colors.surface, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                options.forEach { item ->
                    val isSel = item == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .cursorHand()
                            .clickable {
                                expanded = false
                                onSelectedChange(item)
                            }
                            .background(if (isSel) colors.primarySubtle else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item,
                            style = typography.body2.copy(
                                color = if (isSel) colors.primary else colors.textTitle,
                                fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal,
                            ),
                        )
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
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(4.dp))
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
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("!", style = typography.caption.copy(fontWeight = FontWeight.Bold, color = textColor))
        Column(Modifier.weight(1f)) {
            Text(
                configHint ?: "AI is not configured. Fill Settings.AI, or set SCOOP_AI_API_KEY / SCOOP_AI_BASE_URL environment variables.",
                style = typography.caption.copy(color = textColor, lineHeight = typography.caption.fontSize * 1.45f),
            )
            TextButton(
                onClick = onOpenAiSettings,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.cursorLink().height(28.dp),
            ) {
                Text(
                    "Go to Settings.AI",
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
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primarySubtle)
                .border(1.dp, colors.primary.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.Plus,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = colors.primary,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Create a Scoop manifest with AI",
            style = typography.subtitle1.copy(
                fontWeight = FontWeight.SemiBold,
                color = colors.textTitle,
            ),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Paste a GitHub repository URL and press Send.\nAfter the manifest is ready, you can Test Install / Save.",
            style = typography.body2.copy(color = colors.textBody),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Composer(
            state = state,
            centered = true,
            onUrlChange = onUrlChange,
            onNoteChange = onNoteChange,
            onSend = onSend,
            onCancel = onCancel,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        )
    }
}

@Composable
private fun ChatWorkspace(
    state: CreateState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onUrlChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSend: (url: String, note: String) -> Unit,
    onCancel: () -> Unit,
    onCopyDone: () -> Unit,
    onTestInstall: () -> Unit,
    onSave: () -> Unit,
    onRegenerate: () -> Unit,
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
                    ChatBubble(message)
                }
                if (state.manifestJson.isNotBlank()) {
                    item(key = "manifest-card") {
                        ManifestPreviewCard(
                            appName = state.suggestedAppName ?: "app",
                            manifestJson = state.manifestJson,
                            enabled = !state.running && !state.testing,
                            testing = state.testing,
                            onCopyDone = onCopyDone,
                            onTestInstall = onTestInstall,
                            onSave = onSave,
                            onRegenerate = onRegenerate,
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
            onSend = onSend,
            onCancel = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
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
                if (message.isTyping) {
                    TypingDots()
                } else {
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
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseTag(phase: String, modifier: Modifier = Modifier) {
    val (bg, fg, border) = phaseColors(phase)
    Text(
        phase.uppercase(),
        style = typography.overline.copy(
            fontWeight = FontWeight.Bold,
            color = fg,
            letterSpacing = typography.overline.letterSpacing,
        ),
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
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
        "generate", "refine" -> Triple(
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
                    val statusColor = if (testing) colors.primary else colors.updateDefault
                    Text(
                        if (testing) "Installing" else "Ready",
                        style = typography.overline.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColor.copy(alpha = 0.12f))
                            .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, colors.borderDefault))
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
                    enabled = enabled && !testing,
                    primary = true,
                    loading = testing,
                )
                SmallActionButton(
                    label = "Save to Bucket",
                    onClick = onSave,
                    enabled = enabled && !testing,
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
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(
                    width = 1.dp,
                    color = if (focused) colors.primary else colors.inputBorder,
                    shape = RoundedCornerShape(12.dp),
                ),
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
            horizontalArrangement = Arrangement.End,
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
        Spacer(Modifier.height(6.dp))
        Text(
            if (centered) {
                "Paste a GitHub URL and press Send. Configure provider under Settings.AI."
            } else {
                "Enter a GitHub URL (optional note). Generation uses nonInteractive mode."
            },
            style = typography.caption.copy(color = colors.textMuted),
            modifier = if (centered) Modifier.fillMaxWidth() else Modifier,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}
