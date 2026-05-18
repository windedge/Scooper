package scooper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import scooper.ui.components.rememberPainterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.koin.compose.koinInject
import scooper.service.GitHubRelease
import scooper.service.GitHubService
import scooper.service.ScoopSearchApp
import scooper.service.ScoopClient
import scooper.ui.components.DetailMetadataRow
import scooper.ui.components.ReleaseNoteCard
import scooper.ui.components.SelectableContainer
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.util.safeBrowse
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private enum class SearchDetailTab(val label: String) {
    Changelog("Changelog"),
    Manifest("Manifest"),
}

@Composable
fun ScoopSearchDetailPanel(
    app: ScoopSearchApp,
    onClose: () -> Unit,
    onInstall: (ScoopSearchApp, String) -> Unit,
    isInstalling: Boolean = false,
    isInstalled: Boolean = false,
    isBucketInstalled: Boolean = false,
    modifier: Modifier = Modifier,
    scoopClient: ScoopClient = koinInject(),
    gitHubService: GitHubService = koinInject(),
) {
    val colors = MaterialTheme.colors
    val scope = rememberCoroutineScope()
    val appKey = remember(app) { "${app.Name}_${app.Metadata.Repository}" }
    val defaultBucketName = remember(appKey) { app.Metadata.Repository.substringAfterLast("/") }
    var showInstallDialog by remember { mutableStateOf(false) }

    var releases by remember { mutableStateOf<List<GitHubRelease>?>(null) }
    var releasesLoading by remember { mutableStateOf(false) }
    var releasesError by remember { mutableStateOf<String?>(null) }

    var manifestContent by remember { mutableStateOf<String?>(null) }
    var manifestError by remember { mutableStateOf<String?>(null) }

    var selectedTab by remember { mutableStateOf(SearchDetailTab.Changelog) }
    val listState = rememberLazyListState()

    val githubSourceUrl = remember(app.Homepage, manifestContent) {
        when {
            GitHubService.isGitHubUrl(app.Homepage) -> app.Homepage
            else -> extractGitHubSourceFromManifest(manifestContent)
        }
    }
    val hasChangelog = githubSourceUrl != null

    LaunchedEffect(appKey) {
        selectedTab = if (hasChangelog) SearchDetailTab.Changelog else SearchDetailTab.Manifest
        listState.scrollToItem(0)
    }

    LaunchedEffect(appKey, githubSourceUrl) {
        if (!hasChangelog) {
            releases = emptyList()
            releasesLoading = false
            releasesError = null
            return@LaunchedEffect
        }
        releasesLoading = true
        releasesError = null
        scope.launch(Dispatchers.IO) {
            try {
                releases = gitHubService.fetchReleases(githubSourceUrl, limit = 5)
            } catch (e: Exception) {
                releasesError = e.message
            } finally {
                releasesLoading = false
            }
        }
    }

    LaunchedEffect(appKey) {
        manifestError = null
        manifestContent = null
        scope.launch(Dispatchers.IO) {
            try {
                val content = gitHubService.fetchRawFile(app.Metadata.manifestUrl)
                if (content == null) {
                    manifestError = "Manifest not available."
                } else {
                    manifestContent = content
                }
            } catch (e: Exception) {
                manifestError = e.message ?: "Manifest not available."
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxHeight().width(420.dp),
        border = BorderStroke(1.dp, colors.borderDefault),
        color = colors.surface,
        elevation = 0.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            SearchDetailHeader(
                app = app,
                onClose = onClose,
            )
            if (!isInstalled) {
                SearchInstallAction(
                    app = app,
                    isInstalling = isInstalling,
                    onInstallClick = {
                        if (isBucketInstalled) {
                            onInstall(app, defaultBucketName)
                        } else {
                            showInstallDialog = true
                        }
                    },
                )
            }

            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 0.dp),
                ) {
                    item { SearchMetadataSection(app) }

                    item {
                        Spacer(Modifier.height(8.dp))
                        SearchDetailContentSection(
                            app = app,
                            hasChangelog = hasChangelog,
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                            releases = releases,
                            releasesLoading = releasesLoading,
                            releasesError = releasesError,
                            releasesPageUrl = gitHubService.buildReleasesPageUrl(githubSourceUrl),
                            manifestContent = manifestContent,
                            manifestError = manifestError,
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

    // Install confirm dialog
    if (showInstallDialog && !isBucketInstalled) {
        InstallConfirmDialog(
            app = app,
            scoopClient = scoopClient,
            onConfirm = { bucketName ->
                showInstallDialog = false
                onInstall(app, bucketName)
            },
            onCancel = { showInstallDialog = false },
        )
    }
}

@Composable
private fun SearchDetailHeader(
    app: ScoopSearchApp,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.Name,
                style = typography.h6.copy(fontWeight = FontWeight.Bold, color = colors.textTitle),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val repoLabel = app.Metadata.Repository.substringAfterLast("/")
            if (repoLabel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    repoLabel,
                    style = typography.caption.copy(color = colors.textMuted),
                )
            }
        }

        // Close button
        var closeHover by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier.size(28.dp)
                .cursorHand()
                .onHover { closeHover = it }
                .clip(RoundedCornerShape(6.dp))
                .background(if (closeHover) colors.backgroundHover else Color.Transparent)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.TwoTone.Close, "Close", modifier = Modifier.size(16.dp), tint = colors.textMuted)
        }
    }
}

