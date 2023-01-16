package scooper.ui

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import scooper.util.cursorLink

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OutputScreen(output: String, onBack: () -> Unit = {}) {
    Column {
        Surface(
            modifier = Modifier.height(60.dp).fillMaxWidth(),
            elevation = 2.dp,
            shape = MaterialTheme.shapes.large
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(start = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                IconButton(onClick = { onBack() }) {
                    Icon(
                        Icons.TwoTone.ArrowBack,
                        "",
                        Modifier.cursorLink(),
                        tint = MaterialTheme.colors.onSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.padding(horizontal = 2.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = Color.LightGray,
                    cornerRadius = CornerRadius(5f, 5f),
                    style = Stroke(width = 0.5f)
                )
            }
            BasicTextField(
                output,
                {},
                modifier = Modifier.fillMaxSize().padding(10.dp),
                readOnly = true,
                textStyle = MaterialTheme.typography.caption.copy(color = Color.DarkGray)
            )
        }
    }
}