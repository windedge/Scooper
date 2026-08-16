package scooper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import scooper.ui.icons.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import scooper.data.App
import scooper.data.AppStatus
import scooper.service.GitHubRelease
import scooper.service.GitHubService
import scooper.service.ScoopClient
import scooper.ui.components.DetailMetadataRow
import scooper.ui.components.Link
import scooper.ui.components.ReleaseNoteCard
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.ui.components.SelectableContainer
import scooper.ui.theme.*
import scooper.util.tr
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.util.safeBrowse
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


private enum class ContentTab {
    Changelog,
    Manifest,
}

private fun ContentTab.displayLabel(): String = when (this) {
    ContentTab.Changelog -> tr("Changelog")
    ContentTab.Manifest -> tr("Manifest")
}

@Composable
fun AppDetailPanel(
    app: App,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    gitHubService: GitHubService = koinInject(),
    scoopClient: ScoopClient = koinInject(),
) {
    val colors = MaterialTheme.colors
    val scope = rememberCoroutineScope()
    var releases by remember { mutableStateOf<List<GitHubRelease>?>(null) }
    var releasesLoading by remember { mutableStateOf(false) }
    var releasesError by remember { mutableStateOf<String?>(null) }
    var manifestContent by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(ContentTab.Changelog) }

    val gitHubSourceUrl = when {
        GitHubService.isGitHubUrl(app.homepage) -> app.homepage
        GitHubService.isGitHubUrl(app.url) -> app.url
        else -> null
    }
    val isGitHub = gitHubSourceUrl != null

    LaunchedEffect(app.uniqueName) {
        if (!isGitHub) return@LaunchedEffect
        releasesLoading = true
        releasesError = null
        scope.launch(Dispatchers.IO) {
            try {
                val result = gitHubService.fetchReleases(gitHubSourceUrl, limit = 5)
                releases = result
            } catch (e: Exception) {
                releasesError = e.message
            } finally {
                releasesLoading = false
            }
        }
    }

    LaunchedEffect(app.uniqueName) {
        selectedTab = ContentTab.Changelog
    }

    LaunchedEffect(app.uniqueName) {
        scope.launch(Dispatchers.IO) {
            manifestContent = scoopClient.getManifestContent(app)
        }
    }

    Surface(
        modifier = modifier.fillMaxHeight().width(420.dp),
        border = BorderStroke(1.dp, colors.borderDefault),
        color = colors.surface,
        elevation = 4.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            DetailHeader(app, onClose)

            // Scrollable content
            val listState = rememberLazyListState()
            LaunchedEffect(app.uniqueName) { listState.scrollToItem(0) }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    // Metadata (always visible)
                    item { MetadataSection(app) }

                    // Content tabs: Changelog / Manifest
                    item {
                        Spacer(Modifier.height(8.dp))
                        ContentTabSection(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                            app = app,
                            isGitHub = isGitHub,
                            releases = releases,
                            releasesLoading = releasesLoading,
                            releasesError = releasesError,
                            releasesPageUrl = gitHubService.buildReleasesPageUrl(gitHubSourceUrl),
                            manifestContent = manifestContent,
                            scoopClient = scoopClient,
                        )
                    }
                }
                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().background(colors.surface),
                    adapter = rememberScrollbarAdapter(listState),
                )
            }
        }
    }
}

// ==================== Header ====================

@Composable
private fun DetailHeader(app: App, onClose: () -> Unit) {
    val colors = MaterialTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.name,
                style = typography.h6.copy(
                    fontWeight = FontWeight.Bold,
                    color = colors.textTitle,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!app.bucket?.name.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    app.bucket!!.name,
                    style = typography.caption.copy(color = colors.textMuted),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        var closeHover by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier.size(28.dp)
                .cursorHand()
                .onHover { closeHover = it }
                .clip(RoundedCornerShape(6.dp))
                .background(if (closeHover) colors.backgroundHover else colors.Transparent)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, tr("Close"), modifier = Modifier.size(16.dp), tint = colors.textMuted)
        }
    }
}

// ==================== Content Tab Section ====================

