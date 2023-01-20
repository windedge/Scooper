package scooper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Layout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, color = MaterialTheme.colors.background) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 0.dp, start = 1.dp, end = 1.dp, bottom = 1.dp)) {
            content()
        }
    }
}