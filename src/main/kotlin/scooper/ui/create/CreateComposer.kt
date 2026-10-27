package scooper.ui.create

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import scooper.ui.components.CompactModelSelector
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.util.ImageCodec
import scooper.util.cursorHand
import scooper.viewmodels.CreateState
import scooper.viewmodels.CreateViewModel.Companion.MAX_PENDING_IMAGES
import scooper.viewmodels.ManifestRefSuggestion
import scooper.viewmodels.activeManifestRefToken
import scooper.viewmodels.composerSendEnabled
import scooper.viewmodels.filterRefSuggestions
import scooper.viewmodels.PendingImage
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import scooper.ui.icons.*
import scooper.ui.theme.*


/**
 * Decodes attached image bytes once per id. Decoding is opt-in on failure:
 * a corrupt image is skipped instead of crashing the composer.
 */
@Composable
internal fun rememberImageBitmap(image: PendingImage): ImageBitmap? =
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
internal fun ImagePreviewDialog(
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
/** Round attach button in the composer action strip. */
@Composable
private fun InlineAttachButton(enabled: Boolean, onAttachImages: (List<PendingImage>) -> Unit) {
    val colors = MaterialTheme.colors
    Tooltip("Attach image (paste also works: Ctrl+V)", position = TooltipPosition.Top) {
        Box(
            modifier = Modifier
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
@Composable
internal fun Composer(
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
    var refMenuIndex by remember { mutableStateOf(initialRefMenuIndex()) }
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
        refMenuIndex = initialRefMenuIndex()
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
        val updated = scooper.viewmodels.applyRefSuggestion(fieldValue, query, suggestion)
        if (updated == fieldValue) return
        fieldValue = updated
        // The completed token no longer matches activeManifestRefToken, but keep
        // the menu shut anyway so re-typing the same prefix starts fresh.
        refMenuOpen = false
        refMenuIndex = initialRefMenuIndex()
        onUrlChange(updated.text)
        onNoteChange(updated.text)
    }

    // One bordered box (ChatGPT/Claude style): text area on top, action strip
    // with attach, model selector and Send along the bottom inside the box.
    Column(
        modifier = modifier.then(
            if (centered) {
                Modifier
            } else {
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
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
            BasicTextField(
                value = fieldValue,
                onValueChange = { value ->
                    fieldValue = value
                    refMenuIndex = initialRefMenuIndex()
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
                    .fillMaxWidth()
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
                                    refMenuIndex = clampRefMenuIndex(refMenuIndex + 1, menuSize)
                                    true
                                }
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                                    refMenuIndex = clampRefMenuIndex(refMenuIndex - 1, menuSize)
                                    true
                                }
                                event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                                    !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed -> {
                                    if (menuSize > 0) {
                                        applyRefSuggestion(refSuggestions[clampRefMenuIndex(refMenuIndex, menuSize)])
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
                            .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 4.dp)
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
                                    selectedIndex = clampRefMenuIndex(refMenuIndex, refSuggestions.size),
                                    onPick = ::applyRefSuggestion,
                                )
                            }
                        }
                    }
                },
            )

            // Action strip along the bottom inside the box: attach and model
            // selector on the left, Cancel/Send on the right.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InlineAttachButton(
                    enabled = !state.running && !state.testing && state.pendingImages.size < MAX_PENDING_IMAGES,
                    onAttachImages = onAttachImages,
                )
                CompactModelSelector(
                    selectedModel = state.selectedModel,
                    availableModels = state.availableModels,
                    loading = state.fetchingModels,
                    onModelChange = onModelChange,
                    onFetchModels = onFetchModels,
                    enabled = !state.running && !state.testing,
                )
                Spacer(Modifier.weight(1f))
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

/** Initial @ menu highlight: open on the first row so a bare Enter works. */
internal const val REF_MENU_UNSET = -1

internal fun initialRefMenuIndex(): Int = 0

/**
 * Keeps the @ menu selection inside 0 until size-1; [REF_MENU_UNSET] and any
 * stale out-of-range index land on the first item, an empty list resets to
 * [REF_MENU_UNSET].
 */
internal fun clampRefMenuIndex(index: Int, size: Int): Int =
    if (size <= 0) REF_MENU_UNSET else index.coerceIn(0, size - 1)