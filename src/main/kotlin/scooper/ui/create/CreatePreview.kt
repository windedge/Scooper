package scooper.ui.create

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import scooper.ui.components.SelectableContainer
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.viewmodels.DiffLineType
import scooper.viewmodels.ManifestRevision
import scooper.viewmodels.ReviewActionChip
import scooper.viewmodels.SaveResult
import scooper.viewmodels.computeDetailedLineDiff
import scooper.viewmodels.deploymentAction
import scooper.viewmodels.deployButtonEnabled
import scooper.viewmodels.DeploymentAction
import scooper.viewmodels.deploymentLabel
import scooper.viewmodels.DeploymentState
import scooper.viewmodels.manifestHasShortcuts
import scooper.viewmodels.openAppActionVisible
import scooper.viewmodels.saveToBucketPrimary
import scooper.viewmodels.isNameConflictOnly
import scooper.viewmodels.saveUpdatedHintText
import scooper.ui.icons.*
import scooper.ui.theme.*


@Composable
internal fun ManifestPreviewCard(
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
    /** Manifest vs installed copy; drives the deploy button label and action. */
    deployment: DeploymentState = DeploymentState.NoManifest,
    /** Bucket a save used, so [deploymentAction] can tell same-bucket from foreign. */
    saveBucket: String? = null,
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
                                    chipHovered -> if (chip.isPrimary) colors.primaryHover else colors.warningHover
                                    else -> chipColor
                                }
                                val chipContentColor = if (chip.isPrimary) Color.White else colors.onWarning
                                // Padding on the Text keeps the Surface background and the
                                // clickable area the same size. Always clickable: while
                                // running/testing the VM guard toasts instead of silently
                                // dropping the click.
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = chipFill,
                                    modifier = Modifier
                                        .cursorHand()
                                        .clip(RoundedCornerShape(6.dp))
                                        .onHover { chipHovered = it }
                                        .clickable {
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
                // Deploy button is driven by the derived deployment state: the
                // label decides what it says, the action decides what it does
                // (OpenOnly keeps just the Open button below).
                val deployAction = deploymentAction(deployment, saveBucket)
                val deployLabel = deploymentLabel(deployment)
                if (deployLabel.isNotEmpty() && deployAction != DeploymentAction.OpenOnly) {
                    SmallActionButton(
                        label = deployLabel,
                        onClick = onInstallSaved,
                        // The plan alone gates the button (see [deployButtonEnabled]):
                        // an install in this session must not lock out a later
                        // version or bucket change, nor block a retry after a
                        // failed reinstall.
                        enabled = deployButtonEnabled(
                            manifestJson = manifestJson,
                            action = deployAction,
                            running = running,
                            testing = testing,
                            installing = installing,
                        ),
                        primary = manifestSaved && !installedForUse,
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
internal fun SaveResultPanel(
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
internal fun SmallActionButton(
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