@Composable
private fun SearchInstallAction(
    app: ScoopSearchApp,
    isInstalling: Boolean,
    onInstallClick: () -> Unit,
) {
    val colors = MaterialTheme.colors
    val defaultBucketName = app.Metadata.Repository.substringAfterLast("/")
    val tooltipText = "scoop install $defaultBucketName/${app.Name}"

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tooltip(tooltipText, position = TooltipPosition.Top) {
            Button(
                onClick = onInstallClick,
                enabled = !isInstalling,
                modifier = Modifier.height(28.dp).cursorHand(),
                shape = RoundedCornerShape(6.dp),
                elevation = ButtonDefaults.elevation(defaultElevation = 1.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = colors.primary,
                    disabledBackgroundColor = colors.primary.copy(alpha = 0.7f),
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Installing...",
                        style = typography.caption.copy(color = Color.White, fontWeight = FontWeight.Medium),
                    )
                } else {
                    Icon(
                        rememberPainterResource("package-check.svg"),
                        "Install",
                        modifier = Modifier.size(12.dp),
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Install",
                        style = typography.caption.copy(color = Color.White, fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallConfirmDialog(
    app: ScoopSearchApp,
    scoopClient: ScoopClient,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colors
    val defaultBucketName = app.Metadata.Repository.substringAfterLast("/")
    var bucketName by remember { mutableStateOf(defaultBucketName) }
    var bucketNameError by remember { mutableStateOf(false) }

    val localBuckets = remember { scoopClient.bucketNames }
    val bucketExists = localBuckets.any { it.equals(bucketName, ignoreCase = true) }

    scooper.ui.components.ConfirmDialog(
        title = "Install ${app.Name}",
        confirmText = "Install",
        cancelText = "Cancel",
        onConfirm = {
            val trimmed = bucketName.trim()
            if (trimmed.isBlank()) {
                bucketNameError = true
                return@ConfirmDialog
            }
            onConfirm(trimmed)
        },
        onCancel = onCancel,
        state = DialogState(size = DpSize(760.dp, 520.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "This will install the app and its bucket if not already added.",
                style = typography.body2.copy(color = colors.textBody),
            )

            Spacer(Modifier.height(18.dp))

            // Bucket Name field
            Text(
                "Bucket Name",
                style = typography.caption.copy(
                    color = colors.textTitle,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(8.dp))
            scooper.ui.components.DialogTextField(
                value = bucketName,
                onValueChange = {
                    bucketName = it
                    bucketNameError = false
                },
                placeholder = "e.g. extras",
                isError = bucketNameError,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (bucketNameError) "Bucket name is required" else "Bucket name used locally by Scoop.",
                color = if (bucketNameError) colors.error else colors.textBody,
                style = typography.caption,
            )

            Spacer(Modifier.height(16.dp))

            // Command preview
            Text(
                "Commands",
                style = typography.caption.copy(
                    color = colors.textTitle,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(8.dp))
            val bucketCommand = "scoop bucket add $bucketName ${app.Metadata.Repository}"
            val installCommand = "scoop install $bucketName/${app.Name}"

            SelectableContainer {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!bucketExists) {
                        CommandLineRow(text = bucketCommand)
                    }
                    CommandLineRow(text = installCommand)
                }
            }
        }
    }
}

@Composable
private fun CommandLineRow(
    text: String,
    muted: Boolean = false,
    hint: String? = null,
) {
    val colors = MaterialTheme.colors
    val commandColor = if (muted) colors.textMuted else colors.onSurface

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.inputBackground)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ">",
                style = typography.body1.copy(
                    color = commandColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = typography.body1.copy(
                    color = commandColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Tooltip("Copy", position = TooltipPosition.Top) {
                var copyHover by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier.size(24.dp)
                        .cursorHand()
                        .onHover { copyHover = it }
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (copyHover) colors.backgroundHover else Color.Transparent)
                        .clickable {
                            val selection = StringSelection(text)
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        rememberPainterResource("copy.svg"),
                        "Copy",
                        modifier = Modifier.size(12.dp),
                        tint = colors.textMuted,
                    )
                }
            }
        }

        if (hint != null) {
            Text(
                hint,
                style = typography.caption.copy(color = colors.textMuted),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun SearchMetadataSection(app: ScoopSearchApp) {
    val colors = MaterialTheme.colors
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        if (app.Description.isNotBlank()) {
            Text(
                app.Description,
                style = typography.body2.copy(color = colors.textBody),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        if (app.Version.isNotBlank()) {
            DetailMetadataRow("Version") {
                Text(
                    app.Version,
                    style = typography.body2.copy(fontWeight = FontWeight.Medium, color = colors.textBody),
                )
            }
        }

        if (app.Homepage.isNotBlank()) {
            DetailMetadataRow("Homepage") {
                Text(
                    app.Homepage,
                    style = typography.body2.copy(color = colors.primary),
                    modifier = Modifier.cursorHand().clickable { safeBrowse(app.Homepage) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (app.License.isNotBlank()) {
            DetailMetadataRow("License") {
                Text(
                    app.License,
                    style = typography.body2.copy(color = colors.textBody),
                )
            }
        }

        if (app.Metadata.Committed.isNotBlank()) {
            DetailMetadataRow("Updated") {
                Text(
                    formatCommittedDate(app.Metadata.Committed),
                    style = typography.body2.copy(color = colors.textBody),
                )
            }
        }
    }
}

@Composable
private fun SearchDetailContentSection(
    app: ScoopSearchApp,
    hasChangelog: Boolean,
    selectedTab: SearchDetailTab,
    onTabSelected: (SearchDetailTab) -> Unit,
    releases: List<GitHubRelease>?,
    releasesLoading: Boolean,
    releasesError: String?,
    releasesPageUrl: String?,
    manifestContent: String?,
    manifestError: String?,
) {
    val colors = MaterialTheme.colors
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Divider(color = colors.divider)
        Spacer(Modifier.height(12.dp))

        if (hasChangelog) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchDetailTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    var hover by remember { mutableStateOf(false) }
                    val textColor = when {
                        isSelected -> colors.primary
                        hover -> colors.textTitle
                        else -> colors.textMuted
                    }
                    Box(
                        modifier = Modifier
                            .cursorHand()
                            .clickable { onTabSelected(tab) }
                            .onHover { hover = it }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tab.label,
                            style = typography.body2.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                            ),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))

                if (selectedTab == SearchDetailTab.Changelog && releasesPageUrl != null) {
                    Tooltip(releasesPageUrl, position = TooltipPosition.Top) {
                        Icon(
                            rememberPainterResource("github-fill.svg"),
                            "View releases on GitHub",
                            modifier = Modifier.size(14.dp).cursorHand().clickable { safeBrowse(releasesPageUrl) },
                            tint = colors.textMuted,
                        )
                    }
                }
                if (selectedTab == SearchDetailTab.Manifest) {
                    ManifestActionIcons(app = app, manifestContent = manifestContent)
                }
            }
            Divider(color = colors.divider)
            Spacer(Modifier.height(8.dp))

            when (selectedTab) {
                SearchDetailTab.Changelog -> SearchChangelogContent(
                    releases = releases,
                    loading = releasesLoading,
                    error = releasesError,
                    onSwitchToManifest = { onTabSelected(SearchDetailTab.Manifest) },
                )
                SearchDetailTab.Manifest -> key("manifest_${app.Name}_${app.Metadata.Repository}") {
                    SearchManifestContent(manifestContent = manifestContent, manifestError = manifestError)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Manifest",
                    style = typography.body2.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                ManifestActionIcons(app = app, manifestContent = manifestContent)
            }
            Divider(color = colors.divider)
            Spacer(Modifier.height(8.dp))
            key("manifest_${app.Name}_${app.Metadata.Repository}") {
                SearchManifestContent(manifestContent = manifestContent, manifestError = manifestError)
            }
        }
    }
}

@Composable
private fun ManifestActionIcons(app: ScoopSearchApp, manifestContent: String?) {
    val colors = MaterialTheme.colors
    if (manifestContent != null) {
        Tooltip("Copy to clipboard", position = TooltipPosition.Top) {
            Icon(
                rememberPainterResource("copy.svg"),
                "Copy",
                modifier = Modifier.size(14.dp).cursorHand().clickable {
                    val selection = StringSelection(manifestContent)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                },
                tint = colors.textMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
    }

    if (app.Metadata.manifestUrl.isNotBlank()) {
        Tooltip("Open manifest page", position = TooltipPosition.Top) {
            Icon(
                rememberPainterResource("external_link_icon.xml"),
                "Open",
                modifier = Modifier.size(14.dp).cursorHand().clickable { safeBrowse(app.Metadata.manifestUrl) },
                tint = colors.textMuted,
            )
        }
    }
}

@Composable
private fun SearchChangelogContent(
    releases: List<GitHubRelease>?,
    loading: Boolean,
    error: String?,
    onSwitchToManifest: () -> Unit,
) {
    val colors = MaterialTheme.colors
    when {
        loading -> {
            Box(
                modifier = Modifier.fillMaxWidth().height(72.dp)
                    .border(1.dp, colors.borderDefault, RoundedCornerShape(8.dp))
                    .background(colors.inputBackground, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        error != null -> {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, colors.borderDefault, RoundedCornerShape(8.dp))
                    .background(colors.inputBackground, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    error,
                    style = typography.body2.copy(color = colors.textBody),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "View manifest instead",
                    style = typography.caption.copy(color = colors.primary),
                    modifier = Modifier.cursorHand().clickable { onSwitchToManifest() },
                )
            }
        }
        releases.isNullOrEmpty() -> {
            Box(
                modifier = Modifier.fillMaxWidth().height(72.dp)
                    .border(1.dp, colors.borderDefault, RoundedCornerShape(8.dp))
                    .background(colors.inputBackground, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No releases found.",
                    style = typography.body2.copy(color = colors.textMuted),
                )
            }
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

@Composable
private fun SearchManifestContent(manifestContent: String?, manifestError: String?) {
    val colors = MaterialTheme.colors
    when {
        manifestContent != null -> {
            val horizontalState = rememberScrollState()
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.borderDefault, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(colors.inputBackground, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .horizontalScroll(horizontalState)
                        .padding(12.dp),
                ) {
                    SelectableContainer {
                        Text(
                            manifestContent,
                            style = typography.body2.copy(
                                color = colors.onSurface,
                                fontFamily = FontFamily.Monospace,
                            ),
                            softWrap = false,
                        )
                    }
                }
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(horizontalState),
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, colors.borderDefault, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .background(colors.inputBackground),
                )
            }
        }
        manifestError != null -> {
            Text(
                manifestError,
                style = typography.caption.copy(color = colors.textBody),
            )
        }
        else -> {
            Text(
                "Loading manifest...",
                style = typography.body2.copy(color = colors.textMuted),
            )
        }
    }
}

private fun extractGitHubSourceFromManifest(manifestContent: String?): String? {
    if (manifestContent.isNullOrBlank()) return null

    return try {
        val json = Json.parseToJsonElement(manifestContent).jsonObject

        val checkverGithub = (json["checkver"] as? JsonObject)
            ?.get("github")
            ?.jsonPrimitive
            ?.contentOrNull
        if (GitHubService.isGitHubUrl(checkverGithub)) return checkverGithub

        val topLevelUrl = json["url"]?.let { element ->
            when (element) {
                is JsonArray -> element.firstOrNull()?.jsonPrimitive?.contentOrNull
                else -> element.jsonPrimitive.contentOrNull
            }
        }
        if (GitHubService.isGitHubUrl(topLevelUrl)) return topLevelUrl

        val archObj = json["architecture"] as? JsonObject
        if (archObj != null) {
            val urls = archObj.values.mapNotNull { archElement ->
                val archJson = archElement as? JsonObject ?: return@mapNotNull null
                val urlElement = archJson["url"] ?: return@mapNotNull null
                when (urlElement) {
                    is JsonArray -> urlElement.firstOrNull()?.jsonPrimitive?.contentOrNull
                    else -> urlElement.jsonPrimitive.contentOrNull
                }
            }
            val githubUrl = urls.firstOrNull { GitHubService.isGitHubUrl(it) }
            if (githubUrl != null) return githubUrl
        }

        null
    } catch (_: Exception) {
        null
    }
}

private fun formatCommittedDate(isoDate: String): String {
    return try {
        val instant = Instant.parse(isoDate)
        val date = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        val now = LocalDateTime.now()
        val days = java.time.Duration.between(date, now).toDays()
        when {
            days < 1 -> "Today"
            days == 1L -> "1 day ago"
            days < 30 -> "$days days ago"
            days < 365 -> "${days / 30} month${if (days / 30 > 1) "s" else ""} ago"
            else -> "${days / 365} year${if (days / 365 > 1) "s" else ""} ago"
        }
    } catch (_: Exception) {
        ""
    }
}
