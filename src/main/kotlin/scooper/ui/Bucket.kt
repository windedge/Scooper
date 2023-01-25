package scooper.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AddCircle
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogState
import org.koin.java.KoinJavaComponent.get
import scooper.data.Bucket
import scooper.ui.components.OutlinedTextField
import scooper.ui.components.Tooltip
import scooper.util.*
import scooper.viewmodels.AppsViewModel
import java.awt.Desktop
import java.net.URI

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BucketsScreen(appsViewModel: AppsViewModel = get(AppsViewModel::class.java)) {
    var bucketToDelete by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var bucketName by remember { mutableStateOf("") }
    var bucketUrl by remember { mutableStateOf("") }
    var bucketNameError by remember { mutableStateOf(false) }
    var bucketUrlError by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }

    Surface(elevation = 1.dp, color = colors.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            val scrollState = rememberScrollState(0)
            LaunchedEffect(Unit) { scrollState.scrollTo(0) }
            Column(Modifier.padding(start = 2.dp, end = 14.dp).verticalScroll(scrollState)) {
                val state by appsViewModel.container.stateFlow.collectAsState()
                val buckets = state.buckets
                val bucketNames = buckets.map { it.name }

                for (bucket in buckets) {
                    BucketCard(bucket, onDelete = {
                        bucketToDelete = bucket.name
                        showDeleteDialog = true
                    })
                }

                var isHover by remember { mutableStateOf(false) }
                val stroke = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 2f), 0f))
                Box(
                    Modifier.fillMaxWidth().height(60.dp).padding(4.dp)
                        .background(color = if (isHover) colors.primary else Color.Transparent)
                        .cursorHand()
                        .onPointerEvent(PointerEventType.Enter) { isHover = true }
                        .onPointerEvent(PointerEventType.Exit) { isHover = false }
                        .noRippleClickable {
                            bucketName = ""; bucketUrl = ""; showAddDialog = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val color = if (isHover) colors.onPrimary else colors.onSurface
                    if (!isHover) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRoundRect(color = color, style = stroke)
                        }
                    }
                    Text(
                        textAlign = TextAlign.Center,
                        text = "Add Bucket...",
                        color = color,
                        fontWeight = if (isHover) FontWeight.Bold else FontWeight.Bold,
                    )
                }

                Text(
                    "Known Buckets",
                    style = MaterialTheme.typography.h5,
                    color = colors.onBackground,
                    modifier = Modifier.padding(top = 20.dp)
                )

                KnownBuckets(bucketNames, onAdd = { appsViewModel.queuedAddBucket(it) })

                Spacer(Modifier.height(10.dp))
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().background(color = colors.background),
                adapter = rememberScrollbarAdapter(scrollState = scrollState /* TextBox height + Spacer height*/)
            )
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            text = "Confirm to delete?",
            onConfirm = {
                showDeleteDialog = false
                appsViewModel.queuedRemoveBucket(bucketToDelete)
            },
            onCancel = { showDeleteDialog = false }
        )
    }

    if (showAddDialog) {
        ConfirmDialog(
            title = "Add Bucket",
            onConfirm = {
                showAddDialog = false
                if (bucketName.isBlank()) {
                    bucketNameError = true
                }
                if (bucketUrl.isBlank()) {
                    bucketUrlError = true
                }
                if (bucketNameError || bucketUrlError) {
                    return@ConfirmDialog
                }
                appsViewModel.queuedAddBucket(bucketName, bucketUrl)
            },
            onCancel = { showAddDialog = false },
            confirmText = "Add",
            state = DialogState(size = DpSize(350.dp, 230.dp))
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.Start) {
                    SideEffect {
                        inputFocusRequester.requestFocus()
                    }
                    OutlinedTextField(
                        bucketName,
                        onValueChange = { bucketName = it; bucketNameError = false },
                        label = { Text("bucket") },
                        singleLine = true,
                        modifier = Modifier.height(50.dp).fillMaxWidth().focusRequester(inputFocusRequester)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        bucketUrl,
                        onValueChange = { bucketUrl = it; bucketUrlError = false },
                        label = { Text("url") },
                        singleLine = true,
                        modifier = Modifier.height(50.dp).fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    text: String? = null,
    title: String? = null,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmText: String? = null,
    cancelText: String? = null,
    state: DialogState = DialogState(size = DpSize(300.dp, 180.dp)),
    content: @Composable (() -> Unit)? = null
) {
    Dialog(onCloseRequest = onCancel, state = state, title = title ?: "Scooper") {
        BoxWithConstraints {
            val height = this.maxHeight
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(height - 66.dp)) {
                    if (content != null) content.invoke() else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text ?: "", style = MaterialTheme.typography.h6, color = colors.onSurface)
                        }
                    }
                }
                Divider(Modifier.height(9.dp).padding(horizontal = 8.dp, vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onConfirm, modifier = Modifier.width(80.dp).height(40.dp).cursorLink()) {
                        Text(confirmText ?: "OK")
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.height(40.dp).cursorLink()) {
                        Text(cancelText ?: "Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun BucketCard(
    bucket: Bucket,
    onDelete: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxWidth = this.maxWidth
        Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.height(80.dp).padding(10.dp)) {
                Column(Modifier.width(maxWidth - 80.dp)) {
                    Text(bucket.name, style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(8.dp))
                    Text(bucket.url ?: "", modifier = Modifier.cursorLink().clickable {
                        Desktop.getDesktop().browse(URI.create(bucket.url!!))
                    })
                }

                Box(
                    Modifier.width(60.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.TwoTone.Delete,
                            "",
                            Modifier.cursorLink(),
                            tint = colors.onSecondary
                        )
                    }
                }
            }
        }

    }
}

@Composable
fun KnownBuckets(bucketNames: List<String>, onAdd: (bucketName: String) -> Unit) {
    BoxWithConstraints {
        val maxWidth = this.maxWidth
        Column {
            val columns = (maxWidth.value / 168).toInt()
            val knownBuckets = KNOWN_BUCKETS - bucketNames.toSet()
            // val knownBuckets = KNOWN_BUCKETS
            val rows = knownBuckets.size / columns + 1

            for (i in 0 until rows) {
                Row(
                    Modifier.padding(vertical = 2.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    for (j in 0 until columns) {
                        val idx = i * columns + j
                        if (idx < knownBuckets.size) {
                            Surface(Modifier.width(150.dp).height(80.dp).padding(2.dp)) {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val bucketName = knownBuckets.elementAt(idx)
                                    Text(bucketName, style = MaterialTheme.typography.h6)
                                    Tooltip("Add Bucket") {
                                        IconButton(onClick = { onAdd(bucketName) }) {
                                            Icon(
                                                Icons.TwoTone.AddCircle,
                                                "",
                                                Modifier.cursorLink().size(30.dp),
                                                tint = colors.primary,
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
    }
}

/*
@Composable
fun ConfirmDialog(
    text: String? = null,
    title: String? = null,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmText: String? = null,
    cancelText: String? = null,
    content: @Composable (() -> Unit)? = null
) {
    val body =
        content ?: {
            Box(
                Modifier.height(120.dp).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text ?: "",
                    style = MaterialTheme.typography.h6,
                    color = colors.onSurface
                )
            }
        }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            Button(onClick = onConfirm, contentPadding = PaddingValues(4.dp)) { Text(confirmText ?: "OK") }
        },
        text = body,
        dismissButton = {
            Button(onClick = onCancel, contentPadding = PaddingValues(4.dp)) { Text(cancelText ?: "Cancel") }
        },
        properties = DialogProperties(title = title ?: "Scooper", size = IntSize(240, 150))
    )
}
*/
