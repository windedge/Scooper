package scooper.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.twotone.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.parseAnsiColors
import scooper.viewmodels.AppsViewModel
import scooper.ui.theme.*

@Composable
fun OutputScreen(onBack: () -> Unit = {}) {
    val appsViewModel: AppsViewModel = koinInject()
    val state by appsViewModel.container.stateFlow.collectAsState()
    val output = state.output
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val colors = MaterialTheme.colors

    Column {
        Surface(
            modifier = Modifier.height(60.dp).fillMaxWidth(),
            elevation = 2.dp,
            shape = MaterialTheme.shapes.large
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onBack() }) {
                    Icon(
                        Icons.TwoTone.ArrowBack,
                        "",
                        Modifier.cursorLink(),
                        tint = MaterialTheme.colors.primary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { clipboardManager.setText(AnnotatedString(output)) },
                        modifier = Modifier.cursorHand()
                    ) {
                        Text("Copy")
                    }

                    Button(
                        onClick = { appsViewModel.intent { reduce { state.copy(output = "") } } },
                        modifier = Modifier.cursorHand()
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.padding(horizontal = 2.dp)) {
            val padding = 10.dp
            val showScrollButtons by remember { derivedStateOf { scrollState.maxValue > 0 } }

            val annotatedString = parseAnsiColors(output)
            SelectionContainer {
                Text(
                    text = annotatedString,
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState),
                    style = MaterialTheme.typography.caption.copy(
                        fontSize = 13.sp,
                        color = MaterialTheme.colors.onSurface
                    ),
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = colors.borderDefault,
                    cornerRadius = CornerRadius(5f, 5f),
                    style = Stroke(width = 0.5f)
                )
            }

            VerticalScrollbar(
                rememberScrollbarAdapter(scrollState),
                modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd)
            )

            if (showScrollButtons) {
                ScrollButtons(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                    scope,
                    scrollState
                )
            }
        }
    }
}

@Composable
fun ScrollButtons(modifier: Modifier = Modifier, scope: CoroutineScope, scrollState: ScrollState) {
    Column(modifier = modifier) {
        FloatingActionButton(
            onClick = {
                scope.launch {
                    scrollState.animateScrollTo(0)
                }
            },
            shape = RoundedCornerShape(50),
            backgroundColor = MaterialTheme.colors.primary,
            modifier = Modifier.size(30.dp).cursorHand()
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, "")
        }

        Spacer(modifier = Modifier.height(5.dp))
        FloatingActionButton(
            onClick = {
                scope.launch {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
            },
            shape = RoundedCornerShape(50),
            backgroundColor = MaterialTheme.colors.primary,
            modifier = Modifier.size(30.dp).cursorHand()
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, "")
        }
    }
}
