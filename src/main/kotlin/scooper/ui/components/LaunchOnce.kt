package scooper.ui.components

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun LaunchOnce(block: () -> Unit) {
    var initilized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initilized) {
            block()
            initilized = true
        }
    }
}