@Composable
private fun ContentTabSection(
    selectedTab: ContentTab,
    onTabSelected: (ContentTab) -> Unit,
    app: App,
    isGitHub: Boolean,
    releases: List<GitHubRelease>?,
    releasesLoading: Boolean,
    releasesError: String?,
    releasesPageUrl: String?,
    manifestContent: String?,
    scoopClient: ScoopClient,
) {
    val colors = MaterialTheme.colors
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Divider(color = colors.divider)
        Spacer(Modifier.height(12.dp))

        // Tab header row (hide tabs when Changelog is not available)
        val showTabs = isGitHub
        if (showTabs) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContentTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    var isHovered by remember { mutableStateOf(false) }
                    val textColor = when {
                        isSelected -> colors.primary
                        isHovered -> colors.textTitle
                        else -> colors.textMuted
                    }
                    Box(
                        modifier = Modifier
                            .cursorHand()
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .clickable { onTabSelected(tab) }
                            .onHover { isHovered = it }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tab.displayLabel(),
                            style = typography.body2.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                            ),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Context actions on the right
                if (selectedTab == ContentTab.Changelog && releasesPageUrl != null) {
                    Tooltip(releasesPageUrl, position = TooltipPosition.Top) {
                        Icon(
                            Lucide.Github,
                            tr("View releases on GitHub"),
                            modifier = Modifier.size(14.dp).cursorHand().clickable { safeBrowse(releasesPageUrl) },
                            tint = colors.textMuted,
                        )
                    }
                }
                if (selectedTab == ContentTab.Manifest && manifestContent != null) {
                    Tooltip(tr("Copy to clipboard"), position = TooltipPosition.Top) {
                        Icon(
                            Lucide.Copy,
                            tr("Copy"),
                            modifier = Modifier.size(14.dp).cursorHand().clickable {
                                val selection = StringSelection(manifestContent)
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                            },
                            tint = colors.textMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Tooltip(tr("Open in editor"), position = TooltipPosition.Top) {
                        Icon(
                            Lucide.ExternalLink,
                            tr("Open"),
                            modifier = Modifier.size(14.dp).cursorHand().clickable {
                                val file = scoopClient.getManifestFile(app)
                                if (file != null && file.exists()) {
                                    Desktop.getDesktop().open(file)
                                }
                            },
                            tint = colors.textMuted,
                        )
                    }
                }
            }

            Divider(color = colors.divider)

            Spacer(Modifier.height(8.dp))

            // Tab content
            when (selectedTab) {
                ContentTab.Changelog -> ChangelogContent(
                    isGitHub = isGitHub,
                    releases = releases,
                    loading = releasesLoading,
                    error = releasesError,
                )
                ContentTab.Manifest -> ManifestContent(
                    content = manifestContent,
                )
            }
        } else {
            // No Changelog available, show Manifest section directly with header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tr("Manifest"),
                        style = typography.body2.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primary,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (manifestContent != null) {
                    Tooltip(tr("Copy to clipboard"), position = TooltipPosition.Top) {
                        Icon(
                            Lucide.Copy,
                            tr("Copy"),
                            modifier = Modifier.size(14.dp).cursorHand().clickable {
                                val selection = StringSelection(manifestContent)
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                            },
                            tint = colors.textMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Tooltip(tr("Open in editor"), position = TooltipPosition.Top) {
                        Icon(
                            Lucide.ExternalLink,
                            tr("Open"),
                            modifier = Modifier.size(14.dp).cursorHand().clickable {
                                val file = scoopClient.getManifestFile(app)
                                if (file != null && file.exists()) {
                                    Desktop.getDesktop().open(file)
                                }
                            },
                            tint = colors.textMuted,
                        )
                    }
                }
            }

            Divider(color = colors.divider)

            Spacer(Modifier.height(8.dp))

            ManifestContent(content = manifestContent)
        }
    }
}

// ==================== Changelog Content ====================

