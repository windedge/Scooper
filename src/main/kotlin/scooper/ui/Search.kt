package scooper.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.twotone.Clear
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.koin.java.KoinJavaComponent.get
import org.slf4j.LoggerFactory
import scooper.ui.components.IconButton
import scooper.util.bottomBorder
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.viewmodels.AppsViewModel

@Suppress("unused")
private val logger = LoggerFactory.getLogger("scooper.ui.Search")

/*
@Composable
fun SearchBox() {
    Surface(
        Modifier.fillMaxWidth(), elevation = 1.dp, shape = MaterialTheme.shapes.large
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SearchBar()
            Button(onClick = {}, modifier = Modifier.layout { _, _ ->
                layout(0, 0) { }
            }) {
                Icon(Icons.TwoTone.Refresh, "", modifier = Modifier.size(18.dp))
            }
        }
    }
}
*/

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchBar(show: Boolean = true) {
    if (!show) return;

    val appsViewModel: AppsViewModel = get(AppsViewModel::class.java)

    var isHovered by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderWidth = if (isHovered || isFocused) 1.6.dp else 1.dp
    val modifier = Modifier.padding(bottom = 2.dp).height(35.dp)
        .bottomBorder(borderWidth, color = colors.primary)
        .onHover { isHovered = it }
        .focusable(true, interactionSource = interactionSource)

    Row(
        modifier = modifier, verticalAlignment = Alignment.CenterVertically
    ) {
        val state by appsViewModel.container.stateFlow.collectAsState()
        val buckets = mutableListOf("")
        buckets.addAll(state.buckets.map { it.name })

        var expand by remember { mutableStateOf(false) }
        var selectedItem by remember { mutableStateOf(-1) }
        var bucket by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.height(30.dp)
                .background(color = Color.Unspecified, shape = MaterialTheme.shapes.small)
                .cursorHand()
                .clickable() { expand = !expand },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DropdownMenu(
                expand,
                onDismissRequest = { expand = false },
                modifier = Modifier.width(120.dp).cursorHand(),
                offset = DpOffset(x = (-10).dp, y = 6.dp)
            ) {
                buckets.forEachIndexed() { idx, title ->
                    var hover by remember { mutableStateOf(false) }
                    DropdownMenuItem(onClick = {
                        expand = false
                        selectedItem = idx
                        bucket = title
                        appsViewModel.applyFilters(bucket = bucket)
                    },
                        modifier = Modifier.sizeIn(maxHeight = 40.dp)
                            .background(color = if (hover) colors.primaryVariant else colors.surface)
                            .onHover { hover = it }) {
                        Text(
                            title.ifBlank { "All" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = colors.onSurface,
                        )
                    }
                }
            }

            val color = if (selectedItem > 0) colors.onSurface else Color.LightGray

            Text(
                bucket.ifBlank { "Select bucket" },
                modifier = Modifier.width(90.dp).padding(start = 4.dp),
                style = MaterialTheme.typography.body1.copy(color = color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.TwoTone.KeyboardArrowDown,
                "",
                tint = colors.primary
            )
        }

        val query = state.filter.query
        val inputFocusRequester = remember { FocusRequester() }
        BasicTextField(
            query,
            onValueChange = { appsViewModel.applyFilters(it) },
            modifier = Modifier.padding(start = 5.dp).defaultMinSize(120.dp).fillMaxWidth(0.4f)
                // .cursorInput()
                .focusRequester(inputFocusRequester)
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter) {
                        appsViewModel.applyFilters(query, bucket = bucket)
                        true
                    } else false
                },
            singleLine = true,
            interactionSource = interactionSource
        )

        if (query.isNotEmpty()) {
            IconButton(
                onClick = {
                    appsViewModel.applyFilters(query = "")
                    inputFocusRequester.requestFocus()
                },
                modifier = Modifier.cursorHand().padding(horizontal = 2.5.dp),
                rippleRadius = 10.dp,
            ) {
                Icon(Icons.TwoTone.Clear, "", modifier = Modifier.size(15.dp), tint = colors.onSecondary)
            }
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }

        IconButton(
            onClick = { appsViewModel.applyFilters(query, bucket = bucket) },
            modifier = Modifier.cursorHand().padding(horizontal = 5.dp),
            interactionSource = interactionSource
        ) {
            Icon(Icons.Filled.Search, "", modifier = Modifier.size(18.dp), tint = colors.primary)
        }
    }

}
