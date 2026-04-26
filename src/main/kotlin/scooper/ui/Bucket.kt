package scooper.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import scooper.ui.components.ConfirmDialog
import scooper.ui.components.DialogTextField
import scooper.ui.components.SectionCard
import scooper.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogState
import org.koin.compose.koinInject
import scooper.data.Bucket
import scooper.util.KNOWN_BUCKETS
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.onHover
import scooper.util.safeBrowse
import scooper.viewmodels.AppsViewModel

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BucketsScreen(appsViewModel: AppsViewModel = koinInject()) {
    var bucketToDelete by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var bucketName by remember { mutableStateOf("") }
    var bucketUrl by remember { mutableStateOf("") }
    var bucketNameError by remember { mutableStateOf(false) }
    var bucketUrlError by remember { mutableStateOf<String?>(null) }
    val inputFocusRequester = remember { FocusRequester() }

    Surface(elevation = 0.dp, color = colors.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            val scrollState = rememberScrollState(0)
            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 40.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(Modifier.widthIn(max = 800.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Configured Buckets",
                                style = MaterialTheme.typography.h5.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textTitle
                                )
                            )
                            Text(
                                "Manage repositories where Scoop looks for packages.",
                                style = MaterialTheme.typography.body2.copy(color = colors.textBody)
                            )
                        }
                        Button(
                            onClick = {
                                bucketName = ""
                                bucketUrl = ""
                                bucketNameError = false
                                bucketUrlError = null
                                showAddDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = colors.primary),
                            shape = RoundedCornerShape(8.dp),
                            elevation = ButtonDefaults.elevation(defaultElevation = 1.dp),
                            modifier = Modifier.cursorHand()
                        ) {
                            Icon(Icons.TwoTone.Add, "", modifier = Modifier.size(18.dp), tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("Add Bucket", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }

                    // Configured Buckets List
                    val state by appsViewModel.container.stateFlow.collectAsState()
                    val buckets = state.buckets
                    val bucketNames = buckets.map { it.name }

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.borderDefault),
                        elevation = 0.dp
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            buckets.forEachIndexed { index, bucket ->
                                BucketRow(bucket, onDelete = {
                                    bucketToDelete = bucket.name
                                    showDeleteDialog = true
                                })
                                if (index < buckets.size - 1) {
                                    Divider(color = colors.divider)
                                }
                            }
                        }
                    }

                    // Known Buckets Section
                    Text(
                        "KNOWN BUCKETS",
                        style = MaterialTheme.typography.overline.copy(
                            fontWeight = FontWeight.Bold,
                            color = colors.textBody,
                            letterSpacing = 1.sp
                        ),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    KnownBucketsGrid(bucketNames, onAdd = { appsViewModel.scheduleAddBucket(it) })

                    Spacer(Modifier.height(40.dp))
                }
            }

            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(scrollState = scrollState)
            )
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            text = "Are you sure you want to delete '$bucketToDelete'?",
            title = "Delete Bucket",
            confirmText = "Delete",
            onConfirm = {
                showDeleteDialog = false
                appsViewModel.scheduleRemoveBucket(bucketToDelete)
            },
            onCancel = { showDeleteDialog = false }
        )
    }

    if (showAddDialog) {
        ConfirmDialog(
            title = "Add Bucket",
            onConfirm = {
                val trimmedBucketName = bucketName.trim()
                val trimmedBucketUrl = bucketUrl.trim()
                bucketNameError = trimmedBucketName.isBlank()
                bucketUrlError = when {
                    trimmedBucketUrl.isBlank() -> "Repository URL is required"
                    !isValidBucketUrl(trimmedBucketUrl) -> "URL must start with http:// or https://"
                    else -> null
                }
                if (bucketNameError || bucketUrlError != null) {
                    return@ConfirmDialog
                }
                showAddDialog = false
                appsViewModel.scheduleAddBucket(trimmedBucketName, trimmedBucketUrl)
            },
            onCancel = {
                bucketNameError = false
                bucketUrlError = null
                showAddDialog = false
            },
            confirmText = "Add",
            state = DialogState(size = DpSize(520.dp, 400.dp))
        ) {
            Column(Modifier.fillMaxSize()) {
                SideEffect {
                    inputFocusRequester.requestFocus()
                }

                Text(
                    "Add a custom Scoop bucket by name and repository URL.",
                    style = MaterialTheme.typography.caption.copy(color = colors.textBody)
                )

                Spacer(Modifier.height(18.dp))

                Text(
                    "Bucket Name",
                    style = MaterialTheme.typography.caption.copy(color = colors.textTitle),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                DialogTextField(
                    value = bucketName,
                    onValueChange = {
                        bucketName = it
                        bucketNameError = false
                    },
                    placeholder = "e.g. extras",
                    isError = bucketNameError,
                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (bucketNameError) "Name is required" else "Used as the local bucket alias in Scoop.",
                    color = if (bucketNameError) colors.error else colors.textBody,
                    style = MaterialTheme.typography.caption
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "Repository URL",
                    style = MaterialTheme.typography.caption.copy(color = colors.textTitle),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                DialogTextField(
                    value = bucketUrl,
                    onValueChange = {
                        bucketUrl = it
                        bucketUrlError = null
                    },
                    placeholder = "https://github.com/...",
                    isError = bucketUrlError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (bucketUrlError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        bucketUrlError!!,
                        color = colors.error,
                        style = MaterialTheme.typography.caption
                    )
                }
            }
        }
    }
}

@Composable
fun BucketRow(bucket: Bucket, onDelete: () -> Unit) {
    var isHover by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onHover { isHover = it }
            .background(if (isHover) colors.backgroundHover else colors.surface)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                bucket.name,
                style = MaterialTheme.typography.body1.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textTitle
                )
            )
            Text(
                bucket.url ?: "",
                style = MaterialTheme.typography.caption.copy(color = colors.textMuted),
                modifier = Modifier.cursorLink().clickable { safeBrowse(bucket.url) }
            )
        }

        if (isHover) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.dangerBg)
                    .cursorHand()
            ) {
                Icon(
                    Icons.TwoTone.Delete,
                    "",
                    modifier = Modifier.size(18.dp),
                    tint = colors.dangerDefault
                )
            }
        } else {
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
fun KnownBucketsGrid(bucketNames: List<String>, onAdd: (String) -> Unit) {
    val knownBuckets = KNOWN_BUCKETS - bucketNames.toSet()

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth > 700.dp -> 3
            maxWidth > 450.dp -> 2
            else -> 1
        }

        // Custom Grid
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val rows = (knownBuckets.size + columns - 1) / columns
            for (r in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (c in 0 until columns) {
                        val idx = r * columns + c
                        if (idx < knownBuckets.size) {
                            val name = knownBuckets.elementAt(idx)
                            KnownBucketCard(name, onAdd = { onAdd(name) }, modifier = Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KnownBucketCard(name: String, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    var isHover by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .onHover { isHover = it }
            .height(54.dp)
            .cursorHand()
            .clickable { onAdd() },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (isHover) colors.primary else colors.borderDefault),
        color = colors.surface,
        elevation = if (isHover) 1.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                name,
                style = MaterialTheme.typography.body2.copy(
                    fontWeight = FontWeight.Medium,
                    color = if (isHover) colors.primary else colors.textTitle
                )
            )
            Icon(
                Icons.TwoTone.Add,
                "",
                modifier = Modifier.size(18.dp),
                tint = if (isHover) colors.primary else colors.textMuted
            )
        }
    }
}

private fun isValidBucketUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}

// ConfirmDialog and DialogTextField have been moved to components/ConfirmDialog.kt