@Composable
private fun ChangelogContent(
    isGitHub: Boolean,
    releases: List<GitHubRelease>?,
    loading: Boolean,
    error: String?,
) {
    val colors = MaterialTheme.colors
    when {
        !isGitHub -> {
            Text(
                tr("Not a GitHub repository."),
                style = typography.body2.copy(color = colors.textMuted),
            )
        }
        loading -> {
            Box(
                modifier = Modifier.fillMaxWidth().height(60.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        error != null -> {
            Text(
                error,
                style = typography.caption.copy(color = colors.textBody),
            )
        }
        releases.isNullOrEmpty() -> {
            Text(
                tr("No releases found."),
                style = typography.body2.copy(color = colors.textMuted),
            )
        }
        else -> {
            releases.forEachIndexed { index, release ->
                ReleaseNoteCard(release)
                if (index < releases.size - 1) {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ==================== Manifest Content ====================

@Composable
private fun ManifestContent(
    content: String?,
) {
    val colors = MaterialTheme.colors
    if (content == null) {
        Text(
            tr("Manifest not found."),
            style = typography.body2.copy(color = colors.textMuted),
        )
        return
    }

    // Code block card with horizontal scrollbar
    val horizontalScrollState = rememberScrollState()
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.borderDefault, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(colors.inputBackground, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .horizontalScroll(horizontalScrollState)
                .padding(12.dp),
        ) {
            SelectableContainer {
                Text(
                    content,
                    style = typography.body2.copy(
                        color = colors.onSurface,
                        fontFamily = FontFamily.Monospace,
                    ),
                    softWrap = false,
                )
            }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(horizontalScrollState),
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, colors.borderDefault, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .background(colors.inputBackground),
        )
    }
}

// ==================== Metadata Section ====================

@Composable
private fun MetadataSection(app: App) {
    val colors = MaterialTheme.colors
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        // Description
        if (!app.description.isNullOrBlank()) {
            Text(
                app.description!!,
                style = typography.body2.copy(color = colors.textBody),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        // Version info
        DetailMetadataRow(tr("Version")) {
            if (app.updatable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.version ?: "", style = OldVersionStyle)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Lucide.ChevronRight, "",
                        modifier = Modifier.size(12.dp),
                        tint = colors.textMuted,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(app.latestVersion, style = NewVersionStyle)
                }
            } else {
                Text(
                    app.version ?: app.latestVersion,
                    style = typography.body2.copy(
                        fontWeight = FontWeight.Medium,
                        color = if (app.installed) Slate700 else colors.textBody,
                    ),
                )
            }
        }

        // Status
        DetailMetadataRow(tr("Status")) {
            val statusText = when {
                app.status == AppStatus.INSTALLED -> tr("Installed")
                app.status == AppStatus.FAILED -> tr("Failed")
                app.updatable -> tr("Update Available")
                else -> tr("Not Installed")
            }
            val statusColor = when {
                app.status == AppStatus.INSTALLED && !app.updatable -> colors.updateDefault
                app.updatable -> colors.warningDefault
                app.status == AppStatus.FAILED -> colors.dangerDefault
                else -> colors.textBody
            }
            Text(
                statusText,
                style = typography.body2.copy(
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                ),
            )
        }

        // Homepage
        if (!app.homepage.isNullOrBlank()) {
            DetailMetadataRow(tr("Homepage")) {
                Link(
                    text = app.homepage!!,
                    onClicked = { safeBrowse(app.homepage) },
                )
            }
        }

        // License
        if (!app.license.isNullOrBlank()) {
            DetailMetadataRow(tr("License")) {
                Text(
                    app.license!!,
                    style = typography.body2.copy(color = colors.textBody),
                )
            }
        }

        // Dates
        app.createAt?.let {
            DetailMetadataRow(tr("Added")) {
                Text(
                    it.format(DateFormatter),
                    style = typography.body2.copy(color = colors.textBody),
                )
            }
        }
        app.updateAt?.let {
            DetailMetadataRow(tr("Updated")) {
                Text(
                    it.format(DateFormatter),
                    style = typography.body2.copy(color = colors.textBody),
                )
            }
        }
    }
}

// ==================== Helpers ====================

private val Colors.Transparent get() = androidx.compose.ui.graphics.Color.Transparent
